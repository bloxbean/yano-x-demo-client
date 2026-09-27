package com.bloxbean.yano.democlient.chain.modules;

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
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.bstr;
import static com.bloxbean.yano.democlient.codec.Cbor.text;

/**
 * documents-chain (doc-trail): a tamper-evident trail per entity. Only the document hash goes on chain; the head is
 * a hash chain head_n = blake2b256(head_{n-1} || entryHash || sender).
 */
@ApplicationScoped
public class DocumentsModule implements ChainModule {
    static final String TOPIC = "doc-trail.command.v1";
    private static final String ENTITY_RULE = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}";

    private static final Specs.Chain SPEC = new Specs.Chain(
            "documents-chain", "Document trail", "Tamper-evident history of document versions",
            "Only a fingerprint (BLAKE2b-256 hash) of each document goes on chain, never the document itself. Each"
                    + " entity keeps a running hash chain over its versions and their authors, so any rewrite of history"
                    + " is detectable.",
            "doc-trail", "Foundations", List.of("hash chain", "privacy", "proofs"), false, "sky",
            List.of(new Specs.Command("append", "Record a document version",
                    "Hash a document and append the fingerprint to an entity's trail.",
                    "Privacy-preserving evidence: the chain proves which version existed and who recorded it.",
                    Specs.PersonaKind.MEMBER, List.of(),
                    List.of(Field.text("entity", "Entity id", 100, "e.g. contract-2026-017").withPattern(ENTITY_RULE),
                            Field.textarea("content", "Document content", 8192,
                                    "Hashed on the server — only the hash is submitted"),
                            Field.text("reference", "Reference", 200, "Optional label, e.g. v2 signed PDF").optional()),
                    List.of("Record a second version from another member and watch the head change",
                            "Change one character and compare the hashes"))),
            List.of(new Specs.Lookup("trail", "Look up an entity", "Version count and current head of a trail",
                    List.of(Field.text("entity", "Entity id", 100, "Entity id").withPattern(ENTITY_RULE)))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        return Map.of("entity", context.id("contract"),
                "content", "SUPPLY AGREEMENT\nParties: Acme Components and Northwind Retail\nTerm: 12 months\nVersion: 1",
                "reference", "v1 draft");
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        if (!"append".equals(commandId)) {
            throw new InvalidInput("unknown command " + commandId);
        }
        Inputs in = new Inputs(fields);
        String entity = in.matching("entity", "Entity id", ENTITY_RULE, "must be 1–100 letters, digits or . _ : / -");
        String content = in.text("content", "Document content", 8192);
        String reference = in.optionalText("reference", "Reference", 200);
        byte[] hash = Blake2b.digest256(content.getBytes(StandardCharsets.UTF_8));
        byte[] body = array(text(entity), bstr(hash), text(reference));
        byte[] key = ("e/" + entity).getBytes(StandardCharsets.UTF_8);
        MemberDirectory members = context.members();
        return new Prepared(TOPIC, body, "append " + entity + " #" + Hex.encode(hash).substring(0, 8),
                List.of(new Probe("trail", key)), (before, after, fin) -> {
            Trail was = decode(before.get("trail"));
            Trail now = decode(after.get("trail"));
            byte[] expected = Blake2b.digest256(Cbor.concat(was == null ? new byte[32] : was.head,
                    hash, Hex.decode(fin.sender())));
            List<Fact> facts = List.of(Fact.of("Entity", entity),
                    new Fact("Document hash", Hex.encode(hash), "BLAKE2b-256 of the content; the content never left the server"),
                    Fact.of("Recorded by", members.name(fin.sender())),
                    Fact.of("Versions", now == null ? "0" : Long.toString(now.count)),
                    new Fact("Trail head", now == null ? "-" : Hex.encode(now.head),
                            "blake2b256(previous head ‖ document hash ‖ author)"));
            if (now != null && (was == null ? 0 : was.count) + 1 == now.count
                    && java.util.Arrays.equals(expected, now.head)) {
                return new Outcome(Status.APPLIED, "Version " + now.count + " recorded — head verified",
                        "The demo recomputed the new head from the previous head, the hash and the author and it"
                                + " matches the chain.", facts, Hex.encode(key));
            }
            return new Outcome(Status.APPLIED, "Recorded", "Another append may have landed in the same block, so the"
                    + " head could not be recomputed from this message alone.", facts, Hex.encode(key));
        });
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        String entity = new Inputs(fields).matching("entity", "Entity id", ENTITY_RULE, "is not a valid entity id");
        byte[] key = ("e/" + entity).getBytes(StandardCharsets.UTF_8);
        Trail trail = decode(reader.latest(key));
        return trail == null
                ? new LookupResult("No trail for " + entity, List.of(Fact.of("Entity", entity), Fact.of("Versions", "0")),
                Hex.encode(key))
                : new LookupResult(entity + " · " + trail.count + " version(s)", List.of(Fact.of("Entity", entity),
                Fact.of("Versions", Long.toString(trail.count)), Fact.of("Trail head", Hex.encode(trail.head))),
                Hex.encode(key));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> items = (List<?>) Cbor.decode(body);
            String reference = (String) items.get(2);
            return "append " + items.get(0) + " #" + Hex.encode((byte[]) items.get(1)).substring(0, 8)
                    + (reference.isEmpty() ? "" : " (" + reference + ")");
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    private record Trail(long count, byte[] head) {
    }

    private static Trail decode(StateReader.Entry entry) {
        if (entry == null || !entry.present()) {
            return null;
        }
        List<?> v = (List<?>) Cbor.decode(entry.value());
        return new Trail((Long) v.get(0), (byte[]) v.get(1));
    }
}
