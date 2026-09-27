package com.bloxbean.yano.democlient.chain.modules;

import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.ChainModule.Fact;
import com.bloxbean.yano.democlient.chain.ChainModule.Outcome;
import com.bloxbean.yano.democlient.chain.ChainModule.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Composite binding receipt v1: [version, sourceId, height, status, failedOrdinal, code, steps], each step
 * [ordinal, depth, viaBinding, component, ?, events, conditions[[binding, -1 match | skip index]], status, code].
 */
final class BindingReceipts {
    private BindingReceipts() {
    }

    record Step(long ordinal, long depth, String via, String component, List<String> events, List<String> skipped,
                String status, String code) {
    }

    record Receipt(String status, String code, List<Step> steps) {
        boolean accepted() {
            return "ACCEPTED".equals(status);
        }

        /** In an accepted receipt every listed step was applied (steps report PLANNED). */
        boolean ran(String component) {
            return accepted() && steps.stream().anyMatch(s -> s.component.equals(component));
        }

        boolean skipped(String binding) {
            return steps.stream().anyMatch(s -> s.skipped.contains(binding));
        }
    }

    static Receipt decode(List<?> raw) {
        if (raw == null || raw.size() < 7) {
            return null;
        }
        List<Step> steps = new ArrayList<>();
        for (Object item : (List<?>) raw.get(6)) {
            List<?> s = (List<?>) item;
            List<String> events = new ArrayList<>();
            if (s.get(5) instanceof List<?> list) {
                list.forEach(e -> events.add(String.valueOf(e)));
            }
            List<String> skipped = new ArrayList<>();
            if (s.size() > 6 && s.get(6) instanceof List<?> conditions) {
                for (Object c : conditions) {
                    List<?> condition = (List<?>) c;
                    if (!Long.valueOf(-1).equals(condition.get(1))) {
                        skipped.add(String.valueOf(condition.get(0)));
                    }
                }
            }
            steps.add(new Step((Long) s.get(0), (Long) s.get(1), s.get(2) == null ? "" : String.valueOf(s.get(2)),
                    String.valueOf(s.get(3)), events, skipped, s.size() > 7 ? String.valueOf(s.get(7)) : "",
                    s.size() > 8 && s.get(8) != null ? String.valueOf(s.get(8)) : ""));
        }
        return new Receipt(String.valueOf(raw.get(3)), raw.get(5) == null ? "" : String.valueOf(raw.get(5)), steps);
    }

    /** Steps as facts, e.g. "Step 1 · records" → "ACCEPTED (kv-registry.entry-put.v1)". */
    static List<Fact> stepFacts(Receipt receipt) {
        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.of("Receipt", receipt.status + (receipt.code.isEmpty() ? "" : " · " + receipt.code)));
        for (Step step : receipt.steps) {
            String via = step.via.isEmpty() ? "your message" : "binding “" + step.via + "”";
            String state = receipt.accepted() ? "applied" : step.code.isEmpty() ? "rolled back" : "refused " + step.code;
            facts.add(new Fact("Step " + (step.ordinal + 1) + " · " + step.component, state + " — triggered by " + via,
                    step.events.isEmpty() ? null : "events: " + String.join(", ", step.events)));
            step.skipped.forEach(binding -> facts.add(new Fact("Binding " + binding, "condition false — skipped",
                    "The binding's condition did not match this event, so nothing was derived")));
        }
        return facts;
    }

    /** Headline and detail for a receipt; status null = APPLIED when the receipt is accepted, else NO_EFFECT. */
    record Explanation(Status status, String headline, String detail) {
        Explanation(String headline, String detail) {
            this(null, headline, detail);
        }
    }

    static Outcome outcome(ChainModule.Finalized fin, Function<Receipt, Explanation> explain) {
        Receipt receipt = decode(fin.receipt());
        if (receipt == null) {
            return new Outcome(Status.RECORDED, "Finalized at height " + fin.height(),
                    "The binding receipt was not available yet; look the message up again in a moment.", List.of(), null);
        }
        Explanation text = explain.apply(receipt);
        Status status = text.status() != null ? text.status() : receipt.accepted() ? Status.APPLIED : Status.NO_EFFECT;
        return new Outcome(status, text.headline(), text.detail(), stepFacts(receipt), fin.receiptKeyHex());
    }
}
