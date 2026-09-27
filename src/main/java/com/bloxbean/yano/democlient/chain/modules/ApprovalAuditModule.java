package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.Inputs;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.Specs;
import com.bloxbean.yano.democlient.chain.Specs.Field;
import com.bloxbean.yano.democlient.chain.Specs.Option;
import com.bloxbean.yano.democlient.chain.StateReader;
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

/** approval-audit-chain (declarative-composite): when an item is approved, a binding appends it to an audit trail. */
@ApplicationScoped
public class ApprovalAuditModule implements ChainModule {
    private static final String TOPIC = "reviews.command.v1";
    // Item ids of 218–251 bytes make the pre3 host throw instead of rejecting (runbook L-10).
    private static final String ITEM_RULE = "[a-z0-9][a-z0-9._-]{0,63}";

    private static final Specs.Chain SPEC = new Specs.Chain(
            "approval-audit-chain", "Audited approvals", "Approvals that write their own audit trail",
            "Members vote on review items. The moment an item reaches its quorum, the declarative binding"
                    + " “record-approved” appends the approved payload hash to an audit trail — in the same block,"
                    + " atomically. Rejected items never reach the audit trail.",
            "declarative-composite", "Declarative bindings", List.of("bindings", "quorum", "receipts"), false, "sky",
            List.of(new Specs.Command("propose", "Propose a review item", "Open an item for member votes.",
                            "The business rule lives in the approvals component; the audit reaction lives in YAML.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("item", "Item id", 64, "e.g. po-2044").withPattern(ITEM_RULE),
                                    Field.textarea("payload", "What is being approved", 512, "Public, permanent text"),
                                    new Field("required", "Approvals required", "select", true, null, null, null,
                                            List.of(new Option("2", "2"), new Option("3", "3")))),
                            List.of("Approve from two other members and watch the audit step appear")),
                    new Specs.Command("approve", "Approve", "Vote as the selected member.",
                            "The approval that reaches the quorum triggers the binding in the same transition.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("item", "Item id", 64, "Item to approve").withPattern(ITEM_RULE)),
                            List.of("Vote twice as the same member: the duplicate changes nothing")),
                    new Specs.Command("reject", "Reject", "Reject the item as the selected member.",
                            "Rejected items are never audited.", Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("item", "Item id", 64, "Item to reject").withPattern(ITEM_RULE)),
                            List.of("Look up the item: REJECTED, no audit entry"))),
            List.of(new Specs.Lookup("item", "Look up an item", "Votes and audit status of a review item",
                    List.of(Field.text("item", "Item id", 64, "Item id").withPattern(ITEM_RULE)))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public boolean usesBindingReceipts() {
        return true;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String item = "po-" + context.sessionTag() + "-" + context.sequence();
        return "propose".equals(commandId)
                ? Map.of("item", item, "payload", "Release PO for 12 servers, budget line 7", "required", "2")
                : Map.of("item", item);
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String item = in.matching("item", "Item id", ITEM_RULE, "must be 1–64 lower-case letters, digits or . _ -");
        byte[] body = switch (commandId) {
            case "propose" -> array(uint(0), text(item), bstr(in.text("payload", "Payload", 512).getBytes(StandardCharsets.UTF_8)),
                    uint(in.number("required", "Approvals required", 2, 3)), uint(0));
            case "approve" -> array(uint(1), text(item));
            case "reject" -> array(uint(2), text(item));
            default -> throw new InvalidInput("unknown command " + commandId);
        };
        List<Probe> probes = List.of(new Probe("item", itemKey(item)), new Probe("trail", trailKey(item)));
        MemberDirectory members = context.members();
        return new Prepared(TOPIC, body, commandId + " " + item, probes, (before, after, fin) -> {
            ApprovalsModule.Item was = ApprovalsModule.decode(before.get("item"));
            ApprovalsModule.Item now = ApprovalsModule.decode(after.get("item"));
            Outcome base = BindingReceipts.outcome(fin, r -> {
                if (!r.accepted()) {
                    return new BindingReceipts.Explanation("Refused: " + r.code(), "Nothing changed.");
                }
                if (r.ran("audit")) {
                    return new BindingReceipts.Explanation("Quorum reached — APPROVED and audited in the same step",
                            "The binding appended the approved payload hash to the audit trail atomically.");
                }
                boolean changed = was == null ? now != null : now != null && (was.status() != now.status()
                        || was.approvers().size() != now.approvers().size());
                if (!changed) {
                    return new BindingReceipts.Explanation(Status.NO_EFFECT, "No change", "Duplicate votes and votes on finished items are ignored.");
                }
                return switch (commandId) {
                    case "propose" -> new BindingReceipts.Explanation("Item open — needs " + now.required() + " approvals",
                            "Switch to other members and approve it.");
                    case "reject" -> new BindingReceipts.Explanation("Item REJECTED — no audit entry", "The binding only reacts to approvals.");
                    default -> new BindingReceipts.Explanation("Vote counted (" + now.approvers().size() + " of " + now.required() + ")",
                            "No audit yet: the binding fires when the quorum is reached.");
                };
            });
            List<Fact> facts = new ArrayList<>(ApprovalsModule.facts(item, now, members));
            facts.addAll(base.facts());
            return new Outcome(base.status(), base.headline(), base.detail(), facts, base.proofKeyHex());
        });
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        String item = new Inputs(fields).matching("item", "Item id", ITEM_RULE, "is not a valid item id");
        ApprovalsModule.Item found = ApprovalsModule.decode(reader.latest(itemKey(item)));
        StateReader.Entry trail = reader.latest(trailKey(item));
        List<Fact> facts = new ArrayList<>(ApprovalsModule.facts(item, found, members));
        facts.add(Fact.of("Audit trail", trail.present() ? "entry recorded" : "no entry"));
        return new LookupResult(found == null ? "No item " + item : item + " · " + ApprovalsModule.STATUS[found.status()],
                facts, Hex.encode(itemKey(item)));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> items = (List<?>) Cbor.decode(body);
            long op = (Long) items.get(0);
            return (op == 0 ? "propose " : op == 1 ? "approve " : "reject ") + items.get(1);
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    private static byte[] itemKey(String item) {
        return WorkflowModule.compositeKey("reviews", ("i/" + item).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] trailKey(String item) {
        return WorkflowModule.compositeKey("audit", ("e/" + item).getBytes(StandardCharsets.UTF_8));
    }
}
