package com.bloxbean.yano.democlient.codec;

import java.util.HexFormat;
import java.util.regex.Pattern;

public final class Hex {
    private static final Pattern CANONICAL = Pattern.compile("(?:[0-9a-f]{2})*");

    private Hex() {
    }

    public static String encode(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Lowercase, even-length hex only (the node's canonical form). */
    public static byte[] decode(String hex) {
        if (hex == null || !CANONICAL.matcher(hex).matches()) {
            throw new IllegalArgumentException("expected lowercase even-length hex");
        }
        return HexFormat.of().parseHex(hex);
    }
}
