package com.ucastbridge.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small single-file HTTP server compatible with the original box's GET/Range client. */
public final class RangeHttpServer implements AutoCloseable {
    public static final int PORT = 8080;
    private final File file;
    private final InetAddress bindAddress;
    private final InetAddress allowedPeer;
    private final String token;
    private final int requestedPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread worker;

    public RangeHttpServer(File file, InetAddress phoneWifiIp, InetAddress boxIp) throws Exception {
        this(file, phoneWifiIp, boxIp, PORT);
    }

    /** Test/diagnostic overload; production app uses the protocol port 8080. */
    public RangeHttpServer(File file, InetAddress phoneWifiIp, InetAddress boxIp, int requestedPort) throws Exception {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("Selected media file is not readable");
        this.file = file;
        this.bindAddress = phoneWifiIp;
        this.allowedPeer = boxIp;
        if (requestedPort < 0 || requestedPort > 65535) throw new IllegalArgumentException("Invalid TCP port");
        this.requestedPort = requestedPort;
        this.token = sha1(Long.toString(System.currentTimeMillis() / 1000L));
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        server = new ServerSocket();
        server.setReuseAddress(false);
        // Bind to Wi-Fi IPv4 only; unlike the original app, never expose this file server on all interfaces.
        server.bind(new InetSocketAddress(bindAddress, requestedPort), 4);
        running.set(true);
        worker = new Thread(this::serveLoop, "ucast-file-http");
        worker.setDaemon(true);
        worker.start();
    }

    public int getBoundPort() { return server == null ? -1 : server.getLocalPort(); }
    public String getUrl() { return "http://" + bindAddress.getHostAddress() + ":" + getBoundPort() + "?" + token; }

    private void serveLoop() {
        while (running.get()) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(5000);
                if (!client.getInetAddress().equals(allowedPeer)) {
                    client.close();
                    continue;
                }
                try (Socket accepted = client) { handle(accepted); }
            } catch (IOException e) {
                if (running.get()) { /* next accept may succeed; close() ends the loop */ }
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String headerText = readHeaders(in);
        if (headerText == null) return;
        String[] lines = headerText.split("\\r?\\n");
        if (lines.length == 0) return;
        String[] request = lines[0].trim().split("\\s+");
        if (request.length < 2) { respond(out, 400, "Bad Request", "text/plain", 0); return; }
        String method = request[0].toUpperCase(Locale.ROOT);
        String target = request[1];
        if (!(method.equals("GET") || method.equals("HEAD"))) {
            respond(out, 405, "Method Not Allowed", "text/plain", 0);
            return;
        }
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) query = target.substring(q + 1);
        if (!query.equals(token)) { respond(out, 404, "Not Found", "text/plain", 0); return; }

        String rangeHeader = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String key = lines[i].substring(0, colon).trim();
            if (key.equalsIgnoreCase("Range")) rangeHeader = lines[i].substring(colon + 1).trim();
        }

        long size = file.length();
        long start = 0;
        long end = Math.max(0, size - 1);
        boolean partial = false;
        if (rangeHeader != null) {
            long[] bounds = parseRange(rangeHeader, size);
            if (bounds == null) {
                String response = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush(); return;
            }
            start = bounds[0]; end = bounds[1]; partial = true;
        }
        long length = size == 0 ? 0 : end - start + 1;
        String status = partial ? "206 Partial Content" : "200 OK";
        String safeName = asciiFallback(file.getName());
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Server: UCastBridge/1.0\r\n")
                .append("Content-Type: ").append(mimeType(file.getName())).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Cache-Control: no-cache\r\n")
                .append("Connection: close\r\n");
        if (partial) headers.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        if (method.equals("GET") && length > 0) {
            try (InputStream fileIn = new BufferedInputStream(new FileInputStream(file))) {
                skipFully(fileIn, start);
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int n = fileIn.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) break;
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
        }
        out.flush();
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < 16 * 1024) {
            int b = in.read();
            if (b < 0) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.US_ASCII.name());
            bytes.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) return bytes.toString(StandardCharsets.US_ASCII.name());
            } else state = b == '\r' ? 1 : 0;
        }
        throw new IOException("HTTP request headers exceed 16 KB");
    }

    private static long[] parseRange(String header, long size) {
        if (size <= 0 || !header.toLowerCase(Locale.ROOT).startsWith("bytes=") || header.contains(",")) return null;
        String spec = header.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            long start, end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) return null;
                start = Math.max(0, size - suffix); end = size - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? size - 1 : Long.parseLong(right);
            }
            if (start < 0 || start >= size || end < start) return null;
            end = Math.min(end, size - 1);
            return new long[]{start, end};
        } catch (NumberFormatException e) { return null; }
    }

    private static void skipFully(InputStream in, long amount) throws IOException {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) { if (in.read() < 0) throw new IOException("Unexpected end of file"); skipped = 1; }
            remaining -= skipped;
        }
    }

    private static void respond(OutputStream out, int code, String reason, String mime, long length) throws IOException {
        String value = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + mime
                + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n";
        out.write(value.getBytes(StandardCharsets.US_ASCII)); out.flush();
    }

    private static String mimeType(String name) {
        String ext = name.toLowerCase(Locale.ROOT);
        if (ext.endsWith(".mp4") || ext.endsWith(".m4v")) return "video/mp4";
        if (ext.endsWith(".mkv")) return "video/x-matroska";
        if (ext.endsWith(".avi")) return "video/x-msvideo";
        if (ext.endsWith(".mpg") || ext.endsWith(".mpeg")) return "video/mpeg";
        if (ext.endsWith(".mp3")) return "audio/mpeg";
        if (ext.endsWith(".m4a")) return "audio/mp4";
        if (ext.endsWith(".aac")) return "audio/aac";
        if (ext.endsWith(".wav")) return "audio/wav";
        if (ext.endsWith(".flac")) return "audio/flac";
        if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) return "image/jpeg";
        if (ext.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static String asciiFallback(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "media.bin";
        return safe.length() > 100 ? safe.substring(safe.length() - 100) : safe;
    }

    private static String sha1(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.US_ASCII));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    @Override public synchronized void close() {
        running.set(false);
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        server = null;
        if (worker != null) worker.interrupt();
        worker = null;
    }
}
