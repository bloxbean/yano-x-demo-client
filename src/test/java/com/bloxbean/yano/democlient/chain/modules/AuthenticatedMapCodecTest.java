package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.codec.Hex;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden vectors from the Yano X 0.1.0-pre6 release CLI:
 * <pre>
 * CMD=$(python3 examples/showcase/tools/showcase_codec.py authmap put released-products sku-42 &lt;hex("released: sku-42")&gt;)
 * ACT=$(./yano.sh appchain authenticated-map action --command-hex $CMD --assignments 0:approval:product-release:1)
 * ./yano.sh appchain authenticated-map action-commitment --action-hex $ACT
 * ./yano.sh appchain authenticated-map approval-payload --action-hex $ACT --genesis-id 97840b8f…9d5c
 * EV=$(./yano.sh appchain authenticated-map approval-reference --action-hex $ACT --proposal release-sku-42 \
 *        --indexes 0 --policy product-release --policy-revision 1)
 * ./yano.sh appchain authenticated-map command --action-hex $ACT --evidence-hex $EV
 * </pre>
 */
class AuthenticatedMapCodecTest {
    private static final String ACTION = "8401008187007172656c65617365642d70726f647563747346736b752d34325072656c65617365643a"
            + "20736b752d3432004040818400046f70726f647563742d72656c6561736501";
    private static final String COMMITMENT = "d972abdb67f00afc24e38c061a01891f45daf397c04631beba295478c28c2b62";
    private static final String GENESIS = "97840b8fce9048d914925205d60193f88b136f7f9ff92f20d2aa680cc9b19d5c";
    private static final String PAYLOAD = "eb477f2dedd8117b71ea0e347947fdcd4488254cee4155a80a43085e0dbb0c1b";
    private static final String EVIDENCE = "86016e72656c656173652d736b752d34325820d972abdb67f00afc24e38c061a01891f45daf397c0"
            + "4631beba295478c28c2b6281006f70726f647563742d72656c6561736501";
    private static final String COMMAND = "830158488401008187007172656c65617365642d70726f647563747346736b752d3432507265"
            + "6c65617365643a20736b752d3432004040818400046f70726f647563742d72656c656173650181820158468601"
            + "6e72656c656173652d736b752d34325820d972abdb67f00afc24e38c061a01891f45daf397c04631beba295478c28c2b62"
            + "81006f70726f647563742d72656c6561736501";

    @Test
    void matchesTheReleaseCli() {
        byte[] action = AuthenticatedMapModule.Codec.action("released-products", "sku-42".getBytes(StandardCharsets.UTF_8),
                "released: sku-42".getBytes(StandardCharsets.UTF_8), "product-release");
        assertEquals(ACTION, Hex.encode(action));
        byte[] commitment = AuthenticatedMapModule.Codec.actionCommitment(action);
        assertEquals(COMMITMENT, Hex.encode(commitment));
        assertEquals(PAYLOAD, Hex.encode(AuthenticatedMapModule.Codec.approvalPayloadHash(Hex.decode(GENESIS), commitment)));
        byte[] evidence = AuthenticatedMapModule.Codec.approvalReference("release-sku-42", commitment, "product-release");
        assertEquals(EVIDENCE, Hex.encode(evidence));
        assertEquals(COMMAND, Hex.encode(AuthenticatedMapModule.Codec.command(action, evidence)));
    }
}
