package com.bloxbean.yano.democlient.actor;

import com.bloxbean.yano.democlient.codec.Blake2b;
import com.bloxbean.yano.democlient.codec.Hex;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Golden vectors from the release showcase (public demo seeds; the release signer matched the CLI byte for byte). */
class RoleStatementsTest {
    static byte[] demoSeed(String actorId) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(("yano-showcase-demo-actor:" + actorId).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void derivesTheRegisteredDemoActorKeys() throws Exception {
        assertEquals("67ddc1ac79b76d0e6abae1c133195fa48904948af3b202b761a4c727c4e169ff",
                Hex.encode(Ed25519.publicKey(demoSeed("issuer-a"))));
        assertEquals("f19f6042d44cb82663d9357435c9ed71bece07a7f5fe58fcaaa0c3732581f612",
                Hex.encode(Ed25519.publicKey(demoSeed("auditor-a"))));
        assertEquals("b0dc3aadb1754c025699b57789292055d100dddee5e868c569dee8317898c8d2",
                Hex.encode(Ed25519.publicKey(demoSeed("auditor-b"))));
        assertEquals("88b06723691dad781e5419a47516de3b82418a35de2b3559a9d915fd6cedfa67",
                Hex.encode(Ed25519.publicKey(demoSeed("registry-admin-a"))));
    }

    @Test
    void signsTheDocumentReviewProposalExactly() throws Exception {
        byte[] command = array(uint(1), text("document-review-demo1"), text("document-release"), uint(1),
                text("document-demo1"), bstr(Hex.decode("27ae8fec57354a0bcf499207fc2ce817ac7ba923e4ca9bb4f9ffb35120a9e73f")),
                text("showcase://documents/document-demo1"));
        assertEquals("870175646f63756d656e742d7265766965772d64656d6f3170646f63756d656e742d72656c65617365016e646f63756d656e742d64656d6f31582027ae8fec57354a0bcf499207fc2ce817ac7ba923e4ca9bb4f9ffb35120a9e73f782373686f77636173653a2f2f646f63756d656e74732f646f63756d656e742d64656d6f31",
                Hex.encode(command));
        byte[] commitment = Blake2b.digest256(command);
        assertEquals("d7734cebc4bdb339f3fa23227cf5c32800945185db87e2d03b263c346322e3b9", Hex.encode(commitment));

        var propose = new RoleStatements.Statement(RoleStatements.PROPOSE, "document-review-chain", "document-review-demo1",
                "document-release", 1, "document.review.release.v1", commitment, 304, "issuer-a", 1, "issuer-a-k1", "");
        assertEquals("830158998d010075646f63756d656e742d7265766965772d636861696e75646f63756d656e742d7265766965772d64656d6f3170646f63756d656e742d72656c6561736501781a646f63756d656e742e7265766965772e72656c656173652e76315820d7734cebc4bdb339f3fa23227cf5c32800945185db87e2d03b263c346322e3b9190130686973737565722d61016b6973737565722d612d6b3160584050d7eb3ac30713a2ca0c0952ac5baada315ccea089a4625c8f5e2bae8ce38a1218dfa5664ca023808d966f29ad4bc611d2680134412096d0aadb676db15ddc0c",
                Hex.encode(RoleStatements.signedCommand(propose, demoSeed("issuer-a"))));

        var approve = new RoleStatements.Statement(RoleStatements.APPROVE, "document-review-chain", "document-review-demo1",
                "document-release", 1, "document.review.release.v1", commitment, 304, "auditor-a", 1, "auditor-a-k1",
                "independent-auditors");
        assertEquals("830158af8d010175646f63756d656e742d7265766965772d636861696e75646f63756d656e742d7265766965772d64656d6f3170646f63756d656e742d72656c6561736501781a646f63756d656e742e7265766965772e72656c656173652e76315820d7734cebc4bdb339f3fa23227cf5c32800945185db87e2d03b263c346322e3b91901306961756469746f722d61016c61756469746f722d612d6b3174696e646570656e64656e742d61756469746f72735840d10561a898c5868a58998d2527f03ec8dfeb67246d8ff6853c14ea8b19429f2f4dc27186c4a95cad1f88d0516934a22da7b91785c06fb390152cd6fed66dda08",
                Hex.encode(RoleStatements.signedCommand(approve, demoSeed("auditor-a"))));
    }
}
