package com.bloxbean.yano.democlient.service;

import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.config.DemoConfig;
import com.bloxbean.yano.democlient.codec.Hex;
import com.bloxbean.yano.democlient.node.NodeGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Read models over the generic app-chain API: live chain summaries, member agreement, activity, proofs. */
@ApplicationScoped
public class ClusterService {
    private final NodeGateway gateway;
    private final MemberDirectory members;
    private final ObjectMapper json;
    private final DemoConfig.Quorum quorum;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ClusterService(NodeGateway gateway, MemberDirectory members, ObjectMapper json, DemoConfig config) {
        this.quorum = config.quorum();
        this.gateway = gateway;
        this.members = members;
        this.json = json;
    }

    @RegisterForReflection
    public record ChainLive(String chainId, long tipHeight, String stateRoot, boolean running, int peersUp, int peers,
                            Anchor anchor) {
    }

    @RegisterForReflection
    public record Anchor(boolean enabled, String mode, Long anchoredHeight, String tx, Long lagBlocks,
                         String scriptAddress) {
    }

    @RegisterForReflection
    public record MemberView(String nodeId, String label, boolean reachable, Long height, String stateRoot,
                             Integer certSignatures) {
    }

    @RegisterForReflection
    public record Agreement(String chainId, long comparedHeight, boolean agree, List<MemberView> members) {
    }

    @RegisterForReflection
    public record ActivityItem(long height, String timestamp, String messageId, String topic, String sender,
                               String senderName, String summary) {
    }

    @RegisterForReflection
    public record Signer(String memberKey, String name) {
    }

    @RegisterForReflection
    public record Proof(String keyHex, String presence, String valueHex, long height, String stateRoot,
                        String blockHash, String proofWireHex, List<Signer> signers, int threshold, int memberCount) {
    }

    private record Cached(long at, Object value) {
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, long ttlMillis, Supplier<T> loader) {
        Cached hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at < ttlMillis) {
            return (T) hit.value;
        }
        T value = loader.get();
        cache.put(key, new Cached(System.currentTimeMillis(), value));
        return value;
    }

    public ChainLive live(NodeGateway.Target target, String chainId) {
        return cached("live:" + target.url() + ":" + chainId, 4_000, () -> {
            JsonNode status = read(target, "/api/v1/app-chain/chains/" + chainId + "/status");
            if (status == null) {
                return new ChainLive(chainId, -1, null, false, 0, 0, null);
            }
            int up = 0;
            int total = 0;
            for (JsonNode peer : status.path("peers")) {
                total++;
                if (peer.asBoolean()) {
                    up++;
                }
            }
            JsonNode a = status.path("anchor");
            Anchor anchor = a.isMissingNode() ? null : new Anchor(a.path("enabled").asBoolean(), a.path("mode").asText(null),
                    a.hasNonNull("lastAnchoredHeight") ? a.get("lastAnchoredHeight").asLong() : null,
                    a.path("lastAnchorTx").asText(null),
                    a.hasNonNull("lagBlocks") ? a.get("lagBlocks").asLong() : null, a.path("scriptAddress").asText(null));
            return new ChainLive(chainId, status.path("tipHeight").asLong(-1), status.path("stateRoot").asText(null),
                    status.path("running").asBoolean(), up, total, anchor);
        });
    }

    private static final List<String> LIVE_TOP = List.of("chainId", "stateMachine", "members", "threshold",
            "membershipMode", "membershipEpochFromHeight", "membershipActiveMembers", "membershipActiveThreshold",
            "configuredBlockIntervalMs", "consensusProfile", "capabilityManifest", "stateMachineStatus", "tipHeight");
    private static final List<String> LIVE_SEQUENCER = List.of("mode", "proposer", "consensusProtocol", "roundTimeoutMs");
    private static final List<String> LIVE_COMMITMENT = List.of("profile", "backend", "commitmentFormatId",
            "proofEncodingId", "formatFingerprint", "genesisId");
    private static final List<String> LIVE_ANCHOR = List.of("enabled", "mode", "scriptAddress", "threadPolicyId", "bootstrapped");
    private static final List<String> LIVE_EFFECTS = List.of("enabled", "maxPerBlock", "maxPayloadBytes",
            "maxExpiryBlocks", "defaultGate", "outcomeCommitment");

    /**
     * How the chain is configured, as the node runs it: the configuration parts of /status (consensus limits,
     * membership, sequencer, state commitment, anchoring, effects, and for composite chains the capability
     * manifest). Counters, peers, wallet addresses and executor internals are left out.
     */
    public JsonNode liveConfig(NodeGateway.Target target, String chainId) {
        return cached("liveConfig:" + target.url() + ":" + chainId, 10_000, () -> {
            JsonNode status = read(target, "/api/v1/app-chain/chains/" + chainId + "/status");
            if (status == null) {
                return null;
            }
            com.fasterxml.jackson.databind.node.ObjectNode out = json.createObjectNode();
            out.put("node", target.label());
            copy(status, out, LIVE_TOP);
            copy(status.path("sequencer"), out.putObject("sequencer"), LIVE_SEQUENCER);
            JsonNode commitment = status.path("stateCommitment");
            com.fasterxml.jackson.databind.node.ObjectNode c = out.putObject("stateCommitment");
            copy(commitment, c, LIVE_COMMITMENT);
            c.set("verificationTarget", commitment.path("implementation").path("verificationTarget"));
            if (status.has("anchor")) {
                copy(status.path("anchor"), out.putObject("anchor"), LIVE_ANCHOR);
            }
            if (status.has("effects")) {
                com.fasterxml.jackson.databind.node.ObjectNode e = out.putObject("effects");
                copy(status.path("effects"), e, LIVE_EFFECTS);
                if (status.path("effects").has("executor")) {
                    e.put("executorOnThisNode", status.path("effects").path("executor").path("enabled").asBoolean());
                }
            }
            return out;
        });
    }

    private static void copy(JsonNode from, com.fasterxml.jackson.databind.node.ObjectNode to, List<String> keys) {
        for (String key : keys) {
            if (from.has(key) && !from.get(key).isNull()) {
                to.set(key, from.get(key));
            }
        }
    }

    /** Chain ids hosted by the target node. */
    public List<String> chainIds(NodeGateway.Target target) {
        return cached("chains:" + target.url(), 10_000, () -> {
            JsonNode list = read(target, "/api/v1/app-chain/chains");
            List<String> ids = new ArrayList<>();
            if (list != null) {
                list.forEach(chain -> ids.add(chain.path("chainId").asText()));
            }
            return ids;
        });
    }

    /** Every configured member's view of the same block (the lowest tip among reachable members). */
    public Agreement agreement(String chainId) {
        return cached("agree:" + chainId, 3_000, () -> {
            List<NodeGateway.Target> targets = gateway.members();
            long common = Long.MAX_VALUE;
            Map<String, Long> tips = new java.util.HashMap<>();
            for (NodeGateway.Target target : targets) {
                JsonNode tip = read(target, "/api/v1/app-chain/chains/" + chainId + "/tip");
                if (tip != null) {
                    tips.put(target.id(), tip.path("height").asLong());
                    common = Math.min(common, tip.path("height").asLong());
                }
            }
            List<MemberView> views = new ArrayList<>();
            String root = null;
            boolean agree = !tips.isEmpty() && common > 0;
            for (NodeGateway.Target target : targets) {
                if (!tips.containsKey(target.id()) || common == Long.MAX_VALUE || common < 1) {
                    views.add(new MemberView(target.id(), target.label(), tips.containsKey(target.id()),
                            tips.get(target.id()), null, null));
                    continue;
                }
                JsonNode block = read(target, "/api/v1/app-chain/chains/" + chainId + "/blocks/" + common);
                String stateRoot = block == null ? null : block.path("stateRoot").asText(null);
                views.add(new MemberView(target.id(), target.label(), block != null, tips.get(target.id()), stateRoot,
                        block == null ? null : block.path("certSignatures").asInt()));
                if (stateRoot == null || (root != null && !root.equals(stateRoot))) {
                    agree = false;
                }
                root = root == null ? stateRoot : root;
            }
            return new Agreement(chainId, common == Long.MAX_VALUE ? 0 : common, agree, views);
        });
    }

    public List<ActivityItem> activity(NodeGateway.Target target, ChainModule module, String chainId, int limit) {
        return cached("activity:" + target.url() + ":" + chainId + ":" + limit, 3_000, () -> {
            JsonNode page = read(target, "/api/v1/app-chain/chains/" + chainId + "/blocks?limit=" + limit);
            List<ActivityItem> items = new ArrayList<>();
            if (page == null) {
                return items;
            }
            List<Long> heights = new ArrayList<>();
            page.path("blocks").forEach(block -> heights.add(block.path("height").asLong()));
            heights.sort((a, b) -> Long.compare(b, a));
            for (long height : heights) {
                JsonNode block = read(target, "/api/v1/app-chain/chains/" + chainId + "/blocks/" + height);
                if (block == null) {
                    continue;
                }
                for (JsonNode message : block.path("messages")) {
                    String sender = message.path("sender").asText();
                    String summary = null;
                    if (module != null) {
                        try {
                            summary = module.describe(message.path("topic").asText(),
                                    Hex.decode(message.path("bodyHex").asText()), members);
                        } catch (RuntimeException undecodable) {
                            summary = null;
                        }
                    }
                    items.add(new ActivityItem(height, block.path("timestamp").asText(null),
                            message.path("messageId").asText(), message.path("topic").asText(), sender,
                            members.name(sender), summary));
                    if (items.size() >= limit) {
                        return items;
                    }
                }
            }
            return items;
        });
    }

    public Proof proof(NodeGateway.Target target, String chainId, String keyHex) {
        JsonNode proof = read(target, "/api/v1/app-chain/chains/" + chainId + "/state/proof/" + keyHex);
        if (proof == null) {
            return null;
        }
        List<Signer> signers = new ArrayList<>();
        proof.path("finalityCertificate").path("signatures").forEach(sig -> {
            String signer = sig.path("signer").asText();
            signers.add(new Signer(signer, members.name(signer)));
        });
        JsonNode block = proof.path("block");
        return new Proof(keyHex, proof.path("presence").asText(), proof.path("valueHex").asText(null),
                block.path("height").asLong(proof.path("committedHeight").asLong()),
                block.path("stateRoot").asText(null), block.path("blockHash").asText(proof.path("blockHash").asText(null)),
                proof.path("proofWireHex").asText(null), signers, quorum.threshold(), quorum.members());
    }

    private JsonNode read(NodeGateway.Target target, String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        Map<String, String> query = new java.util.LinkedHashMap<>();
        if (q >= 0) {
            for (String pair : pathAndQuery.substring(q + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        NodeGateway.NodeResponse response = gateway.get(target, path, query);
        if (!response.ok()) {
            return null;
        }
        try {
            return json.readTree(response.body());
        } catch (Exception malformed) {
            return null;
        }
    }
}
