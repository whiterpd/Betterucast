package com.ucastbridge;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ucastbridge.network.RangeHttpServer;
import com.ucastbridge.network.UdpBoxClient;
import com.ucastbridge.util.NetworkUtils;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_MEDIA = 7101;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText boxIpField;
    private EditText boxNameField;
    private EditText boxPortField;
    private EditText webUrlField;
    private TextView phoneIpLabel;
    private TextView localPortLabel;
    private TextView selectedMediaLabel;
    private TextView statusView;

    private UdpBoxClient udpClient;
    private Inet4Address phoneIp;
    private File selectedMedia;
    private String selectedDisplayName;
    private RangeHttpServer httpServer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshNetworkDetails();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("UCast Bridge");
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("A compatibility-focused replacement for the UCast Android companion app.");
        subtitle.setTextSize(14);
        content.addView(subtitle, params(LinearLayout.LayoutParams.MATCH_PARENT, -2, 0, 0, 0, dp(12)));

        section(content, "PHONE NETWORK");
        phoneIpLabel = infoText("Wi-Fi IPv4: checking…");
        content.addView(phoneIpLabel);
        localPortLabel = infoText("UDP source port: not opened");
        content.addView(localPortLabel, params(-1, -2, 0, 0, 0, dp(14)));

        section(content, "RECEIVER BOX");
        boxIpField = field("Box IPv4 address", "192.168.1.100"); content.addView(boxIpField, fieldParams());
        boxNameField = field("Box/device name (case-sensitive)", ""); content.addView(boxNameField, fieldParams());
        boxPortField = field("Box UDP port", Integer.toString(UdpBoxClient.DISCOVERY_PORT));
        boxPortField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        content.addView(boxPortField, fieldParams());

        Button scanButton = button("1  ·  Discover UCast boxes");
        content.addView(scanButton, buttonParams());
        scanButton.setOnClickListener(v -> scanForBoxes());
        Button pairButton = button("2  ·  Pair / connect to box");
        content.addView(pairButton, buttonParams());
        pairButton.setOnClickListener(v -> pairToBox());

        section(content, "SEND A WEB VIDEO URL");
        webUrlField = field("https://…", "https://");
        webUrlField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(webUrlField, fieldParams());
        Button sendUrlButton = button("Send URL to receiver");
        content.addView(sendUrlButton, buttonParams());
        sendUrlButton.setOnClickListener(v -> sendWebUrl());

        section(content, "SEND A LOCAL VIDEO / AUDIO FILE");
        selectedMediaLabel = infoText("No media selected");
        content.addView(selectedMediaLabel, params(-1, -2, 0, 0, 0, dp(8)));
        Button pickButton = button("Choose video or audio file");
        content.addView(pickButton, buttonParams());
        pickButton.setOnClickListener(v -> chooseMedia());
        Button sendMediaButton = button("Start private file host for this box and send");
        content.addView(sendMediaButton, buttonParams());
        sendMediaButton.setOnClickListener(v -> sendLocalMedia());

        section(content, "ACTIVITY LOG");
        statusView = infoText("Ready. Connect the phone and UCast box to the same Wi-Fi network.");
        statusView.setTextIsSelectable(true);
        content.addView(statusView, params(-1, -2, 0, 0, 0, dp(20)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void refreshNetworkDetails() {
        phoneIp = NetworkUtils.getWifiIpv4(this);
        phoneIpLabel.setText("Wi-Fi IPv4: " + (phoneIp == null ? "not available" : phoneIp.getHostAddress()));
        if (phoneIp == null) log("Connect to the same Wi-Fi network as the receiver. No Wi-Fi IPv4 was detected.");
    }

    private void scanForBoxes() {
        refreshNetworkDetails();
        if (phoneIp == null) { log("Discovery stopped: phone Wi-Fi IPv4 is unavailable."); return; }
        runWorker("Searching for receivers…", () -> {
            ensureUdpClient();
            java.util.List<UdpBoxClient.DeviceCandidate> candidates = udpClient.discover(3500);
            runOnUiThread(() -> {
                localPortLabel.setText("UDP source port: " + udpClient.getLocalPort());
                if (candidates.isEmpty()) {
                    log("No UCast replies received on UDP/17789. Confirm same Wi-Fi/subnet and that the box is awake.");
                    return;
                }
                StringBuilder report = new StringBuilder("Found " + candidates.size() + " candidate(s):\n");
                for (UdpBoxClient.DeviceCandidate candidate : candidates) report.append("• ").append(candidate).append("\n\n");
                UdpBoxClient.DeviceCandidate first = candidates.get(0);
                boxIpField.setText(first.ip);
                boxPortField.setText(Integer.toString(first.port));
                if (!first.name.isEmpty()) boxNameField.setText(first.name);
                log(report.toString() + "Selected the first candidate. Verify name and port before pairing.");
            });
        });
    }

    private void pairToBox() {
        refreshNetworkDetails();
        String ip = boxIpField.getText().toString().trim();
        String name = boxNameField.getText().toString().trim();
        int port = parsePort(boxPortField.getText().toString());
        if (phoneIp == null || ip.isEmpty() || name.isEmpty() || port < 1) {
            log("Pairing requires phone Wi-Fi IPv4, receiver IP, exact receiver name and UDP port."); return;
        }
        runWorker("Sending UCast pairing request…", () -> {
            ensureUdpClient();
            udpClient.pair(ip, port, name);
            String result = udpClient.waitForConnectAck(2500);
            runOnUiThread(() -> log(result + "\nPhone UDP source port: " + udpClient.getLocalPort()));
        });
    }

    private void sendWebUrl() {
        String url = webUrlField.getText().toString().trim();
        if (url.isEmpty() || url.equals("https://")) { log("Enter a web video URL first."); return; }
        refreshNetworkDetails();
        if (phoneIp == null) { log("No usable phone IPv4 address. Cannot send the legacy command."); return; }
        runWorker("Encoding and sending web URL…", () -> {
            ensureUdpClient();
            udpClient.sendWebUrl(url);
            InetAddress receiver = udpClient.getBoxIp();
            runOnUiThread(() -> log("Web URL command sent to " + (receiver == null ? "the paired receiver" : receiver.getHostAddress()) + "."));
        });
    }

    private void chooseMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_MEDIA);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MEDIA || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        runWorker("Importing selected media…", () -> {
            String displayName = queryDisplayName(uri);
            File target = new File(getCacheDir(), "ucast-share-" + System.currentTimeMillis() + "-" + safeFileName(displayName));
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new java.io.IOException("Unable to open the selected document");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            }
            selectedMedia = target;
            selectedDisplayName = displayName;
            runOnUiThread(() -> {
                selectedMediaLabel.setText(displayName + "  (" + formatSize(target.length()) + ")");
                log("Media copied into the app's private cache. It is not publicly shared.");
            });
        });
    }

    private void sendLocalMedia() {
        final File mediaToSend = selectedMedia;
        final String displayName = selectedDisplayName;
        if (mediaToSend == null || !mediaToSend.isFile() || !mediaToSend.canRead()) {
            log("Choose a readable local video or audio file first."); return;
        }
        refreshNetworkDetails();
        final Inet4Address bindIp = phoneIp;
        if (bindIp == null) { log("No Wi-Fi IPv4. Cannot host a media file for the box."); return; }
        if (udpClient == null || !udpClient.isPaired()) {
            log("Discover and pair with the receiver first."); return;
        }
        if (!bindIp.equals(udpClient.getPhoneIp())) {
            log("The phone Wi-Fi address changed. Scan and pair with the receiver again before sending media."); return;
        }
        final InetAddress pairedBoxIp = udpClient.getBoxIp();
        if (pairedBoxIp == null) { log("The paired receiver address is unavailable. Pair again."); return; }
        final String name = displayName == null ? mediaToSend.getName() : displayName;
        final String lower = name.toLowerCase(Locale.ROOT);
        final String marker;
        if (lower.matches(".*\\.(mp4|m4v|mkv|avi|mpg|mpeg|mov|webm)$")) marker = "utLocV0100";
        else if (lower.matches(".*\\.(mp3|m4a|aac|wav|flac|ogg)$")) marker = "utLocA0100";
        else { log("Unsupported media type. Choose a common video or audio file."); return; }

        runWorker("Starting the phone-side media server…", () -> {
            if (!udpClient.isPaired() || !pairedBoxIp.equals(udpClient.getBoxIp()))
                throw new IllegalStateException("The receiver pairing changed. Pair with the box again.");
            if (httpServer != null) { httpServer.close(); httpServer = null; }
            RangeHttpServer newServer = new RangeHttpServer(mediaToSend, bindIp, pairedBoxIp);
            try {
                newServer.start();
                String url = newServer.getUrl();
                udpClient.sendLocalMedia(marker, name, url);
                httpServer = newServer;
                runOnUiThread(() -> log("Local media command sent. Phone server: " + url
                        + "\nThe server is bound only to " + bindIp.getHostAddress() + ":8080 and only accepts the paired box IP."
                        + "\nThe local-media command format is reverse-engineered but has not yet been validated on your physical box."));
            } catch (Exception failure) {
                newServer.close();
                throw failure;
            }
        });
    }

    private void ensureUdpClient() throws Exception {
        if (phoneIp == null) throw new IllegalStateException("A usable phone IPv4 address is required");
        if (udpClient != null && phoneIp.equals(udpClient.getPhoneIp())) return;
        boolean reset = udpClient != null;
        if (udpClient != null) udpClient.close();
        udpClient = new UdpBoxClient();
        udpClient.open(phoneIp);
        if (reset) log("Wi-Fi address changed. The UDP session was reset; pair with the receiver again.");
    }

    private void runWorker(String initial, ThrowingRunnable action) {
        log(initial);
        worker.execute(() -> {
            try { action.run(); }
            catch (Exception e) { runOnUiThread(() -> log("ERROR: " + (e.getMessage() == null ? e.toString() : e.getMessage()))); }
        });
    }

    private void log(String message) {
        if (statusView == null) return;
        runOnUiThread(() -> {
            CharSequence old = statusView.getText();
            statusView.setText(old + "\n\n" + message);
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "media.bin";
    }

    private static String safeFileName(String name) { return name.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static int parsePort(String value) { try { int p = Integer.parseInt(value.trim()); return p >= 1 && p <= 65535 ? p : -1; } catch (Exception e) { return -1; } }
    private static String formatSize(long size) { return size < 1024 ? size + " B" : String.format(Locale.ROOT, "%.1f MB", size / 1048576.0); }

    private void section(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextColor(0xff345b83);
        root.addView(label, params(-1, -2, 0, dp(12), 0, dp(6)));
    }
    private TextView infoText(String text) { TextView t = new TextView(this); t.setText(text); t.setTextSize(13); t.setTextColor(0xff333333); return t; }
    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setSingleLine(true); e.setText(value); e.setHint(hint); e.setTextSize(15); return e; }
    private Button button(String text) { Button b = new Button(this); b.setText(text); return b; }
    private LinearLayout.LayoutParams buttonParams() { return params(-1, -2, 0, 0, 0, dp(4)); }
    private LinearLayout.LayoutParams fieldParams() { return params(-1, -2, 0, 0, 0, dp(4)); }
    private LinearLayout.LayoutParams params(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(left, top, right, bottom); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (httpServer != null) httpServer.close();
        if (udpClient != null) udpClient.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
