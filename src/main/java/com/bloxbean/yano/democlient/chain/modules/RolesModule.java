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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/**
 * roles-chain: the stock role-approval state machine (recipe role-approval, preset role-approvals-v1) on its own.
 * It started empty; the member nodes added the organisations, people and the "application-approval" policy afterwards
 * through member governance (propose, approve to the genesis threshold, activate). People then decide: a proposer
 * submits an application for approval, a reviewer from another organisation approves or rejects it. The chain records
 * a signed, provable decision bound to the hash of the exact application; the application itself stays off chain.
 */
@ApplicationScoped
public class RolesModule implements ChainModule {
    static final String CHAIN = "roles-chain";
    static final String ROLE_BUNDLE = "org.yanoproject.x.role-workflow";
    private static final String TOPIC = "role-approvals.command.v1";
    private static final String POLICY = "application-approval";
    private static final String CLAUSE = "reviewers";
    /** The byte contract of the approved payload: [1, proposalId, applicant, request] in canonical CBOR. */
    static final String PAYLOAD_DOMAIN = "yano.demo.application.v1";
    private static final String ID_RULE = "[a-z][a-z0-9-]{0,62}";
    private static final String REQUIRED = "1 reviewer from another organisation";
    private static final String COMMITMENT_HINT = "blake2b-256 of the exact application (" + PAYLOAD_DOMAIN + ")";
    /** Proposal lifetime asked for; the policy caps it at 1000 blocks. */
    private static final long DEADLINE_BLOCKS = 600;
    /** The records the member nodes activated from roles-chain.role-plan.yaml. */
    private static final List<String> ORGANIZATIONS = List.of("organization-a", "organization-b");
    private static final List<String> PEOPLE = List.of("proposer-a", "reviewer-a");

    private final ActorDirectory actors;

    public RolesModule(ActorDirectory actors) {
        this.actors = actors;
    }

    private static final List<Field> APPLICATION_FIELDS = List.of(
            Field.text("proposal", "Proposal id", 63, "e.g. vendor-acme-7").withPattern(ID_RULE),
            Field.text("applicant", "Applicant", 120, "Who is applying, e.g. Acme Supplies Ltd"),
            Field.textarea("request", "Application", 4000, "What is being approved; only its hash goes on chain"));

    private static final Specs.Chain SPEC = new Specs.Chain(
            CHAIN, "Role approvals", "Members onboard people at runtime; people approve",
            "The stock role-approval state machine on its own. The chain started with no people at all: the member"
                    + " nodes added the organisations, the proposer and reviewer and the application-approval policy"
                    + " afterwards, each through member governance (proposed, approved by 2 of the members, activated)."
                    + " Now people decide: a proposer submits an application, and a reviewer from another organisation"
                    + " approves or rejects it with their own key. The chain keeps a signed, provable decision bound to"
                    + " the hash of the exact application.",
            "role-approvals", "People & roles", List.of("stock recipe", "runtime onboarding", "actor signatures"), false,
            "amber",
            List.of(new Specs.Command("propose", "1 · Propose (proposer)", "The proposer submits an application for approval.",
                            "An actor-signed proposal bound to the hash of the exact application.",
                            Specs.PersonaKind.ACTOR, List.of("proposer-a", "reviewer-a"), APPLICATION_FIELDS,
                            List.of("Switch to the reviewer and approve", "Try proposing as the reviewer: ROLE_MISMATCH")),
                    new Specs.Command("approve", "2 · Approve (reviewer)", "Approve as the selected person.",
                            "Policy clause “reviewers”: 1 reviewer from a different organisation than the proposer.",
                            Specs.PersonaKind.ACTOR, List.of("reviewer-a", "proposer-a"),
                            List.of(Field.text("proposal", "Proposal id", 63, "The proposal to approve").withPattern(ID_RULE)),
                            List.of("Approve again: the proposal is already decided", "Try approving as the proposer: ROLE_MISMATCH")),
                    new Specs.Command("reject", "Reject (reviewer)", "Reject the proposal as the selected person.",
                            "Any eligible reviewer can stop it.", Specs.PersonaKind.ACTOR, List.of("reviewer-a"),
                            List.of(Field.text("proposal", "Proposal id", 63, "The proposal to reject").withPattern(ID_RULE)),
                            List.of("Then try approving it: the proposal is already decided"))),
            List.of(new Specs.Lookup("proposal", "Look up a proposal", "Status and signed decisions",
                            List.of(Field.text("proposal", "Proposal id", 63, "Proposal id").withPattern(ID_RULE))),
                    new Specs.Lookup("check", "Check an application", "Is this the exact application that was decided?",
                            APPLICATION_FIELDS),
                    new Specs.Lookup("people", "Who can decide", "Organisations, people, keys and the policy, read from the chain",
                            List.of())));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String n = context.sessionTag() + "-" + context.sequence();
        return switch (commandId) {
            case "approve", "reject" -> Map.of("proposal", "vendor-" + n);
            default -> Map.of("proposal", "vendor-" + n, "applicant", "Acme Supplies Ltd",
                    "request", "Add Acme Supplies Ltd as an approved vendor for packaging materials, up to 50,000 EUR a year.");
        };
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String proposal = in.matching("proposal", "Proposal id", ID_RULE, "must start with a letter: a–z, 0–9, - (max 63)");
        byte[] proposalKey = RoleApprovalProposals.proposalKey(proposal);
        StateReader reader = context.reader();
        String actor = actor(context, commandId);
        return switch (commandId) {
            case "propose" -> {
                long tip = reader.tipHeight();
                if (tip < 0) {
                    throw new InvalidInput("The chain tip is not readable right now; try again");
                }
                var statement = new RoleStatements.Statement(RoleStatements.PROPOSE, CHAIN, proposal, POLICY, 1,
                        PAYLOAD_DOMAIN, payloadHash(proposal, in), tip + DEADLINE_BLOCKS, actor, 1, key(actor), "");
                yield new Prepared(TOPIC, actors.signedStatement(actor, statement), "propose " + proposal + " as " + actor,
                        List.of(new Probe("proposal", proposalKey)), judge(proposal, proposalKey, commandId, actor));
            }
            case "approve", "reject" -> {
                RoleApprovalProposals.Proposal current = RoleApprovalProposals.decode(reader.latest(proposalKey));
                if (current == null) {
                    throw new InvalidInput("Proposal " + proposal + " does not exist — propose it first");
                }
                var statement = new RoleStatements.Statement("approve".equals(commandId) ? RoleStatements.APPROVE
                        : RoleStatements.REJECT, CHAIN, proposal, POLICY, 1, PAYLOAD_DOMAIN, current.payloadHash(),
                        current.deadline(), actor, 1, key(actor), CLAUSE);
                yield new Prepared(TOPIC, actors.signedStatement(actor, statement), commandId + " " + proposal + " as " + actor,
                        List.of(new Probe("proposal", proposalKey)), judge(proposal, proposalKey, commandId, actor));
            }
            default -> throw new InvalidInput("unknown command " + commandId);
        };
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        Inputs in = new Inputs(fields);
        return switch (lookupId) {
            case "people" -> people(reader);
            case "check" -> {
                String proposal = in.matching("proposal", "Proposal id", ID_RULE, "is not a valid proposal id");
                byte[] key = RoleApprovalProposals.proposalKey(proposal);
                RoleApprovalProposals.Proposal p = RoleApprovalProposals.decode(reader.latest(key));
                if (p == null) {
                    yield new LookupResult("No proposal " + proposal, List.of(), Hex.encode(key));
                }
                byte[] hash = payloadHash(proposal, in);
                boolean same = Arrays.equals(hash, p.payloadHash());
                List<Fact> facts = new ArrayList<>(List.of(
                        new Fact("This application hashes to", Hex.encode(hash), COMMITMENT_HINT),
                        new Fact("The proposal committed to", Hex.encode(p.payloadHash()), "Signed by " + p.proposer())));
                facts.addAll(RoleApprovalProposals.facts(proposal, p, COMMITMENT_HINT, REQUIRED));
                yield new LookupResult(same ? "Match — this is the application that was " + p.statusName()
                        : "No match — this is not the application that was proposed", facts, Hex.encode(key));
            }
            default -> {
                String proposal = in.matching("proposal", "Proposal id", ID_RULE, "is not a valid proposal id");
                byte[] key = RoleApprovalProposals.proposalKey(proposal);
                RoleApprovalProposals.Proposal p = RoleApprovalProposals.decode(reader.latest(key));
                yield new LookupResult(p == null ? "No proposal " + proposal : proposal + " · " + p.statusName(),
                        RoleApprovalProposals.facts(proposal, p, COMMITMENT_HINT, REQUIRED), Hex.encode(key));
            }
        };
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        if (!TOPIC.equals(topic)) {
            return "actors.command.v1".equals(topic) ? "member governance: organisation / person record" : null;
        }
        try {
            List<?> v = (List<?>) Cbor.decode(body);
            List<?> statement = (List<?>) Cbor.decode((byte[]) v.get(1));
            String action = switch (((Long) statement.get(1)).intValue()) {
                case 0 -> "propose";
                case 1 -> "approve";
                case 2 -> "reject";
                default -> "cancel";
            };
            return action + " " + statement.get(3) + " — signed by " + statement.get(9);
        } catch (RuntimeException notAStatement) {
            return "member governance: policy record";
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Everyone the members onboarded, read back from the chain with the record's governance revision. */
    private static LookupResult people(StateReader reader) {
        List<Fact> facts = new ArrayList<>();
        String proofKey = null;
        int found = 0;
        for (String id : ORGANIZATIONS) {
            JsonNode r = reader.plugin(ROLE_BUNDLE, "organizations/" + id);
            if (r != null) {
                found++;
                facts.add(Fact.of("Organisation " + id, r.path("record").path("status").asText() + " · revision "
                        + r.path("record").path("revision").asText()));
            }
        }
        for (String id : PEOPLE) {
            JsonNode r = reader.plugin(ROLE_BUNDLE, "actors/" + id);
            if (r == null) {
                continue;
            }
            found++;
            JsonNode record = r.path("record");
            List<String> roles = new ArrayList<>();
            record.path("roles").forEach(role -> roles.add(role.asText()));
            JsonNode k = record.path("keys").path(0);
            facts.add(new Fact("Person " + id, String.join(", ", roles) + " · " + record.path("organizationId").asText()
                    + " · " + record.path("status").asText() + " · revision " + record.path("revision").asText(),
                    "Key " + k.path("keyId").asText() + ": " + k.path("publicKey").asText()));
            proofKey = proofKey == null ? r.path("proofKey").asText(null) : proofKey;
        }
        JsonNode policy = reader.plugin(ROLE_BUNDLE, "policies/" + POLICY);
        if (policy != null) {
            found++;
            JsonNode record = policy.path("record");
            List<String> clauses = new ArrayList<>();
            record.path("clauses").forEach(c -> clauses.add(c.path("minimumCount").asText() + " × "
                    + c.path("role").asText() + " (distinct by " + c.path("distinctBy").asText().toLowerCase() + ")"));
            List<String> proposers = new ArrayList<>();
            record.path("proposerRoles").forEach(role -> proposers.add(role.asText()));
            facts.add(new Fact("Policy " + POLICY, "proposed by " + String.join("/", proposers) + "; needs "
                    + String.join(" and ", clauses) + "; rejection " + record.path("rejectionMode").asText().toLowerCase()
                    + "; lifetime " + record.path("maximumLifetimeBlocks").asText() + " blocks", "revision "
                    + record.path("revision").asText()));
        }
        if (found == 0) {
            return new LookupResult("Nobody onboarded yet", List.of(Fact.of("Next step",
                    "The member nodes add the people and the policy with `yano.sh appchain role bootstrap` (see the Config tab's role plan)")),
                    null);
        }
        facts.add(0, Fact.of("Added by", "member governance after the chain started (propose → approve 2 of the members → activate)"));
        return new LookupResult(found + " records onboarded by the members", facts, proofKey);
    }

    /**
     * The plain role-approval workflow records no per-message result code (the governed variant used by
     * document-review and authenticated-map does), so a refused statement simply changes nothing. The outcome is the
     * state change of the proposal; the reason for a refusal is inferred from the person's role and the proposal.
     */
    private Judge judge(String proposal, byte[] proposalKey, String action, String actor) {
        String role = actors.find(actor).map(ActorDirectory.ActorInfo::role).orElse("?");
        String organization = actors.find(actor).map(ActorDirectory.ActorInfo::organization).orElse("?");
        return (before, after, fin) -> {
            RoleApprovalProposals.Proposal was = RoleApprovalProposals.decode(before.get("proposal"));
            RoleApprovalProposals.Proposal p = RoleApprovalProposals.decode(after.get("proposal"));
            List<Fact> facts = new ArrayList<>(RoleApprovalProposals.facts(proposal, p, COMMITMENT_HINT, REQUIRED));
            boolean applied = "propose".equals(action)
                    ? was == null && p != null
                    : p != null && was != null && p.decisions().size() > was.decisions().size();
            facts.add(0, new Fact("Judged from", "the proposal's state before and after this message",
                    "roles-chain's role-approval workflow does not record a result code per message"));
            if (!applied) {
                String why = switch (action) {
                    case "propose" -> was != null ? "a proposal with this id already exists"
                            : !"proposer".equals(role) ? actor + " holds the role “" + role + "”, but the policy lets only"
                            + " a proposer propose" : "the policy refused it (for example an expired deadline)";
                    default -> was == null ? "the proposal does not exist"
                            : was.status() != 0 ? "the proposal is already " + was.statusName()
                            : !"reviewer".equals(role) ? actor + " holds the role “" + role + "”, but the clause needs a reviewer"
                            : organization.equals(was.proposerOrg()) ? "the reviewer must be from another organisation than"
                            + " the proposer" : was.decisions().stream().anyMatch(d -> d.actor().equals(actor))
                            ? actor + " has already decided on this proposal" : "the policy refused it";
                };
                return new Outcome(Status.NO_EFFECT, "Refused — nothing changed", "The chain finalized the message but the"
                        + " policy did not accept it: " + why + ".", facts, Hex.encode(proposalKey));
            }
            String headline = switch (action) {
                case "propose" -> "Proposal open — needs a reviewer from another organisation";
                case "reject" -> "Proposal REJECTED";
                default -> p.status() == 1 ? "Policy satisfied — application APPROVED" : "Approval accepted";
            };
            String detail = switch (action) {
                case "propose" -> "Switch to the reviewer (organization-b) and approve or reject.";
                case "reject" -> "The decision is final and provable; nothing else can change it.";
                default -> "The decision is final and provable. Use “Check an application” to confirm which application it covers.";
            };
            return new Outcome(Status.APPLIED, headline, detail, facts, Hex.encode(proposalKey));
        };
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

    /** blake2b-256 of [1, proposalId, applicant, request]: what the proposer and reviewer sign over. */
    static byte[] payloadHash(String proposal, Inputs in) {
        String applicant = in.text("applicant", "Applicant", 120);
        String request = in.text("request", "Application", 4000);
        return Blake2b.digest256(array(uint(1), text(proposal), text(applicant), text(request)));
    }
}
