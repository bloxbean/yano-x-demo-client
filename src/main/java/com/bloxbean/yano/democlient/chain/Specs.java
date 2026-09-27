package com.bloxbean.yano.democlient.chain;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Descriptors served to the UI; the UI renders catalog cards, forms and hints from these. */
public final class Specs {
    private Specs() {
    }

    public enum PersonaKind { MEMBER, ACTOR, NONE }

    @RegisterForReflection
    public record Option(String value, String label) {
    }

    /** type: text | textarea | number | select | member (a member picker; value = member key hex). */
    @RegisterForReflection
    public record Field(String name, String label, String type, boolean required, Integer maxLength,
                        String pattern, String help, List<Option> options) {
        public static Field text(String name, String label, int maxLength, String help) {
            return new Field(name, label, "text", true, maxLength, null, help, List.of());
        }

        public static Field textarea(String name, String label, int maxLength, String help) {
            return new Field(name, label, "textarea", true, maxLength, null, help, List.of());
        }

        public static Field number(String name, String label, String help) {
            return new Field(name, label, "number", true, null, "[0-9]{1,18}", help, List.of());
        }

        public static Field member(String name, String label, String help) {
            return new Field(name, label, "member", true, null, null, help, List.of());
        }

        public Field optional() {
            return new Field(name, label, type, false, maxLength, pattern, help, options);
        }

        public Field withPattern(String regex) {
            return new Field(name, label, type, required, maxLength, regex, help, options);
        }
    }

    @RegisterForReflection
    public record Command(String id, String title, String summary, String demonstrates, PersonaKind personaKind,
                          List<String> actors, List<Field> fields, List<String> tryNext) {
    }

    /** A read-only lookup (e.g. "approval item by id"). */
    @RegisterForReflection
    public record Lookup(String id, String title, String summary, List<Field> fields) {
    }

    @RegisterForReflection
    public record Chain(String id, String title, String tagline, String story, String machine, String category,
                        List<String> tags, boolean readOnly, String accent, List<Command> commands,
                        List<Lookup> lookups) {
    }
}
