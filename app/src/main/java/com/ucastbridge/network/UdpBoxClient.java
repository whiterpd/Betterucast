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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps one UDP socket/source port alive for discovery, pairing and commands. */
public final class UdpBoxClient implements AutoCloseable {
    public static final int DISCOVERY_PORT = 17789;
    public static final int PREFERRED_LOCAL_PORT = 60000;

    private DatagramSocket socket;
    private Inet4Address phoneIp;
    private int localPort;
    private InetAddress boxIp;
    private int boxPort;

    public synchronized void open(Inet4Address localIpv4) throws IOException {
        closeSocketOnly();
        phoneIp = localIpv4;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PREFERRED_LOCAL_PORT));
        } catch (IOException fixedPortFailure) {
            closeSocketOnly();
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
        }
        socket.setBroadcast(true);
        socket.setSoTimeout(450);
        localPort = socket.getLocalPort();
    }

    public synchronized int getLocalPort() { return localPort; }
    public synchronized Inet4Address getPhoneIp() { return phoneIp; }
    public synchronized boolean isPaired() {
        return socket != null && !socket.isClosed() && boxIp != null && boxPort > 0;
    }

    public List<DeviceCandidate> discover(long listenMillis) throws Exception {
        ensureOpen();
        byte[] packet = LegacyUcastProtocol.discoveryPacket(localPort, phoneIp);
        DatagramPacket request = new DatagramPacket(packet, packet.length,
                InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
        socket.send(request);

        long until = System.currentTimeMillis() + Math.max(500, listenMillis);
        Map<String, DeviceCandidate> found = new LinkedHashMap<>();
        byte[] buffer = new byte[8192];
        while (System.currentTimeMillis() < until) {
            try {
                DatagramPacket received = new DatagramPacket(buffer, buffer.length);
                socket.receive(received);
                String sourceIp = received.getAddress().getHostAddress();
                String clear = decodeBestEffort(received.getData(), received.getLength());
                DeviceCandidate candidate = parseCandidate(sourceIp, received.getPort(), clear);
                found.put(sourceIp + ":" + candidate.port, candidate);
            } catch (java.net.SocketTimeoutException timeout) {
                // Continue until the discovery window expires.
            }
        }
        return new ArrayList<>(found.values());
    }

    public synchronized void pair(String ip, int port, String receiverName) throws Exception {
        ensureOpen();
        InetAddress parsed = InetAddress.getByName(ip.trim());
        if (!(parsed instanceof Inet4Address)) throw new IllegalArgumentException("Receiver must have an IPv4 address");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid receiver UDP port");
        byte[] payload = LegacyUcastProtocol.pairingPacket(receiverName, localPort, phoneIp);
        send(payload, parsed, port);
        // Mark the session paired only after the datagram was accepted by the local socket.
        boxIp = parsed;
        boxPort = port;
    }

    public synchronized void sendWebUrl(String url) throws Exception {
        ensurePaired();
        send(LegacyUcastProtocol.webUrlPacket(url, phoneIp), boxIp, boxPort);
    }

    public synchronized void sendLocalMedia(String marker, String fileName, String mediaUrl) throws Exception {
        ensurePaired();
        send(LegacyUcastProtocol.localMediaPacket(marker, fileName, mediaUrl, phoneIp), boxIp, boxPort);
    }

    public synchronized String waitForConnectAck(long timeoutMillis) throws IOException {
        ensureOpen();
        long until = System.currentTimeMillis() + timeoutMillis;
        byte[] buffer = new byte[8192];
        while (System.currentTimeMillis() < until) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String clear = decodeBestEffort(packet.getData(), packet.getLength());
                if (clear.toLowerCase().contains("utconnectok")) return "Receiver acknowledged connection: " + clear;
                if (!clear.isEmpty()) return "Receiver replied: " + clear;
            } catch (java.net.SocketTimeoutException ignored) { }
        }
        return "No explicit connection acknowledgement received. The pairing request was sent.";
    }

    public synchronized InetAddress getBoxIp() { return boxIp; }
    public synchronized int getBoxPort() { return boxPort; }

    private void send(byte[] bytes, InetAddress target, int port) throws IOException {
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, target, port);
        socket.send(packet);
    }

    private String decodeBestEffort(byte[] bytes, int length) {
        try { return LegacyUcastProtocol.decodeEnvelope(bytes, length, phoneIp); }
        catch (Exception notLegacyOrBadKey) {
            return new String(bytes, 0, length, StandardCharsets.UTF_8).replace("\u0000", "").trim();
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
                if (p > 0 && p <= 65535 && p != localPort && p != DISCOVERY_PORT) { port = p; break; }
            } catch (NumberFormatException ignored) { }
        }
        String[] pieces = raw.split("[\\[\\]{};,|\\r\\n]+" );
        for (String piece : pieces) {
            String value = piece.trim();
            if (value.isEmpty() || value.contains(ip) || value.matches("[0-9]{1,5}") || value.equalsIgnoreCase("utconnectok")) continue;
            if (value.length() <= 64 && !value.contains("http") && !value.contains("ucastdev")) { name = value; break; }
        }
        return new DeviceCandidate(ip, name, port, sourcePort, raw);
    }

    private void ensureOpen() throws SocketException {
        if (socket == null || socket.isClosed()) throw new SocketException("UDP socket is not open. Start device discovery first.");
    }

    private void ensurePaired() throws SocketException {
        ensureOpen();
        if (boxIp == null || boxPort < 1) throw new SocketException("Pair with a receiver first");
    }

    private void closeSocketOnly() {
        if (socket != null) socket.close();
        socket = null;
        localPort = 0;
        boxIp = null;
        boxPort = 0;
    }

    @Override public synchronized void close() { closeSocketOnly(); }

    public static final class DeviceCandidate {
        public final String ip;
        public final String name;
        public final int port;
        public final int sourcePort;
        public final String raw;
        DeviceCandidate(String ip, String name, int port, int sourcePort, String raw) {
            this.ip = ip; this.name = name; this.port = port; this.sourcePort = sourcePort; this.raw = raw;
        }
        @Override public String toString() {
            return ip + ":" + port + (name.isEmpty() ? "" : "  —  " + name)
                    + (raw.isEmpty() ? "" : "\n  Reply: " + raw);
        }
    }
}
