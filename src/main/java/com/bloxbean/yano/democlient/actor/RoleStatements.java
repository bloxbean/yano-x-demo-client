package com.bloxbean.yano.democlient.actor;

import com.bloxbean.yano.democlient.codec.Cbor;

import java.nio.charset.StandardCharsets;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/**
 * Role-workflow v1 actor statements (role-workflow-v1.cddl):
 * statement = [1, action, chainId, proposalId, policyId, policyRev, payloadDomain, payloadHash32, deadlineHeight,
 * actorId, actorRev, keyId, clauseId]; signed command = [1, bstr(statement), bstr(sig64)], signature over
 * "yano:role-approval:v1\0" || u32be(len(statement)) || statement.
 */
public final class RoleStatements {
    public static final int PROPOSE = 0;
    public static final int APPROVE = 1;
    public static final int REJECT = 2;
    private static final byte[] DOMAIN = "yano:role-approval:v1\0".getBytes(StandardCharsets.US_ASCII);

    private RoleStatements() {
    }

    public record Statement(int action, String chainId, String proposalId, String policyId, long policyRevision,
                            String payloadDomain, byte[] payloadHash, long deadlineHeight, String actorId,
                            long actorRevision, String keyId, String clauseId) {
        public byte[] encode() {
            return array(uint(1), uint(action), text(chainId), text(proposalId), text(policyId), uint(policyRevision),
                    text(payloadDomain), bstr(payloadHash), uint(deadlineHeight), text(actorId), uint(actorRevision),
                    text(keyId), text(clauseId));
        }
    }

    public static byte[] signedCommand(Statement statement, byte[] seed) {
        byte[] encoded = statement.encode();
        byte[] signature = Ed25519.sign(seed, preimage(DOMAIN, encoded));
        return array(uint(1), bstr(encoded), bstr(signature));
    }

    static byte[] preimage(byte[] domain, byte[] body) {
        byte[] length = {(byte) (body.length >>> 24), (byte) (body.length >>> 16), (byte) (body.length >>> 8), (byte) body.length};
        return Cbor.concat(domain, length, body);
    }
}
