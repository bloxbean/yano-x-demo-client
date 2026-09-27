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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.bloxbean.yano.democlient.codec.Cbor.array;
import static com.bloxbean.yano.democlient.codec.Cbor.text;
import static com.bloxbean.yano.democlient.codec.Cbor.uint;

/** balances-chain (balances): account balances; a member's own account is its member key (hex). */
@ApplicationScoped
public class BalancesModule implements ChainModule {
    static final String TOPIC = "balances.command.v1";
    private static final long MAX_AMOUNT = 1_000_000_000L;
    private static final String ACCOUNT_RULE = "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}";

    private static final Specs.Chain SPEC = new Specs.Chain(
            "balances-chain", "Token balances", "Mint and move a demo token between members",
            "Each member's account is its own member key. Mint demo tokens to an account, then transfer from the"
                    + " acting member's account. Overdrafts are refused by the state machine even though the message"
                    + " is finalized.",
            "balances", "Foundations", List.of("accounts", "transfers", "proofs"), false, "amber",
            List.of(new Specs.Command("mint", "Mint tokens", "Create demo tokens in an account.",
                            "Deterministic replicated balances: every member computes the same state root.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.member("account", "Account", "A member account (or type any account name)"),
                                    Field.number("amount", "Amount", "1 – 1,000,000,000")),
                            List.of("Transfer part of it to another member")),
                    new Specs.Command("transfer", "Transfer", "Send tokens from the acting member's own account.",
                            "Only the sender's own account is debited; overdrafts finalize but change nothing.",
                            Specs.PersonaKind.MEMBER, List.of(),
                            List.of(Field.member("to", "Recipient", "A member account (or any account name)"),
                                    Field.number("amount", "Amount", "Must not exceed your balance")),
                            List.of("Transfer more than you have to see the refusal",
                                    "Switch to the recipient member and send tokens onward"))),
            List.of(new Specs.Lookup("balance", "Look up a balance", "Balance of an account",
                    List.of(Field.member("account", "Account", "Member key or account name")))));

    @Override
    public Specs.Chain spec() {
        return SPEC;
    }

    @Override
    public Map<String, String> mock(String commandId, MockContext context) {
        String me = context.actingMemberKey() == null ? "" : context.actingMemberKey();
        if ("mint".equals(commandId)) {
            return Map.of("account", me, "amount", "1000");
        }
        String other = context.members().members().stream().map(MemberDirectory.Member::memberKey)
                .filter(k -> k != null && !k.equals(me)).findFirst().orElse("");
        return Map.of("to", other, "amount", "250");
    }

    @Override
    public Prepared prepare(String commandId, Map<String, String> fields, CommandContext context) {
        Inputs in = new Inputs(fields);
        String me = context.actingMemberKey();
        MemberDirectory members = context.members();
        return switch (commandId) {
            case "mint" -> {
                String account = account(fields.get("account"));
                long amount = in.number("amount", "Amount", 1, MAX_AMOUNT);
                byte[] body = array(uint(0), text(account), uint(amount));
                yield new Prepared(TOPIC, body, "mint " + amount + " → " + label(account, members),
                        List.of(new Probe("account", key(account))), (before, after, fin) -> {
                    BigInteger was = balance(before.get("account"));
                    BigInteger now = balance(after.get("account"));
                    List<Fact> facts = List.of(Fact.of("Account", label(account, members)),
                            Fact.of("Before", was.toString()), Fact.of("After", now.toString()));
                    return now.subtract(was).equals(BigInteger.valueOf(amount))
                            ? new Outcome(Status.APPLIED, "Minted " + amount, "The account balance increased.", facts,
                            Hex.encode(key(account)))
                            : new Outcome(Status.NO_EFFECT, "Balance did not change by the minted amount",
                            "Another transfer may have landed in the same block.", facts, Hex.encode(key(account)));
                });
            }
            case "transfer" -> {
                String to = account(fields.get("to"));
                long amount = in.number("amount", "Amount", 1, MAX_AMOUNT);
                if (to.equals(me)) {
                    throw new InvalidInput("Pick a recipient other than the acting member");
                }
                byte[] body = array(uint(1), text(to), uint(amount));
                yield new Prepared(TOPIC, body, "transfer " + amount + " → " + label(to, members),
                        List.of(new Probe("from", key(me)), new Probe("to", key(to))), (before, after, fin) -> {
                    BigInteger fromWas = balance(before.get("from"));
                    BigInteger fromNow = balance(after.get("from"));
                    BigInteger toNow = balance(after.get("to"));
                    List<Fact> facts = List.of(Fact.of("From", members.name(me)),
                            Fact.of("From balance", fromWas + " → " + fromNow),
                            Fact.of("To", label(to, members)), Fact.of("To balance", balance(before.get("to")) + " → " + toNow));
                    if (fromWas.compareTo(BigInteger.valueOf(amount)) < 0) {
                        return new Outcome(Status.NO_EFFECT, "Refused: insufficient balance",
                                "The message was finalized but the state machine ignored it (BALANCE_INSUFFICIENT):"
                                        + " the sender had " + fromWas + ".", facts, Hex.encode(key(me)));
                    }
                    return fromWas.subtract(fromNow).equals(BigInteger.valueOf(amount))
                            ? new Outcome(Status.APPLIED, "Transferred " + amount, "Debited from the acting member's own"
                            + " account and credited to the recipient.", facts, Hex.encode(key(to)))
                            : new Outcome(Status.NO_EFFECT, "No change", "The transfer is not visible after this block.",
                            facts, Hex.encode(key(me)));
                });
            }
            default -> throw new InvalidInput("unknown command " + commandId);
        };
    }

    @Override
    public LookupResult lookup(String lookupId, Map<String, String> fields, StateReader reader, MemberDirectory members) {
        String account = account(fields.get("account"));
        BigInteger balance = balance(reader.latest(key(account)));
        return new LookupResult(label(account, members) + ": " + balance,
                List.of(Fact.of("Account", label(account, members)), Fact.of("Balance", balance.toString())),
                Hex.encode(key(account)));
    }

    @Override
    public String describe(String topic, byte[] body, MemberDirectory members) {
        try {
            List<?> items = (List<?>) Cbor.decode(body);
            return ((Long) items.get(0) == 0 ? "mint " : "transfer ") + items.get(2) + " → "
                    + label((String) items.get(1), members);
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    private static String account(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.matches("[0-9A-Fa-f]{64}")) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        if (!value.matches(ACCOUNT_RULE)) {
            throw new InvalidInput("Account must be a member key or 1–100 letters, digits or . _ : -");
        }
        return value;
    }

    private static byte[] key(String account) {
        return ("b/" + account).getBytes(StandardCharsets.UTF_8);
    }

    private static BigInteger balance(StateReader.Entry entry) {
        return entry == null || !entry.present() ? BigInteger.ZERO : new BigInteger(1, entry.value());
    }

    private static String label(String account, MemberDirectory members) {
        return account.matches("[0-9a-f]{64}") ? members.name(account) : account;
    }
}
