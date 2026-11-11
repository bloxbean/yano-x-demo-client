package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.chain.ChainModule.Fact;
import com.bloxbean.yano.democlient.chain.ChainModule.Finalized;
import com.bloxbean.yano.democlient.chain.StateReader;
import com.bloxbean.yano.democlient.codec.Cbor;
import com.bloxbean.yano.democlient.codec.Hex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The role-workflow "role-approvals" component as both people-approval chains store it (document-review-chain and
 * authenticated-map-chain): proposals at {@code q/<id>}, a per-message result code at {@code r/c/<messageId>}.
 */
final class RoleApprovalProposals {
    static final String COMPONENT = "role-approvals";
    static final String[] STATUS = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED"};
    static final String[] RESULT = {"ACCEPTED", "INVALID_PAYLOAD", "UNSUPPORTED_VERSION", "INVALID_SIGNATURE",
            "UNAUTHORIZED_RELAY", "UNAUTHORIZED_ACTOR", "UNKNOWN_RECORD", "CONFLICT", "EXACT_REPLAY", "EXPIRED", "TERMINAL",
            "ROLE_MISMATCH", "DISTINCTNESS_DUPLICATE", "GOVERNANCE_THRESHOLD_NOT_MET", "GOVERNANCE_PROOF_INVALID",
            "LIMIT_EXCEEDED", "CAPACITY_EXCEEDED", "CRYPTO_WORK_EXCEEDED", "WRONG_GENESIS", "WRONG_REVISION", "NOT_READY",
            "SUPERSEDED", "GOVERNED_ROUTE_UNSUPPORTED"};

    private RoleApprovalProposals() {
    }

    record Decision(int action, String actor, String organization) {
    }

    record Proposal(int status, byte[] payloadHash, long deadline, String proposer, String proposerOrg,
                    long createdHeight, List<Decision> decisions) {
        long approvals() {
            return decisions.stream().filter(d -> d.action == 1).count();
        }

        String statusName() {
            return status >= 0 && status < STATUS.length ? STATUS[status] : "STATUS_" + status;
        }
    }

    static byte[] proposalKey(String proposal) {
        return WorkflowModule.compositeKey(COMPONENT, ("q/" + proposal).getBytes(StandardCharsets.UTF_8));
    }

    static Proposal decode(StateReader.Entry entry) {
        if (entry == null || !entry.present()) {
            return null;
        }
        List<?> v = (List<?>) Cbor.decode(entry.value());
        List<Decision> decisions = new ArrayList<>();
        for (Object d : (List<?>) v.get(16)) {
            List<?> decision = (List<?>) d;
            decisions.add(new Decision(((Long) decision.get(0)).intValue(), (String) decision.get(1), (String) decision.get(2)));
        }
        return new Proposal(((Long) v.get(8)).intValue(), (byte[]) v.get(6), (Long) v.get(7), (String) v.get(9),
                (String) v.get(10), (Long) v.get(15), decisions);
    }

    /** The role-approvals result code recorded for this message, or NO_RESULT. */
    static String resultCode(Finalized fin) {
        byte[] key = WorkflowModule.compositeKey(COMPONENT, ("r/c/" + fin.messageId()).getBytes(StandardCharsets.US_ASCII));
        StateReader.Entry entry = fin.reader().entry(key, fin.height());
        if (!entry.present()) {
            return "NO_RESULT";
        }
        int code = ((Long) ((List<?>) Cbor.decode(entry.value())).get(3)).intValue();
        return code >= 0 && code < RESULT.length ? RESULT[code] : "CODE_" + code;
    }

    static String explain(String code) {
        return switch (code) {
            case "ROLE_MISMATCH" -> "This person does not hold the role the policy requires for this action.";
            case "DISTINCTNESS_DUPLICATE" -> "The policy needs approvals from different organisations — this one is already counted.";
            case "EXACT_REPLAY" -> "The same signed statement was already applied.";
            case "TERMINAL" -> "The proposal is already finished.";
            case "EXPIRED" -> "The proposal's deadline has passed.";
            case "UNKNOWN_RECORD" -> "The proposal (or actor) does not exist.";
            case "CONFLICT" -> "A proposal with this id already exists with different content.";
            case "WRONG_GENESIS" -> "The signed payload is bound to a different chain genesis.";
            default -> "The message was finalized, but the role-approval policy refused it.";
        };
    }

    static List<Fact> facts(String proposal, Proposal p, String commitmentHint) {
        return facts(proposal, p, commitmentHint, "2 (different organisations)");
    }

    /** required: e.g. "2 (different organisations)" or "1 reviewer from another organisation". */
    static List<Fact> facts(String proposal, Proposal p, String commitmentHint, String required) {
        if (p == null) {
            return List.of(Fact.of("Proposal", proposal), Fact.of("Status", "not found"));
        }
        List<Fact> facts = new ArrayList<>(List.of(Fact.of("Proposal", proposal), Fact.of("Status", p.statusName()),
                Fact.of("Proposed by", p.proposer + " (" + p.proposerOrg + ")"),
                Fact.of("Approvals", p.approvals() + " of " + required),
                Fact.of("Deadline", "block " + p.deadline),
                new Fact("Commitment", Hex.encode(p.payloadHash), commitmentHint)));
        for (Decision d : p.decisions) {
            facts.add(Fact.of(d.action == 1 ? "Approved by" : "Rejected by", d.actor + " (" + d.organization + ")"));
        }
        return facts;
    }
}
