package com.bloxbean.yano.democlient.chain;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** One app chain as the demo presents it: descriptors, payload builders, mock data and outcome evaluation. */
public interface ChainModule {

    Specs.Chain spec();

    default String chainId() {
        return spec().id();
    }

    /** Editable default values for a command; ids carry the visitor's session tag. */
    Map<String, String> mock(String commandId, MockContext context);

    /** Validates fields and builds the message. Throws {@link InvalidInput} with a user-facing message. */
    Prepared prepare(String commandId, Map<String, String> fields, CommandContext context);

    /** Read-only lookups (optional). */
    default LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader,
                                MemberDirectory members) {
        throw new InvalidInput("unknown lookup " + lookupId);
    }

    /** Short human summary of a finalized message body for activity feeds; null when not decodable. */
    default String describe(String topic, byte[] body, MemberDirectory members) {
        return null;
    }

    // ------------------------------------------------------------------ supporting types

    record MockContext(String sessionTag, long sequence, String actingMemberKey, MemberDirectory members) {
        public String id(String prefix) {
            return "demo-" + sessionTag + "-" + prefix + "-" + sequence;
        }
    }

    /** reader: committed state of this chain through the acting member's node (for commands that embed state). */
    record CommandContext(String actingMemberKey, String actorId, MemberDirectory members, StateReader reader) {
    }

    /** A state key read before and after the message, with a label for display. */
    record Probe(String label, byte[] key) {
    }

    /**
     * Where the message landed (GET /messages/{id} on the submitting node). For binding chains, receipt is the decoded
     * composite binding receipt and receiptKeyHex its physical state key; otherwise null.
     */
    record Finalized(String messageId, long height, int index, String sender, List<?> receipt, String receiptKeyHex) {
    }

    /** Declarative-binding chains: the service fetches the composite binding receipt of each message. */
    default boolean usesBindingReceipts() {
        return false;
    }

    @FunctionalInterface
    interface Judge {
        Outcome judge(Map<String, StateReader.Entry> before, Map<String, StateReader.Entry> after, Finalized finalized);
    }

    /**
     * @param followUp optional second judgement on the latest state after the first outcome (e.g. an effect that
     *                 confirms a few blocks later); returns null while not settled
     * @param exclusiveKey when set, only one submission with this key may be in flight in the whole app
     */
    record Prepared(String topic, byte[] body, String summary, List<Probe> probes, Judge judge, Judge followUp,
                    String exclusiveKey) {
        public Prepared(String topic, byte[] body, String summary, List<Probe> probes, Judge judge) {
            this(topic, body, summary, probes, judge, null, null);
        }
    }

    enum Status { APPLIED, NO_EFFECT, REJECTED, RECORDED }

    @RegisterForReflection
    record Fact(String label, String value, String hint) {
        public static Fact of(String label, String value) {
            return new Fact(label, value, null);
        }
    }

    @RegisterForReflection
    record Outcome(Status status, String headline, String detail, List<Fact> facts, String proofKeyHex) {
    }

    @RegisterForReflection
    record LookupResult(String headline, List<Fact> facts, String proofKeyHex) {
    }

    final class InvalidInput extends RuntimeException {
        public InvalidInput(String message) {
            super(message);
        }
    }
}
