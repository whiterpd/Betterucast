package com.ucastbridge.network;

import com.ucastbridge.protocol.LegacyUcastProtocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one UDP socket/source port alive for discovery, pairing and commands.
 *
 * The diagnostic buffer is deliberately bounded and contains packet metadata,
 * packet bytes, and best-effort decoded text. It does not log AES keys or IVs.
 */
public final class UdpBoxClient implements AutoCloseable {
    public static final int DISCOVERY_PORT = 17789;
    public static final int PREFERRED_LOCAL_PORT = 60000;
    private static final int MAX_DIAGNOSTIC_CHARS = 16000;
    private static final int MAX_LOGGED_PACKET_BYTES = 512;

    private DatagramSocket socket;
    private Inet4Address phoneIp;
    private int localPort;
    private InetAddress boxIp;
    private int boxPort;
    private final StringBuilder diagnostics = new StringBuilder();

    public synchronized void open(Inet4Address localIpv4) throws IOException {
        closeSocketOnly();
        phoneIp = localIpv4;
        log("OPEN requested; phone IPv4=" + addressText(localIpv4));

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            log("BIND attempt: local UDP port " + PREFERRED_LOCAL_PORT);
            socket.bind(new InetSocketAddress(PREFERRED_LOCAL_PORT));
            log("BIND success: local UDP port " + socket.getLocalPort());
        } catch (IOException fixedPortFailure) {
            log("BIND failed for port " + PREFERRED_LOCAL_PORT + ": "
                    + fixedPortFailure.getClass().getSimpleName() + ": "
                    + safeMessage(fixedPortFailure));
            closeSocketOnly();
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            log("BIND fallback attempt: ephemeral local UDP port 0");
            socket.bind(new InetSocketAddress(0));
            log("BIND fallback success: local UDP port " + socket.getLocalPort());
        }

        socket.setBroadcast(true);
        socket.setSoTimeout(450);
        localPort = socket.getLocalPort();
        log("SOCKET ready: local=" + addressText(socket.getLocalAddress()) + ":" + localPort
                + ", broadcast=true, receiveTimeoutMs=450");
    }

    public synchronized int getLocalPort() { return localPort; }
    public synchronized Inet4Address getPhoneIp() { return phoneIp; }
    public synchronized boolean isPaired() {
        return socket != null && !socket.isClosed() && boxIp != null && boxPort > 0;
    }

    /** Returns the bounded diagnostic text for display in the activity log. */
    public synchronized String getDiagnostics() {
        return diagnostics.toString();
    }

    /** Clears old diagnostic entries without closing the socket. */
    public synchronized void clearDiagnostics() {
        diagnostics.setLength(0);
    }

    public synchronized List<DeviceCandidate> discover(long listenMillis) throws Exception {
        ensureOpen();
        log("DISCOVERY start: listenWindowMs=" + listenMillis
                + ", destination=255.255.255.255:" + DISCOVERY_PORT
                + ", advertisedPhonePort=" + localPort);

        byte[] packet = LegacyUcastProtocol.discoveryPacket(localPort, phoneIp);
        DatagramPacket request = new DatagramPacket(packet, packet.length,
                InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
        send(request, "discovery");

        long until = System.currentTimeMillis() + Math.max(500, listenMillis);
        Map<String, DeviceCandidate> found = new LinkedHashMap<>();
        byte[] buffer = new byte[8192];
        while (System.currentTimeMillis() < until) {
            try {
                DatagramPacket received = new DatagramPacket(buffer, buffer.length);
                socket.receive(received);
                logReceived("discovery", received);
                String sourceIp = received.getAddress().getHostAddress();
                String clear = decodeBestEffort(received.getData(), received.getLength());
                DeviceCandidate candidate = parseCandidate(sourceIp, received.getPort(), clear);
                found.put(sourceIp + ":" + candidate.port, candidate);
                log("DISCOVERY accepted candidate: " + candidate);
            } catch (java.net.SocketTimeoutException timeout) {
                // Continue until the discovery window expires.
            }
        }
        log("DISCOVERY complete: candidates=" + found.size());
        return new ArrayList<>(found.values());
    }

    public synchronized void pair(String ip, int port, String receiverName) throws Exception {
        ensureOpen();
        InetAddress parsed = InetAddress.getByName(ip.trim());
        if (!(parsed instanceof Inet4Address))
            throw new IllegalArgumentException("Receiver must have an IPv4 address");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid receiver UDP port");

        log("PAIR start: receiver=" + parsed.getHostAddress() + ":" + port
                + ", receiverName=" + quoteForLog(receiverName)
                + ", phoneIp=" + addressText(phoneIp)
                + ", phoneSourcePort=" + localPort);

        byte[] payload = LegacyUcastProtocol.pairingPacket(receiverName, localPort, phoneIp);
        DatagramPacket request = new DatagramPacket(payload, payload.length, parsed, port);
        send(request, "pairing");

        // A successful send() only means the local OS accepted the datagram.
        log("PAIR transport result: datagram accepted by local socket; awaiting receiver response");
        boxIp = parsed;
        boxPort = port;
    }

    public synchronized void sendWebUrl(String url) throws Exception {
        ensurePaired();
        byte[] payload = LegacyUcastProtocol.webUrlPacket(url, phoneIp);
        send(new DatagramPacket(payload, payload.length, boxIp, boxPort), "web-url");
    }

    public synchronized void sendLocalMedia(String marker, String fileName, String mediaUrl) throws Exception {
        ensurePaired();
        byte[] payload = LegacyUcastProtocol.localMediaPacket(marker, fileName, mediaUrl, phoneIp);
        send(new DatagramPacket(payload, payload.length, boxIp, boxPort), "local-media");
    }

    public synchronized String waitForConnectAck(long timeoutMillis) throws IOException {
        ensureOpen();
        long started = System.currentTimeMillis();
        long until = started + timeoutMillis;
        log("ACK wait start: timeoutMs=" + timeoutMillis
                + ", expected=" + (boxIp == null ? "any receiver" : boxIp.getHostAddress() + ":" + boxPort));

        byte[] buffer = new byte[8192];
        while (System.currentTimeMillis() < until) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                logReceived("ack-wait", packet);

                String clear = decodeBestEffort(packet.getData(), packet.getLength());
                boolean fromExpectedBox = boxIp == null || boxIp.equals(packet.getAddress());
                if (!fromExpectedBox) {
                    log("ACK parser: discarded packet because source IP is not the selected receiver");
                    continue;
                }

                if (clear.toLowerCase(Locale.US).contains("utconnectok")) {
                    log("ACK parser: VALID acknowledgement matched utconnectok");
                    return "Receiver acknowledged connection: " + clear + "\n\nDiagnostics:\n" + diagnostics;
                }
                if (!clear.isEmpty()) {
                    log("ACK parser: packet received but no utconnectok marker; treated as non-acknowledgement");
                    return "Receiver replied without explicit connection acknowledgement: " + clear
                            + "\n\nDiagnostics:\n" + diagnostics;
                }
                log("ACK parser: packet received but decoded text was empty; treated as non-acknowledgement");
            } catch (java.net.SocketTimeoutException ignored) {
                // Continue until the overall deadline expires.
            }
        }

        long elapsed = System.currentTimeMillis() - started;
        log("ACK wait timeout: no acceptable acknowledgement after " + elapsed + " ms");
        return "No explicit connection acknowledgement received. The pairing request was sent."
                + "\n\nDiagnostics:\n" + diagnostics;
    }

    public synchronized InetAddress getBoxIp() { return boxIp; }
    public synchronized int getBoxPort() { return boxPort; }

    private void send(DatagramPacket packet, String label) throws IOException {
        ensureOpen();
        log("SEND " + label + ": localPort=" + localPort
                + ", destination=" + addressText(packet.getAddress()) + ":" + packet.getPort()
                + ", length=" + packet.getLength()
                + ", hex=" + hex(packet.getData(), packet.getOffset(), packet.getLength()));
        socket.send(packet);
        log("SEND " + label + " success: socket.send returned normally");
    }

    private void logReceived(String label, DatagramPacket packet) {
        int length = packet.getLength();
        String clear = decodeBestEffort(packet.getData(), length);
        log("RECV " + label + ": source=" + addressText(packet.getAddress()) + ":" + packet.getPort()
                + ", length=" + length
                + ", hex=" + hex(packet.getData(), packet.getOffset(), length)
                + ", decoded=" + quoteForLog(clear));
    }

    private String decodeBestEffort(byte[] bytes, int length) {
        try {
            return LegacyUcastProtocol.decodeEnvelope(bytes, length, phoneIp);
        } catch (Exception notLegacyOrBadKey) {
            return new String(bytes, 0, length, StandardCharsets.UTF_8)
                    .replace("\u0000", "").trim();
        }
    }

    private DeviceCandidate parseCandidate(String sourceIp, int sourcePort, String raw) {
        String ip = sourceIp;
        String name = "";
        int port = sourcePort;
        Matcher ipMatcher = Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])").matcher(raw);
        if (ipMatcher.find()) ip = ipMatcher.group();
        Matcher portMatcher = Pattern.compile("(?i)(?:port|udp|:|\\[|,|\\s)([0-9]{2,5})(?:\\]|,|\\s|$)").matcher(raw);
        while (portMatcher.find()) {
            try {
                int p = Integer.parseInt(portMatcher.group(1));
                if (p > 0 && p <= 65535 && p != localPort && p != DISCOVERY_PORT) {
                    port = p;
                    break;
                }
            } catch (NumberFormatException ignored) { }
        }
        String[] pieces = raw.split("[\\[\\]{};,|\\r\\n]+");
        for (String piece : pieces) {
            String value = piece.trim();
            if (value.isEmpty() || value.contains(ip) || value.matches("[0-9]{1,5}")
                    || value.equalsIgnoreCase("utconnectok")) continue;
            if (value.length() <= 64 && !value.contains("http") && !value.contains("ucastdev")) {
                name = value;
                break;
            }
        }
        return new DeviceCandidate(ip, name, port, sourcePort, raw);
    }

    private void ensureOpen() throws SocketException {
        if (socket == null || socket.isClosed())
            throw new SocketException("UDP socket is not open. Start device discovery first.");
    }

    private void ensurePaired() throws SocketException {
        ensureOpen();
        if (boxIp == null || boxPort < 1)
            throw new SocketException("Pair with a receiver first");
    }

    private void closeSocketOnly() {
        if (socket != null) socket.close();
        socket = null;
        localPort = 0;
        boxIp = null;
        boxPort = 0;
    }

    private synchronized void log(String message) {
        String line = "[" + System.currentTimeMillis() + "] " + message + "\n";
        diagnostics.append(line);
        if (diagnostics.length() > MAX_DIAGNOSTIC_CHARS) {
            diagnostics.delete(0, diagnostics.length() - MAX_DIAGNOSTIC_CHARS);
            int firstNewline = diagnostics.indexOf("\n");
            if (firstNewline >= 0) diagnostics.delete(0, firstNewline + 1);
        }
    }

    private static String hex(byte[] bytes, int offset, int length) {
        int count = Math.min(length, MAX_LOGGED_PACKET_BYTES);
        StringBuilder out = new StringBuilder(count * 3 + 32);
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", bytes[offset + i] & 0xff));
        }
        if (length > count) out.append(" …(+").append(length - count).append(" bytes)");
        return out.toString();
    }

    private static String addressText(InetAddress address) {
        return address == null ? "<none>" : address.getHostAddress();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isEmpty() ? "no detail" : message;
    }

    private static String quoteForLog(String value) {
        if (value == null) return "<null>";
        String normalized = value.replace("\r", "\\r").replace("\n", "\\n");
        if (normalized.length() > 300) normalized = normalized.substring(0, 300) + "…";
        return "\"" + normalized + "\"";
    }

    @Override public synchronized void close() {
        log("CLOSE requested");
        closeSocketOnly();
    }

    public static final class DeviceCandidate {
        public final String ip;
        public final String name;
        public final int port;
        public final int sourcePort;
        public final String raw;

        DeviceCandidate(String ip, String name, int port, int sourcePort, String raw) {
            this.ip = ip;
            this.name = name;
            this.port = port;
            this.sourcePort = sourcePort;
            this.raw = raw;
        }

        @Override public String toString() {
            return ip + ":" + port + (name.isEmpty() ? "" : "  —  " + name)
                    + (raw.isEmpty() ? "" : "\n  Reply: " + raw);
        }
    }
}
