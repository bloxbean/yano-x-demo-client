package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.actor.ActorDirectory;
import com.bloxbean.yano.democlient.actor.RoleStatements;
import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.Inputs;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.Specs;
import com.bloxbean.yano.democlient.chain.Specs.Field;
import com.bloxbean.yano.democlient.chain.StateReader;
import com.bloxbean.yano.democlient.codec.Blake2b;
import com.bloxbean.yano.democlient.codec.Cbor;
import com.bloxbean.yano.democlient.codec.Hex;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/**
 * authenticated-map-chain, a stock state machine (recipe authenticated-map) whose "released-products" collection is
 * governed by the approval policy "product-release" declared in the sealed YAML: an issuer proposes the exact write,
 * two auditors from different organisations approve it, and only then can a member write it, consuming the approval
 * once. People sign role-approval statements with their own keys; member nodes only relay.
 */
@ApplicationScoped
public class AuthenticatedMapModule implements ChainModule {
    static final String CHAIN = "authenticated-map-chain";
    static final String STDLIB = "org.yanoproject.x.stdlib";
    private static final String ROLE_TOPIC = "role-approvals.command.v1";
    private static final String MAP_TOPIC = "authenticated-map.command.v1";
    static final String COLLECTION = "released-products";
    static final String POLICY = "product-release";
    private static final String CLAUSE = "independent-auditors";
    private static final String ID_RULE = "[a-z][a-z0-9-]{0,62}";
    private static final String KEY_RULE = "[A-Za-z0-9._:-]{1,64}";
    private static final String COMMITMENT_HINT =
            "Genesis-bound hash of the exact map write (collection, key, value, policy) that the people signed";
    /** Proposal lifetime asked for; the policy caps it at 600 blocks. */
    private static final long DEADLINE_BLOCKS = 300;

    private final ActorDirectory actors;

    public AuthenticatedMapModule(ActorDirectory actors) {
        this.actors = actors;
    }

    private static final List<Field> RELEASE_FIELDS = List.of(
            Field.text("proposal", "Proposal id", 63, "e.g. release-sku-42").withPattern(ID_RULE),
            Field.text("product", "Product key", 64, "The entry key in released-products, e.g. sku-42").withPattern(KEY_RULE),
            Field.textarea("note", "Release record", 4000, "The value written on release (stored as-is, up to 4 KB)"));

    private static final Specs.Chain SPEC = new Specs.Chain(
            CHAIN, "Product release", "Stock state machine, people approve: issuer + two independent auditors",
            "A built-in authenticated-map chain whose released-products collection is governed by an approval policy"
                    + " written in the chain's YAML, not in custom code. The issuer proposes the exact entry; two auditors"
                    + " from different organisations approve with their own keys. Only then can a member write it, and the"
                    + " write consumes the approval once. Open the Config tab to see the people, roles and policy.",
            "authenticated-map", "People & roles", List.of("stock recipe", "actor signatures", "approval policy"), false,
            "sky",
            List.of(new Specs.Command("propose", "1 · Propose (issuer)", "The issuer proposes writing a product release.",
                            "An actor-signed proposal bound to the exact map write and the chain's genesis.",
                            Specs.PersonaKind.ACTOR, List.of("issuer-a", "auditor-a"), RELEASE_FIELDS,
                            List.of("Switch to an auditor and approve", "Try proposing as an auditor: ROLE_MISMATCH")),
                    new Specs.Command("approve", "2 · Approve (auditor)", "Approve as the selected person.",
                            "Policy clause “independent-auditors”: 2 auditors from different organisations.",
                            Specs.PersonaKind.ACTOR, List.of("auditor-a", "auditor-b", "issuer-a"),
                            List.of(Field.text("proposal", "Proposal id", 63, "The proposal to approve").withPattern(ID_RULE)),
                            List.of("Approve again as the same auditor: refused as an exact replay",
                                    "Try approving as the issuer: ROLE_MISMATCH")),
                    new Specs.Command("reject", "Reject (auditor)", "Reject the proposal as the selected person.",
                            "Any eligible auditor can stop a release.", Specs.PersonaKind.ACTOR,
                            List.of("auditor-a", "auditor-b"),
                            List.of(Field.text("proposal", "Proposal id", 63, "The proposal to reject").withPattern(ID_RULE)),
                            List.of("Try releasing it afterwards: refused")),
                    new Specs.Command("release", "3 · Release (any member)", "A member node writes the approved entry.",
                            "The write must match the approved bytes exactly and consumes the approval once.",
                            Specs.PersonaKind.MEMBER, List.of(), RELEASE_FIELDS,
                            List.of("Change one character of the record and release: refused",
                                    "Release twice: the approval is already consumed"))),
            List.of(new Specs.Lookup("proposal", "Look up a proposal", "Status and signed decisions",
                            List.of(Field.text("proposal", "Proposal id", 63, "Proposal id").withPattern(ID_RULE))),
                    new Specs.Lookup("released", "Look up a released product", "The entry in released-products",
                            List.of(Field.text("product", "Product key", 64, "e.g. sku-42").withPattern(KEY_RULE)))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String n = context.sessionTag() + "-" + context.sequence();
        return switch (commandId) {
            case "approve", "reject" -> Map.of("proposal", "release-" + n);
            default -> Map.of("proposal", "release-" + n, "product", "sku-" + n,
                    "note", "Product sku-" + n + " · batch B-2026-11 · passed final inspection · cleared for sale");
        };
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String proposal = in.matching("proposal", "Proposal id", ID_RULE, "must start with a letter: a–z, 0–9, - (max 63)");
        MemberDirectory members = context.members();
        byte[] proposalKey = RoleApprovalProposals.proposalKey(proposal);
        StateReader reader = context.reader();
        return switch (commandId) {
            case "propose" -> {
                String actor = actor(context, commandId);
                byte[] action = Codec.action(COLLECTION, productKey(in), note(in), POLICY);
                byte[] payloadHash = Codec.approvalPayloadHash(genesisId(reader), Codec.actionCommitment(action));
                long tip = reader.tipHeight();
                if (tip < 0) {
                    throw new InvalidInput("The chain tip is not readable right now; try again");
                }
                var statement = new RoleStatements.Statement(RoleStatements.PROPOSE, CHAIN, proposal, POLICY, 1,
                        Codec.APPROVAL_PAYLOAD_DOMAIN, payloadHash, tip + DEADLINE_BLOCKS, actor, 1, key(actor), "");
                yield new Prepared(ROLE_TOPIC, actors.signedStatement(actor, statement), "propose " + proposal + " as " + actor,
                        List.of(new Probe("proposal", proposalKey)), actorJudge(proposal, proposalKey, "propose"));
            }
            case "approve", "reject" -> {
                String actor = actor(context, commandId);
                RoleApprovalProposals.Proposal current = RoleApprovalProposals.decode(reader.latest(proposalKey));
                if (current == null) {
                    throw new InvalidInput("Proposal " + proposal + " does not exist — propose it first");
                }
                var statement = new RoleStatements.Statement("approve".equals(commandId) ? RoleStatements.APPROVE
                        : RoleStatements.REJECT, CHAIN, proposal, POLICY, 1, Codec.APPROVAL_PAYLOAD_DOMAIN,
                        current.payloadHash(), current.deadline(), actor, 1, key(actor), CLAUSE);
                yield new Prepared(ROLE_TOPIC, actors.signedStatement(actor, statement), commandId + " " + proposal + " as " + actor,
                        List.of(new Probe("proposal", proposalKey)), actorJudge(proposal, proposalKey, commandId));
            }
            case "release" -> {
                byte[] product = productKey(in);
                byte[] action = Codec.action(COLLECTION, product, note(in), POLICY);
                byte[] commitment = Codec.actionCommitment(action);
                byte[] payloadHash = Codec.approvalPayloadHash(genesisId(reader), commitment);
                byte[] body = Codec.command(action, Codec.approvalReference(proposal, commitment, POLICY));
                byte[] entryKey = entryProofKey(reader, product);
                String productId = new String(product, StandardCharsets.UTF_8);
                yield new Prepared(MAP_TOPIC, body, "release " + productId + " under " + proposal,
                        List.of(new Probe("entry", entryKey), new Probe("proposal", proposalKey)),
                        (before, after, fin) -> {
                            RoleApprovalProposals.Proposal p = RoleApprovalProposals.decode(after.get("proposal"));
                            List<Fact> facts = new ArrayList<>(RoleApprovalProposals.facts(proposal, p, COMMITMENT_HINT));
                            facts.add(0, Fact.of("Entry", COLLECTION + " / " + productId));
                            StateReader.Entry was = before.get("entry");
                            StateReader.Entry now = after.get("entry");
                            if (now.present() && !now.sameAs(was)) {
                                return new Outcome(Status.APPLIED, "Released — written to " + COLLECTION,
                                        "The chain matched the write against the approved commitment, wrote the entry and"
                                                + " consumed the approval, so it cannot be used again.", facts,
                                        Hex.encode(entryKey));
                            }
                            String code = receiptError(fin);
                            facts.add(0, new Fact("Receipt", code == null ? "not available" : "REJECTED · " + code,
                                    "The chain's receipt for this message (authenticated-map/receipts/<message>)"));
                            String why = switch (code == null ? "" : code) {
                                case "APPROVAL_NOT_APPROVED" -> "the proposal is " + (p == null ? "missing" : p.statusName())
                                        + ", not APPROVED";
                                case "APPROVAL_MISMATCH" -> "the product key or record differs from what the people approved";
                                case "APPROVAL_REPLAY" -> "this approval was already consumed by an earlier release";
                                case "WRONG_GENESIS" -> "the approval is bound to a different chain genesis";
                                default -> code == null ? "an authorization check failed" : "the chain refused it (" + code + ")";
                            };
                            return new Outcome(Status.NO_EFFECT, "Release refused" + (code == null ? "" : ": " + code),
                                    "Nothing was written because " + why + ".", facts, Hex.encode(proposalKey));
                        });
            }
            default -> throw new InvalidInput("unknown command " + commandId);
        };
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        Inputs in = new Inputs(fields);
        if ("released".equals(lookupId)) {
            byte[] product = productKey(in);
            String productId = new String(product, StandardCharsets.UTF_8);
            JsonNode entry = reader.plugin(STDLIB, "authenticated-map/entries/" + COLLECTION + "/" + Hex.encode(product));
            if (entry == null) {
                throw new InvalidInput("The node did not answer the entry lookup; try again");
            }
            JsonNode record = entry.path("record");
            boolean present = record.path("presence").asInt(0) == 1;
            List<Fact> facts = new ArrayList<>(List.of(Fact.of("Collection", COLLECTION), Fact.of("Key", productId),
                    Fact.of("Presence", present ? "present" : "absent"),
                    Fact.of("At height", entry.path("committedHeight").asText())));
            if (present) {
                facts.add(Fact.of("Revision", record.path("revision").asText()));
                String proofKey = entry.path("proofKey").asText("");
                StateReader.Entry leaf = proofKey.isEmpty() ? null : reader.latest(Hex.decode(proofKey));
                if (leaf != null && leaf.present()) {
                    // entry leaf: [version, status, revision, controller, value, valueHash, ...]
                    Object value = ((List<?>) Cbor.decode(leaf.value())).get(4);
                    if (value instanceof byte[] bytes) {
                        facts.add(Fact.of("Record", new String(bytes, StandardCharsets.UTF_8)));
                    }
                }
                facts.add(new Fact("Value hash", record.path("logicalValueHash").asText(), "blake2b-256 of the stored value"));
            }
            return new LookupResult(present ? productId + " is released" : "No released entry " + productId, facts,
                    entry.path("proofKey").asText(null));
        }
        String proposal = in.matching("proposal", "Proposal id", ID_RULE, "is not a valid proposal id");
        byte[] key = RoleApprovalProposals.proposalKey(proposal);
        RoleApprovalProposals.Proposal p = RoleApprovalProposals.decode(reader.latest(key));
        return new LookupResult(p == null ? "No proposal " + proposal : proposal + " · " + p.statusName(),
                RoleApprovalProposals.facts(proposal, p, COMMITMENT_HINT), Hex.encode(key));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> v = (List<?>) Cbor.decode(body);
            if (MAP_TOPIC.equals(topic)) {
                List<?> action = (List<?>) Cbor.decode((byte[]) v.get(1));
                List<?> mutation = (List<?>) ((List<?>) action.get(2)).get(0);
                String proposal = "";
                List<?> evidence = (List<?>) v.get(2);
                if (!evidence.isEmpty()) {
                    List<?> reference = (List<?>) Cbor.decode((byte[]) ((List<?>) evidence.get(0)).get(1));
                    proposal = " under " + reference.get(1);
                }
                return "write " + mutation.get(1) + "/" + new String((byte[]) mutation.get(2), StandardCharsets.UTF_8) + proposal;
            }
            List<?> statement = (List<?>) Cbor.decode((byte[]) v.get(1));
            String action = switch (((Long) statement.get(1)).intValue()) {
                case 0 -> "propose";
                case 1 -> "approve";
                case 2 -> "reject";
                default -> "cancel";
            };
            return action + " " + statement.get(3) + " — signed by " + statement.get(9);
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Judge actorJudge(String proposal, byte[] proposalKey, String action) {
        return (before, after, fin) -> {
            RoleApprovalProposals.Proposal p = RoleApprovalProposals.decode(after.get("proposal"));
            List<Fact> facts = new ArrayList<>(RoleApprovalProposals.facts(proposal, p, COMMITMENT_HINT));
            String code = RoleApprovalProposals.resultCode(fin);
            facts.add(0, new Fact("Result code", code, "Recorded on chain for this message (role-approvals r/c/<message>)"));
            if (!"ACCEPTED".equals(code)) {
                return new Outcome(Status.NO_EFFECT, "Refused by the policy: " + code, RoleApprovalProposals.explain(code),
                        facts, Hex.encode(proposalKey));
            }
            String headline = switch (action) {
                case "propose" -> "Proposal open — needs 2 independent auditors";
                case "reject" -> "Proposal REJECTED";
                default -> p != null && p.status() == 1 ? "Policy satisfied — proposal APPROVED"
                        : "Approval accepted (" + (p == null ? 0 : p.approvals()) + " of 2)";
            };
            String detail = switch (action) {
                case "propose" -> "Switch to Aiko or Bruno (auditors from different organisations) and approve.";
                case "reject" -> "This entry can no longer be written under this proposal.";
                default -> p != null && p.status() == 1 ? "Next: any member node writes the entry (step 3)."
                        : "One more approval from an auditor of a different organisation is needed.";
            };
            return new Outcome(Status.APPLIED, headline, detail, facts, Hex.encode(proposalKey));
        };
    }

    private static final String[] MAP_ERRORS = {"NONE", "UNKNOWN_COLLECTION", "COLLECTION_BOUNDS", "UNAUTHORIZED",
            "ALREADY_EXISTS", "ABSENT", "REVOKED", "ACTIVE", "PRECONDITION", "RESTORE_FORBIDDEN", "VALUE_ENCODING",
            "VALUE_SCHEMA", "VALUE_VALIDATOR", "AUTHORIZATION_ASSIGNMENT", "UNKNOWN_POLICY", "POLICY_INACTIVE",
            "ACTOR_INELIGIBLE", "ACTOR_SIGNATURE", "AUTHORIZATION_DEADLINE", "DIRECT_AUTHORIZATION_REPLAY",
            "APPROVAL_NOT_APPROVED", "APPROVAL_MISMATCH", "APPROVAL_REPLAY", "CAPACITY_EXCEEDED", "CRYPTO_WORK_EXCEEDED",
            "GOVERNED_ROUTE_UNSUPPORTED", "WRONG_GENESIS", "WRONG_REVISION"};

    /** The error name in this message's authenticated-map receipt, or null when the receipt is not readable. */
    private static String receiptError(Finalized fin) {
        JsonNode receipt = fin.reader().plugin(STDLIB, "authenticated-map/receipts/" + fin.messageId());
        if (receipt == null || receipt.path("record").path("presence").asInt(0) != 1) {
            return null;
        }
        int code = receipt.path("record").path("errorCode").asInt(-1);
        return code >= 0 && code < MAP_ERRORS.length ? MAP_ERRORS[code] : "ERROR_" + code;
    }

    private String actor(CommandContext context, String commandId) {
        String actor = context.actorId();
        if (actor == null || actors.find(actor).isEmpty()) {
            throw new InvalidInput("Choose who signs this " + commandId);
        }
        return actor;
    }

    private String key(String actor) {
        return actors.find(actor).orElseThrow().keyId();
    }

    private static byte[] productKey(Inputs in) {
        return in.matching("product", "Product key", KEY_RULE, "use 1–64 letters, digits, . _ : -")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] note(Inputs in) {
        return in.text("note", "Release record", 4000).getBytes(StandardCharsets.UTF_8);
    }

    /** The authenticated map's genesis id (signed into every approval payload), from the node's domain API. */
    private static byte[] genesisId(StateReader reader) {
        JsonNode metadata = reader.plugin(STDLIB, "authenticated-map");
        String id = metadata == null ? "" : metadata.path("record").path("genesisId").asText("");
        if (!id.matches("[0-9a-f]{64}")) {
            throw new InvalidInput("The node did not report the authenticated map's genesis; try again");
        }
        return Hex.decode(id);
    }

    /** The composite state key of the entry, as the node reports it (never built by hand). */
    private static byte[] entryProofKey(StateReader reader, byte[] product) {
        JsonNode entry = reader.plugin(STDLIB, "authenticated-map/entries/" + COLLECTION + "/" + Hex.encode(product));
        String key = entry == null ? "" : entry.path("proofKey").asText("");
        if (!key.matches("(?:[0-9a-f]{2})+")) {
            throw new InvalidInput("The node did not report the entry's state key; try again");
        }
        return Hex.decode(key);
    }

    /**
     * authenticated-map v1 wire contract (AuthenticatedMapAuthorizationContract in yano-x stdlib-contracts), pinned by
     * golden vectors from `yano.sh appchain authenticated-map` in AuthenticatedMapCodecTest.
     */
    static final class Codec {
        static final String APPROVAL_PAYLOAD_DOMAIN = "yano.authenticated-map.action.v1";
        private static final byte[] ACTION_DOMAIN = "yano:authenticated-map:action:v1\0".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] PAYLOAD_DOMAIN =
                "yano:authenticated-map:approval-payload:v1\0".getBytes(StandardCharsets.US_ASCII);
        private static final int OP_PUT = 0;
        private static final int AUTH_APPROVAL = 4;
        private static final int EVIDENCE_APPROVAL = 1;

        private Codec() {
        }

        /** [1, batch=0, [[op, collection, key, value, expectedRevision=0, h'', h'']], [[0, kind=approval, policy, handle=1]]]. */
        static byte[] action(String collection, byte[] key, byte[] value, String policy) {
            byte[] mutation = array(uint(OP_PUT), text(collection), bstr(key), bstr(value), uint(0), bstr(new byte[0]),
                    bstr(new byte[0]));
            byte[] assignment = array(uint(0), uint(AUTH_APPROVAL), text(policy), uint(1));
            return array(uint(1), uint(0), array(mutation), array(assignment));
        }

        static byte[] actionCommitment(byte[] action) {
            return Blake2b.digest256(Cbor.concat(ACTION_DOMAIN, action));
        }

        static byte[] approvalPayloadHash(byte[] genesisId, byte[] commitment) {
            return Blake2b.digest256(Cbor.concat(PAYLOAD_DOMAIN, genesisId, commitment));
        }

        /** [1, proposalId, commitment, [0], policy, policyRevision=1]. */
        static byte[] approvalReference(String proposal, byte[] commitment, String policy) {
            return array(uint(1), text(proposal), bstr(commitment), array(uint(0)), text(policy), uint(1));
        }

        /** [1, bstr(action), [[kind=approval, bstr(evidence)]]]. */
        static byte[] command(byte[] action, byte[] approvalReference) {
            return array(uint(1), bstr(action), array(array(uint(EVIDENCE_APPROVAL), bstr(approvalReference))));
        }
    }
}
