package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.codec.Hex;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Composite state keys, golden vectors from the release codec (`showcase_codec.py composite-key`). */
class WorkflowKeysTest {
    @Test
    void compositeKeysMatchTheReleaseCodec() {
        assertEquals("79616e6f2d636f6d706f736974652d73746174652d763100066f7264657273000b6f726465722d432d323030",
                Hex.encode(WorkflowModule.compositeKey("orders", "order-C-200".getBytes(StandardCharsets.UTF_8))));
        assertEquals("79616e6f2d636f6d706f736974652d73746174652d76310009617070726f76616c730010692f70726f706f73616c2d432d323030",
                Hex.encode(WorkflowModule.compositeKey("approvals", "i/proposal-C-200".getBytes(StandardCharsets.UTF_8))));
        assertEquals("79616e6f2d636f6d706f736974652d73746174652d7631000772656c65617365000f722f72656c656173652d432d323030",
                Hex.encode(WorkflowModule.compositeKey("release", "r/release-C-200".getBytes(StandardCharsets.UTF_8))));
    }
}
