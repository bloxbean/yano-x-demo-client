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
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/**
 * document-review-chain (document-role-approval-v1): named people sign, not nodes. An issuer proposes releasing a
 * document; two auditors from different organisations must approve; any member then relays the release, which
 * consumes the approval exactly once and appends the document to a trail.
 */
@ApplicationScoped
public class DocumentReviewModule implements ChainModule {
    static final String CHAIN = "document-review-chain";
    private static final String ROLE_TOPIC = "role-approvals.command.v1";
    private static final String RELEASE_TOPIC = "document-review.release.v1";
    private static final String POLICY = "document-release";
    private static final String DOMAIN = "document.review.release.v1";
    private static final String CLAUSE = "independent-auditors";
    private static final String ID_RULE = "[a-z][a-z0-9-]{0,62}";
    private static final String[] PROPOSAL_STATUS = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED"};
    static final String[] RESULT = {"ACCEPTED", "INVALID_PAYLOAD", "UNSUPPORTED_VERSION", "INVALID_SIGNATURE",
            "UNAUTHORIZED_RELAY", "UNAUTHORIZED_ACTOR", "UNKNOWN_RECORD", "CONFLICT", "EXACT_REPLAY", "EXPIRED", "TERMINAL",
            "ROLE_MISMATCH", "DISTINCTNESS_DUPLICATE", "GOVERNANCE_THRESHOLD_NOT_MET", "GOVERNANCE_PROOF_INVALID",
            "LIMIT_EXCEEDED", "CAPACITY_EXCEEDED", "CRYPTO_WORK_EXCEEDED", "WRONG_GENESIS", "WRONG_REVISION", "NOT_READY",
            "SUPERSEDED", "GOVERNED_ROUTE_UNSUPPORTED"};

    private final ActorDirectory actors;

    public DocumentReviewModule(ActorDirectory actors) {
        this.actors = actors;
    }

    private static final List<Field> DOCUMENT_FIELDS = List.of(
            Field.text("proposal", "Proposal id", 63, "e.g. release-contract-17").withPattern(ID_RULE),
            Field.text("entity", "Document id", 63, "e.g. contract-17").withPattern(ID_RULE),
            Field.textarea("content", "Document content", 8192, "Only its SHA-256 fingerprint goes on chain"),
            Field.text("reference", "Reference", 200, "Where the document lives, e.g. dms://contracts/17"));

    private static final Specs.Chain SPEC = new Specs.Chain(
            CHAIN, "Document review", "People sign, not servers: issuer + two independent auditors",
            "Releasing a document needs the issuer's proposal and approvals from two auditors of different"
                    + " organisations. Each person signs with their own key; the member nodes only relay. The policy is"
                    + " enforced on chain: wrong role, same-organisation approvals and replays are refused, and an"
                    + " approval can be consumed exactly once.",
            "document-review", "People & roles", List.of("actor signatures", "policy", "separation of duties"), false, "violet",
            List.of(new Specs.Command("propose", "1 · Propose (issuer)", "The issuer proposes releasing a document.",
                            "An actor-signed proposal bound to the exact document fingerprint and a deadline.",
                            Specs.PersonaKind.ACTOR, List.of("issuer-a"), DOCUMENT_FIELDS,
                            List.of("Switch to an auditor and approve")),
                    new Specs.Command("approve", "2 · Approve (auditor)", "Approve as the selected person.",
                            "Policy clause “independent-auditors”: 2 auditors from different organisations.",
                            Specs.PersonaKind.ACTOR, List.of("auditor-a", "auditor-b", "issuer-a"),
                            List.of(Field.text("proposal", "Proposal id", 63, "The proposal to approve").withPattern(ID_RULE)),
                            List.of("Approve again as the same auditor: refused as an exact replay",
                                    "Try approving as the issuer: ROLE_MISMATCH")),
                    new Specs.Command("reject", "Reject (auditor)", "Reject the proposal as the selected person.",
                            "Any eligible auditor can stop a release.", Specs.PersonaKind.ACTOR,
                            List.of("auditor-a", "auditor-b", "issuer-a"),
                            List.of(Field.text("proposal", "Proposal id", 63, "The proposal to reject").withPattern(ID_RULE)),
                            List.of("Try releasing it afterwards: refused")),
                    new Specs.Command("release", "3 · Release (any member)", "A member node relays the release.",
                            "The release consumes the approval exactly once and must match the approved fingerprint.",
                            Specs.PersonaKind.MEMBER, List.of(), DOCUMENT_FIELDS,
                            List.of("Change one character of the content and release: no matching approval",
                                    "Release twice: the approval is already consumed"))),
            List.of(new Specs.Lookup("proposal", "Look up a proposal", "Status and signed decisions",
                    List.of(Field.text("proposal", "Proposal id", 63, "Proposal id").withPattern(ID_RULE)))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String n = context.sessionTag() + "-" + context.sequence();
        String entity = "contract-" + n;
        return switch (commandId) {
            case "approve", "reject" -> Map.of("proposal", "release-" + n);
            default -> Map.of("proposal", "release-" + n, "entity", entity,
                    "content", "MASTER SERVICES AGREEMENT\nAcme Manufacturing × Northwind Retail\nSigned version 3",
                    "reference", "dms://contracts/" + entity);
        };
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String proposal = in.matching("proposal", "Proposal id", ID_RULE, "must start with a letter: a–z, 0–9, - (max 63)");
        MemberDirectory members = context.members();
        byte[] proposalKey = WorkflowModule.compositeKey("role-approvals", ("q/" + proposal).getBytes(StandardCharsets.UTF_8));
        return switch (commandId) {
            case "propose" -> {
                String actor = actor(context, commandId);
                byte[] command = releaseCommand(proposal, in);
                long tip = context.reader().tipHeight();
                if (tip < 0) {
                    throw new InvalidInput("The chain tip is not readable right now; try again");
                }
                var statement = new RoleStatements.Statement(RoleStatements.PROPOSE, CHAIN, proposal, POLICY, 1, DOMAIN,
                        Blake2b.digest256(command), tip + 300, actor, 1, key(actor), "");
                yield new Prepared(ROLE_TOPIC, actors.signedStatement(actor, statement), "propose " + proposal + " as " + actor,
                        List.of(new Probe("proposal", proposalKey)), actorJudge(proposal, proposalKey, members, "propose"));
            }
            case "approve", "reject" -> {
                String actor = actor(context, commandId);
                Proposal current = decodeProposal(context.reader().latest(proposalKey));
                if (current == null) {
                    throw new InvalidInput("Proposal " + proposal + " does not exist — propose it first");
                }
                var statement = new RoleStatements.Statement("approve".equals(commandId) ? RoleStatements.APPROVE
                        : RoleStatements.REJECT, CHAIN, proposal, POLICY, 1, DOMAIN, current.payloadHash, current.deadline,
                        actor, 1, key(actor), CLAUSE);
                yield new Prepared(ROLE_TOPIC, actors.signedStatement(actor, statement), commandId + " " + proposal + " as " + actor,
                        List.of(new Probe("proposal", proposalKey)), actorJudge(proposal, proposalKey, members, commandId));
            }
            case "release" -> {
                byte[] command = releaseCommand(proposal, in);
                String entity = in.text("entity", "Document id", 63);
                byte[] receiptKey = WorkflowModule.compositeKey("document-review-receipts",
                        ("approval/" + proposal).getBytes(StandardCharsets.US_ASCII));
                byte[] trailKey = WorkflowModule.compositeKey("documents", ("e/" + entity).getBytes(StandardCharsets.UTF_8));
                yield new Prepared(RELEASE_TOPIC, command, "release " + entity + " under " + proposal,
                        List.of(new Probe("receipt", receiptKey), new Probe("trail", trailKey), new Probe("proposal", proposalKey)),
                        (before, after, fin) -> {
                            Proposal p = decodeProposal(after.get("proposal"));
                            List<Fact> facts = new ArrayList<>(proposalFacts(proposal, p));
                            if (before.get("receipt").present()) {
                                return new Outcome(Status.NO_EFFECT, "Already released",
                                        "The approval was consumed by an earlier release; replays change nothing.", facts,
                                        Hex.encode(receiptKey));
                            }
                            if (after.get("receipt").present()) {
                                facts.add(Fact.of("Document trail entries", String.valueOf(count(after.get("trail")))));
                                return new Outcome(Status.APPLIED, "Released — approval consumed exactly once",
                                        "The chain checked the fingerprint against the approved commitment, appended the"
                                                + " document to its trail and wrote a release receipt.", facts, Hex.encode(receiptKey));
                            }
                            String why = p == null ? "the proposal does not exist"
                                    : p.status != 1 ? "the proposal is " + PROPOSAL_STATUS[p.status] + ", not APPROVED"
                                    : !java.util.Arrays.equals(p.payloadHash, Blake2b.digest256(command))
                                    ? "the document, id or reference differs from what was approved" : "a policy check failed";
                            return new Outcome(Status.NO_EFFECT, "Release refused", "Nothing was released because " + why + ".",
                                    facts, Hex.encode(proposalKey));
                        });
            }
            default -> throw new InvalidInput("unknown command " + commandId);
        };
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        String proposal = new Inputs(fields).matching("proposal", "Proposal id", ID_RULE, "is not a valid proposal id");
        byte[] key = WorkflowModule.compositeKey("role-approvals", ("q/" + proposal).getBytes(StandardCharsets.UTF_8));
        Proposal p = decodeProposal(reader.latest(key));
        return new LookupResult(p == null ? "No proposal " + proposal : proposal + " · " + PROPOSAL_STATUS[p.status],
                proposalFacts(proposal, p), Hex.encode(key));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> v = (List<?>) Cbor.decode(body);
            if (RELEASE_TOPIC.equals(topic)) {
                return "release " + v.get(4) + " under " + v.get(1);
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

    private Judge actorJudge(String proposal, byte[] proposalKey, MemberDirectory members, String action) {
        return (before, after, fin) -> {
            Proposal p = decodeProposal(after.get("proposal"));
            List<Fact> facts = new ArrayList<>(proposalFacts(proposal, p));
            String code = resultCode(fin);
            facts.add(0, new Fact("Result code", code, "Recorded on chain for this message (role-approvals r/c/<message>)"));
            if (!"ACCEPTED".equals(code)) {
                return new Outcome(Status.NO_EFFECT, "Refused by the policy: " + code, explain(code), facts, Hex.encode(proposalKey));
            }
            String headline = switch (action) {
                case "propose" -> "Proposal open — needs 2 independent auditors";
                case "reject" -> "Proposal REJECTED";
                default -> p != null && p.status == 1 ? "Policy satisfied — proposal APPROVED"
                        : "Approval accepted (" + (p == null ? 0 : p.approvals()) + " of 2)";
            };
            String detail = switch (action) {
                case "propose" -> "Switch to Aiko or Bruno (auditors from different organisations) and approve.";
                case "reject" -> "A release is no longer possible for this proposal.";
                default -> p != null && p.status == 1 ? "Next: relay the release from any member node (step 3)."
                        : "One more approval from an auditor of a different organisation is needed.";
            };
            return new Outcome(Status.APPLIED, headline, detail, facts, Hex.encode(proposalKey));
        };
    }

    private static String explain(String code) {
        return switch (code) {
            case "ROLE_MISMATCH" -> "This person does not hold the role the policy requires for this action.";
            case "DISTINCTNESS_DUPLICATE" -> "The policy needs approvals from different organisations — this one is already counted.";
            case "EXACT_REPLAY" -> "The same signed statement was already applied.";
            case "TERMINAL" -> "The proposal is already finished.";
            case "EXPIRED" -> "The proposal's deadline has passed.";
            case "UNKNOWN_RECORD" -> "The proposal (or actor) does not exist.";
            case "CONFLICT" -> "A proposal with this id already exists with different content.";
            default -> "The message was finalized, but the role-approval policy refused it.";
        };
    }

    private static String resultCode(Finalized fin) {
        byte[] key = WorkflowModule.compositeKey("role-approvals", ("r/c/" + fin.messageId()).getBytes(StandardCharsets.US_ASCII));
        StateReader.Entry entry = fin.reader().entry(key, fin.height());
        if (!entry.present()) {
            return "NO_RESULT";
        }
        int code = ((Long) ((List<?>) Cbor.decode(entry.value())).get(3)).intValue();
        return code >= 0 && code < RESULT.length ? RESULT[code] : "CODE_" + code;
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

    /** [1, proposalId, "document-release", 1, entityId, bstr(sha256(content)), reference]; its blake2b is the commitment. */
    private static byte[] releaseCommand(String proposal, Inputs in) {
        String entity = in.matching("entity", "Document id", ID_RULE, "must start with a letter: a–z, 0–9, - (max 63)");
        String content = in.text("content", "Document content", 8192);
        String reference = in.text("reference", "Reference", 200);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return array(uint(1), text(proposal), text(POLICY), uint(1), text(entity), bstr(hash), text(reference));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Decision(int action, String actor, String organization) {
    }

    private record Proposal(int status, byte[] payloadHash, long deadline, String proposer, String proposerOrg,
                            long createdHeight, List<Decision> decisions) {
        long approvals() {
            return decisions.stream().filter(d -> d.action == 1).count();
        }
    }

    private static Proposal decodeProposal(StateReader.Entry entry) {
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

    private static List<Fact> proposalFacts(String proposal, Proposal p) {
        if (p == null) {
            return List.of(Fact.of("Proposal", proposal), Fact.of("Status", "not found"));
        }
        List<Fact> facts = new ArrayList<>(List.of(Fact.of("Proposal", proposal), Fact.of("Status", PROPOSAL_STATUS[p.status]),
                Fact.of("Proposed by", p.proposer + " (" + p.proposerOrg + ")"),
                Fact.of("Approvals", p.approvals() + " of 2 (different organisations)"),
                Fact.of("Deadline", "block " + p.deadline),
                new Fact("Commitment", Hex.encode(p.payloadHash), "blake2b-256 of the release command (document fingerprint, id, reference)")));
        for (Decision d : p.decisions) {
            facts.add(Fact.of(d.action == 1 ? "Approved by" : "Rejected by", d.actor + " (" + d.organization + ")"));
        }
        return facts;
    }

    private static long count(StateReader.Entry entry) {
        return entry == null || !entry.present() ? 0 : (Long) ((List<?>) Cbor.decode(entry.value())).get(0);
    }
}
