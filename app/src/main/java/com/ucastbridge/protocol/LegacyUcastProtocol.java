package com.ucastbridge.protocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire-compatibility implementation inferred from UCast 1.0.2's ARM64 native library.
 * This intentionally reproduces its legacy AES-256-CBC / zero-padding protocol.
 * It is NOT suitable as a security design for new protocols.
 */
public final class LegacyUcastProtocol {
    private static final byte[] KEY_SEED = "S58B7BME75V9V549C2T6SB26412K2U2U".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IV_SEED = "U6XN2CFBNC3VCT74".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OUTER = new byte[]{'u', 't'};

    private LegacyUcastProtocol() {}

    public static byte[] discoveryPacket(int localUdpPort, InetAddress phoneIpv4) throws Exception {
        if (localUdpPort < 1 || localUdpPort > 65535) throw new IllegalArgumentException("Invalid local UDP port");
        requireIpv4(phoneIpv4);
        String text = "ut search ucastdev[" + localUdpPort + "]";
        return wrapPlaintext(text.getBytes(StandardCharsets.UTF_8), phoneIpv4);
    }

    public static byte[] pairingPacket(String boxName, int localUdpPort, InetAddress phoneIpv4) throws Exception {
        if (boxName == null || boxName.trim().isEmpty()) throw new IllegalArgumentException("Receiver name is required for legacy pairing");
        if (localUdpPort < 1 || localUdpPort > 65535) throw new IllegalArgumentException("Invalid local UDP port");
        if (boxName.indexOf(']') >= 0 || boxName.indexOf('\n') >= 0 || boxName.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Receiver name contains unsupported characters");
        requireIpv4(phoneIpv4);
        String text = "paring[" + boxName + "]::" + localUdpPort + "ut" + phoneIpv4.getHostAddress();
        return wrapPlaintext(text.getBytes(StandardCharsets.UTF_8), phoneIpv4);
    }

    /** Encodes the complete legacy web playback message. */
    public static byte[] webUrlPacket(String urlText, InetAddress phoneIpv4) throws Exception {
        URI uri = URI.create(urlText.trim());
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null)
            throw new IllegalArgumentException("Enter a valid http:// or https:// URL");
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        if (fileName.isEmpty()) fileName = uri.getHost();
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        byte[] urlBytes = urlText.trim().getBytes(StandardCharsets.UTF_8);
        if (fileName.length() > 255) throw new IllegalArgumentException("URL filename is too long for the legacy packet");
        if (urlText.trim().length() > 65535) throw new IllegalArgumentException("URL is too long for the legacy packet");

        ByteArrayBuilder message = new ByteArrayBuilder();
        message.addAscii("utWebV0100");
        message.add((byte) (fileName.length() & 0xff)); // Qt QString::size(): UTF-16 code units
        message.add(nameBytes);
        int urlLength = urlText.trim().length();
        message.add((byte) ((urlLength >>> 8) & 0xff));
        message.add((byte) (urlLength & 0xff));
        message.add(urlBytes);
        return checkedCommandPacket(message.toByteArray(), phoneIpv4);
    }

    /**
     * Recreates the local-video/local-audio message shape seen in MediaDataInfo.
     * The marker and field layout are recovered from native code; receiver-side behavior
     * still needs validation against hardware before being treated as certified.
     */
    public static byte[] localMediaPacket(String marker, String fileName, String httpUrl, InetAddress phoneIpv4) throws Exception {
        if (!("utLocV0100".equals(marker) || "utLocA0100".equals(marker)))
            throw new IllegalArgumentException("Only local video and audio markers are implemented");
        if (fileName == null || fileName.trim().isEmpty()) throw new IllegalArgumentException("File name is required");
        if (fileName.length() > 255 || httpUrl.length() > 65535) throw new IllegalArgumentException("File metadata is too long");
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        byte[] urlBytes = httpUrl.getBytes(StandardCharsets.UTF_8);
        ByteArrayBuilder message = new ByteArrayBuilder();
        message.addAscii(marker);
        message.add((byte) (fileName.length() & 0xff));
        message.add(nameBytes);
        int urlLength = httpUrl.length();
        message.add((byte) ((urlLength >>> 8) & 0xff));
        message.add((byte) (urlLength & 0xff));
        message.add(urlBytes);
        return checkedCommandPacket(message.toByteArray(), phoneIpv4);
    }

    private static byte[] checkedCommandPacket(byte[] fullCommand, InetAddress phoneIpv4) throws Exception {
        if (fullCommand.length < 2 || fullCommand[0] != 'u' || fullCommand[1] != 't')
            throw new IllegalArgumentException("Legacy command must start with 'ut'");
        int xor = 0;
        for (byte value : fullCommand) xor ^= (value & 0xff);
        byte[] checksum = new byte[]{(byte) xor, '0', '0', '0', '0', '0'};
        byte[] withChecksum = new byte[fullCommand.length + checksum.length];
        System.arraycopy(fullCommand, 0, withChecksum, 0, 6);
        System.arraycopy(checksum, 0, withChecksum, 6, checksum.length);
        System.arraycopy(fullCommand, 6, withChecksum, 12, fullCommand.length - 6);
        // Native writeDataToUdpDevice() strips the command's initial two bytes before encryption.
        return wrapPlaintext(Arrays.copyOfRange(withChecksum, 2, withChecksum.length), phoneIpv4);
    }

    private static byte[] wrapPlaintext(byte[] plaintext, InetAddress phoneIpv4) throws Exception {
        byte[] encrypted = aesEncryptZeroPadded(plaintext, phoneIpv4);
        String encoded = Base64Compat.encodeToString(encrypted)
                .replace('+', '!').replace('/', ')').replace('=', '(');
        byte[] body = encoded.getBytes(StandardCharsets.US_ASCII);
        byte[] packet = new byte[OUTER.length + body.length];
        System.arraycopy(OUTER, 0, packet, 0, OUTER.length);
        System.arraycopy(body, 0, packet, OUTER.length, body.length);
        return packet;
    }

    public static String decodeEnvelope(byte[] datagram, int length, InetAddress phoneIpv4) throws Exception {
        if (length < 3 || datagram[0] != 'u' || datagram[1] != 't')
            throw new IllegalArgumentException("Not a UCast envelope");
        String b64 = new String(datagram, 2, length - 2, StandardCharsets.US_ASCII)
                .replace('!', '+').replace(')', '/').replace('(', '=');
        byte[] cipher = Base64Compat.decode(b64);
        Cipher aes = configuredCipher(Cipher.DECRYPT_MODE, phoneIpv4);
        byte[] clear = aes.doFinal(cipher);
        int used = clear.length;
        while (used > 0 && clear[used - 1] == 0) used--;
        return new String(clear, 0, used, StandardCharsets.UTF_8);
    }

    private static byte[] aesEncryptZeroPadded(byte[] plaintext, InetAddress phoneIpv4) throws Exception {
        int paddedLength = ((plaintext.length + 15) / 16) * 16;
        byte[] padded = Arrays.copyOf(plaintext, paddedLength);
        return configuredCipher(Cipher.ENCRYPT_MODE, phoneIpv4).doFinal(padded);
    }

    private static Cipher configuredCipher(int mode, InetAddress phoneIpv4) throws Exception {
        requireIpv4(phoneIpv4);
        byte[][] material = transitionKey(phoneIpv4);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(material[0], "AES"), new IvParameterSpec(material[1]));
        return cipher;
    }

    /** Key/IV mutation recovered from DeviceOperation::transitionKey(). */
    static byte[][] transitionKey(InetAddress phoneIpv4) {
        int ip = phoneIpv4.getAddress()[0] & 0xff;
        int first = ip / 100;
        int tens = (ip / 10) % 10;
        int ones = ip % 10;
        int second = first == tens ? first + 1 : tens;
        int third = first == ones ? first + 2 : ones;

        byte[] key = KEY_SEED.clone();
        byte[] iv = IV_SEED.clone();
        swap(key, first, first + 29);
        iv[first] = (byte) ((iv[first] + 1) & 0xff);
        swap(key, second, second + 19);
        iv[second] = (byte) ((iv[second] + 1) & 0xff);
        swap(key, third, third + 9);
        iv[third] = (byte) ((iv[third] + 1) & 0xff);
        return new byte[][]{key, iv};
    }

    private static void swap(byte[] bytes, int a, int b) {
        if (a < 0 || b < 0 || a >= bytes.length || b >= bytes.length)
            throw new IllegalArgumentException("IP-derived legacy key index is outside bounds");
        byte temp = bytes[a]; bytes[a] = bytes[b]; bytes[b] = temp;
    }

    private static void requireIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) throw new IllegalArgumentException("A valid phone Wi-Fi IPv4 address is required");
    }

    private static final class ByteArrayBuilder {
        private byte[] data = new byte[128];
        private int size;
        void add(byte b) { ensure(1); data[size++] = b; }
        void add(byte[] bytes) { ensure(bytes.length); System.arraycopy(bytes, 0, data, size, bytes.length); size += bytes.length; }
        void addAscii(String value) { add(value.getBytes(StandardCharsets.US_ASCII)); }
        byte[] toByteArray() { return Arrays.copyOf(data, size); }
        private void ensure(int more) {
            if (size + more > data.length) data = Arrays.copyOf(data, Math.max(size + more, data.length * 2));
        }
    }
}
