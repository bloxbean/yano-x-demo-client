package com.bloxbean.yano.democlient.cardano;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.AbstractTx;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.yano.democlient.actor.ActorDirectory;
import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.StateReader;
import com.bloxbean.yano.democlient.config.DemoConfig;
import com.bloxbean.yano.democlient.node.NodeGateway;
import com.bloxbean.yano.democlient.service.SubmissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/**
 * "On Cardano": three validators that act on facts proven against a chain's script-anchored state root
 * (onchain/validators). The server holds a testnet wallet that pays the fees; validators are parameterised at
 * runtime from the checked-in blueprint with julc ({@link ScriptParams}).
 */
@ApplicationScoped
@RegisterForReflection(targets = {com.bloxbean.cardano.client.api.model.ProtocolParams.class})
public class CardanoService {
    private static final Logger LOG = Logger.getLogger(CardanoService.class);
    private static final HexFormat HEX = HexFormat.of();
    static final String REGISTRY = "registry";
    static final String POINTS = "POINTS";

    private final DemoConfig.Cardano config;
    private final NodeGateway gateway;
    private final MemberDirectory members;
    private final SubmissionService submissions;
    private final ActorDirectory actors;
    private final ObjectMapper json;
    private final Map<String, ChainModule> modules = new LinkedHashMap<>();
    private final Jobs jobs = new Jobs();
    private final Deque<Long> txTimes = new ArrayDeque<>();
    private final String disabledReason;
    private final Account wallet;
    private final Account supplier;
    private final L1 l1;
    private final Store store;
    private final JsonNode blueprint;
    private final Optional<String> operatorToken;
    private final Optional<String> adminKey;

    public CardanoService(DemoConfig config, NodeGateway gateway, MemberDirectory members, SubmissionService submissions,
                          ActorDirectory actors, ObjectMapper json, Instance<ChainModule> all) {
        this.config = config.cardano();
        this.gateway = gateway;
        this.members = members;
        this.submissions = submissions;
        this.actors = actors;
        this.json = json;
        all.forEach(m -> modules.put(m.chainId(), m));
        this.operatorToken = this.config.operatorTokenFile().map(CardanoService::secret);
        this.adminKey = this.config.adminApiKeyFile().map(CardanoService::secret);
        String reason = null;
        Account w = null;
        Account s = null;
        L1 chain = null;
        Store st = null;
        JsonNode bp = null;
        if (!this.config.enabled()) {
            reason = "On Cardano is not enabled (demo.cardano.enabled)";
        } else if (this.config.walletMnemonicFile().isEmpty()) {
            reason = "demo.cardano.wallet-mnemonic-file is not set";
        } else {
            try {
                String phrase = secret(this.config.walletMnemonicFile().get());
                w = Account.createFromMnemonic(Networks.preprod(), phrase);
                s = new Account(Networks.preprod(), phrase, 1);
                chain = new L1(gateway.defaultTarget().url(), config.http().apiKeyFile().map(CardanoService::secret),
                        config.http().userAgent());
                st = new Store(Path.of(this.config.stateFile()));
                try (InputStream in = CardanoService.class.getResourceAsStream("/cardano/plutus.json")) {
                    bp = Json.MAPPER.readTree(in);
                }
            } catch (Exception e) {
                reason = "On Cardano could not start: " + e.getMessage();
                LOG.warn(reason);
            }
        }
        this.disabledReason = reason;
        this.wallet = reason == null ? w : null;
        this.supplier = reason == null ? s : null;
        this.l1 = chain;
        this.store = st;
        this.blueprint = bp;
    }

    private static String secret(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file);
        }
    }

    // ------------------------------------------------------------------ status

    public boolean enabled() {
        return disabledReason == null;
    }

    public void requireEnabled() {
        if (!enabled()) {
            throw new IllegalStateException(disabledReason);
        }
    }

    /** Operator actions need the configured token (when one is configured). */
    public void requireOperator(String token) {
        requireEnabled();
        if (operatorToken.isPresent() && !operatorToken.get().equals(token)) {
            throw new SecurityException("operator token required");
        }
    }

    public Jobs jobs() {
        return jobs;
    }

    public ObjectNode overview() {
        ObjectNode o = Json.object();
        o.put("enabled", enabled());
        if (!enabled()) {
            o.put("reason", disabledReason);
            return o;
        }
        o.put("wallet", wallet.baseAddress());
        o.put("walletLovelace", lovelace(wallet.baseAddress()));
        o.put("operatorTokenRequired", operatorToken.isPresent());
        o.put("anchorNowAvailable", adminKey.isPresent());
        o.put("anchorEveryBlocks", config.anchorEveryBlocks());
        o.put("transactionsPerHour", config.transactionsPerHour());
        ArrayNode chains = o.putArray("chains");
        for (String[] c : List.of(new String[] {config.certificatesChain(), "certificates"},
                new String[] {config.escrowChain(), "escrow"}, new String[] {config.rewardsChain(), "rewards"})) {
            ObjectNode p = progress(c[0]);
            p.put("useCase", c[1]);
            try {
                p.set("anchor", Anchor.load(l1, c[0]).json());
            } catch (RuntimeException e) {
                p.put("anchorError", e.getMessage());
            }
            chains.add(p);
        }
        o.set("certificates", deployment("certificates").deepCopy().without(List.of("compiledCode", "claims")));
        o.set("rewards", deployment("rewards").deepCopy().without(List.of("compiledCode", "claims")));
        return o;
    }

    ObjectNode progress(String chainId) {
        JsonNode status = l1.status(chainId);
        long tip = status.path("tipHeight").asLong();
        long anchored = status.path("anchor").path("lastAnchoredHeight").asLong();
        ObjectNode o = Json.object();
        o.put("chainId", chainId);
        o.put("tip", tip);
        o.put("lastAnchored", anchored);
        o.put("blocksToAnchor", Math.max(0, config.anchorEveryBlocks() - (tip - anchored)));
        return o;
    }

    private long lovelace(String address) {
        return l1.utxos(address).stream().flatMap(u -> u.getAmount().stream())
                .filter(a -> a.getUnit().equals("lovelace")).mapToLong(a -> a.getQuantity().longValue()).sum();
    }

    // ------------------------------------------------------------------ scripts and transactions

    private record Script(String code, String hash, PlutusScript plutus) {
        String address() {
            return AddressProvider.getEntAddress(plutus, Networks.preprod()).toBech32();
        }
    }

    private Script script(String module, List<PlutusData> params) {
        for (JsonNode v : blueprint.path("validators")) {
            if (v.path("title").asText().startsWith(module + "." + module + ".")) {
                return script(ScriptParams.apply(v.path("compiledCode").asText(), params));
            }
        }
        throw new IllegalStateException("validator " + module + " missing from the blueprint");
    }

    private static Script script(String compiledCode) {
        PlutusScript plutus = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(compiledCode, PlutusVersion.v3);
        try {
            return new Script(compiledCode, HEX.formatHex(plutus.getScriptHash()), plutus);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds, evaluates, signs and submits; then waits until the member's chain index knows the transaction. */
    private String submit(String label, UnaryOperator<QuickTxBuilder.TxContext> options, AbstractTx<?>... txs) {
        synchronized (txTimes) {
            long now = System.currentTimeMillis();
            while (!txTimes.isEmpty() && now - txTimes.peekFirst() > 3_600_000) {
                txTimes.pollFirst();
            }
            if (txTimes.size() >= config.transactionsPerHour()) {
                throw new IllegalStateException("the demo's Cardano transaction budget for this hour is used up");
            }
            txTimes.addLast(now);
        }
        QuickTxBuilder.TxContext context = new QuickTxBuilder(l1, l1, l1, l1).compose(txs)
                .feePayer(wallet.baseAddress())
                .collateralPayer(wallet.baseAddress())
                .withSigner(SignerProviders.signerFrom(wallet));
        TxResult result;
        try {
            result = options.apply(context).complete();
        } catch (RuntimeException failure) {
            throw refusedOr(label, String.valueOf(failure.getMessage()));
        }
        if (!result.isSuccessful()) {
            throw refusedOr(label, String.valueOf(result.getResponse()));
        }
        String hash = result.getValue();
        for (int i = 0; i < 90; i++) {
            if (l1.txKnown(hash)) {
                return hash;
            }
            sleep(2_000);
        }
        throw new IllegalStateException(label + " submitted as " + hash + " but not seen on chain within 3 minutes");
    }

    /** A script evaluation failure is the validator saying no; anything else is a plain failure. */
    private static IllegalStateException refusedOr(String label, String response) {
        if (response.contains("valuation failed") || response.contains("script cost") || response.contains("EvaluationFailure")) {
            return new IllegalStateException("Refused by the validator (" + label + "): the script failed, nothing was submitted");
        }
        return new IllegalStateException(label + " failed: " + shorten(response));
    }

    private static String shorten(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    private static PlutusData outRef(Utxo utxo) {
        return Pd.constr(0, Pd.hex(utxo.getTxHash()), Pd.integer(utxo.getOutputIndex()));
    }

    private static String unit(String policy, String assetName) {
        return policy + HEX.formatHex(assetName.getBytes(StandardCharsets.UTF_8));
    }

    private Utxo registry(Script script) {
        String unit = unit(script.hash(), REGISTRY);
        List<Utxo> found = l1.utxos(script.address()).stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> a.getUnit().equals(unit))).toList();
        if (found.size() != 1) {
            throw new IllegalStateException("registry UTxO not found");
        }
        return found.getFirst();
    }

    private static List<Amount> keep(Utxo utxo) {
        return utxo.getAmount().stream().map(a -> a.getUnit().equals("lovelace") ? Amount.lovelace(a.getQuantity())
                : Amount.asset(a.getUnit(), a.getQuantity())).toList();
    }

    private ObjectNode deployment(String name) {
        return store.section(name);
    }

    private Script deployed(String name) {
        ObjectNode d = deployment(name);
        if (!d.has("compiledCode")) {
            throw new IllegalStateException(name.equals("rewards") ? "no rewards season is open yet"
                    : "the " + name + " registry is not open yet");
        }
        return script(d.get("compiledCode").asText());
    }

    private static Map<String, String> claims(ObjectNode deployment) {
        Map<String, String> claimed = new LinkedHashMap<>();
        deployment.path("claims").fields().forEachRemaining(e -> claimed.put(e.getKey(), e.getValue().asText()));
        return claimed;
    }

    private static void recordClaim(ObjectNode deployment, byte[] key, byte[] value) {
        if (!deployment.has("claims")) {
            deployment.putObject("claims");
        }
        ((ObjectNode) deployment.get("claims")).put(HEX.formatHex(key), HEX.formatHex(value));
    }

    /** Opens a one-shot registry: mints its token on a wallet UTxO and locks it with the initial datum. */
    private String openRegistry(Script script, Utxo seed, PlutusData datum, Utxo anchorRef, String label) {
        ScriptTx tx = new ScriptTx().collectFrom(seed);
        if (anchorRef != null) {
            tx.readFrom(anchorRef);
        }
        tx.mintAsset(script.plutus(), new Asset(REGISTRY, BigInteger.ONE), Pd.constr(0))
                .payToContract(script.address(), List.of(Amount.ada(3), Amount.asset(unit(script.hash(), REGISTRY),
                        BigInteger.ONE)), datum)
                .withChangeAddress(wallet.baseAddress());
        return submit(label, c -> c, tx);
    }

    // ------------------------------------------------------------------ demo writes (app ledger)

    /** Submits a demo command server-side, like a visitor would, and waits for its evaluated outcome. */
    JsonNode write(String chainId, String command, String node, String actor, Map<String, String> fields) {
        ChainModule module = modules.get(chainId);
        if (module == null) {
            throw new IllegalStateException(chainId + " is not in the demo");
        }
        NodeGateway.Target target = gateway.resolve(node, null);
        MemberDirectory.Member acting = members.byNode(target.id()).filter(m -> m.memberKey() != null)
                .orElseThrow(() -> new IllegalStateException(target.label() + " is not reachable"));
        // The acting node may be a block behind the one that finalized the previous step: retry briefly.
        ChainModule.Prepared prepared = null;
        for (int attempt = 0; prepared == null; attempt++) {
            try {
                prepared = module.prepare(command, fields, new ChainModule.CommandContext(
                        acting.memberKey(), actor, members, new StateReader(gateway, json, target, chainId)));
            } catch (ChainModule.InvalidInput notYet) {
                if (attempt >= 6) {
                    throw new IllegalStateException(notYet.getMessage());
                }
                sleep(2_000);
            }
        }
        String actorLabel = actor == null ? null : actors.find(actor)
                .map(a -> a.label() + " (" + a.role() + ", " + a.organization() + ")").orElse(null);
        SubmissionService.View view = submissions.start(module, command, prepared, target, acting.label(), actorLabel);
        for (int i = 0; i < 90; i++) {
            view = submissions.find(view.id());
            switch (view.stage()) {
                case EVALUATED -> {
                    return json.valueToTree(view);
                }
                case FAILED, REFUSED -> throw new IllegalStateException(command + " " + view.stage() + ": " + view.error());
                default -> sleep(2_000);
            }
        }
        throw new IllegalStateException(command + " did not finalize in 3 minutes");
    }

    // ------------------------------------------------------------------ 1 · certified release NFT

    public JsonNode openCertificates(Jobs.Log log) {
        if (deployment("certificates").has("policy")) {
            throw new IllegalStateException("the certificate registry is already open");
        }
        Anchor anchor = Anchor.load(l1, config.certificatesChain());
        Utxo seed = l1.utxos(wallet.baseAddress()).getFirst();
        Script script = script("certificates", List.of(anchor.ref(), Pd.text(config.releasePolicy()), outRef(seed)));
        log.step("Validator parameterised with " + anchor.chainId() + "'s anchor (thread token "
                + anchor.threadPolicy().substring(0, 12) + "…): policy " + script.hash());
        String tx = openRegistry(script, seed, Pd.constr(0, Pd.bytes(new byte[32])), null, "open certificate registry");
        ObjectNode d = deployment("certificates");
        d.put("policy", script.hash());
        d.put("address", script.address());
        d.put("compiledCode", script.code());
        d.put("openTx", tx);
        d.putObject("claims");
        store.save();
        log.step("Registry open in " + tx);
        return d.deepCopy().without(List.of("compiledCode"));
    }

    /** Released on the app ledger? covered by the anchor on Cardano? already minted? */
    public ObjectNode lookupRelease(String proposal) {
        String chainId = config.certificatesChain();
        ObjectNode o = Json.object();
        o.put("proposal", proposal);
        JsonNode tip = l1.stateEntry(chainId, Facts.receiptKey(proposal));
        boolean released = "PRESENT".equals(tip.path("presence").asText());
        o.put("released", released);
        o.put("appTip", tip.path("committedHeight").asLong());
        Anchor anchor = Anchor.load(l1, chainId);
        o.put("anchoredHeight", anchor.height());
        if (released) {
            List<PlutusData> rf = Pd.fields(Facts.receipt(HEX.parseHex(tip.path("valueHex").asText())));
            long at = Pd.asInt(rf.get(5)).longValue();
            o.put("entity", new String(Pd.asBytes(rf.get(1)), StandardCharsets.UTF_8));
            o.put("policy", new String(Pd.asBytes(rf.get(3)), StandardCharsets.UTF_8));
            o.put("releasedHeight", at);
            o.put("anchored", at <= anchor.height());
        }
        ObjectNode d = deployment("certificates");
        o.put("registryOpen", d.has("policy"));
        o.put("minted", claims(d).containsKey(HEX.formatHex(proposal.getBytes(StandardCharsets.UTF_8))));
        for (JsonNode m : store.list("certificateMints")) {
            if (m.path("proposal").asText().equals(proposal)) {
                o.put("mintTx", m.path("tx").asText());
                o.put("policyId", d.path("policy").asText());
            }
        }
        return o;
    }

    public JsonNode mintCertificate(Jobs.Log log, String proposal) {
        if (proposal.getBytes(StandardCharsets.UTF_8).length > 32 || proposal.equals(REGISTRY)) {
            throw new IllegalStateException("a certificate is named after its proposal id (at most 32 bytes)");
        }
        String chainId = config.certificatesChain();
        Script script = deployed("certificates");
        Anchor anchor = Anchor.load(l1, chainId);
        log.step("Anchor on Cardano: height " + anchor.height() + ", root " + anchor.stateRoot().substring(0, 16)
                + "…, co-signed by " + anchor.threshold() + " of " + anchor.memberCount() + " members");
        JsonNode proof = l1.stateProof(chainId, Facts.receiptKey(proposal), anchor.height());
        if (!"PRESENT".equals(proof.path("presence").asText())) {
            throw new IllegalStateException("no release receipt for " + proposal + " at the anchored height "
                    + anchor.height() + ": not released yet, or released after the last anchor");
        }
        if (!anchor.stateRoot().equals(proof.path("stateRoot").asText())) {
            throw new IllegalStateException("proof root differs from the anchored root");
        }
        PlutusData receipt = Facts.receipt(HEX.parseHex(proof.path("valueHex").asText()));
        List<PlutusData> rf = Pd.fields(receipt);
        log.step("Release receipt proven at height " + anchor.height() + " (" + proof.path("proofWireHex").asText().length() / 2
                + "-byte MPF proof); approval consumed at height " + Pd.asInt(rf.get(5)));
        Utxo registry = registry(script);
        String onChain = HEX.formatHex(Pd.asBytes(Pd.fields(Pd.decode(registry.getInlineDatum())).getFirst()));
        ObjectNode d = deployment("certificates");
        NullifierTrie trie = new NullifierTrie(claims(d));
        if (!trie.root().equals(onChain)) {
            throw new IllegalStateException("the local claimed set differs from the registry on chain");
        }
        byte[] nullifier = proposal.getBytes(StandardCharsets.UTF_8);
        byte[] commitment = Pd.asBytes(rf.get(2));
        boolean already = trie.contains(nullifier);
        log.step(already ? "'" + proposal + "' is already in the claimed set: the validator must refuse this"
                : "'" + proposal + "' is absent from the claimed set (exclusion proof for mpf.insert)");
        PlutusData nullProof = already ? Pd.list(List.of()) : trie.exclusionProof(nullifier);
        String next = already ? onChain : trie.rootAfter(nullifier, commitment);
        CBORMetadataMap asset = new CBORMetadataMap()
                .put("name", "Certified release " + proposal)
                .put("document", new String(Pd.asBytes(rf.get(1)), StandardCharsets.UTF_8))
                .put("approval_policy", config.releasePolicy())
                .put("app_ledger", chainId)
                .put("anchored_height", BigInteger.valueOf(anchor.height()))
                .put("anchor_utxo", anchor.utxo().getTxHash());
        CBORMetadata metadata = new CBORMetadata();
        metadata.put(BigInteger.valueOf(721), new CBORMetadataMap().put(script.hash(), new CBORMetadataMap().put(proposal, asset)));
        ScriptTx tx = new ScriptTx()
                .collectFrom(registry, Pd.constr(0, receipt, Pd.decode(proof.path("proofWireHex").asText()), nullProof))
                .readFrom(anchor.utxo())
                .mintAsset(script.plutus(), new Asset(proposal, BigInteger.ONE), Pd.constr(1), wallet.baseAddress())
                .payToContract(script.address(), keep(registry), Pd.constr(0, Pd.hex(next)))
                .attachMetadata(metadata)
                .withChangeAddress(wallet.baseAddress());
        log.step("Submitting: spend the registry, read the anchor UTxO as a reference input, mint 1 × " + proposal);
        String hash = submit("mint certificate " + proposal, c -> c, tx);
        recordClaim(d, nullifier, commitment);
        ObjectNode m = store.list("certificateMints").addObject();
        m.put("proposal", proposal);
        m.put("tx", hash);
        m.put("anchoredHeight", anchor.height());
        store.save();
        log.step("Minted in " + hash);
        return m;
    }

    /** Runs the full release flow server-side (operator shortcut; visitors use the chain's own commands). */
    public JsonNode releaseDocument(Jobs.Log log, String proposal, String entity, String content) {
        String chainId = config.certificatesChain();
        Map<String, String> fields = Map.of("proposal", proposal, "entity", entity, "content", content,
                "reference", "dms://certificates/" + entity);
        log.step("Propose as issuer-a");
        write(chainId, "propose", "node-0", "issuer-a", fields);
        log.step("Approve as auditor-a");
        write(chainId, "approve", "node-1", "auditor-a", Map.of("proposal", proposal));
        log.step("Approve as auditor-b");
        write(chainId, "approve", "node-2", "auditor-b", Map.of("proposal", proposal));
        log.step("Release through Member 3");
        JsonNode released = write(chainId, "release", "node-3", null, fields);
        log.step("Released at height " + released.path("height").asLong());
        return released;
    }

    public ArrayNode certificateMints() {
        return store.list("certificateMints").deepCopy();
    }

    // ------------------------------------------------------------------ 2 · delivery escrow

    private Script escrowScript() {
        ObjectNode d = deployment("escrow");
        if (!d.has("compiledCode")) {
            Anchor anchor = Anchor.load(l1, config.escrowChain());
            Script s = script("escrow", List.of(anchor.ref()));
            d.put("scriptHash", s.hash());
            d.put("address", s.address());
            d.put("compiledCode", s.code());
            store.save();
        }
        return script(d.get("compiledCode").asText());
    }

    public JsonNode createEscrow(Jobs.Log log, String order, String carrierKey) {
        Script script = escrowScript();
        long now = l1.latestBlock().path("time").asLong() * 1000;
        long refundAfter = now + 2 * 3_600_000L;
        long ada = config.escrowAda();
        PlutusData datum = Pd.constr(0, Pd.text(order),
                Pd.bytes(new Address(supplier.baseAddress()).getPaymentCredentialHash().orElseThrow()),
                Pd.bytes(new Address(wallet.baseAddress()).getPaymentCredentialHash().orElseThrow()),
                carrierKey == null || carrierKey.isBlank() ? Pd.none() : Pd.some(Pd.hex(carrierKey)),
                Pd.integer(refundAfter));
        log.step("Lock " + ada + " ADA for order " + order + (carrierKey == null || carrierKey.isBlank() ? ""
                : "; only member " + carrierKey.substring(0, 12) + "… may confirm the delivery"));
        String hash = submit("create escrow", c -> c,
                new Tx().payToContract(script.address(), Amount.ada(ada), datum).from(wallet.baseAddress()));
        ObjectNode e = store.list("escrows").addObject();
        e.put("order", order);
        e.put("fromHeight", progress(config.escrowChain()).path("tip").asLong());
        e.put("ada", ada);
        e.put("carrier", carrierKey == null ? "" : carrierKey);
        e.put("createTx", hash);
        e.put("refundAfter", refundAfter);
        store.save();
        log.step("Escrow created in " + hash);
        return e;
    }

    /** Find the newest finalized "delivered" event for an order (from any visitor or member). */
    public JsonNode recordDelivery(Jobs.Log log, String order, String node) {
        log.step("Append {event: delivered, order: " + order + "} through " + node);
        JsonNode done = write(config.escrowChain(), "append", node, null, Map.of("order", order, "event", "delivered"));
        log.step("Finalized at height " + done.path("height").asLong() + " (message "
                + done.path("messageId").asText().substring(0, 12) + "…)");
        for (JsonNode e : store.list("escrows")) {
            if (e.path("order").asText().equals(order) && !e.has("releaseTx")) {
                ((ObjectNode) e).put("deliveryMessage", done.path("messageId").asText());
                ((ObjectNode) e).put("deliveryHeight", done.path("height").asLong());
            }
        }
        store.save();
        return done;
    }

    public JsonNode releaseEscrow(Jobs.Log log, String createTx) {
        Script script = escrowScript();
        ObjectNode e = escrow(createTx);
        String chainId = config.escrowChain();
        String messageId = e.path("deliveryMessage").asText("");
        if (messageId.isEmpty()) {
            messageId = findDelivery(e).orElseThrow(() -> new IllegalStateException("no finalized \"delivered\" event for "
                    + e.path("order").asText() + (e.path("carrier").asText().isEmpty() ? "" : " from the carrier's member")
                    + " yet: record it on the order log first"));
            e.put("deliveryMessage", messageId);
            e.put("deliveryHeight", l1.message(chainId, messageId).path("height").asLong());
        }
        Anchor anchor = Anchor.load(l1, chainId);
        long height = e.path("deliveryHeight").asLong();
        log.step("Anchor on Cardano: height " + anchor.height() + ", root " + anchor.stateRoot().substring(0, 16) + "…");
        if (anchor.height() < height) {
            throw new IllegalStateException("the delivery is at height " + height + " but Cardano holds the anchor for "
                    + anchor.height() + ": wait for (or trigger) the next anchor");
        }
        JsonNode m = l1.suppliedMessage(chainId, messageId);
        JsonNode at = l1.message(chainId, messageId);
        byte[] sender = Base64.getDecoder().decode(m.path("sender").asText());
        byte[] body = Base64.getDecoder().decode(m.path("body").asText());
        log.step("Delivered event: " + new String(body, StandardCharsets.UTF_8));
        JsonNode proof = l1.stateProof(chainId, Facts.finalizedMessageKey(HEX.parseHex(messageId)), anchor.height());
        if (!"PRESENT".equals(proof.path("presence").asText()) || !anchor.stateRoot().equals(proof.path("stateRoot").asText())) {
            throw new IllegalStateException("the finalized-message record is not provable at the anchored height");
        }
        log.step("Finalized-message record proven at height " + anchor.height() + " ("
                + proof.path("proofWireHex").asText().length() / 2 + "-byte proof)");
        Utxo locked = l1.utxos(script.address()).stream().filter(u -> u.getTxHash().equals(createTx)).findFirst()
                .orElseThrow(() -> new IllegalStateException("the escrow UTxO is gone: already released or refunded"));
        long lovelace = locked.getAmount().stream().filter(a -> a.getUnit().equals("lovelace")).findFirst()
                .orElseThrow().getQuantity().longValue();
        PlutusData redeemer = Pd.constr(0, Pd.bytes(sender), Pd.integer(m.path("senderSeq").asLong()),
                Pd.integer(m.path("expiresAt").asLong()), Pd.bytes(body), Pd.integer(at.path("height").asLong()),
                Pd.integer(at.path("index").asLong()), Pd.decode(proof.path("proofWireHex").asText()));
        ScriptTx tx = new ScriptTx()
                .collectFrom(locked, redeemer)
                .readFrom(anchor.utxo())
                .payToAddress(supplier.baseAddress(), Amount.lovelace(BigInteger.valueOf(lovelace)))
                .attachSpendingValidator(script.plutus())
                .withChangeAddress(wallet.baseAddress());
        log.step("Submitting: spend the escrow, read the anchor UTxO, pay the supplier " + lovelace / 1_000_000.0 + " ADA");
        String hash = submit("release escrow", c -> c, tx);
        e.put("releaseTx", hash);
        store.save();
        log.step("Released in " + hash);
        return e;
    }

    /**
     * The first finalized {"event":"delivered",…,"order":"<id>"} on the order log since the escrow was created,
     * from the carrier's member when the escrow names one: whoever recorded it (a visitor in Try it, or us).
     */
    private Optional<String> findDelivery(ObjectNode e) {
        String chainId = config.escrowChain();
        String prefix = "{\"event\":\"delivered\"";
        String suffix = ",\"order\":\"" + e.path("order").asText() + "\"}";
        String carrier = e.path("carrier").asText("");
        long from = e.path("fromHeight").asLong();
        for (int page = 0; page < 5; page++) {
            JsonNode list = l1.get("/app-chain/chains/" + chainId + "/messages/by-topic/orders.command.v1?fromHeight="
                    + from + "&limit=100").require("order log").path("messages");
            if (!list.isArray() || list.isEmpty()) {
                return Optional.empty();
            }
            for (JsonNode item : list) {
                JsonNode m = l1.message(chainId, item.path("messageIdHex").asText());
                String body = new String(HEX.parseHex(m.path("bodyHex").asText("")), StandardCharsets.UTF_8);
                if (body.startsWith(prefix) && body.endsWith(suffix)
                        && (carrier.isEmpty() || carrier.equalsIgnoreCase(m.path("sender").asText()))) {
                    return Optional.of(item.path("messageIdHex").asText());
                }
                from = Math.max(from, item.path("height").asLong() + 1);
            }
            if (list.size() < 100) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public JsonNode refundEscrow(Jobs.Log log, String createTx) {
        Script script = escrowScript();
        ObjectNode e = escrow(createTx);
        Utxo locked = l1.utxos(script.address()).stream().filter(u -> u.getTxHash().equals(createTx)).findFirst()
                .orElseThrow(() -> new IllegalStateException("the escrow UTxO is gone"));
        JsonNode tip = l1.latestBlock();
        if (tip.path("time").asLong() * 1000 <= e.path("refundAfter").asLong()) {
            throw new IllegalStateException("the refund opens after the deadline");
        }
        String hash = submit("refund escrow", c -> c.validFrom(tip.path("slot").asLong())
                .withRequiredSigners(new Address(wallet.baseAddress())),
                new ScriptTx().collectFrom(locked, Pd.constr(1)).payToAddress(wallet.baseAddress(), locked.getAmount())
                        .attachSpendingValidator(script.plutus()).withChangeAddress(wallet.baseAddress()));
        e.put("refundTx", hash);
        store.save();
        log.step("Refunded in " + hash);
        return e;
    }

    private ObjectNode escrow(String createTx) {
        for (JsonNode e : store.list("escrows")) {
            if (e.path("createTx").asText().equals(createTx)) {
                return (ObjectNode) e;
            }
        }
        throw new IllegalStateException("unknown escrow " + createTx);
    }

    public ObjectNode escrows() {
        ObjectNode o = Json.object();
        ObjectNode d = deployment("escrow");
        o.put("supplier", supplier.baseAddress());
        o.put("supplierLovelace", lovelace(supplier.baseAddress()));
        o.put("escrowAda", config.escrowAda());
        ArrayNode list = store.list("escrows").deepCopy();
        if (d.has("address")) {
            List<Utxo> open = l1.utxos(d.get("address").asText());
            list.forEach(e -> ((ObjectNode) e).put("open",
                    open.stream().anyMatch(u -> u.getTxHash().equals(e.path("createTx").asText()))));
        }
        o.set("escrows", list);
        return o;
    }

    // ------------------------------------------------------------------ 3 · snapshot rewards

    public JsonNode openSeason(Jobs.Log log) {
        if (deployment("rewards").has("policy")) {
            throw new IllegalStateException("a season is already open at height " + deployment("rewards").get("snapshotHeight"));
        }
        Anchor anchor = Anchor.load(l1, config.rewardsChain());
        log.step("Snapshot: " + anchor.chainId() + " as anchored at height " + anchor.height());
        Utxo seed = l1.utxos(wallet.baseAddress()).getFirst();
        Script script = script("rewards", List.of(anchor.ref(), outRef(seed)));
        PlutusData season = Pd.constr(0, Pd.integer(anchor.height()), Pd.hex(anchor.stateRoot()), Pd.bytes(new byte[32]));
        String tx = openRegistry(script, seed, season, anchor.utxo(), "open rewards season");
        ObjectNode d = deployment("rewards");
        d.put("policy", script.hash());
        d.put("address", script.address());
        d.put("compiledCode", script.code());
        d.put("snapshotHeight", anchor.height());
        d.put("snapshotRoot", anchor.stateRoot());
        d.put("openTx", tx);
        d.putObject("claims");
        store.save();
        log.step("Season open in " + tx);
        return d.deepCopy().without(List.of("compiledCode"));
    }

    public ArrayNode accounts() {
        ArrayNode list = Json.MAPPER.createArrayNode();
        ObjectNode d = deployment("rewards");
        Long height = d.has("snapshotHeight") ? d.get("snapshotHeight").asLong() : null;
        Map<String, String> claimed = claims(d);
        for (MemberDirectory.Member m : members.members()) {
            if (m.memberKey() == null) {
                continue;
            }
            ObjectNode a = list.addObject();
            a.put("nodeId", m.nodeId());
            a.put("label", m.label());
            a.put("memberKey", m.memberKey());
            a.put("owner", Facts.ownerAddress(HEX.parseHex(m.memberKey())));
            if (height != null) {
                JsonNode proof = l1.stateProof(config.rewardsChain(), Facts.balanceKey(m.memberKey()), height);
                boolean present = "PRESENT".equals(proof.path("presence").asText());
                a.put("snapshotBalance", present ? new BigInteger(HEX.parseHex(proof.path("valueHex").asText())).longValue() : 0);
                a.put("claimed", claimed.containsKey(m.memberKey()));
            }
        }
        for (JsonNode c : store.list("rewardClaims")) {
            list.forEach(a -> {
                if (a.path("memberKey").asText().equals(c.path("memberKey").asText())) {
                    ((ObjectNode) a).put("claimTx", c.path("tx").asText());
                }
            });
        }
        return list;
    }

    public JsonNode claimRewards(Jobs.Log log, String memberKey) {
        Script script = deployed("rewards");
        ObjectNode d = deployment("rewards");
        long height = d.get("snapshotHeight").asLong();
        JsonNode proof = l1.stateProof(config.rewardsChain(), Facts.balanceKey(memberKey), height);
        if (!"PRESENT".equals(proof.path("presence").asText())) {
            throw new IllegalStateException("this account had no balance at the snapshot height " + height);
        }
        if (!d.get("snapshotRoot").asText().equals(proof.path("stateRoot").asText())) {
            throw new IllegalStateException("proof root differs from the season snapshot");
        }
        long amount = new BigInteger(HEX.parseHex(proof.path("valueHex").asText())).longValue();
        log.step("Balance at snapshot height " + height + ": " + amount + " (" + proof.path("proofWireHex").asText().length() / 2
                + "-byte MPF proof)");
        Utxo registry = registry(script);
        List<PlutusData> datum = Pd.fields(Pd.decode(registry.getInlineDatum()));
        String onChain = HEX.formatHex(Pd.asBytes(datum.get(2)));
        NullifierTrie trie = new NullifierTrie(claims(d));
        if (!trie.root().equals(onChain)) {
            throw new IllegalStateException("the local claimed set differs from the season on chain");
        }
        byte[] account = HEX.parseHex(memberKey);
        byte[] value = Facts.uintCbor(amount);
        boolean already = trie.contains(account);
        log.step(already ? "Already claimed this season: the validator must refuse this"
                : "Account absent from the claimed set (exclusion proof for mpf.insert)");
        PlutusData nullProof = already ? Pd.list(List.of()) : trie.exclusionProof(account);
        String next = already ? onChain : trie.rootAfter(account, value);
        String owner = Facts.ownerAddress(account);
        ScriptTx tx = new ScriptTx()
                .collectFrom(registry, Pd.constr(0, Pd.bytes(account), Pd.integer(amount),
                        Pd.decode(proof.path("proofWireHex").asText()), nullProof))
                .mintAsset(script.plutus(), new Asset(POINTS, BigInteger.valueOf(amount)), Pd.constr(1))
                .payToAddress(owner, List.of(Amount.ada(2), Amount.asset(unit(script.hash(), POINTS), BigInteger.valueOf(amount))))
                .payToContract(script.address(), keep(registry), Pd.constr(0, datum.get(0), datum.get(1), Pd.hex(next)))
                .withChangeAddress(wallet.baseAddress());
        log.step("Submitting: mint " + amount + " " + POINTS + " to the member's own key address");
        String hash = submit("claim rewards", c -> c, tx);
        recordClaim(d, account, value);
        ObjectNode c = store.list("rewardClaims").addObject();
        c.put("memberKey", memberKey);
        c.put("amount", amount);
        c.put("tx", hash);
        store.save();
        log.step("Claimed in " + hash);
        return c;
    }

    public JsonNode mintPoints(Jobs.Log log, String memberKey, long amount) {
        log.step("Mint " + amount + " points to " + memberKey.substring(0, 12) + "…");
        JsonNode done = write(config.rewardsChain(), "mint", "node-0", null,
                Map.of("account", memberKey, "amount", Long.toString(amount)));
        log.step("Applied at height " + done.path("height").asLong());
        return done.path("outcome");
    }

    // ------------------------------------------------------------------ anchoring

    /** Writes demo traffic until the chain is due for its next anchor, then waits for it on Cardano. */
    public JsonNode populate(Jobs.Log log, String chainId) {
        ObjectNode now = progress(chainId);
        long anchoredAtStart = Anchor.load(l1, chainId).height();
        if (now.path("tip").asLong() <= anchoredAtStart && anchoredAtStart > 0
                && now.path("blocksToAnchor").asInt() == config.anchorEveryBlocks()) {
            log.step("Everything up to the tip (" + anchoredAtStart + ") is already anchored; nothing to do");
            return Anchor.load(l1, chainId).json();
        }
        log.step(chainId + ": tip " + now.path("tip").asLong() + ", last anchored " + now.path("lastAnchored").asLong()
                + "; " + now.path("blocksToAnchor").asInt() + " more blocks before the next anchor");
        String tag = Long.toString(System.currentTimeMillis() % 100_000, 36);
        int written = 0;
        List<String> nodes = members.members().stream().filter(m -> m.memberKey() != null)
                .map(MemberDirectory.Member::nodeId).toList();
        while (now.path("blocksToAnchor").asInt() > 0) {
            if (written >= 60) {
                throw new IllegalStateException("stopped after 60 writes");
            }
            filler(chainId, tag, written, nodes.get(written % nodes.size()));
            written++;
            now = progress(chainId);
            if (written % 5 == 0) {
                log.step("  " + written + " written; tip " + now.path("tip").asLong() + ", "
                        + now.path("blocksToAnchor").asInt() + " to go");
            }
        }
        return awaitAnchor(log, chainId, Math.max(now.path("lastAnchored").asLong(), Anchor.load(l1, chainId).height()));
    }

    /** "Anchor now" through the node's admin force-anchor (only when an admin key is configured). */
    public JsonNode anchorNow(Jobs.Log log, String chainId) {
        String key = adminKey.orElseThrow(() -> new IllegalStateException("no node admin key configured"));
        long before = Anchor.load(l1, chainId).height();
        L1.Reply reply = l1.post("/app-chain/chains/" + chainId + "/admin/force-anchor", "application/json",
                java.net.http.HttpRequest.BodyPublishers.ofString("{}"), Optional.of(key));
        if (!reply.ok()) {
            throw new IllegalStateException("force-anchor refused (HTTP " + reply.status() + ")");
        }
        log.step("Asked the members to anchor " + chainId + " now");
        return awaitAnchor(log, chainId, before);
    }

    private JsonNode awaitAnchor(Jobs.Log log, String chainId, long before) {
        log.step("Waiting for the members to co-sign the next anchor on Cardano…");
        for (int i = 0; i < 120; i++) {
            Anchor anchor = Anchor.load(l1, chainId);
            if (anchor.height() > before) {
                log.step("New anchor: height " + anchor.height() + " in " + anchor.utxo().getTxHash());
                return anchor.json();
            }
            sleep(10_000);
        }
        throw new IllegalStateException("no new anchor after 20 minutes");
    }

    private void filler(String chainId, String tag, int i, String node) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (chainId.equals(config.escrowChain())) {
            write(chainId, "append", node, null, Map.of("order", "F-" + tag.toUpperCase() + "-" + (100 + i),
                    "event", List.of("created", "paid", "packed", "shipped").get(r.nextInt(4))));
        } else if (chainId.equals(config.rewardsChain())) {
            List<MemberDirectory.Member> all = members.members().stream().filter(m -> m.memberKey() != null).toList();
            write(chainId, "mint", "node-0", null, Map.of("account", all.get(r.nextInt(all.size())).memberKey(),
                    "amount", Integer.toString(1 + r.nextInt(25))));
        } else if (chainId.equals(config.certificatesChain())) {
            // Propose, then reject at once: two blocks, and nothing is left pending. role-approvals allows only
            // 64 pending proposals per proposer, and every demo proposal comes from issuer-a.
            String proposal = "fill-" + tag + "-" + i;
            write(chainId, "propose", "node-0", "issuer-a", Map.of("proposal", proposal,
                    "entity", "draft-" + i, "content", "Draft " + i, "reference", "dms://drafts/" + i));
            write(chainId, "reject", "node-0", "auditor-a", Map.of("proposal", proposal));
        } else {
            throw new IllegalArgumentException("populate supports the three On Cardano chains only");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
