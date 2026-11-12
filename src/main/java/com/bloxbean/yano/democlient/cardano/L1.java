package com.bloxbean.yano.democlient.cardano;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cardano access through a member's own Blockfrost-compatible API (a Yano node is a Cardano node): UTxOs,
 * protocol parameters, script cost evaluation and submission. Implements cardano-client-lib's supplier
 * interfaces directly, so no Retrofit/OkHttp backend is needed (keeps the native image small).
 */
public final class L1 implements UtxoSupplier, ProtocolParamsSupplier, ScriptSupplier, TransactionProcessor {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String base;
    private final Optional<String> apiKey;
    private final String userAgent;

    public L1(String nodeUrl, Optional<String> apiKey, String userAgent) {
        this.base = nodeUrl.replaceAll("/+$", "") + "/api/v1";
        this.apiKey = apiKey;
        this.userAgent = userAgent;
    }

    // ------------------------------------------------------------------ plain JSON access

    public record Reply(int status, JsonNode body, String text) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonNode require(String what) {
            if (!ok()) {
                String error = body != null && body.hasNonNull("error") ? body.get("error").asText()
                        : body != null && body.hasNonNull("message") ? body.get("message").asText() : "HTTP " + status;
                throw new IllegalStateException(what + ": " + error);
            }
            return body;
        }
    }

    public Reply get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public Reply post(String path, String contentType, HttpRequest.BodyPublisher body, Optional<String> key) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", contentType).POST(body);
        key.ifPresent(k -> builder.header("X-API-Key", k));
        return send(builder);
    }

    private Reply send(HttpRequest.Builder builder) {
        builder.timeout(Duration.ofSeconds(60)).header("Accept", "application/json").header("User-Agent", userAgent);
        apiKey.ifPresent(k -> builder.setHeader("X-API-Key", k));
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode node = null;
            if (text != null && !text.isBlank()) {
                try {
                    node = MAPPER.readTree(text);
                } catch (IOException notJson) {
                    node = MAPPER.createObjectNode().put("error", text.length() > 300 ? text.substring(0, 300) : text);
                }
            }
            return new Reply(response.statusCode(), node, text);
        } catch (IOException e) {
            return new Reply(502, MAPPER.createObjectNode().put("error", "node unreachable"), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(503, MAPPER.createObjectNode().put("error", "interrupted"), "");
        }
    }

    // ------------------------------------------------------------------ app-ledger reads

    public JsonNode status(String chainId) {
        return get("/app-chain/chains/" + chainId + "/status").require("status of " + chainId);
    }

    public JsonNode stateProof(String chainId, byte[] key, long height) {
        return get("/app-chain/chains/" + chainId + "/state/proof/" + HexFormat.of().formatHex(key) + "?height=" + height)
                .require("state proof");
    }

    public JsonNode stateEntry(String chainId, byte[] key) {
        return get("/app-chain/chains/" + chainId + "/state/entry/" + HexFormat.of().formatHex(key)).require("state entry");
    }

    public JsonNode suppliedMessage(String chainId, String messageIdHex) {
        return get("/app-chain/chains/" + chainId + "/messages/" + messageIdHex + "/proof-package")
                .require("message proof package").get("suppliedMessage");
    }

    public JsonNode message(String chainId, String messageIdHex) {
        return get("/app-chain/chains/" + chainId + "/messages/" + messageIdHex).require("message");
    }

    public JsonNode latestBlock() {
        return get("/blocks/latest").require("latest Cardano block");
    }

    public boolean txKnown(String txHash) {
        return get("/txs/" + txHash).ok();
    }

    // ------------------------------------------------------------------ cardano-client-lib suppliers

    @Override
    public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        Reply reply = get("/addresses/" + address + "/utxos?count=" + nrOfItems + "&page=" + (page + 1) + "&order=asc");
        if (reply.status() == 404 || reply.body() == null || !reply.body().isArray()) {
            return List.of();
        }
        List<Utxo> list = new ArrayList<>();
        reply.body().forEach(u -> list.add(utxo(u, address)));
        return list;
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        Reply reply = get("/txs/" + txHash + "/utxos");
        if (!reply.ok()) {
            return Optional.empty();
        }
        for (JsonNode o : reply.body().path("outputs")) {
            if (o.path("output_index").asInt() == outputIndex) {
                Utxo u = utxo(o, o.path("address").asText());
                u.setTxHash(txHash);
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    public List<Utxo> utxos(String address) {
        List<Utxo> all = new ArrayList<>();
        for (int page = 0; page < 20; page++) {
            List<Utxo> batch = getPage(address, 100, page, OrderEnum.asc);
            all.addAll(batch);
            if (batch.size() < 100) {
                break;
            }
        }
        return all;
    }

    private static Utxo utxo(JsonNode u, String address) {
        List<Amount> amounts = new ArrayList<>();
        u.path("amount").forEach(a -> amounts.add(a.path("unit").asText().equals("lovelace")
                ? Amount.lovelace(new BigInteger(a.path("quantity").asText()))
                : Amount.asset(a.path("unit").asText(), new BigInteger(a.path("quantity").asText()))));
        return Utxo.builder()
                .txHash(u.path("tx_hash").asText())
                .outputIndex(u.path("output_index").asInt())
                .address(u.hasNonNull("address") ? u.get("address").asText() : address)
                .amount(amounts)
                .dataHash(text(u, "data_hash"))
                .inlineDatum(text(u, "inline_datum"))
                .referenceScriptHash(text(u, "reference_script_hash"))
                .build();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** Reference scripts by hash (Plutus V3), for fee calculation when an input carries one. */
    @Override
    public Optional<PlutusScript> getScript(String scriptHash) {
        Reply reply = get("/scripts/" + scriptHash + "/cbor");
        if (!reply.ok() || reply.body() == null || !reply.body().hasNonNull("cbor")) {
            return Optional.empty();
        }
        return Optional.of(PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(reply.body().get("cbor").asText(), PlutusVersion.v3));
    }

    @Override
    public ProtocolParams getProtocolParams() {
        JsonNode params = get("/epochs/latest/parameters").require("protocol parameters");
        try {
            return MAPPER.treeToValue(params, ProtocolParams.class);
        } catch (IOException e) {
            throw new IllegalStateException("unexpected protocol parameters: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<String> submitTransaction(byte[] cbor) {
        Reply reply = post("/tx/submit", "application/cbor", HttpRequest.BodyPublishers.ofByteArray(cbor), Optional.empty());
        if (!reply.ok()) {
            return Result.error(reply.text()).code(reply.status());
        }
        String hash = reply.body() != null && reply.body().isTextual() ? reply.body().asText() : reply.text().replace("\"", "").trim();
        return Result.success(hash).withValue(hash).code(reply.status());
    }

    /** Script costs from the node's Ogmios-style evaluator (v5 and v6 response shapes). */
    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputs) {
        Reply reply = post("/utils/txs/evaluate", "application/cbor",
                HttpRequest.BodyPublishers.ofString(HexFormat.of().formatHex(cbor), StandardCharsets.UTF_8), Optional.empty());
        JsonNode body = reply.body();
        if (!reply.ok() || body == null) {
            return Result.<List<EvaluationResult>>error(reply.text()).withValue(List.of()).code(reply.status());
        }
        JsonNode result = body.has("result") ? body.get("result") : body;
        if (body.has("fault") || result.has("EvaluationFailure") || body.has("error")) {
            return Result.<List<EvaluationResult>>error(reply.text()).withValue(List.of()).code(500);
        }
        List<EvaluationResult> out = new ArrayList<>();
        if (result.has("EvaluationResult")) {
            for (Iterator<Map.Entry<String, JsonNode>> it = result.get("EvaluationResult").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String[] pointer = e.getKey().split(":");
                out.add(new EvaluationResult(tag(pointer[0]), Integer.parseInt(pointer[1]), ExUnits.builder()
                        .mem(e.getValue().path("memory").bigIntegerValue())
                        .steps(e.getValue().path("steps").bigIntegerValue()).build()));
            }
        } else if (result.isArray()) {
            for (JsonNode r : result) {
                out.add(new EvaluationResult(tag(r.path("validator").path("purpose").asText()),
                        r.path("validator").path("index").asInt(), ExUnits.builder()
                        .mem(r.path("budget").path("memory").bigIntegerValue())
                        .steps(r.path("budget").path("cpu").bigIntegerValue()).build()));
            }
        } else {
            return Result.<List<EvaluationResult>>error(reply.text()).withValue(List.of()).code(500);
        }
        return Result.success("OK").withValue(out).code(200);
    }

    private static RedeemerTag tag(String purpose) {
        return switch (purpose) {
            case "spend" -> RedeemerTag.Spend;
            case "mint" -> RedeemerTag.Mint;
            case "cert", "publish", "certificate" -> RedeemerTag.Cert;
            case "reward", "withdraw", "withdrawal" -> RedeemerTag.Reward;
            case "vote", "voting" -> RedeemerTag.Voting;
            case "propose", "proposing" -> RedeemerTag.Proposing;
            default -> throw new IllegalArgumentException("unknown redeemer purpose " + purpose);
        };
    }
}
