package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.Inputs;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.Specs;
import com.bloxbean.yano.democlient.chain.Specs.Field;
import com.bloxbean.yano.democlient.chain.Specs.Option;
import com.bloxbean.yano.democlient.codec.Hex;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/** orders-chain (ordered-log): an append-only, totally ordered log; every member agrees on position and content. */
@ApplicationScoped
public class OrdersModule implements ChainModule {
    static final String TOPIC = "orders.command.v1";
    private static final byte[] FINALIZED_NS = "~yano/finalized-message/v1/".getBytes(StandardCharsets.US_ASCII);

    private static final Specs.Chain SPEC = new Specs.Chain(
            "orders-chain", "Order log", "An ordered event log that no single party can rewrite",
            "Every order event is appended to one shared log. All five members agree on exactly which events exist and"
                    + " in what order; each entry is provable against a state root that is anchored on Cardano.",
            "ordered-log", "Foundations", List.of("ordering", "audit trail", "proofs"), false, "cyan",
            List.of(new Specs.Command("append", "Append an order event",
                    "Record an event for an order. Any member may append.",
                    "Total ordering and finality: the event gets a block height and position that all members sign.",
                    Specs.PersonaKind.MEMBER, List.of(),
                    List.of(Field.text("order", "Order id", 64, "e.g. A-1042"),
                            new Field("event", "Event", "select", true, null, null, "What happened to the order",
                                    List.of(new Option("created", "Created"), new Option("paid", "Paid"),
                                            new Option("packed", "Packed"), new Option("shipped", "Shipped"),
                                            new Option("delivered", "Delivered"))),
                            Field.textarea("note", "Note", 280, "Free text (public, permanent)").optional()),
                    List.of("Append another event for the same order from a different member",
                            "Open Verify to see the finality certificate for this entry"))),
            List.of());

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        return Map.of("order", "A-" + context.sessionTag().toUpperCase() + "-" + (1000 + context.sequence()),
                "event", "created", "note", "Two pallets, express delivery");
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        if (!"append".equals(commandId)) {
            throw new InvalidInput("unknown command " + commandId);
        }
        Inputs in = new Inputs(fields);
        String order = in.text("order", "Order id", 64);
        String event = in.matching("event", "Event", "created|paid|packed|shipped|delivered", "is not a known event");
        String note = in.optionalText("note", "Note", 280);
        String json = "{\"event\":" + quote(event) + (note.isEmpty() ? "" : ",\"note\":" + quote(note))
                + ",\"order\":" + quote(order) + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new Prepared(TOPIC, body, "Order " + order + " → " + event, List.of(),
                (before, after, fin) -> new Outcome(Status.RECORDED,
                        "Recorded at height " + fin.height() + ", position " + fin.index(),
                        "The log now contains this event at a fixed position that every member signed.",
                        List.of(Fact.of("Order", order), Fact.of("Event", event), Fact.of("Height", Long.toString(fin.height())),
                                Fact.of("Position in block", Integer.toString(fin.index()))),
                        Hex.encode(finalizedMessageKey(fin.messageId()))));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 120 ? text.substring(0, 117) + "…" : text;
    }

    /** Physical state key of the finalized-message index entry (typed subject finalized-message-v1). */
    static byte[] finalizedMessageKey(String messageIdHex) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(FINALIZED_NS);
            sha.update(Hex.decode(messageIdHex));
            return sha.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
