package com.ucastbridge.protocol;

import com.ucastbridge.network.RangeHttpServer;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class RangeHttpServerSmokeTest {
    public static void main(String[] args) throws Exception {
        File file = File.createTempFile("ucastbridge", ".txt");
        Files.write(file.toPath(), "0123456789".getBytes(StandardCharsets.US_ASCII));
        RangeHttpServer server = new RangeHttpServer(file, InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.1"), 0);
        try {
            server.start();
            String url = server.getUrl();
            int port = server.getBoundPort();
            String query = url.substring(url.indexOf('?') + 1);
            String partial = request(port, "GET /?" + query + " HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=2-5\r\n\r\n");
            require(partial.startsWith("HTTP/1.1 206 Partial Content"), "206 response");
            require(partial.endsWith("2345"), "partial body");
            String invalid = request(port, "GET /?" + query + " HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=99-100\r\n\r\n");
            require(invalid.startsWith("HTTP/1.1 416 Range Not Satisfiable"), "invalid range response");
            String missingToken = request(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            require(missingToken.startsWith("HTTP/1.1 404 Not Found"), "token gate");
            String head = request(port, "HEAD /?" + query + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            require(head.startsWith("HTTP/1.1 200 OK") && head.endsWith("\r\n\r\n"), "HEAD response without body");
            String fragmented = requestFragmented(port, "GET /?" + query + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            require(fragmented.startsWith("HTTP/1.1 200 OK") && fragmented.endsWith("0123456789"), "fragmented request headers");
            require(unauthorizedPeerIsRejected(port, query), "peer IP allowlist");
            System.out.println("PASS: HTTP range/416, HEAD, fragmented headers, token gate and paired-peer allowlist");
        } finally {
            server.close();
            file.delete();
        }
    }

    private static String request(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2500);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[8192];
            int n = socket.getInputStream().read(buffer);
            return n <= 0 ? "" : new String(buffer, 0, n, StandardCharsets.ISO_8859_1);
        }
    }

    private static String requestFragmented(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2500);
            for (byte value : request.getBytes(StandardCharsets.US_ASCII)) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
            }
            byte[] buffer = new byte[8192];
            int n = socket.getInputStream().read(buffer);
            return n <= 0 ? "" : new String(buffer, 0, n, StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean unauthorizedPeerIsRejected(int port, String token) throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0));
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(1500);
            socket.getOutputStream().write(("GET /?" + token + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return socket.getInputStream().read() == -1;
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
