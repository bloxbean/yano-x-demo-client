package com.bloxbean.yano.democlient.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Golden vectors produced by the Yano X 0.1.0-pre3 release tool examples/showcase/tools/showcase_codec.py. */
class CborTest {
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void encodesLikeTheReleaseShowcaseCodec() {
        assertEquals("83004864656d6f2f6b65794568656c6c6f",
                Hex.encode(array(uint(0), bstr(utf8("demo/key")), bstr(utf8("hello")))));
        assertEquals("8500666974656d2d31477061796c6f61640200",
                Hex.encode(array(uint(0), text("item-1"), bstr(utf8("payload")), uint(2), uint(0))));
        assertEquals("830065616c6963651903e8", Hex.encode(array(uint(0), text("alice"), uint(1000))));
        assertEquals("830163626f621819", Hex.encode(array(uint(1), text("bob"), uint(25))));
        assertEquals("8368656e746974792d3158202d49316473cb68324b3f807c6d88c5618f6a422801f52ee3f6b3c29784504fc060",
                Hex.encode(array(text("entity-1"), bstr(Blake2b.digest256(utf8("content"))), text(""))));
        assertEquals("8301008187006b6174746163686d656e7473426b31426869004040",
                Hex.encode(array(uint(1), uint(0), array(array(uint(0), text("attachments"), bstr(utf8("k1")),
                        bstr(Hex.decode("6869")), uint(0), bstr(new byte[0]), bstr(new byte[0]))))));
    }

    @Test
    void encodesEveryHeadWidth() {
        assertEquals("17", Hex.encode(uint(23)));
        assertEquals("1818", Hex.encode(uint(24)));
        assertEquals("190100", Hex.encode(uint(256)));
        assertEquals("1a00010000", Hex.encode(uint(65536)));
        assertEquals("1b0000000100000000", Hex.encode(uint(4294967296L)));
    }

    @Test
    void blake2bMatchesPythonHashlib() {
        assertEquals("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
                Hex.encode(Blake2b.digest256(new byte[0])));
        assertEquals("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319",
                Hex.encode(Blake2b.digest256(utf8("abc"))));
        assertEquals("7eb9c902d76f4876a0494066a9773bd4f8c0caf612f4683e352af66bc12af33e",
                Hex.encode(Blake2b.digest256(utf8("x".repeat(200)))));
    }

    @Test
    void decodesForDisplay() {
        Object decoded = Cbor.decode(Hex.decode("8500666974656d2d31477061796c6f61640200"));
        assertEquals(List.of(0L, "item-1", List.of(), 2L, 0L).size(), ((List<?>) decoded).size());
        List<?> items = (List<?>) decoded;
        assertEquals("item-1", items.get(1));
        assertArrayEquals(utf8("payload"), (byte[]) items.get(2));
        Map<?, ?> map = (Map<?, ?>) Cbor.decode(Cbor.textMap(Map.of("sku", text("A-1"))));
        assertEquals("A-1", map.get("sku"));
        assertThrows(IllegalArgumentException.class, () -> Cbor.decode(Hex.decode("8301")));
        assertThrows(IllegalArgumentException.class, () -> Cbor.decode(Hex.decode("0000")));
    }
}
