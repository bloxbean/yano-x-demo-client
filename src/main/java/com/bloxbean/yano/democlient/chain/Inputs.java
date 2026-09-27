package com.bloxbean.yano.democlient.chain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** Field validation shared by modules; messages are shown to the visitor as-is. */
public final class Inputs {
    private static final Pattern MEMBER_KEY = Pattern.compile("[0-9a-f]{64}");

    private final Map<String, String> fields;

    public Inputs(Map<String, String> fields) {
        this.fields = fields == null ? Map.of() : fields;
    }

    public String text(String name, String label, int maxBytes) {
        String value = raw(name).strip();
        if (value.isEmpty()) {
            throw new ChainModule.InvalidInput(label + " is required");
        }
        return bounded(value, label, maxBytes);
    }

    public String optionalText(String name, String label, int maxBytes) {
        return bounded(raw(name).strip(), label, maxBytes);
    }

    public String matching(String name, String label, String regex, String rule) {
        String value = raw(name).strip();
        if (!value.matches(regex)) {
            throw new ChainModule.InvalidInput(label + " " + rule);
        }
        return value;
    }

    public long number(String name, String label, long min, long max) {
        String value = raw(name).strip();
        try {
            long parsed = new BigInteger(value).longValueExact();
            if (parsed < min || parsed > max) {
                throw new ChainModule.InvalidInput(label + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException | ArithmeticException notNumber) {
            throw new ChainModule.InvalidInput(label + " must be a whole number");
        }
    }

    public String memberKey(String name, String label) {
        String value = raw(name).strip().toLowerCase(java.util.Locale.ROOT);
        if (!MEMBER_KEY.matcher(value).matches()) {
            throw new ChainModule.InvalidInput(label + " must be a member key (64 hex characters)");
        }
        return value;
    }

    private String raw(String name) {
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    private static String bounded(String value, String label, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new ChainModule.InvalidInput(label + " is limited to " + maxBytes + " bytes");
        }
        if (value.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) {
            throw new ChainModule.InvalidInput(label + " contains control characters");
        }
        return value;
    }
}
