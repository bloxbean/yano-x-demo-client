package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.Inputs;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.Specs;
import com.bloxbean.yano.democlient.chain.Specs.Field;
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
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/**
 * registry-audit-chain (declarative-composite): a registry write whose declarative binding "audit-record" appends to an
 * audit trail in the same atomic transition — but only when the value is shorter than 100 bytes.
 */
@ApplicationScoped
public class RegistryAuditModule implements ChainModule {
    private static final String TOPIC = "records.command.v1";
    // Keys of 112–127 / 223–256 bytes make the pre3 host throw instead of rejecting (runbook L-10): stay well below.
    private static final int MAX_KEY = 100;

    private static final Specs.Chain SPEC = new Specs.Chain(
            "registry-audit-chain", "Self-auditing registry", "A registry write that audits itself — declaratively",
            "Two components, one rule written in YAML instead of code: when a registry entry is written with a value"
                    + " shorter than 100 bytes, the binding “audit-record” appends its hash to an audit trail in"
                    + " the same atomic step. If any step fails, the whole cascade rolls back. Each message gets a"
                    + " provable receipt listing every step.",
            "declarative-composite", "Declarative bindings", List.of("bindings", "receipts", "atomic cascade"), false, "lime",
            List.of(new Specs.Command("put", "Publish a record",
                            "Write a registry entry; short values are audited automatically.",
                            "A cross-component cascade defined declaratively, with a per-message receipt.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("key", "Key", MAX_KEY, "e.g. supplier/acme"),
                                    Field.textarea("value", "Value", 1024, "Under 100 bytes → audited; 100+ → binding skipped")),
                            List.of("Put a value of 100+ characters: the binding's condition skips the audit",
                                    "Write the same key from another member: REJECTED KV_NOT_OWNER, nothing changes")),
                    new Specs.Command("delete", "Delete a record", "Remove an entry your member owns.",
                            "Deletes are not audited: the binding only listens to entry-put events.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("key", "Key", MAX_KEY, "The key to delete")),
                            List.of("Publish it again and compare the audit trail"))),
            List.of(new Specs.Lookup("trail", "Audit trail of a key", "How many audited writes a key has",
                    List.of(Field.text("key", "Key", MAX_KEY, "Registry key")))));

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
        String key = "supplier/" + context.sessionTag() + "-acme-" + context.sequence();
        return "delete".equals(commandId) ? Map.of("key", key) : Map.of("key", key, "value", "ISO-9001 certified");
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String key = in.text("key", "Key", MAX_KEY);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        List<Probe> probes = List.of(new Probe("trail", trailKey(keyBytes)));
        if ("delete".equals(commandId)) {
            return new Prepared(TOPIC, array(uint(1), bstr(keyBytes), bstr(new byte[0])), "delete " + key, probes,
                    (before, after, fin) -> BindingReceipts.outcome(fin, r -> r.accepted()
                            ? new BindingReceipts.Explanation("Deleted — no audit entry", "The binding only reacts to entry-put events.")
                            : new BindingReceipts.Explanation("Refused: " + r.code(), "Nothing changed; the receipt records why.")));
        }
        if (!"put".equals(commandId)) {
            throw new InvalidInput("unknown command " + commandId);
        }
        String value = in.text("value", "Value", 1024);
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        byte[] body = array(uint(0), bstr(keyBytes), bstr(value.getBytes(StandardCharsets.UTF_8)));
        return new Prepared(TOPIC, body, "put " + key + " (" + length + " bytes)", probes, (before, after, fin) ->
                BindingReceipts.outcome(fin, r -> {
                    long audits = count(after.get("trail"));
                    if (!r.accepted()) {
                        return new BindingReceipts.Explanation("Refused: " + r.code(), "The whole cascade was rolled back — neither the"
                                + " registry nor the audit trail changed. The refusal itself is recorded in a provable receipt.");
                    }
                    if (r.ran("audit")) {
                        return new BindingReceipts.Explanation("Written and audited in one step",
                                "The registry accepted the write and the binding appended its hash to the audit trail"
                                        + " (now " + audits + " entr" + (audits == 1 ? "y" : "ies") + " for this key).");
                    }
                    return new BindingReceipts.Explanation("Written — audit skipped by the binding condition",
                            "The value is " + length + " bytes; the binding only audits values under 100 bytes.");
                }));
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        String key = new Inputs(fields).text("key", "Key", MAX_KEY);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        StateReader.Entry trail = reader.latest(trailKey(keyBytes));
        StateReader.Entry record = reader.latest(WorkflowModule.compositeKey("records", keyBytes));
        List<Fact> facts = new ArrayList<>(List.of(Fact.of("Key", key), Fact.of("Audited writes", Long.toString(count(trail)))));
        if (record.present()) {
            List<?> v = (List<?>) Cbor.decode(record.value());
            facts.add(Fact.of("Current value", new String((byte[]) v.get(1), StandardCharsets.UTF_8)));
            facts.add(Fact.of("Owner", members.name(Hex.encode((byte[]) v.get(0)))));
        }
        return new LookupResult(key + " · " + count(trail) + " audited write(s)", facts, Hex.encode(trailKey(keyBytes)));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> items = (List<?>) Cbor.decode(body);
            String key = new String((byte[]) items.get(1), StandardCharsets.UTF_8);
            return (Long) items.get(0) == 0 ? "put " + key + " (" + ((byte[]) items.get(2)).length + " bytes)" : "delete " + key;
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    /** The binding uses entityId = hex(key) for the audit trail. */
    private static byte[] trailKey(byte[] keyBytes) {
        return WorkflowModule.compositeKey("audit", ("e/" + Hex.encode(keyBytes)).getBytes(StandardCharsets.UTF_8));
    }

    private static long count(StateReader.Entry entry) {
        return entry == null || !entry.present() ? 0 : (Long) ((List<?>) Cbor.decode(entry.value())).get(0);
    }
}
