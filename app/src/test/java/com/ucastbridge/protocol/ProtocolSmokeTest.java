package com.ucastbridge.protocol;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Standalone smoke test: run with ../tools/run-smoke-tests.sh or compile manually with javac. */
public final class ProtocolSmokeTest {
    public static void main(String[] args) throws Exception {
        require("Zm9vYmFy".equals(Base64Compat.encodeToString("foobar".getBytes(StandardCharsets.US_ASCII))), "Base64 encode vector");
        require("foobar".equals(new String(Base64Compat.decode("Zm9vYmFy"), StandardCharsets.US_ASCII)), "Base64 decode vector");
        try { Base64Compat.decode("A==="); throw new AssertionError("invalid Base64 padding accepted"); }
        catch (IllegalArgumentException expected) { /* expected */ }

        InetAddress phone = InetAddress.getByName("192.168.1.23");
        byte[] discovery = LegacyUcastProtocol.discoveryPacket(60000, phone);
        String decodedDiscovery = LegacyUcastProtocol.decodeEnvelope(discovery, discovery.length, phone);
        require("ut search ucastdev[60000]".equals(decodedDiscovery), "discovery round trip");

        byte[] pair = LegacyUcastProtocol.pairingPacket("LivingRoom", 60000, phone);
        String decodedPair = LegacyUcastProtocol.decodeEnvelope(pair, pair.length, phone);
        require("paring[LivingRoom]::60000ut192.168.1.23".equals(decodedPair), "pairing round trip");

        byte[] web = LegacyUcastProtocol.webUrlPacket("https://example.test/path/movie.mp4", phone);
        String decodedWeb = LegacyUcastProtocol.decodeEnvelope(web, web.length, phone);
        require(decodedWeb.startsWith("WebV"), "web command envelope");
        require(decodedWeb.contains("0100"), "web command type/version field");
        require(decodedWeb.contains("movie.mp4"), "web command filename");
        require(decodedWeb.contains("https://example.test/path/movie.mp4"), "web command URL");

        byte[] local = LegacyUcastProtocol.localMediaPacket("utLocV0100", "movie.mp4", "http://192.168.1.23:8080?token", phone);
        String decodedLocal = LegacyUcastProtocol.decodeEnvelope(local, local.length, phone);
        require(decodedLocal.startsWith("LocV"), "local video envelope");
        require(decodedLocal.contains("movie.mp4"), "local video filename");
        require(decodedLocal.contains("http://192.168.1.23:8080?token"), "local video URL");

        System.out.println("PASS: legacy AES envelope round-trip, discovery, pairing, web URL and local media packet smoke tests");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
