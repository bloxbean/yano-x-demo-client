package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.Inputs;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.Specs;
import com.bloxbean.yano.democlient.chain.Specs.Field;
import com.bloxbean.yano.democlient.chain.Specs.Option;
import com.bloxbean.yano.democlient.chain.StateReader;
import com.bloxbean.yano.democlient.codec.Blake2b;
import com.bloxbean.yano.democlient.codec.Cbor;
import com.bloxbean.yano.democlient.codec.Hex;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/**
 * workflow-chain (showcase-composite, order-approval-outbox-v1): one chain composing an order registry, approvals, an
 * audit trail and a release workflow whose effect (an outbox write by node-0) reports back into consensus.
 */
@ApplicationScoped
public class WorkflowModule implements ChainModule {
    private static final String ORDERS_TOPIC = "orders.command.v1";
    private static final String APPROVALS_TOPIC = "approvals.command.v1";
    private static final String RELEASE_TOPIC = "showcase.release.v1";
    private static final String ID_RULE = "[a-z0-9][a-z0-9._-]{0,63}";
    private static final String[] RELEASE_STATUS = {"PENDING", "CONFIRMED", "FAILED"};
    private static final int RELEASES_PER_HOUR = 10;

    private final Deque<Long> releases = new ArrayDeque<>();

    private static final Specs.Chain SPEC = new Specs.Chain(
            "workflow-chain", "Order release workflow", "Register → approve → release, with a real side effect",
            "Four components share one chain: an order registry, approvals, an audit trail and a release workflow. An"
                    + " order can only be released after its exact bytes were approved by a quorum of members. The release"
                    + " emits an effect — a member node writes the order to an outbox — and the effect's result comes back"
                    + " into consensus. Walk through the four steps in order.",
            "showcase-composite", "Workflows", List.of("composition", "effects", "quorum"), false, "rose",
            List.of(new Specs.Command("register", "1 · Register order", "Record an order in the chain's order registry.",
                            "The order's exact bytes become the thing that must be approved.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("order", "Order id", 64, "Lower-case id, e.g. order-2044").withPattern(ID_RULE),
                                    Field.number("amount", "Amount", "1 – 1,000,000"),
                                    new Field("currency", "Currency", "select", true, null, null, null,
                                            List.of(new Option("USD", "USD"), new Option("EUR", "EUR"), new Option("ADA", "ADA")))),
                            List.of("Step 2: propose the order for approval")),
                    new Specs.Command("propose", "2 · Propose approval", "Ask members to approve this exact order.",
                            "The approval commits to a hash of the order bytes read from chain state.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("proposal", "Proposal id", 64, "e.g. proposal-2044").withPattern(ID_RULE),
                                    Field.text("order", "Order id", 64, "The registered order").withPattern(ID_RULE),
                                    new Field("required", "Approvals required", "select", true, null, null, null,
                                            List.of(new Option("2", "2"), new Option("3", "3")))),
                            List.of("Step 3: switch member and approve (twice, from two members)")),
                    new Specs.Command("approve", "3 · Approve", "Vote for the proposal as the selected member.",
                            "Each member counts once; the proposal flips to APPROVED at the quorum.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("proposal", "Proposal id", 64, "The proposal to approve").withPattern(ID_RULE)),
                            List.of("Approve from a second member, then release")),
                    new Specs.Command("release", "4 · Release", "Release the approved order.",
                            "Cross-component checks in one transition, then an effect executed by a member node whose"
                                    + " result is written back on chain (PENDING → CONFIRMED).",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("release", "Release id", 64, "One-shot id, e.g. release-2044").withPattern(ID_RULE),
                                    Field.text("order", "Order id", 64, "The approved order").withPattern(ID_RULE),
                                    Field.text("proposal", "Proposal id", 64, "Its approved proposal").withPattern(ID_RULE)),
                            List.of("Release again with the same id: one-shot ids are refused",
                                    "Look up the release to see the effect outcome"))),
            List.of(new Specs.Lookup("release", "Look up a release", "Release record and its audit entry",
                            List.of(Field.text("release", "Release id", 64, "Release id").withPattern(ID_RULE))),
                    new Specs.Lookup("order", "Look up an order", "Registered order and its owner",
                            List.of(Field.text("order", "Order id", 64, "Order id").withPattern(ID_RULE))),
                    new Specs.Lookup("proposal", "Look up a proposal", "Approval status of a proposal",
                            List.of(Field.text("proposal", "Proposal id", 64, "Proposal id").withPattern(ID_RULE)))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String n = context.sessionTag() + "-" + context.sequence();
        return switch (commandId) {
            case "register" -> Map.of("order", "order-" + n, "amount", "4200", "currency", "USD");
            case "propose" -> Map.of("proposal", "proposal-" + n, "order", "order-" + n, "required", "2");
            case "approve" -> Map.of("proposal", "proposal-" + n);
            default -> Map.of("release", "release-" + n, "order", "order-" + n, "proposal", "proposal-" + n);
        };
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        MemberDirectory members = context.members();
        return switch (commandId) {
            case "register" -> {
                String order = id(in, "order", "Order id");
                long amount = in.number("amount", "Amount", 1, 1_000_000);
                String currency = in.matching("currency", "Currency", "USD|EUR|ADA", "is not supported");
                byte[] value = ("{\"amount\":" + amount + ",\"currency\":\"" + currency + "\",\"id\":\"" + order + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                byte[] key = orderKey(order);
                byte[] body = array(uint(0), bstr(order.getBytes(StandardCharsets.UTF_8)), bstr(value));
                yield new Prepared(ORDERS_TOPIC, body, "register " + order + " (" + amount + " " + currency + ")",
                        List.of(new Probe("order", key)), (before, after, fin) -> {
                    StateReader.Entry was = before.get("order");
                    StateReader.Entry now = after.get("order");
                    List<Fact> facts = orderFacts(order, now, members);
                    if (was != null && was.present()) {
                        return new Outcome(Status.NO_EFFECT, "Order id already registered",
                                "Order ids are owned by the first member that registered them; pick a new id.", facts, Hex.encode(key));
                    }
                    return now != null && now.present()
                            ? new Outcome(Status.APPLIED, "Order registered", "Next: propose it for approval. The"
                            + " proposal will commit to the hash of exactly these bytes.", facts, Hex.encode(key))
                            : new Outcome(Status.NO_EFFECT, "No change", "The order is not in state after this block.", facts, null);
                });
            }
            case "propose" -> {
                String proposal = id(in, "proposal", "Proposal id");
                String order = id(in, "order", "Order id");
                long required = in.number("required", "Approvals required", 2, 3);
                byte[] orderValue = orderValue(context.reader().latest(orderKey(order)));
                if (orderValue == null) {
                    throw new InvalidInput("Order " + order + " is not registered yet — do step 1 first");
                }
                byte[] key = approvalKey(proposal);
                byte[] body = array(uint(0), text(proposal), bstr(orderValue), uint(required), uint(0));
                yield new Prepared(APPROVALS_TOPIC, body, "propose " + proposal + " for " + order,
                        List.of(new Probe("proposal", key)), approvalJudge(proposal, key, members, "propose", context));
            }
            case "approve" -> {
                String proposal = id(in, "proposal", "Proposal id");
                byte[] key = approvalKey(proposal);
                yield new Prepared(APPROVALS_TOPIC, array(uint(1), text(proposal)), "approve " + proposal,
                        List.of(new Probe("proposal", key)), approvalJudge(proposal, key, members, "approve", context));
            }
            case "release" -> {
                String release = id(in, "release", "Release id");
                String order = id(in, "order", "Order id");
                String proposal = id(in, "proposal", "Proposal id");
                ApprovalsModule.Item approval = ApprovalsModule.decode(context.reader().latest(approvalKey(proposal)));
                if (approval == null || approval.status() != 1) {
                    throw new InvalidInput("Proposal " + proposal + " is not APPROVED yet — collect the approvals first");
                }
                if (!takeReleaseBudget()) {
                    throw new InvalidInput("The public demo allows " + RELEASES_PER_HOUR + " releases per hour; try again later");
                }
                byte[] body = array(uint(1), text(release), bstr(order.getBytes(StandardCharsets.UTF_8)), text(proposal));
                byte[] recordKey = releaseKey(release);
                List<Probe> probes = List.of(new Probe("release", recordKey), new Probe("audit", auditKey(release)));
                yield new Prepared(RELEASE_TOPIC, body, "release " + release, probes, (before, after, fin) -> {
                    Release was = decodeRelease(before.get("release"));
                    Release now = decodeRelease(after.get("release"));
                    if (was != null) {
                        return new Outcome(Status.NO_EFFECT, "Release id already used",
                                "Release ids are one-shot: the workflow claims the id once and ignores replays.",
                                releaseFacts(release, was, after.get("audit")), Hex.encode(recordKey));
                    }
                    if (now == null) {
                        return new Outcome(Status.NO_EFFECT, "Release refused by the workflow",
                                "One of the checks failed (order present, proposal APPROVED, approved hash equals the"
                                        + " order hash).", List.of(Fact.of("Release", release)), null);
                    }
                    return new Outcome(Status.APPLIED, "Released — effect emitted, waiting for the outbox",
                            "The workflow checked the order against its approval, appended the audit trail and emitted an"
                                    + " outbox effect. A member node executes it and reports the result back on chain.",
                            releaseFacts(release, now, after.get("audit")), Hex.encode(recordKey));
                }, (before, latest, fin) -> {
                    Release now = decodeRelease(latest.get("release"));
                    if (now == null || now.status == 0) {
                        return null;
                    }
                    return new Outcome(now.status == 1 ? Status.APPLIED : Status.NO_EFFECT,
                            now.status == 1 ? "Released and CONFIRMED by the outbox effect" : "Effect FAILED",
                            "The effect ran off-chain on a member node and its result was finalized back into the chain"
                                    + " — the full round trip is provable.", releaseFacts(release, now, latest.get("audit")),
                            Hex.encode(recordKey));
                }, "workflow-release");
            }
            default -> throw new InvalidInput("unknown command " + commandId);
        };
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        Inputs in = new Inputs(fields);
        return switch (lookupId) {
            case "release" -> {
                String release = id(in, "release", "Release id");
                Release record = decodeRelease(reader.latest(releaseKey(release)));
                yield new LookupResult(record == null ? "No release " + release : release + " · " + RELEASE_STATUS[record.status],
                        record == null ? List.of(Fact.of("Release", release), Fact.of("Status", "not found"))
                                : releaseFacts(release, record, reader.latest(auditKey(release))), Hex.encode(releaseKey(release)));
            }
            case "order" -> {
                String order = id(in, "order", "Order id");
                StateReader.Entry entry = reader.latest(orderKey(order));
                yield new LookupResult(entry.present() ? "Order " + order : "No order " + order,
                        orderFacts(order, entry, members), Hex.encode(orderKey(order)));
            }
            case "proposal" -> {
                String proposal = id(in, "proposal", "Proposal id");
                ApprovalsModule.Item item = ApprovalsModule.decode(reader.latest(approvalKey(proposal)));
                yield new LookupResult(item == null ? "No proposal " + proposal : proposal + " · " + ApprovalsModule.STATUS[item.status()],
                        ApprovalsModule.facts(proposal, item, members), Hex.encode(approvalKey(proposal)));
            }
            default -> throw new InvalidInput("unknown lookup " + lookupId);
        };
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> items = (List<?>) Cbor.decode(body);
            return switch (topic) {
                case ORDERS_TOPIC -> "register order " + new String((byte[]) items.get(1), StandardCharsets.UTF_8);
                case APPROVALS_TOPIC -> ((Long) items.get(0) == 0 ? "propose " : (Long) items.get(0) == 1 ? "approve " : "reject ")
                        + items.get(1);
                case RELEASE_TOPIC -> "release " + items.get(1) + " of " + new String((byte[]) items.get(2), StandardCharsets.UTF_8);
                default -> topic;
            };
        } catch (RuntimeException undecodable) {
            return topic.startsWith("~fx") ? "effect result reported by a member node" : null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Judge approvalJudge(String proposal, byte[] key, MemberDirectory members, String op, CommandContext context) {
        String me = context.actingMemberKey();
        return (before, after, fin) -> {
            ApprovalsModule.Item was = ApprovalsModule.decode(before.get("proposal"));
            ApprovalsModule.Item now = ApprovalsModule.decode(after.get("proposal"));
            List<Fact> facts = ApprovalsModule.facts(proposal, now, members);
            if ("propose".equals(op)) {
                return was != null ? new Outcome(Status.NO_EFFECT, "Proposal id already exists", "Pick a new id.", facts, Hex.encode(key))
                        : new Outcome(Status.APPLIED, "Proposal open — needs " + (now == null ? "?" : now.required()) + " approvals",
                        "Switch to other members and approve it (step 3).", facts, Hex.encode(key));
            }
            if (was == null) {
                return new Outcome(Status.NO_EFFECT, "No such proposal", "Propose it first (step 2).", facts, Hex.encode(key));
            }
            if (was.approvers().contains(me)) {
                return new Outcome(Status.NO_EFFECT, "Already approved by this member", "Switch to another member.", facts, Hex.encode(key));
            }
            if (was.status() != 0) {
                return new Outcome(Status.NO_EFFECT, "Proposal is already " + ApprovalsModule.STATUS[was.status()],
                        "Terminal proposals never change.", facts, Hex.encode(key));
            }
            return now != null && now.status() == 1
                    ? new Outcome(Status.APPLIED, "Quorum reached — APPROVED", "Next: release the order (step 4).", facts, Hex.encode(key))
                    : new Outcome(Status.APPLIED, "Vote counted (" + (now == null ? 0 : now.approvers().size()) + " of "
                    + (now == null ? "?" : now.required()) + ")", "Approve from another member.", facts, Hex.encode(key));
        };
    }

    private synchronized boolean takeReleaseBudget() {
        long now = System.currentTimeMillis();
        while (!releases.isEmpty() && now - releases.peekFirst() > 3_600_000) {
            releases.removeFirst();
        }
        if (releases.size() >= RELEASES_PER_HOUR) {
            return false;
        }
        releases.addLast(now);
        return true;
    }

    private static String id(Inputs in, String name, String label) {
        return in.matching(name, label, ID_RULE, "must be 1–64 lower-case letters, digits or . _ -");
    }

    private record Release(int status, long effectHeight, String externalRef, String orderHash, String approvalId) {
    }

    private static Release decodeRelease(StateReader.Entry entry) {
        if (entry == null || !entry.present()) {
            return null;
        }
        List<?> v = (List<?>) Cbor.decode(entry.value());
        return new Release(((Long) v.get(1)).intValue(), v.get(2) instanceof Long h ? h : -1,
                v.get(5) instanceof String ref ? ref : v.get(5) instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : "",
                v.get(7) instanceof byte[] hash ? Hex.encode(hash) : "", String.valueOf(v.get(8)));
    }

    private static List<Fact> releaseFacts(String release, Release record, StateReader.Entry audit) {
        List<Fact> facts = new ArrayList<>(List.of(Fact.of("Release", release), Fact.of("Status", RELEASE_STATUS[record.status]),
                Fact.of("Approval", record.approvalId), new Fact("Order hash", record.orderHash, "blake2b-256 of the order bytes")));
        if (record.effectHeight >= 0) {
            facts.add(Fact.of("Effect emitted at block", Long.toString(record.effectHeight)));
        }
        if (!record.externalRef.isEmpty()) {
            facts.add(new Fact("Outbox reference", record.externalRef, "Reported by the member node that executed the effect"));
        }
        if (audit != null && audit.present()) {
            List<?> trail = (List<?>) Cbor.decode(audit.value());
            facts.add(Fact.of("Audit trail entries", String.valueOf(trail.get(0))));
        }
        return facts;
    }

    private static byte[] orderValue(StateReader.Entry entry) {
        if (entry == null || !entry.present()) {
            return null;
        }
        return (byte[]) ((List<?>) Cbor.decode(entry.value())).get(1);
    }

    private static List<Fact> orderFacts(String order, StateReader.Entry entry, MemberDirectory members) {
        if (entry == null || !entry.present()) {
            return List.of(Fact.of("Order", order), Fact.of("Presence", "absent"));
        }
        List<?> v = (List<?>) Cbor.decode(entry.value());
        byte[] value = (byte[]) v.get(1);
        return List.of(Fact.of("Order", order), Fact.of("Order bytes", new String(value, StandardCharsets.UTF_8)),
                new Fact("Order hash", Hex.encode(Blake2b.digest256(value)), "What the approval must commit to"),
                Fact.of("Registered by", members.name(Hex.encode((byte[]) v.get(0)))));
    }

    static byte[] compositeKey(String component, byte[] local) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("yano-composite-state-v1\0".getBytes(StandardCharsets.US_ASCII));
        byte[] name = component.getBytes(StandardCharsets.US_ASCII);
        out.write(name.length);
        out.writeBytes(name);
        out.write(local.length >> 8);
        out.write(local.length & 0xff);
        out.writeBytes(local);
        return out.toByteArray();
    }

    private static byte[] orderKey(String order) {
        return compositeKey("orders", order.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] approvalKey(String proposal) {
        return compositeKey("approvals", ("i/" + proposal).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] releaseKey(String release) {
        return compositeKey("release", ("r/" + release).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] auditKey(String release) {
        return compositeKey("audit", ("e/" + release).getBytes(StandardCharsets.UTF_8));
    }
}
