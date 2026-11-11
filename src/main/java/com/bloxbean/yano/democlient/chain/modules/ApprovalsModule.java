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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/** approvals-chain (approvals): propose an item, members approve until the quorum is reached; one reject ends it. */
@ApplicationScoped
public class ApprovalsModule implements ChainModule {
    static final String TOPIC = "approvals.command.v1";
    private static final int MAX_ITEM = 200;
    private static final String ITEM_RULE = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}";
    static final String[] STATUS = {"PENDING", "APPROVED", "REJECTED", "EXPIRED"};

    private static final Specs.Chain SPEC = new Specs.Chain(
            "approvals-chain", "Multi-party approval", "Proposals that need a quorum of member votes",
            "One member proposes an item with a required number of approvals. Other members vote from their own"
                    + " nodes; the item becomes APPROVED once the quorum is reached, and a single rejection ends it."
                    + " Votes are cast by member organisations, so pick who you act as.",
            "approvals", "Workflows", List.of("quorum", "voting", "proofs"), false, "mint",
            List.of(new Specs.Command("propose", "Propose an item", "Open an item for approval.",
                            "A business quorum (e.g. 2 approvals) on top of the consensus quorum (the member signatures that finalize every block).",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("item", "Item id", MAX_ITEM, "Unique id, e.g. purchase-order-7").withPattern(ITEM_RULE),
                                    Field.textarea("payload", "What is being approved", 1024, "Public, permanent text"),
                                    new Field("required", "Approvals required", "select", true, null, null,
                                            "How many distinct members must approve",
                                            List.of(new Option("1", "1"), new Option("2", "2"), new Option("3", "3"),
                                                    new Option("4", "4"), new Option("5", "5")))),
                            List.of("Switch to another member and approve it",
                                    "Approve twice as the same member: the second vote is ignored")),
                    new Specs.Command("approve", "Approve", "Vote for a pending item as the selected member.",
                            "Each member counts once; the item flips to APPROVED at the quorum.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("item", "Item id", MAX_ITEM, "The item to approve").withPattern(ITEM_RULE)),
                            List.of("Approve from another member until the quorum is reached")),
                    new Specs.Command("reject", "Reject", "Reject a pending item as the selected member.",
                            "A single rejection from any member ends a pending item.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("item", "Item id", MAX_ITEM, "The item to reject").withPattern(ITEM_RULE)),
                            List.of("Try approving it afterwards: terminal items do not change"))),
            List.of(new Specs.Lookup("item", "Look up an item", "Status, votes and quorum of an item",
                    List.of(Field.text("item", "Item id", MAX_ITEM, "Item id").withPattern(ITEM_RULE)))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String item = context.id("po");
        return "propose".equals(commandId)
                ? Map.of("item", item, "payload", "Purchase order: 40 units of part X-17 from Acme Components, €12,400",
                "required", "2")
                : Map.of("item", item);
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String item = in.matching("item", "Item id", ITEM_RULE, "must be 1–200 letters, digits or . _ : / -");
        String me = context.actingMemberKey();
        MemberDirectory members = context.members();
        byte[] key = itemKey(item);
        List<Probe> probes = List.of(new Probe("item", key));
        byte[] body;
        String summary;
        switch (commandId) {
            case "propose" -> {
                String payload = in.text("payload", "What is being approved", 1024);
                long required = in.number("required", "Approvals required", 1, 5);
                body = array(uint(0), text(item), bstr(payload.getBytes(StandardCharsets.UTF_8)), uint(required), uint(0));
                summary = "propose " + item + " (needs " + required + ")";
            }
            case "approve" -> {
                body = array(uint(1), text(item));
                summary = "approve " + item;
            }
            case "reject" -> {
                body = array(uint(2), text(item));
                summary = "reject " + item;
            }
            default -> throw new InvalidInput("unknown command " + commandId);
        }
        return new Prepared(TOPIC, body, summary, probes, (before, after, fin) -> {
            Item was = decode(before.get("item"));
            Item now = decode(after.get("item"));
            List<Fact> facts = facts(item, now, members);
            String proof = Hex.encode(key);
            return switch (commandId) {
                case "propose" -> was != null
                        ? new Outcome(Status.NO_EFFECT, "Item already exists", "Proposing an existing id is ignored.", facts, proof)
                        : now != null ? new Outcome(Status.APPLIED, "Item proposed — waiting for " + now.required + " approval(s)",
                        "Switch to other members and approve it.", facts, proof)
                        : new Outcome(Status.NO_EFFECT, "No change", "The item is not in state after this block.", facts, proof);
                case "approve" -> {
                    if (was == null) {
                        yield new Outcome(Status.NO_EFFECT, "No such item", "Propose it first.", facts, proof);
                    }
                    if (was.status != 0) {
                        yield new Outcome(Status.NO_EFFECT, "Item is already " + STATUS[was.status],
                                "Terminal items never change.", facts, proof);
                    }
                    if (was.approvers.contains(me)) {
                        yield new Outcome(Status.NO_EFFECT, "Already approved by this member",
                                "Each member counts once — a second vote is ignored.", facts, proof);
                    }
                    if (now != null && now.status == 1) {
                        yield new Outcome(Status.APPLIED, "Quorum reached — item APPROVED",
                                now.approvers.size() + " of " + now.required + " required approvals.", facts, proof);
                    }
                    if (now != null && now.status == 3) {
                        yield new Outcome(Status.NO_EFFECT, "Item expired", "Its deadline had passed.", facts, proof);
                    }
                    yield now != null && now.approvers.contains(me)
                            ? new Outcome(Status.APPLIED, "Vote counted (" + now.approvers.size() + " of " + now.required + ")",
                            "More approvals from other members are needed.", facts, proof)
                            : new Outcome(Status.NO_EFFECT, "No change", "The vote is not in state after this block.", facts, proof);
                }
                default -> {
                    if (was == null) {
                        yield new Outcome(Status.NO_EFFECT, "No such item", "Nothing to reject.", facts, proof);
                    }
                    if (was.status != 0) {
                        yield new Outcome(Status.NO_EFFECT, "Item is already " + STATUS[was.status],
                                "Terminal items never change.", facts, proof);
                    }
                    yield now != null && now.status == 2
                            ? new Outcome(Status.APPLIED, "Item REJECTED", "One rejection ends a pending item.", facts, proof)
                            : new Outcome(Status.NO_EFFECT, "No change", "The rejection is not in state after this block.", facts, proof);
                }
            };
        });
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        String item = new Inputs(fields).matching("item", "Item id", ITEM_RULE, "is not a valid item id");
        Item found = decode(reader.latest(itemKey(item)));
        return new LookupResult(found == null ? "No item " + item : item + " · " + STATUS[found.status],
                facts(item, found, members), Hex.encode(itemKey(item)));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> items = (List<?>) Cbor.decode(body);
            long op = (Long) items.get(0);
            String item = (String) items.get(1);
            return op == 0 ? "propose " + item + " (needs " + items.get(3) + ")" : (op == 1 ? "approve " : "reject ") + item;
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    static byte[] itemKey(String item) {
        return ("i/" + item).getBytes(StandardCharsets.UTF_8);
    }

    record Item(int status, String proposer, String payloadDigest, long required, List<String> approvers,
                        String rejecter) {
    }

    static Item decode(StateReader.Entry entry) {
        if (entry == null || !entry.present()) {
            return null;
        }
        List<?> v = (List<?>) Cbor.decode(entry.value());
        List<String> approvers = new ArrayList<>();
        for (Object approver : (List<?>) v.get(5)) {
            approvers.add(Hex.encode((byte[]) approver));
        }
        byte[] rejecter = (byte[]) v.get(6);
        return new Item(((Long) v.get(0)).intValue(), Hex.encode((byte[]) v.get(1)), Hex.encode((byte[]) v.get(2)),
                (Long) v.get(3), approvers, rejecter.length == 0 ? null : Hex.encode(rejecter));
    }

    static List<Fact> facts(String item, Item found, MemberDirectory members) {
        if (found == null) {
            return List.of(Fact.of("Item", item), Fact.of("Status", "not found"));
        }
        List<Fact> facts = new ArrayList<>(List.of(Fact.of("Item", item), Fact.of("Status", STATUS[found.status]),
                Fact.of("Approvals", found.approvers.size() + " of " + found.required),
                Fact.of("Proposed by", members.name(found.proposer))));
        found.approvers.forEach(a -> facts.add(Fact.of("Approved by", members.name(a))));
        if (found.rejecter != null) {
            facts.add(Fact.of("Rejected by", members.name(found.rejecter)));
        }
        facts.add(new Fact("Payload digest", found.payloadDigest, "blake2b-256 of the proposed text"));
        return facts;
    }

    static String digest(String payload) {
        return Hex.encode(Blake2b.digest256(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
