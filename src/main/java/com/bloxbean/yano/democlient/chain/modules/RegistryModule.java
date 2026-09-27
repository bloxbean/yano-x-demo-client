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
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/** registry-chain (kv-registry): first writer owns a key; only the owner may change or delete it. */
@ApplicationScoped
public class RegistryModule implements ChainModule {
    static final String TOPIC = "kv.command.v1";
    private static final int MAX_KEY = 100;
    private static final int MAX_VALUE = 1024;

    private static final Specs.Chain SPEC = new Specs.Chain(
            "registry-chain", "Service registry", "A shared registry where every entry has an owner",
            "Members publish entries (services, products, endpoints). The first member to write a key owns it; the"
                    + " state machine silently refuses changes from anyone else. Try updating someone else's key.",
            "kv-registry", "Foundations", List.of("ownership", "key-value", "proofs"), false, "violet",
            List.of(new Specs.Command("put", "Publish an entry",
                    "Create a key, or update it if your member owns it.",
                    "Ownership enforced by consensus: a write from a non-owner finalizes but changes nothing.",
                    Specs.PersonaKind.MEMBER, List.of(),
                    List.of(Field.text("key", "Key", MAX_KEY, "e.g. service/payments-api"),
                            Field.textarea("value", "Value", MAX_VALUE, "UTF-8 text, e.g. an endpoint URL or status")),
                    List.of("Switch to another member and update the same key: it will be refused",
                            "Delete the key as its owner, then claim it from another member")),
                    new Specs.Command("delete", "Delete an entry", "Remove a key your member owns.",
                            "Only the owner can delete; afterwards anyone may claim the key again.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.text("key", "Key", MAX_KEY, "The key to delete")),
                            List.of("Publish the same key from a different member"))),
            List.of(new Specs.Lookup("entry", "Look up a key", "Current owner and value of a key",
                    List.of(Field.text("key", "Key", MAX_KEY, "Key to look up")))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String key = "service/" + context.sessionTag() + "-payments-api-" + context.sequence();
        return "delete".equals(commandId) ? Map.of("key", key)
                : Map.of("key", key, "value", "https://payments.example/v1 · status=active");
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String key = in.text("key", "Key", MAX_KEY);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        String me = context.actingMemberKey();
        MemberDirectory members = context.members();
        List<Probe> probes = List.of(new Probe("entry", keyBytes));
        return switch (commandId) {
            case "put" -> {
                String value = in.text("value", "Value", MAX_VALUE);
                byte[] body = array(uint(0), bstr(keyBytes), bstr(value.getBytes(StandardCharsets.UTF_8)));
                yield new Prepared(TOPIC, body, "put " + key, probes, (before, after, fin) -> {
                    Entry was = decode(before.get("entry"));
                    Entry now = decode(after.get("entry"));
                    List<Fact> facts = facts(key, now, members);
                    if (now != null && me.equals(now.owner) && value.equals(now.value)) {
                        return new Outcome(Status.APPLIED, was == null ? "Entry created — your member owns it"
                                : "Entry updated by its owner",
                                "The registry accepted the write because " + members.name(me) + " owns this key.",
                                facts, Hex.encode(keyBytes));
                    }
                    if (was != null && !me.equals(was.owner)) {
                        return new Outcome(Status.NO_EFFECT, "Refused: key is owned by another member",
                                "The message was finalized, but the state machine ignored it (KV_NOT_OWNER): the"
                                        + " key belongs to " + members.name(was.owner) + ".", facts, Hex.encode(keyBytes));
                    }
                    return new Outcome(Status.NO_EFFECT, "No change", "The state after this block does not show the"
                            + " new value (another write may have landed in the same block).", facts, Hex.encode(keyBytes));
                });
            }
            case "delete" -> {
                byte[] body = array(uint(1), bstr(keyBytes), bstr(new byte[0]));
                yield new Prepared(TOPIC, body, "delete " + key, probes, (before, after, fin) -> {
                    Entry was = decode(before.get("entry"));
                    Entry now = decode(after.get("entry"));
                    if (was == null) {
                        return new Outcome(Status.NO_EFFECT, "Nothing to delete", "The key did not exist.",
                                List.of(Fact.of("Key", key)), null);
                    }
                    if (now == null) {
                        return new Outcome(Status.APPLIED, "Entry deleted by its owner",
                                "Anyone may now claim this key again.", List.of(Fact.of("Key", key)), Hex.encode(keyBytes));
                    }
                    return new Outcome(Status.NO_EFFECT, "Refused: only the owner can delete",
                            "The key belongs to " + members.name(was.owner) + " (KV_NOT_OWNER).",
                            facts(key, now, members), Hex.encode(keyBytes));
                });
            }
            default -> throw new InvalidInput("unknown command " + commandId);
        };
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader,
                               MemberDirectory members) {
        String key = new Inputs(fields).text("key", "Key", MAX_KEY);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        Entry entry = decode(reader.latest(keyBytes));
        if (entry == null) {
            return new LookupResult("No entry for " + key, List.of(Fact.of("Key", key), Fact.of("Presence", "absent")),
                    Hex.encode(keyBytes));
        }
        return new LookupResult("Entry " + key, facts(key, entry, members), Hex.encode(keyBytes));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> items = (List<?>) Cbor.decode(body);
            String key = new String((byte[]) items.get(1), StandardCharsets.UTF_8);
            return ((Long) items.get(0)) == 0 ? "put " + key + " = " + new String((byte[]) items.get(2), StandardCharsets.UTF_8)
                    : "delete " + key;
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    private record Entry(String owner, String value) {
    }

    private static Entry decode(StateReader.Entry entry) {
        if (entry == null || !entry.present()) {
            return null;
        }
        List<?> items = (List<?>) Cbor.decode(entry.value());
        return new Entry(Hex.encode((byte[]) items.get(0)), new String((byte[]) items.get(1), StandardCharsets.UTF_8));
    }

    private static List<Fact> facts(String key, Entry entry, MemberDirectory members) {
        if (entry == null) {
            return List.of(Fact.of("Key", key), Fact.of("Presence", "absent"));
        }
        return List.of(Fact.of("Key", key), Fact.of("Value", entry.value), Fact.of("Owner", members.name(entry.owner)));
    }
}
