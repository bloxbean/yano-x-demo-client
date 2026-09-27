package com.bloxbean.yano.democlient.chain;

import com.bloxbean.yano.democlient.codec.Hex;
import com.bloxbean.yano.democlient.node.NodeGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Reads committed state entries of one chain through one node. */
public final class StateReader {
    private final NodeGateway gateway;
    private final ObjectMapper json;
    private final NodeGateway.Target target;
    private final String chainId;

    public StateReader(NodeGateway gateway, ObjectMapper json, NodeGateway.Target target, String chainId) {
        this.gateway = gateway;
        this.json = json;
        this.target = target;
        this.chainId = chainId;
    }

    /** presence: PRESENT | ABSENT | TOMBSTONED | UNAVAILABLE. */
    public record Entry(String presence, String valueHex, long committedHeight) {
        public boolean present() {
            return "PRESENT".equals(presence);
        }

        public byte[] value() {
            return valueHex == null ? new byte[0] : Hex.decode(valueHex);
        }

        public boolean sameAs(Entry other) {
            return other != null && presence.equals(other.presence)
                    && java.util.Objects.equals(valueHex, other.valueHex);
        }
    }

    public String chainId() {
        return chainId;
    }

    public NodeGateway.Target target() {
        return target;
    }

    /** height &lt;= 0 means latest. */
    public Entry entry(byte[] key, long height) {
        Map<String, String> query = height > 0 ? Map.of("height", Long.toString(height)) : Map.of();
        NodeGateway.NodeResponse response = gateway.get(target,
                "/api/v1/app-chain/chains/" + chainId + "/state/entry/" + Hex.encode(key), query);
        if (!response.ok()) {
            return new Entry("UNAVAILABLE", null, -1);
        }
        try {
            JsonNode node = json.readTree(response.body());
            return new Entry(node.path("presence").asText("UNAVAILABLE"),
                    node.hasNonNull("valueHex") ? node.get("valueHex").asText() : null,
                    node.path("committedHeight").asLong(-1));
        } catch (Exception malformed) {
            return new Entry("UNAVAILABLE", null, -1);
        }
    }

    /** Current tip height of the chain on this node, or -1. */
    public long tipHeight() {
        NodeGateway.NodeResponse response = gateway.get(target, "/api/v1/app-chain/chains/" + chainId + "/tip", Map.of());
        if (!response.ok()) {
            return -1;
        }
        try {
            return json.readTree(response.body()).path("height").asLong(-1);
        } catch (Exception malformed) {
            return -1;
        }
    }

    public Entry latest(byte[] key) {
        return entry(key, 0);
    }
}
