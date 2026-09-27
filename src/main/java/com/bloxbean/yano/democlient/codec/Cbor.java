package com.bloxbean.yano.democlient.codec;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal deterministic CBOR (RFC 8949) used for app-chain payloads.
 *
 * <p>Encoding matches the release showcase codec byte for byte: definite lengths and the shortest
 * argument encoding. Decoding is for display only: it turns a value into plain Java objects
 * (Long/BigInteger, byte[], String, List, Map, Tagged, Boolean, null).
 */
public final class Cbor {
    private Cbor() {
    }

    /** A tagged data item (major type 6), e.g. Plutus constructors 121..127. */
    public record Tagged(long tag, Object value) {
    }

    // ------------------------------------------------------------------ encoding

    public static byte[] uint(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("uint must be non-negative");
        }
        return head(0, value);
    }

    public static byte[] bstr(byte[] value) {
        return concat(head(2, value.length), value);
    }

    public static byte[] text(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        return concat(head(3, utf8.length), utf8);
    }

    public static byte[] array(byte[]... items) {
        return array(List.of(items));
    }

    public static byte[] array(List<byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head(4, items.size()));
        items.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Map with text keys in the given order (callers pass canonical order when it matters). */
    public static byte[] textMap(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head(5, entries.size()));
        entries.forEach((key, value) -> {
            out.writeBytes(text(key));
            out.writeBytes(value);
        });
        return out.toByteArray();
    }

    public static byte[] head(int major, long value) {
        int type = major << 5;
        if (value < 24) {
            return new byte[] {(byte) (type | value)};
        }
        if (value < 0x100) {
            return new byte[] {(byte) (type | 24), (byte) value};
        }
        if (value < 0x10000) {
            return new byte[] {(byte) (type | 25), (byte) (value >> 8), (byte) value};
        }
        if (value < 0x1_0000_0000L) {
            return new byte[] {(byte) (type | 26), (byte) (value >> 24), (byte) (value >> 16),
                    (byte) (value >> 8), (byte) value};
        }
        byte[] out = new byte[9];
        out[0] = (byte) (type | 27);
        for (int i = 0; i < 8; i++) {
            out[8 - i] = (byte) (value >> (8 * i));
        }
        return out;
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ decoding

    public static Object decode(byte[] cbor) {
        Reader reader = new Reader(cbor);
        Object value = reader.item(0);
        if (reader.position != cbor.length) {
            throw new IllegalArgumentException("trailing bytes after CBOR item");
        }
        return value;
    }

    private static final class Reader {
        private static final int MAX_DEPTH = 64;
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        Object item(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("CBOR nesting too deep");
            }
            int initial = next();
            int major = initial >>> 5;
            int info = initial & 0x1f;
            if (major == 7) {
                return simple(info);
            }
            if (info == 31) {
                return indefinite(major, depth);
            }
            BigInteger argument = argument(info);
            return switch (major) {
                case 0 -> number(argument);
                case 1 -> number(argument.negate().subtract(BigInteger.ONE));
                case 2 -> take(length(argument));
                case 3 -> new String(take(length(argument)), StandardCharsets.UTF_8);
                case 4 -> {
                    int size = length(argument);
                    List<Object> items = new ArrayList<>(Math.min(size, 1024));
                    for (int i = 0; i < size; i++) {
                        items.add(item(depth + 1));
                    }
                    yield items;
                }
                case 5 -> {
                    int size = length(argument);
                    Map<Object, Object> entries = new LinkedHashMap<>();
                    for (int i = 0; i < size; i++) {
                        Object key = item(depth + 1);
                        entries.put(key instanceof byte[] bytes ? Hex.encode(bytes) : key, item(depth + 1));
                    }
                    yield entries;
                }
                case 6 -> new Tagged(argument.longValueExact(), item(depth + 1));
                default -> throw new IllegalArgumentException("unsupported CBOR major type " + major);
            };
        }

        private Object indefinite(int major, int depth) {
            switch (major) {
                case 2, 3 -> {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    while (peek() != 0xff) {
                        Object chunk = item(depth + 1);
                        out.writeBytes(chunk instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : (byte[]) chunk);
                    }
                    position++;
                    return major == 2 ? out.toByteArray() : out.toString(StandardCharsets.UTF_8);
                }
                case 4 -> {
                    List<Object> items = new ArrayList<>();
                    while (peek() != 0xff) {
                        items.add(item(depth + 1));
                    }
                    position++;
                    return items;
                }
                case 5 -> {
                    Map<Object, Object> entries = new LinkedHashMap<>();
                    while (peek() != 0xff) {
                        Object key = item(depth + 1);
                        entries.put(key instanceof byte[] bytes ? Hex.encode(bytes) : key, item(depth + 1));
                    }
                    position++;
                    return entries;
                }
                default -> throw new IllegalArgumentException("invalid indefinite-length item");
            }
        }

        private Object simple(int info) {
            return switch (info) {
                case 20 -> Boolean.FALSE;
                case 21 -> Boolean.TRUE;
                case 22, 23 -> null;
                case 25 -> {
                    take(2);
                    yield "half-float";
                }
                case 26 -> Float.intBitsToFloat(argument(26).intValue());
                case 27 -> Double.longBitsToDouble(argument(27).longValue());
                default -> throw new IllegalArgumentException("unsupported simple value " + info);
            };
        }

        private BigInteger argument(int info) {
            if (info < 24) {
                return BigInteger.valueOf(info);
            }
            int size = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw new IllegalArgumentException("reserved CBOR additional info " + info);
            };
            return new BigInteger(1, take(size));
        }

        private static Object number(BigInteger value) {
            return value.bitLength() < 63 ? (Object) value.longValue() : value;
        }

        private int length(BigInteger argument) {
            if (argument.bitLength() > 31 || argument.intValue() > data.length - position) {
                throw new IllegalArgumentException("CBOR length exceeds input");
            }
            return argument.intValue();
        }

        private int next() {
            if (position >= data.length) {
                throw new IllegalArgumentException("truncated CBOR");
            }
            return data[position++] & 0xff;
        }

        private int peek() {
            if (position >= data.length) {
                throw new IllegalArgumentException("truncated CBOR");
            }
            return data[position] & 0xff;
        }

        private byte[] take(int size) {
            if (size > data.length - position) {
                throw new IllegalArgumentException("truncated CBOR");
            }
            byte[] out = new byte[size];
            System.arraycopy(data, position, out, 0, size);
            position += size;
            return out;
        }
    }
}
