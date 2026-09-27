package com.bloxbean.yano.democlient.chain;

import com.bloxbean.yano.democlient.node.NodeGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Member identities: each configured node signs as its own member key, so "act as member N" = submit through node N.
 * Keys are discovered from the nodes (chain status .memberKey) and cached.
 */
@ApplicationScoped
public class MemberDirectory {
    private static final long TTL_MILLIS = 5 * 60_000;
    private static final String PROBE_CHAIN = "orders-chain";

    private final NodeGateway gateway;
    private final ObjectMapper json;
    private volatile Map<String, String> keyByNode = Map.of();
    private volatile long loadedAt;

    public MemberDirectory(NodeGateway gateway, ObjectMapper json) {
        this.gateway = gateway;
        this.json = json;
    }

    public record Member(String nodeId, String label, String memberKey) {
        public String shortKey() {
            return memberKey == null ? "unknown" : memberKey.substring(0, 8) + "…" + memberKey.substring(56);
        }
    }

    public List<Member> members() {
        Map<String, String> keys = keys();
        return gateway.members().stream()
                .map(target -> new Member(target.id(), target.label(), keys.get(target.id())))
                .toList();
    }

    public Optional<Member> byNode(String nodeId) {
        return members().stream().filter(m -> m.nodeId().equals(nodeId)).findFirst();
    }

    public Optional<Member> byKey(String memberKey) {
        return members().stream().filter(m -> memberKey != null && memberKey.equals(m.memberKey())).findFirst();
    }

    /** "Member 2 (4aae46d4…bce4)" or the short key when it is not one of ours. */
    public String name(String memberKey) {
        return byKey(memberKey).map(m -> m.label() + " (" + m.shortKey() + ")")
                .orElseGet(() -> memberKey == null || memberKey.length() < 64 ? String.valueOf(memberKey)
                        : memberKey.substring(0, 8) + "…" + memberKey.substring(56));
    }

    private Map<String, String> keys() {
        if (System.currentTimeMillis() - loadedAt < TTL_MILLIS && keyByNode.size() == gateway.members().size()) {
            return keyByNode;
        }
        synchronized (this) {
            Map<String, String> loaded = new LinkedHashMap<>(keyByNode);
            for (NodeGateway.Target target : gateway.members()) {
                NodeGateway.NodeResponse response = gateway.get(target,
                        "/api/v1/app-chain/chains/" + PROBE_CHAIN + "/status", Map.of());
                if (!response.ok()) {
                    continue;
                }
                try {
                    JsonNode status = json.readTree(response.body());
                    String key = status.path("memberKey").asText(null);
                    if (key != null && key.matches("[0-9a-f]{64}")) {
                        loaded.put(target.id(), key);
                    }
                } catch (Exception ignored) {
                    // unreachable node: keep the previous key, if any
                }
            }
            keyByNode = Map.copyOf(loaded);
            loadedAt = System.currentTimeMillis();
            return keyByNode;
        }
    }
}
