// NetworkMonitor.java - Max feature + packet tests edition:
// - Download & upload speed
// - Multi-ping stats (avg, jitter, loss)
// - HTTP válaszidő
// - Live graph (sebesség + ping)
// - Traceroute
// - Netstat viewer
// - LAN scan (/24 reachability)
// - UPnP port forward (Add/DeletePortMapping)
// - Unicast / Broadcast / Multicast / Anycast tesztek + külön grafikon a válaszidőkre
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class NetworkMonitor extends JFrame {

    // Tabs
    private JTabbedPane tabs;

    // Monitor tab
    private JComboBox<NetworkInterfaceWrapper> interfaceCombo;
    private JLabel downloadLabel;
    private JLabel uploadLabel;
    private JLabel pingLabel;
    private JLabel jitterLabel;
    private JLabel lossLabel;
    private JLabel httpRespLabel;

    private JTextArea logArea;
    private GraphPanel graphPanel;
    private JButton startButton;
    private JButton stopButton;
    private JSpinner intervalSpinner; // sec

    // Traceroute tab
    private JTextField tracerouteField;
    private JButton tracerouteButton;
    private JTextArea tracerouteArea;

    // Netstat tab
    private JButton refreshNetstatButton;
    private JTextArea netstatArea;

    // LAN scan tab
    private JTextArea lanScanArea;
    private JButton lanScanButton;
    private JLabel lanScanInfoLabel;

    // Port forward tab (UPnP)
    private JTextField pfExternalPortField;
    private JTextField pfInternalPortField;
    private JTextField pfInternalHostField;
    private JComboBox<String> pfProtocolCombo;
    private JTextField pfDescriptionField;
    private JButton pfAddButton;
    private JButton pfDeleteButton;
    private JTextArea pfLogArea;

    // Packet tests tab
    private JTextField unicastHostField;
    private JSpinner unicastPortSpinner;
    private JButton unicastPingButton;
    private JButton unicastUdpButton;

    private JButton broadcastTestButton;

    private JTextField multicastGroupField;
    private JSpinner multicastPortSpinner;
    private JButton multicastTestButton;

    private JButton anycastCloudflareButton;
    private JButton anycastGoogleButton;


    // Packet test loop controls (run until stopped)
    private volatile boolean unicastUdpRunning = false;
    private volatile boolean broadcastRunning = false;
    private volatile boolean multicastRunning = false;
    private volatile boolean anycastRunning = false;

    private Future<?> unicastUdpFuture;
    private Future<?> broadcastFuture;
    private Future<?> multicastFuture;
    private Future<?> anycastFuture;

    private String anycastIpRunning = null;
    private String anycastUrlRunning = null;

    private JTextArea packetTestArea;
    private PacketGraphPanel packetGraphPanel;

    // Settings tab
    private JTextField pingTargetField;
    private JSpinner pingCountSpinner;
    private JTextField speedTestUrlField;
    private JSpinner downloadBytesSpinner;
    private JSpinner uploadBytesSpinner;
    private JTextField httpTestUrlField;
    private JButton exportJsonButton;
    private JCheckBox darkThemeCheck;

    // Logic
    private ScheduledExecutorService scheduler;
    private ExecutorService backgroundExec = Executors.newCachedThreadPool();
    private File csvLogFile;
    private File jsonLogFile;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private DecimalFormat df2 = new DecimalFormat("0.00");

    // Runtime config
    private volatile String pingTarget = "8.8.8.8";
    private volatile int pingCount = 5;
    private volatile String speedTestUrl = "https://speed.hetzner.de/10MB.bin";
    private volatile int downloadBytes = 2 * 1024 * 1024; // 2MB
    private volatile int uploadBytes = 512 * 1024;        // 512KB
    private volatile String httpTestUrl = "https://www.google.com";

    // History for JSON export
    private final List<Measurement> history = Collections.synchronizedList(new ArrayList<>());

    public NetworkMonitor() {
        super("Hálózati monitor (max feature + packet tests)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1250, 780);
        setLocationRelativeTo(null);

        initComponents();
        loadInterfaces();

        csvLogFile = new File("network_log.csv");
        jsonLogFile = new File("network_log.json");
        if (!csvLogFile.exists()) {
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(csvLogFile, true), StandardCharsets.UTF_8))) {
                pw.println("timestamp;interface;download_mbps;upload_mbps;ping_avg_ms;jitter_ms;packet_loss_percent;http_resp_ms");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        applyTheme(false);
    }

    private void initComponents() {
        tabs = new JTabbedPane();

        // ========== MONITOR TAB ==========
        JPanel monitorPanel = new JPanel(new BorderLayout());

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        interfaceCombo = new JComboBox<>();
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);

        intervalSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 3600, 1));
        topBar.add(new JLabel("Hálózati kártya:"));
        topBar.add(interfaceCombo);
        topBar.add(startButton);
        topBar.add(stopButton);
        topBar.add(new JLabel("Intervallum (s):"));
        topBar.add(intervalSpinner);

        startButton.addActionListener(this::onStart);
        stopButton.addActionListener(this::onStop);

        JPanel statusBar = new JPanel(new GridLayout(2, 3));
        downloadLabel = new JLabel("Download: - Mbps");
        uploadLabel   = new JLabel("Upload: - Mbps");
        pingLabel     = new JLabel("Ping átlag: - ms");
        jitterLabel   = new JLabel("Jitter: - ms");
        lossLabel     = new JLabel("Veszteség: - %");
        httpRespLabel = new JLabel("HTTP válaszidő: - ms");
        statusBar.add(downloadLabel);
        statusBar.add(uploadLabel);
        statusBar.add(pingLabel);
        statusBar.add(jitterLabel);
        statusBar.add(lossLabel);
        statusBar.add(httpRespLabel);

        graphPanel = new GraphPanel();
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, graphPanel, logScroll);
        split.setResizeWeight(0.5);

        monitorPanel.add(topBar, BorderLayout.NORTH);
        monitorPanel.add(statusBar, BorderLayout.SOUTH);
        monitorPanel.add(split, BorderLayout.CENTER);

        tabs.addTab("Monitor", monitorPanel);

        // ========== TRACEROUTE TAB ==========
        JPanel traceroutePanel = new JPanel(new BorderLayout());
        JPanel trTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tracerouteField = new JTextField("8.8.8.8", 20);
        tracerouteButton = new JButton("Traceroute indítás");
        tracerouteButton.addActionListener(this::onTraceroute);
        trTop.add(new JLabel("Cél host:"));
        trTop.add(tracerouteField);
        trTop.add(tracerouteButton);
        tracerouteArea = new JTextArea();
        tracerouteArea.setEditable(false);
        tracerouteArea.setBorder(new TitledBorder("Traceroute kimenet"));
        traceroutePanel.add(trTop, BorderLayout.NORTH);
        traceroutePanel.add(new JScrollPane(tracerouteArea), BorderLayout.CENTER);
        tabs.addTab("Traceroute", traceroutePanel);

        // ========== NETSTAT TAB ==========
        JPanel netstatPanel = new JPanel(new BorderLayout());
        refreshNetstatButton = new JButton("Kapcsolatok frissítése (netstat)");
        refreshNetstatButton.addActionListener(this::onRefreshNetstat);
        netstatArea = new JTextArea();
        netstatArea.setEditable(false);
        netstatArea.setBorder(new TitledBorder("Aktív kapcsolatok (netstat -ano)"));
        netstatPanel.add(refreshNetstatButton, BorderLayout.NORTH);
        netstatPanel.add(new JScrollPane(netstatArea), BorderLayout.CENTER);
        tabs.addTab("Kapcsolatok", netstatPanel);

        // ========== LAN SCAN TAB ==========
        JPanel lanPanel = new JPanel(new BorderLayout());
        JPanel lanTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lanScanButton = new JButton("LAN feltérképezés (aktuális interfész /24)");
        lanScanButton.addActionListener(this::onLanScan);
        lanScanInfoLabel = new JLabel("Interfészen lévő IPv4 alapján /24 hálózat pingelése.");
        lanTop.add(lanScanButton);
        lanPanel.add(lanTop, BorderLayout.NORTH);
        lanScanArea = new JTextArea();
        lanScanArea.setEditable(false);
        lanScanArea.setBorder(new TitledBorder("LAN eszközök"));
        lanPanel.add(new JScrollPane(lanScanArea), BorderLayout.CENTER);
        lanPanel.add(lanScanInfoLabel, BorderLayout.SOUTH);
        tabs.addTab("LAN feltérképezés", lanPanel);

        // ========== PORT FORWARD (UPnP) TAB ==========
        JPanel pfPanel = new JPanel(new BorderLayout());

        JPanel pfTop = new JPanel(new GridLayout(3, 4, 5, 5));
        pfTop.setBorder(new TitledBorder("UPnP Port forwarding (router támogatás szükséges)"));
        pfExternalPortField = new JTextField("55555", 6);
        pfInternalPortField = new JTextField("55555", 6);
        pfInternalHostField = new JTextField("192.168.0.100", 12);
        pfProtocolCombo = new JComboBox<>(new String[]{"TCP", "UDP"});
        pfDescriptionField = new JTextField("NetworkMonitorPort", 15);
        pfTop.add(new JLabel("Külső port:"));
        pfTop.add(pfExternalPortField);
        pfTop.add(new JLabel("Belső IP:"));
        pfTop.add(pfInternalHostField);
        pfTop.add(new JLabel("Belső port:"));
        pfTop.add(pfInternalPortField);
        pfTop.add(new JLabel("Protokoll:"));
        pfTop.add(pfProtocolCombo);
        pfTop.add(new JLabel("Leírás:"));
        pfTop.add(pfDescriptionField);

        JPanel pfButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pfAddButton = new JButton("Port forward hozzáadása");
        pfDeleteButton = new JButton("Port forward törlése");
        pfAddButton.addActionListener(this::onAddPortForward);
        pfDeleteButton.addActionListener(this::onDeletePortForward);
        pfButtons.add(pfAddButton);
        pfButtons.add(pfDeleteButton);

        pfLogArea = new JTextArea();
        pfLogArea.setEditable(false);
        pfLogArea.setBorder(new TitledBorder("UPnP napló"));

        JPanel pfTopContainer = new JPanel(new BorderLayout());
        pfTopContainer.add(pfTop, BorderLayout.NORTH);
        pfTopContainer.add(pfButtons, BorderLayout.SOUTH);

        pfPanel.add(pfTopContainer, BorderLayout.NORTH);
        pfPanel.add(new JScrollPane(pfLogArea), BorderLayout.CENTER);

        tabs.addTab("Port forward (UPnP)", pfPanel);

        // ========== PACKET TESTS TAB ==========
        JPanel packetPanel = new JPanel(new BorderLayout());

        // Left: controls in vertical layout
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));

        // Unicast panel
        JPanel uniPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        uniPanel.setBorder(new TitledBorder("Unicast tesztek"));
        unicastHostField = new JTextField("8.8.8.8", 12);
        unicastPortSpinner = new JSpinner(new SpinnerNumberModel(7, 1, 65535, 1));
        unicastPingButton = new JButton("ICMP ping");
        unicastUdpButton = new JButton("UDP echo teszt");
        unicastPingButton.addActionListener(this::onUnicastPing);
        unicastUdpButton.addActionListener(this::onUnicastUdp);
        uniPanel.add(new JLabel("Host:"));
        uniPanel.add(unicastHostField);
        uniPanel.add(new JLabel("UDP port:"));
        uniPanel.add(unicastPortSpinner);
        uniPanel.add(unicastPingButton);
        uniPanel.add(unicastUdpButton);

        // Broadcast panel
        JPanel bcPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bcPanel.setBorder(new TitledBorder("Broadcast teszt"));
        broadcastTestButton = new JButton("Broadcast UDP küldése + válaszok figyelése");
        broadcastTestButton.addActionListener(this::onBroadcastTest);
        bcPanel.add(broadcastTestButton);

        // Multicast panel
        JPanel mcPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        mcPanel.setBorder(new TitledBorder("Multicast teszt"));
        multicastGroupField = new JTextField("224.0.0.251", 12);
        multicastPortSpinner = new JSpinner(new SpinnerNumberModel(5353, 1, 65535, 1));
        multicastTestButton = new JButton("Multicast teszt");
        multicastTestButton.addActionListener(this::onMulticastTest);
        mcPanel.add(new JLabel("Csoport:"));
        mcPanel.add(multicastGroupField);
        mcPanel.add(new JLabel("Port:"));
        mcPanel.add(multicastPortSpinner);
        mcPanel.add(multicastTestButton);

        // Anycast panel
        JPanel acPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        acPanel.setBorder(new TitledBorder("Anycast tesztek"));
        anycastCloudflareButton = new JButton("Cloudflare 1.1.1.1");
        anycastGoogleButton = new JButton("Google 8.8.8.8");
        anycastCloudflareButton.addActionListener(e -> onAnycastTest("1.1.1.1", "https://1.1.1.1/cdn-cgi/trace"));
        anycastGoogleButton.addActionListener(e -> onAnycastTest("8.8.8.8", "https://dns.google/dns-query"));
        acPanel.add(new JLabel("Anycast DNS szogláltatók:"));
        acPanel.add(anycastCloudflareButton);
        acPanel.add(anycastGoogleButton);

        controlPanel.add(uniPanel);
        controlPanel.add(bcPanel);
        controlPanel.add(mcPanel);
        controlPanel.add(acPanel);

        // Right: packet graph + text area
        packetGraphPanel = new PacketGraphPanel();
        packetGraphPanel.setBorder(new TitledBorder("Teszt válaszidők (ms)"));

        packetTestArea = new JTextArea();
        packetTestArea.setEditable(false);
        JScrollPane packetScroll = new JScrollPane(packetTestArea);
        packetScroll.setBorder(new TitledBorder("Teszt napló"));

        JSplitPane packetSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, packetGraphPanel, packetScroll);
        packetSplit.setResizeWeight(0.4);

        packetPanel.add(controlPanel, BorderLayout.WEST);
        packetPanel.add(packetSplit, BorderLayout.CENTER);

        tabs.addTab("Csomag tesztek", packetPanel);

        // ========== SETTINGS / EXPORT TAB ==========
        JPanel settingsPanel = new JPanel();
        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));

        JPanel pingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pingPanel.setBorder(new TitledBorder("Ping beállítások"));
        pingTargetField = new JTextField(pingTarget, 15);
        pingCountSpinner = new JSpinner(new SpinnerNumberModel(pingCount, 1, 50, 1));
        pingPanel.add(new JLabel("Ping célpont:"));
        pingPanel.add(pingTargetField);
        pingPanel.add(new JLabel("Pingek száma / mérés:"));
        pingPanel.add(pingCountSpinner);

        JPanel speedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        speedPanel.setBorder(new TitledBorder("Sebességmérés beállítások"));
        speedTestUrlField = new JTextField(speedTestUrl, 30);
        downloadBytesSpinner = new JSpinner(new SpinnerNumberModel(downloadBytes, 64 * 1024, 50 * 1024 * 1024, 64 * 1024));
        uploadBytesSpinner   = new JSpinner(new SpinnerNumberModel(uploadBytes,   64 * 1024, 10 * 1024 * 1024, 64 * 1024));
        speedPanel.add(new JLabel("Letöltési URL:"));
        speedPanel.add(speedTestUrlField);
        speedPanel.add(new JLabel("Letöltés mérete (byte):"));
        speedPanel.add(downloadBytesSpinner);
        speedPanel.add(new JLabel("Feltöltés mérete (byte):"));
        speedPanel.add(uploadBytesSpinner);

        JPanel httpPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        httpPanel.setBorder(new TitledBorder("HTTP válaszidő mérés"));
        httpTestUrlField = new JTextField(httpTestUrl, 30);
        httpPanel.add(new JLabel("HTTP URL:"));
        httpPanel.add(httpTestUrlField);

        JPanel themePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themePanel.setBorder(new TitledBorder("Megjelenés"));
        darkThemeCheck = new JCheckBox("Sötét téma");
        darkThemeCheck.addActionListener(e -> applyTheme(darkThemeCheck.isSelected()));
        themePanel.add(darkThemeCheck);

        JPanel exportPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        exportPanel.setBorder(new TitledBorder("Export"));
        exportJsonButton = new JButton("Log export JSON-be");
        exportJsonButton.addActionListener(this::onExportJson);
        JButton applySettingsButton = new JButton("Beállítások mentése");
        applySettingsButton.addActionListener(this::onApplySettings);
        exportPanel.add(exportJsonButton);
        exportPanel.add(applySettingsButton);

        settingsPanel.add(pingPanel);
        settingsPanel.add(speedPanel);
        settingsPanel.add(httpPanel);
        settingsPanel.add(themePanel);
        settingsPanel.add(exportPanel);
        settingsPanel.add(Box.createVerticalGlue());

        tabs.addTab("Beállítások / Export", settingsPanel);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabs, BorderLayout.CENTER);
    }

    private void loadInterfaces() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) {
                    interfaceCombo.addItem(new NetworkInterfaceWrapper(ni));
                }
            }
        } catch (SocketException e) {
            appendLog("Hiba hálózati kártyák lekérdezésekor: " + e.getMessage());
        }
    }

    // ========== MONITOR LOGIC ==========

    private void onStart(ActionEvent e) {
        NetworkInterfaceWrapper selected = (NetworkInterfaceWrapper) interfaceCombo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Nincs kártya kiválasztva", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        startButton.setEnabled(false);
        interfaceCombo.setEnabled(false);
        stopButton.setEnabled(true);

        int intervalSec = (Integer) intervalSpinner.getValue();
        if (intervalSec < 1) intervalSec = 1;

        scheduler = Executors.newSingleThreadScheduledExecutor();
        NetworkInterfaceWrapper wrapper = selected;
        int finalIntervalSec = intervalSec;
        scheduler.scheduleAtFixedRate(() -> runMeasurement(wrapper), 0, finalIntervalSec, TimeUnit.SECONDS);
    }

    private void onStop(ActionEvent e) {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        startButton.setEnabled(true);
        interfaceCombo.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void runMeasurement(NetworkInterfaceWrapper wrapper) {
        try {
            InetAddress addr = wrapper.getIPv4();
            if (addr != null) {
                appendLog("Mérés indul ezen az IP-n (info): " + addr.getHostAddress());
            }

            SpeedResult speed = testSpeed();
            PingStats stats = testMultiPing(pingTarget, pingCount);
            double httpRespMs = testHttpResponseTime(httpTestUrl);

            Measurement m = new Measurement(
                    new Date(),
                    wrapper.toString(),
                    speed.downloadMbps,
                    speed.uploadMbps,
                    stats.avgMs,
                    stats.jitterMs,
                    stats.lossPercent,
                    httpRespMs
            );
            history.add(m);

            String line = String.format("%s;\"%s\";%.2f;%.2f;%.2f;%.2f;%.2f;%.2f",
                    dateFormat.format(m.timestamp),
                    m.interfaceName.replace("\"", "'"),
                    m.downloadMbps,
                    m.uploadMbps,
                    m.pingAvgMs,
                    m.jitterMs,
                    m.packetLossPercent,
                    m.httpResponseMs
            );

            appendLog("Eredmény: " + line);
            writeCsv(line);

            SwingUtilities.invokeLater(() -> {
                downloadLabel.setText("Download: " + df2.format(m.downloadMbps) + " Mbps");
                uploadLabel.setText("Upload: " + df2.format(m.uploadMbps) + " Mbps");
                pingLabel.setText("Ping átlag: " + df2.format(m.pingAvgMs) + " ms");
                jitterLabel.setText("Jitter: " + df2.format(m.jitterMs) + " ms");
                lossLabel.setText("Veszteség: " + df2.format(m.packetLossPercent) + " %");
                httpRespLabel.setText("HTTP válaszidő: " + df2.format(m.httpResponseMs) + " ms");
                graphPanel.addPoint(m.downloadMbps, m.uploadMbps, m.pingAvgMs);
            });

        } catch (Exception ex) {
            appendLog("Hiba mérés közben: " + ex.toString());
        }
    }

    private void writeCsv(String line) {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(csvLogFile, true), StandardCharsets.UTF_8))) {
            pw.println(line);
        } catch (Exception e) {
            appendLog("CSV írás hiba: " + e.getMessage());
        }
    }

    private void appendLog(String t) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(t + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private SpeedResult testSpeed() {
        double dl = testDownload();
        double ul = testUpload();
        return new SpeedResult(dl, ul);
    }

    private double testDownload() {
        String urlStr = speedTestUrl;
        int bytesToRead = downloadBytes;
        appendLog("Letöltés mérés: " + urlStr + " (" + bytesToRead + " byte)");

        long start = 0L;
        long end = 0L;
        int total = 0;

        try {
            URL url = new URL(urlStr);
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                start = System.nanoTime();
                while (total < bytesToRead) {
                    int toRead = Math.min(buf.length, bytesToRead - total);
                    int r = in.read(buf, 0, toRead);
                    if (r == -1) break;
                    total += r;
                }
                end = System.nanoTime();
            }
        } catch (Exception e) {
            appendLog("Letöltésmérés hiba: " + e.toString());
            return 0.0;
        }

        if (end <= start || total == 0) {
            appendLog("Letöltésmérés: nincs elég adat (total=" + total + ")");
            return 0.0;
        }

        double sec = (end - start) / 1e9;
        double mbit = (total * 8.0) / 1_000_000.0;
        double mbps = mbit / sec;
        appendLog(String.format("Letöltés: %.2f Mbps (%.2f Mbit, %.2f s)", mbps, mbit, sec));
        return mbps;
    }

    private double testUpload() {
        String urlStr = "https://httpbin.org/post";
        int bytesToSend = uploadBytes;
        appendLog("Feltöltés mérés: " + urlStr + " (" + bytesToSend + " byte)");

        long start = 0L;
        long end = 0L;

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            byte[] buf = new byte[8192];
            Arrays.fill(buf, (byte)65);

            start = System.nanoTime();
            try (OutputStream out = conn.getOutputStream()) {
                int remaining = bytesToSend;
                while (remaining > 0) {
                    int toWrite = Math.min(buf.length, remaining);
                    out.write(buf, 0, toWrite);
                    remaining -= toWrite;
                }
                out.flush();
            }

            try (InputStream in = conn.getInputStream()) {
                byte[] tmp = new byte[1024];
                while (in.read(tmp) != -1) {
                    break;
                }
            }
            end = System.nanoTime();
        } catch (Exception e) {
            appendLog("Feltöltésmérés hiba: " + e.toString());
            return 0.0;
        }

        if (end <= start || bytesToSend <= 0) {
            appendLog("Feltöltésmérés: hibás időadat");
            return 0.0;
        }

        double sec = (end - start) / 1e9;
        double mbit = (bytesToSend * 8.0) / 1_000_000.0;
        double mbps = mbit / sec;
        appendLog(String.format("Feltöltés: %.2f Mbps (%.2f Mbit, %.2f s)", mbps, mbit, sec));
        return mbps;
    }

    private PingStats testMultiPing(String host, int count) {
        List<Long> times = new ArrayList<>();
        int success = 0;
        int total = count;

        appendLog("Multi-ping: " + host + " (" + count + " db)");

        for (int i = 0; i < count; i++) {
            long t = singlePing(host);
            if (t >= 0) {
                success++;
                times.add(t);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
        }

        double avg = 0.0;
        double jitter = 0.0;
        if (!times.isEmpty()) {
            long sum = 0;
            for (long v : times) sum += v;
            avg = sum / (double) times.size();

            double varSum = 0.0;
            for (long v : times) {
                varSum += (v - avg) * (v - avg);
            }
            jitter = Math.sqrt(varSum / times.size());
        }
        double lossPercent = 100.0 * (total - success) / (double) total;
        appendLog(String.format("Ping stat: átlag=%.2f ms, jitter=%.2f ms, veszteség=%.2f %%", avg, jitter, lossPercent));
        return new PingStats(avg, jitter, lossPercent);
    }

    private long singlePing(String host) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ping", "-n", "1", host);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String lower = line.toLowerCase();
                    if (lower.contains("idő=") || lower.contains("time=")) {
                        int idx = lower.indexOf("idő=");
                        if (idx < 0) idx = lower.indexOf("time=");
                        int msIdx = lower.indexOf("ms", idx);
                        if (idx >= 0 && msIdx > idx) {
                            String num = lower.substring(idx, msIdx).replaceAll("[^0-9]", "");
                            if (!num.isEmpty()) {
                                return Long.parseLong(num);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private double testHttpResponseTime(String urlStr) {
        appendLog("HTTP válaszidő mérés: " + urlStr);
        long start = 0;
        long end = 0;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            start = System.nanoTime();
            conn.getResponseCode();
            end = System.nanoTime();
        } catch (Exception e) {
            appendLog("HTTP válaszidő hiba: " + e.toString());
            return 0.0;
        }
        double ms = (end - start) / 1e6;
        appendLog(String.format("HTTP válaszidő: %.2f ms", ms));
        return ms;
    }

    // ========== TRACEROUTE & NETSTAT ==========

    private void onTraceroute(ActionEvent e) {
        String host = tracerouteField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Adj meg egy hostot traceroute-hoz!", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        tracerouteArea.setText("");
        backgroundExec.submit(() -> runTraceroute(host));
    }

    private void runTraceroute(String host) {
        appendTraceroute("Traceroute indul: tracert " + host);
        try {
            ProcessBuilder pb = new ProcessBuilder("tracert", host);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendTraceroute(line);
                }
            }
        } catch (Exception ex) {
            appendTraceroute("Traceroute hiba: " + ex.toString());
        }
        appendTraceroute("Traceroute vége: " + host);
    }

    private void appendTraceroute(String t) {
        SwingUtilities.invokeLater(() -> {
            tracerouteArea.append(t + System.lineSeparator());
            tracerouteArea.setCaretPosition(tracerouteArea.getDocument().getLength());
        });
    }

    private void onRefreshNetstat(ActionEvent e) {
        netstatArea.setText("");
        backgroundExec.submit(this::runNetstat);
    }

    private void runNetstat() {
        appendNetstat("netstat -ano futtatása...");
        try {
            ProcessBuilder pb = new ProcessBuilder("netstat", "-ano");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendNetstat(line);
                }
            }
        } catch (Exception ex) {
            appendNetstat("Netstat hiba: " + ex.toString());
        }
        appendNetstat("Netstat vége.");
    }

    private void appendNetstat(String t) {
        SwingUtilities.invokeLater(() -> {
            netstatArea.append(t + System.lineSeparator());
            netstatArea.setCaretPosition(netstatArea.getDocument().getLength());
        });
    }

    // ========== LAN SCAN ==========

    private void onLanScan(ActionEvent e) {
        lanScanArea.setText("");
        NetworkInterfaceWrapper selected = (NetworkInterfaceWrapper) interfaceCombo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Nincs interfész kiválasztva!", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        InetAddress addr = selected.getIPv4();
        if (addr == null || !(addr instanceof Inet4Address)) {
            JOptionPane.showMessageDialog(this, "Nincs IPv4 ezen az interfészen.", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String ip = addr.getHostAddress();
        String prefix = ip.substring(0, ip.lastIndexOf("."));
        lanScanInfoLabel.setText("Feltérképezés: " + prefix + ".1 - " + prefix + ".254");
        backgroundExec.submit(() -> scanLan(prefix));
    }

    private void scanLan(String prefix) {
        appendLan("LAN scan indul: " + prefix + ".1-254");
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + "." + i;
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isReachable(300)) {
                    appendLan("Elérhető: " + host + " (" + addr.getHostName() + ")");
                }
            } catch (IOException ignored) {}
        }
        appendLan("LAN scan vége.");
    }

    private void appendLan(String t) {
        SwingUtilities.invokeLater(() -> {
            lanScanArea.append(t + System.lineSeparator());
            lanScanArea.setCaretPosition(lanScanArea.getDocument().getLength());
        });
    }

    // ========== PORT FORWARD (UPnP) ==========

    private void onAddPortForward(ActionEvent e) {
        backgroundExec.submit(() -> upnpPortMapping(true));
    }

    private void onDeletePortForward(ActionEvent e) {
        backgroundExec.submit(() -> upnpPortMapping(false));
    }

    private void upnpPortMapping(boolean add) {
        String action = add ? "AddPortMapping" : "DeletePortMapping";
        String extPortStr = pfExternalPortField.getText().trim();
        String intPortStr = pfInternalPortField.getText().trim();
        String intHostStr = pfInternalHostField.getText().trim();
        String proto = ((String) pfProtocolCombo.getSelectedItem()).toUpperCase(Locale.ROOT);
        String desc = pfDescriptionField.getText().trim();

        appendPf("UPnP keresés indul (" + action + ")...");

        try {
            String ssdpReq =
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST:239.255.255.250:1900\r\n" +
                    "ST:urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                    "MAN:\"ssdp:discover\"\r\n" +
                    "MX:2\r\n\r\n";

            DatagramSocket ds = new DatagramSocket();
            ds.setSoTimeout(3000);
            byte[] data = ssdpReq.getBytes(StandardCharsets.UTF_8);
            DatagramPacket dp = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName("239.255.255.250"), 1900
            );
            ds.send(dp);

            byte[] buf = new byte[2048];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            ds.receive(resp);
            ds.close();

            String respStr = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
            appendPf("SSDP válasz:\n" + respStr);

            String location = null;
            for (String line : respStr.split("\r\n")) {
                if (line.toLowerCase().startsWith("location:")) {
                    location = line.substring(9).trim();
                    break;
                }
            }
            if (location == null) {
                appendPf("Nem található LOCATION header.");
                return;
            }
            appendPf("Device leíró letöltése: " + location);

            URL devUrl = new URL(location);
            HttpURLConnection conn = (HttpURLConnection) devUrl.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            InputStream in = conn.getInputStream();

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(in);
            doc.getDocumentElement().normalize();

            String controlUrl = findControlUrl(doc);
            if (controlUrl == null) {
                appendPf("Nem található WANIPConnection vezérlő URL.");
                return;
            }

            URL control = new URL(devUrl, controlUrl);
            appendPf("Control URL: " + control.toString());

            if (add) {
                sendAddPortMapping(control, extPortStr, intPortStr, intHostStr, proto, desc);
            } else {
                sendDeletePortMapping(control, extPortStr, proto);
            }

        } catch (SocketTimeoutException ste) {
            appendPf("UPnP SSDP időtúllépés – lehet, hogy nincs UPnP-s router, vagy tiltva van.");
        } catch (Exception ex) {
            appendPf("UPnP hiba: " + ex.toString());
        }
    }

    private String findControlUrl(Document doc) {
        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element s = (Element) services.item(i);
            String serviceType = getText(s, "serviceType");
            if (serviceType != null && serviceType.contains("WANIPConnection")) {
                String ctrl = getText(s, "controlURL");
                return ctrl;
            }
        }
        return null;
    }

    private String getText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent().trim();
    }

    private void sendAddPortMapping(URL controlUrl, String extPort, String intPort,
                                    String intHost, String proto, String desc) {
        String body =
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:AddPortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">" +
                "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>" + extPort + "</NewExternalPort>" +
                "<NewProtocol>" + proto + "</NewProtocol>" +
                "<NewInternalPort>" + intPort + "</NewInternalPort>" +
                "<NewInternalClient>" + intHost + "</NewInternalClient>" +
                "<NewEnabled>1</NewEnabled>" +
                "<NewPortMappingDescription>" + desc + "</NewPortMappingDescription>" +
                "<NewLeaseDuration>0</NewLeaseDuration>" +
                "</u:AddPortMapping>" +
                "</s:Body></s:Envelope>";

        sendSoap(controlUrl, body, "AddPortMapping");
    }

    private void sendDeletePortMapping(URL controlUrl, String extPort, String proto) {
        String body =
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:DeletePortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">" +
                "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>" + extPort + "</NewExternalPort>" +
                "<NewProtocol>" + proto + "</NewProtocol>" +
                "</u:DeletePortMapping>" +
                "</s:Body></s:Envelope>";

        sendSoap(controlUrl, body, "DeletePortMapping");
    }

    private void sendSoap(URL controlUrl, String body, String action) {
        try {
            HttpURLConnection conn = (HttpURLConnection) controlUrl.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:WANIPConnection:1#" + action + "\"");

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            appendPf(action + " HTTP kód: " + code);
            appendPf("Válasz:\n" + sb.toString());
        } catch (Exception ex) {
            appendPf("SOAP hiba (" + action + "): " + ex.toString());
        }
    }

    private void appendPf(String t) {
        SwingUtilities.invokeLater(() -> {
            pfLogArea.append(t + System.lineSeparator());
            pfLogArea.setCaretPosition(pfLogArea.getDocument().getLength());
        });
    }

    // ========== PACKET TESTS (UNICAST/BROADCAST/MULTICAST/ANYCAST) ==========

    private void onUnicastPing(ActionEvent e) {
        String host = unicastHostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Adj meg egy hostot unicast pinghez!", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        backgroundExec.submit(() -> {
            appendPacket("[Unicast ICMP] Ping " + host);
            long t = singlePing(host);
            if (t >= 0) {
                appendPacket("  Válaszidő: " + t + " ms");
                packetGraphPanel.addPoint("unicast", t);
            } else {
                appendPacket("  Nincs válasz / hiba.");
                packetGraphPanel.addPoint("unicast", 0);
            }
        });
    }

    private void onUnicastUdp(ActionEvent e) {
        String host = unicastHostField.getText().trim();
        int port = (Integer) unicastPortSpinner.getValue();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Adj meg egy hostot!", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Toggle start/stop
        if (unicastUdpRunning) {
            unicastUdpRunning = false;
            if (unicastUdpFuture != null) unicastUdpFuture.cancel(true);
            SwingUtilities.invokeLater(() -> unicastUdpButton.setText("UDP echo teszt"));
            appendPacket("[Unicast UDP] Leállítás kérve.");
            return;
        }

        unicastUdpRunning = true;
        SwingUtilities.invokeLater(() -> unicastUdpButton.setText("STOP UDP echo"));

        unicastUdpFuture = backgroundExec.submit(() -> {
            appendPacket("[Unicast UDP] Folyamatos mérés indul: " + host + ":" + port);
            while (unicastUdpRunning && !Thread.currentThread().isInterrupted()) {
                unicastUdpTest(host, port);
                try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
            }
            unicastUdpRunning = false;
            SwingUtilities.invokeLater(() -> unicastUdpButton.setText("UDP echo teszt"));
            appendPacket("[Unicast UDP] Folyamatos mérés leállt.");
        });
    }

    private void unicastUdpTest(String host, int port) {
        appendPacket("[Unicast UDP] Echo teszt " + host + ":" + port);
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(1000);
            byte[] msg = "UN1C4ST_TEST".getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(msg, msg.length, InetAddress.getByName(host), port);
            long start = System.nanoTime();
            socket.send(p);

            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(resp);
            } catch (SocketTimeoutException ste) {
                appendPacket("  Nincs UDP echo válasz (timeout).");
                packetGraphPanel.addPoint("unicast", 0);
                socket.close();
                return;
            }
            long end = System.nanoTime();
            socket.close();
            double ms = (end - start) / 1e6;
            String respStr = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
            appendPacket("  Válasz " + resp.getAddress().getHostAddress() + ":" + resp.getPort()
                    + " (" + respStr + "), idő: " + df2.format(ms) + " ms");
            packetGraphPanel.addPoint("unicast", ms);
        } catch (Exception ex) {
            appendPacket("  Hiba unicast UDP tesztnél: " + ex.toString());
            packetGraphPanel.addPoint("unicast", 0);
        }
    }

    private void onBroadcastTest(ActionEvent e) {
        NetworkInterfaceWrapper selected = (NetworkInterfaceWrapper) interfaceCombo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Előbb válassz interfészt!", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        InetAddress addr = selected.getIPv4();
        if (addr == null || !(addr instanceof Inet4Address)) {
            JOptionPane.showMessageDialog(this, "Nincs IPv4 az interfészen.", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String ip = addr.getHostAddress();
        String prefix = ip.substring(0, ip.lastIndexOf("."));
        String broadcastIp = prefix + ".255";

        // Toggle start/stop
        if (broadcastRunning) {
            broadcastRunning = false;
            if (broadcastFuture != null) broadcastFuture.cancel(true);
            SwingUtilities.invokeLater(() -> broadcastTestButton.setText("Broadcast UDP küldése + válaszok figyelése"));
            appendPacket("[Broadcast UDP] Leállítás kérve.");
            return;
        }

        broadcastRunning = true;
        SwingUtilities.invokeLater(() -> broadcastTestButton.setText("STOP Broadcast teszt"));

        broadcastFuture = backgroundExec.submit(() -> {
            appendPacket("[Broadcast UDP] Folyamatos mérés indul: " + broadcastIp + ":55555");
            while (broadcastRunning && !Thread.currentThread().isInterrupted()) {
                broadcastTest(broadcastIp);
                try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
            }
            broadcastRunning = false;
            SwingUtilities.invokeLater(() -> broadcastTestButton.setText("Broadcast UDP küldése + válaszok figyelése"));
            appendPacket("[Broadcast UDP] Folyamatos mérés leállt.");
        });
    }

    private void broadcastTest(String broadcastIp) {
        appendPacket("[Broadcast UDP] Küldés broadcast címre: " + broadcastIp + ":55555");
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            byte[] msg = "BR0ADCAST_TEST".getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(msg, msg.length, InetAddress.getByName(broadcastIp), 55555);
            long start = System.nanoTime();
            socket.send(p);

            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);

            long bestNs = Long.MAX_VALUE;
            int count = 0;
            while (true) {
                try {
                    socket.receive(resp);
                    long now = System.nanoTime();
                    long diff = now - start;
                    if (diff < bestNs) bestNs = diff;
                    count++;
                    String respStr = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                    appendPacket("  Válasz " + resp.getAddress().getHostAddress() + ":" + resp.getPort()
                            + " (" + respStr + "), +"
                            + df2.format(diff / 1e6) + " ms");
                } catch (SocketTimeoutException ste) {
                    break;
                }
            }
            socket.close();

            if (count == 0) {
                appendPacket("  Nem érkezett broadcast válasz.");
                packetGraphPanel.addPoint("broadcast", 0);
            } else {
                double ms = bestNs / 1e6;
                appendPacket("  " + count + " válasz, leggyorsabb: " + df2.format(ms) + " ms");
                packetGraphPanel.addPoint("broadcast", ms);
            }
        } catch (Exception ex) {
            appendPacket("  Broadcast hiba: " + ex.toString());
            packetGraphPanel.addPoint("broadcast", 0);
        }
    }

    private void onMulticastTest(ActionEvent e) {
        String groupStr = multicastGroupField.getText().trim();
        int port = (Integer) multicastPortSpinner.getValue();
        if (groupStr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Adj meg multicast címet!", "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Toggle start/stop
        if (multicastRunning) {
            multicastRunning = false;
            if (multicastFuture != null) multicastFuture.cancel(true);
            SwingUtilities.invokeLater(() -> multicastTestButton.setText("Multicast teszt"));
            appendPacket("[Multicast] Leállítás kérve.");
            return;
        }

        multicastRunning = true;
        SwingUtilities.invokeLater(() -> multicastTestButton.setText("STOP Multicast"));

        multicastFuture = backgroundExec.submit(() -> {
            appendPacket("[Multicast] Folyamatos mérés indul: " + groupStr + ":" + port);
            while (multicastRunning && !Thread.currentThread().isInterrupted()) {
                multicastTest(groupStr, port);
                try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
            }
            multicastRunning = false;
            SwingUtilities.invokeLater(() -> multicastTestButton.setText("Multicast teszt"));
            appendPacket("[Multicast] Folyamatos mérés leállt.");
        });
    }

    private void multicastTest(String groupStr, int port) {
        appendPacket("[Multicast] Teszt " + groupStr + ":" + port);
        MulticastSocket socket = null;
        try {
            InetAddress group = InetAddress.getByName(groupStr);
            socket = new MulticastSocket(port);
            socket.setSoTimeout(1500);
            socket.joinGroup(group);
            byte[] msg = "MULTICAST_TEST".getBytes(StandardCharsets.UTF_8);
            DatagramPacket out = new DatagramPacket(msg, msg.length, group, port);
            long start = System.nanoTime();
            socket.send(out);

            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);

            long bestNs = Long.MAX_VALUE;
            int count = 0;
            while (true) {
                try {
                    socket.receive(resp);
                    long now = System.nanoTime();
                    long diff = now - start;
                    if (diff < bestNs) bestNs = diff;
                    count++;
                    String respStr = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                    appendPacket("  Válasz " + resp.getAddress().getHostAddress() + ":" + resp.getPort()
                            + " (" + respStr + "), +"
                            + df2.format(diff / 1e6) + " ms");
                } catch (SocketTimeoutException ste) {
                    break;
                }
            }
            socket.leaveGroup(group);
            socket.close();

            if (count == 0) {
                appendPacket("  Nem érkezett multicast válasz.");
                packetGraphPanel.addPoint("multicast", 0);
            } else {
                double ms = bestNs / 1e6;
                appendPacket("  " + count + " válasz, leggyorsabb: " + df2.format(ms) + " ms");
                packetGraphPanel.addPoint("multicast", ms);
            }
        } catch (Exception ex) {
            appendPacket("  Multicast hiba: " + ex.toString());
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            packetGraphPanel.addPoint("multicast", 0);
        }
    }

    private void onAnycastTest(String ip, String url) {

        // If already running for this target -> stop
        if (anycastRunning && Objects.equals(anycastIpRunning, ip)) {
            anycastRunning = false;
            if (anycastFuture != null) anycastFuture.cancel(true);

            SwingUtilities.invokeLater(() -> {
                anycastCloudflareButton.setEnabled(true);
                anycastGoogleButton.setEnabled(true);
                anycastCloudflareButton.setText("Cloudflare 1.1.1.1");
                anycastGoogleButton.setText("Google 8.8.8.8");
            });

            appendPacket("[Anycast] Leállítás kérve: " + ip);
            return;
        }

        // If running for another target -> stop it first
        if (anycastRunning) {
            anycastRunning = false;
            if (anycastFuture != null) anycastFuture.cancel(true);
        }

        anycastRunning = true;
        anycastIpRunning = ip;
        anycastUrlRunning = url;

        SwingUtilities.invokeLater(() -> {
            if ("1.1.1.1".equals(ip)) {
                anycastCloudflareButton.setText("STOP 1.1.1.1");
                anycastGoogleButton.setEnabled(false);
            } else {
                anycastGoogleButton.setText("STOP 8.8.8.8");
                anycastCloudflareButton.setEnabled(false);
            }
        });

        anycastFuture = backgroundExec.submit(() -> {
            appendPacket("[Anycast] Folyamatos mérés indul: " + ip + " / " + url);
            while (anycastRunning && Objects.equals(anycastIpRunning, ip) && !Thread.currentThread().isInterrupted()) {
                long pingMs = singlePing(ip);
                double httpMs = testHttpResponseTime(url);
                appendPacket("  Ping: " + (pingMs >= 0 ? pingMs + " ms" : "nincs válasz")
                        + ", HTTP: " + df2.format(httpMs) + " ms");
                double val;
                if (pingMs < 0 && httpMs > 0) val = httpMs;
                else if (pingMs >= 0 && httpMs <= 0) val = pingMs;
                else if (pingMs >= 0 && httpMs > 0) val = (pingMs + httpMs) / 2.0;
                else val = 0.0;
                packetGraphPanel.addPoint("anycast", val);

                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }

            anycastRunning = false;
            anycastIpRunning = null;
            anycastUrlRunning = null;

            SwingUtilities.invokeLater(() -> {
                anycastCloudflareButton.setEnabled(true);
                anycastGoogleButton.setEnabled(true);
                anycastCloudflareButton.setText("Cloudflare 1.1.1.1");
                anycastGoogleButton.setText("Google 8.8.8.8");
            });

            appendPacket("[Anycast] Folyamatos mérés leállt.");
        });
    }

    private void appendPacket(String t) {
        SwingUtilities.invokeLater(() -> {
            packetTestArea.append(t + System.lineSeparator());
            packetTestArea.setCaretPosition(packetTestArea.getDocument().getLength());
        });
    }

    // ========== SETTINGS & EXPORT ==========

    private void onApplySettings(ActionEvent e) {
        pingTarget = pingTargetField.getText().trim();
        pingCount = (Integer) pingCountSpinner.getValue();
        speedTestUrl = speedTestUrlField.getText().trim();
        downloadBytes = (Integer) downloadBytesSpinner.getValue();
        uploadBytes   = (Integer) uploadBytesSpinner.getValue();
        httpTestUrl   = httpTestUrlField.getText().trim();

        appendLog("Beállítások frissítve:");
        appendLog("  Ping cél: " + pingTarget + ", darab: " + pingCount);
        appendLog("  Letöltési URL: " + speedTestUrl);
        appendLog("  Letöltés méret: " + downloadBytes + " byte");
        appendLog("  Feltöltés méret: " + uploadBytes + " byte");
        appendLog("  HTTP válaszidő URL: " + httpTestUrl);
        JOptionPane.showMessageDialog(this, "Beállítások elmentve (következő méréstől érvényes).");
    }

    private void onExportJson(ActionEvent e) {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(jsonLogFile, false), StandardCharsets.UTF_8))) {
            pw.println("[");
            synchronized (history) {
                for (int i = 0; i < history.size(); i++) {
                    Measurement m = history.get(i);
                    pw.print("  ");
                    pw.print(m.toJson());
                    if (i < history.size() - 1) pw.println(",");
                    else pw.println();
                }
            }
            pw.println("]");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "JSON export hiba: " + ex.getMessage(), "Hiba", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this, "JSON export kész: " + jsonLogFile.getAbsolutePath());
    }

    private void applyTheme(boolean dark) {
        Color bg, fg;
        if (dark) {
            bg = new Color(45, 45, 45);
            fg = new Color(230, 230, 230);
        } else {
            bg = UIManager.getColor("Panel.background");
            fg = UIManager.getColor("Panel.foreground");
        }

        setComponentTheme(this.getContentPane(), bg, fg);
        graphPanel.setDark(dark);
        packetGraphPanel.setDark(dark);
        repaint();
    }

    private void setComponentTheme(Component c, Color bg, Color fg) {
        if (c instanceof JPanel || c instanceof JScrollPane || c instanceof JSplitPane || c instanceof JTabbedPane) {
            c.setBackground(bg);
            c.setForeground(fg);
        }
        if (c instanceof JLabel || c instanceof JButton || c instanceof JCheckBox || c instanceof JTextField || c instanceof JSpinner || c instanceof JTextArea) {
            c.setBackground(bg);
            c.setForeground(fg);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                setComponentTheme(child, bg, fg);
            }
        }
    }

    // ========== Helper classes ==========

    private static class NetworkInterfaceWrapper {
        NetworkInterface ni;
        NetworkInterfaceWrapper(NetworkInterface n) { ni = n; }

        InetAddress getIPv4() {
            try {
                Enumeration<InetAddress> e = ni.getInetAddresses();
                while (e.hasMoreElements()) {
                    InetAddress a = e.nextElement();
                    if (a instanceof Inet4Address) return a;
                }
            } catch (Exception ignored) {}
            return null;
        }

        public String toString() {
            String d = ni.getDisplayName();
            return (d == null || d.isEmpty()) ? ni.getName() : d;
        }
    }

    private static class SpeedResult {
        final double downloadMbps;
        final double uploadMbps;
        SpeedResult(double d, double u) { downloadMbps = d; uploadMbps = u; }
    }

    private static class PingStats {
        final double avgMs;
        final double jitterMs;
        final double lossPercent;
        PingStats(double a, double j, double l) { avgMs = a; jitterMs = j; lossPercent = l; }
    }

    private static class Measurement {
        final Date   timestamp;
        final String interfaceName;
        final double downloadMbps;
        final double uploadMbps;
        final double pingAvgMs;
        final double jitterMs;
        final double packetLossPercent;
        final double httpResponseMs;

        Measurement(Date ts, String iface, double d, double u,
                    double p, double j, double loss, double httpMs) {
            timestamp = ts;
            interfaceName = iface;
            downloadMbps = d;
            uploadMbps = u;
            pingAvgMs = p;
            jitterMs = j;
            packetLossPercent = loss;
            httpResponseMs = httpMs;
        }

        String toJson() {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return String.format(
                    "{\"timestamp\":\"%s\",\"interface\":\"%s\",\"download_mbps\":%.4f," +
                    "\"upload_mbps\":%.4f,\"ping_avg_ms\":%.4f,\"jitter_ms\":%.4f," +
                    "\"packet_loss_percent\":%.4f,\"http_response_ms\":%.4f}",
                    df.format(timestamp),
                    escapeJson(interfaceName),
                    downloadMbps,
                    uploadMbps,
                    pingAvgMs,
                    jitterMs,
                    packetLossPercent,
                    httpResponseMs
            );
        }

        private String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // Graph panel: download (blue), upload (green), ping (red)
    private static class GraphPanel extends JPanel {
        private java.util.List<Double> downloadValues = new ArrayList<>();
        private java.util.List<Double> uploadValues = new ArrayList<>();
        private java.util.List<Double> pingValues = new ArrayList<>();
        private int maxPoints = 240;
        private boolean dark = false;

        public GraphPanel() {
            setBackground(Color.WHITE);
        }

        public synchronized void addPoint(double dl, double ul, double ping) {
            downloadValues.add(dl);
            uploadValues.add(ul);
            pingValues.add(ping);
            if (downloadValues.size() > maxPoints) {
                downloadValues.remove(0);
                uploadValues.remove(0);
                pingValues.remove(0);
            }
            repaint();
        }

        public void setDark(boolean d) {
            dark = d;
            if (dark) setBackground(new Color(30, 30, 30));
            else setBackground(Color.WHITE);
        }

        @Override
        protected synchronized void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            int leftPad = 50;
            int rightPad = 20;
            int topPad = 25;
            int bottomPad = 30;

            int gw = w - leftPad - rightPad;
            int gh = h - topPad - bottomPad;
            if (gw <= 10 || gh <= 10) return;

            Color axisColor = dark ? Color.GRAY : Color.LIGHT_GRAY;
            Color textColor = dark ? Color.WHITE : Color.BLACK;

            g2.setColor(axisColor);
            g2.drawRect(leftPad, topPad, gw, gh);

            int n = downloadValues.size();
            if (n < 2) {
                g2.setColor(textColor);
                g2.drawString("Nincs még elég mérés a grafikonhoz...", leftPad + 10, topPad + 20);
                return;
            }

            double maxVal = 1.0;
            for (int i = 0; i < n; i++) {
                maxVal = Math.max(maxVal, downloadValues.get(i));
                maxVal = Math.max(maxVal, uploadValues.get(i));
                maxVal = Math.max(maxVal, pingValues.get(i) / 10.0);
            }

            double xStep = (double) gw / (n - 1);

            g2.setColor(Color.BLUE);
            drawSeries(g2, downloadValues, maxVal, leftPad, topPad, gh, xStep);

            g2.setColor(new Color(0, 180, 0));
            drawSeries(g2, uploadValues, maxVal, leftPad, topPad, gh, xStep);

            g2.setColor(Color.RED);
            List<Double> scaledPing = new ArrayList<>();
            for (double v : pingValues) scaledPing.add(v / 10.0);
            drawSeries(g2, scaledPing, maxVal, leftPad, topPad, gh, xStep);

            g2.setColor(textColor);
            g2.drawString("Kék: Download (Mbps)", leftPad + 10, topPad + 15);
            g2.drawString("Zöld: Upload (Mbps)", leftPad + 180, topPad + 15);
            g2.drawString("Piros: Ping/10 (ms)", leftPad + 360, topPad + 15);
        }

        private void drawSeries(Graphics2D g2, List<Double> vals, double maxVal,
                                int leftPad, int topPad, int gh, double xStep) {
            int n = vals.size();
            int prevX = 0, prevY = 0;
            for (int i = 0; i < n; i++) {
                int x = leftPad + (int) Math.round(i * xStep);
                double v = vals.get(i);
                int y = topPad + gh - (int) Math.round((v / maxVal) * gh * 0.9);
                if (i > 0) {
                    g2.drawLine(prevX, prevY, x, y);
                }
                prevX = x;
                prevY = y;
            }
        }
    }

    // PacketGraphPanel: unicast/broadcast/multicast/anycast válaszidők
    private static class PacketGraphPanel extends JPanel {
        private java.util.List<Double> unicastVals = new ArrayList<>();
        private java.util.List<Double> broadcastVals = new ArrayList<>();
        private java.util.List<Double> multicastVals = new ArrayList<>();
        private java.util.List<Double> anycastVals = new ArrayList<>();
        private int maxPoints = 100;
        private boolean dark = false;

        public PacketGraphPanel() {
            setBackground(Color.WHITE);
        }

        public synchronized void addPoint(String type, double ms) {
            if (type.equals("unicast")) {
                unicastVals.add(ms);
                if (unicastVals.size() > maxPoints) unicastVals.remove(0);
            } else if (type.equals("broadcast")) {
                broadcastVals.add(ms);
                if (broadcastVals.size() > maxPoints) broadcastVals.remove(0);
            } else if (type.equals("multicast")) {
                multicastVals.add(ms);
                if (multicastVals.size() > maxPoints) multicastVals.remove(0);
            } else if (type.equals("anycast")) {
                anycastVals.add(ms);
                if (anycastVals.size() > maxPoints) anycastVals.remove(0);
            }
            repaint();
        }

        public void setDark(boolean d) {
            dark = d;
            if (dark) setBackground(new Color(30, 30, 30));
            else setBackground(Color.WHITE);
        }

        @Override
        protected synchronized void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            int leftPad = 50;
            int rightPad = 20;
            int topPad = 25;
            int bottomPad = 30;

            int gw = w - leftPad - rightPad;
            int gh = h - topPad - bottomPad;
            if (gw <= 10 || gh <= 10) return;

            Color axisColor = dark ? Color.GRAY : Color.LIGHT_GRAY;
            Color textColor = dark ? Color.WHITE : Color.BLACK;

            g2.setColor(axisColor);
            g2.drawRect(leftPad, topPad, gw, gh);

            int n = Math.max(Math.max(unicastVals.size(), broadcastVals.size()),
                             Math.max(multicastVals.size(), anycastVals.size()));
            if (n < 1) {
                g2.setColor(textColor);
                g2.drawString("Nincs még teszt mérés...", leftPad + 10, topPad + 20);
                return;
            }

            double maxVal = 1.0;
            for (double v : unicastVals) maxVal = Math.max(maxVal, v);
            for (double v : broadcastVals) maxVal = Math.max(maxVal, v);
            for (double v : multicastVals) maxVal = Math.max(maxVal, v);
            for (double v : anycastVals) maxVal = Math.max(maxVal, v);

            double xStep = (double) gw / Math.max(1, n - 1);

            // Minden sorozatot külön színnel rajzolunk (ha van elég pont)
            g2.setColor(Color.BLUE);
            drawSeries(g2, unicastVals, maxVal, leftPad, topPad, gh, xStep);

            g2.setColor(new Color(0, 200, 0));
            drawSeries(g2, broadcastVals, maxVal, leftPad, topPad, gh, xStep);

            g2.setColor(Color.ORANGE);
            drawSeries(g2, multicastVals, maxVal, leftPad, topPad, gh, xStep);

            g2.setColor(Color.MAGENTA);
            drawSeries(g2, anycastVals, maxVal, leftPad, topPad, gh, xStep);

            g2.setColor(textColor);
            g2.drawString("Kék: Unicast", leftPad + 10, topPad + 15);
            g2.drawString("Zöld: Broadcast", leftPad + 120, topPad + 15);
            g2.drawString("Narancs: Multicast", leftPad + 260, topPad + 15);
            g2.drawString("Lila: Anycast", leftPad + 420, topPad + 15);
        }

        private void drawSeries(Graphics2D g2, List<Double> vals, double maxVal,
                                int leftPad, int topPad, int gh, double xStep) {
            int n = vals.size();
            if (n == 0) return;
            int prevX = 0, prevY = 0;
            for (int i = 0; i < n; i++) {
                int x = leftPad + (int) Math.round(i * xStep);
                double v = vals.get(i);
                int y = topPad + gh - (int) Math.round((v / maxVal) * gh * 0.9);
                if (i > 0) {
                    g2.drawLine(prevX, prevY, x, y);
                }
                prevX = x;
                prevY = y;
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            new NetworkMonitor().setVisible(true);
        });
    }
}
