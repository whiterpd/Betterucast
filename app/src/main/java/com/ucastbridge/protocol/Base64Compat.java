package com.ucastbridge.protocol;

/** Small RFC 4648 Base64 codec kept Android API 23 compatible (java.util.Base64 is API 26+). */
final class Base64Compat {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < REVERSE.length; i++) REVERSE[i] = -1;
        for (int i = 0; i < ALPHABET.length; i++) REVERSE[ALPHABET[i]] = i;
    }

    private Base64Compat() {}

    static String encodeToString(byte[] input) {
        StringBuilder out = new StringBuilder(((input.length + 2) / 3) * 4);
        for (int i = 0; i < input.length; i += 3) {
            int a = input[i] & 0xff;
            int hasB = i + 1 < input.length ? 1 : 0;
            int hasC = i + 2 < input.length ? 1 : 0;
            int b = hasB == 1 ? input[i + 1] & 0xff : 0;
            int c = hasC == 1 ? input[i + 2] & 0xff : 0;
            out.append(ALPHABET[a >>> 2]);
            out.append(ALPHABET[((a & 0x03) << 4) | (b >>> 4)]);
            out.append(hasB == 1 ? ALPHABET[((b & 0x0f) << 2) | (c >>> 6)] : '=');
            out.append(hasC == 1 ? ALPHABET[c & 0x3f] : '=');
        }
        return out.toString();
    }

    static byte[] decode(String input) {
        if (input == null) throw new IllegalArgumentException("Base64 input is null");
        if ((input.length() & 3) != 0) throw new IllegalArgumentException("Invalid Base64 length");
        int padding = 0;
        if (!input.isEmpty() && input.charAt(input.length() - 1) == '=') padding++;
        if (input.length() > 1 && input.charAt(input.length() - 2) == '=') padding++;
        byte[] output = new byte[(input.length() / 4) * 3 - padding];
        int out = 0;
        for (int i = 0; i < input.length(); i += 4) {
            boolean last = i + 4 == input.length();
            char ca = input.charAt(i), cb = input.charAt(i + 1);
            char cc = input.charAt(i + 2), cd = input.charAt(i + 3);
            int a = value(ca), b = value(cb);
            int c = cc == '=' ? 0 : value(cc);
            int d = cd == '=' ? 0 : value(cd);
            if (a < 0 || b < 0 || c < 0 || d < 0) throw new IllegalArgumentException("Invalid Base64 character");
            if ((!last && (cc == '=' || cd == '=')) || (cc == '=' && cd != '='))
                throw new IllegalArgumentException("Invalid Base64 padding");
            if (cc == '=' && (b & 0x0f) != 0) throw new IllegalArgumentException("Non-zero Base64 padding bits");
            if (last && cd == '=' && cc != '=' && (c & 0x03) != 0) throw new IllegalArgumentException("Non-zero Base64 padding bits");
            int packed = (a << 18) | (b << 12) | (c << 6) | d;
            if (out < output.length) output[out++] = (byte) (packed >>> 16);
            if (out < output.length) output[out++] = (byte) (packed >>> 8);
            if (out < output.length) output[out++] = (byte) packed;
        }
        return output;
    }

    private static int value(char value) {
        return value < REVERSE.length ? REVERSE[value] : -1;
    }
}
