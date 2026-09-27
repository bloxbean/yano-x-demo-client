package com.bloxbean.yano.democlient.node;

import com.bloxbean.yano.democlient.config.DemoConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The only way the backend talks to a Yano node. Targets are the configured members or (when enabled) a validated
 * custom https URL; reads are limited to the public app-chain/plugin API, writes to message submission.
 */
@ApplicationScoped
public class NodeGateway {
    private static final Pattern READ_PATH = Pattern.compile("/api/v1/(app-chain|plugins)/[A-Za-z0-9._~/%:-]*");
    private static final Pattern CHAIN_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final DemoConfig config;
    private final HttpClient http;
    private final Optional<String> apiKey;

    public NodeGateway(DemoConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.http().timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.apiKey = config.http().apiKeyFile().map(NodeGateway::readKey);
    }

    public record Target(String id, String label, String url, boolean custom) {
    }

    public record NodeResponse(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public List<Target> members() {
        return config.nodes().stream()
                .map(node -> new Target(node.id(), node.label(), trim(node.url()), false))
                .toList();
    }

    public Target defaultTarget() {
        return config.nodes().stream().filter(DemoConfig.Node::isDefault).findFirst()
                .or(() -> config.nodes().stream().findFirst())
                .map(node -> new Target(node.id(), node.label(), trim(node.url()), false))
                .orElseThrow(() -> new IllegalStateException("no demo.nodes configured"));
    }

    /** Resolves a node id, or a custom URL when enabled; null/blank means the default node. */
    public Target resolve(String nodeId, String customUrl) {
        if (customUrl != null && !customUrl.isBlank()) {
            return custom(customUrl.trim());
        }
        if (nodeId == null || nodeId.isBlank()) {
            return defaultTarget();
        }
        return members().stream().filter(t -> t.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown node " + nodeId));
    }

    public NodeResponse get(Target target, String path, Map<String, String> query) {
        if (!READ_PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException("path not allowed");
        }
        String url = target.url() + path + encodeQuery(query);
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), target);
    }

    /** POST /api/v1/app-chain/chains/{chain}/messages with a server-built payload. */
    public NodeResponse submit(Target target, String chainId, String topic, String bodyHex) {
        if (!CHAIN_ID.matcher(chainId).matches()) {
            throw new IllegalArgumentException("invalid chain id");
        }
        String json = "{\"topic\":" + jsonString(topic) + ",\"bodyHex\":" + jsonString(bodyHex) + "}";
        return send(HttpRequest.newBuilder(URI.create(target.url() + "/api/v1/app-chain/chains/" + chainId + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), target);
    }

    private NodeResponse send(HttpRequest.Builder builder, Target target) {
        builder.timeout(config.http().timeout()).header("User-Agent", config.http().userAgent())
                .header("Accept", "application/json");
        if (!target.custom()) {
            apiKey.ifPresent(key -> builder.header("X-API-Key", key));
        }
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body.length > MAX_BODY_BYTES) {
                return new NodeResponse(502, "{\"error\":\"node response too large\"}");
            }
            return new NodeResponse(response.statusCode(), new String(body, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            return new NodeResponse(502, "{\"error\":\"node unreachable\"}");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new NodeResponse(503, "{\"error\":\"interrupted\"}");
        }
    }

    private Target custom(String url) {
        if (!config.customNode().enabled()) {
            throw new IllegalArgumentException("custom node URLs are disabled on this deployment");
        }
        URI uri = URI.create(trim(url));
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getRawQuery() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || !Pattern.matches(config.customNode().hostPattern(), host)) {
            throw new IllegalArgumentException("custom node must be https://<host>[:port] matching the allowed host pattern");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()
                        || isUniqueLocalV6(address)) {
                    throw new IllegalArgumentException("custom node resolves to a private address");
                }
            }
        } catch (UnknownHostException unknown) {
            throw new IllegalArgumentException("custom node host does not resolve");
        }
        return new Target("custom", host, trim(url), true);
    }

    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] raw = address.getAddress();
        return raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
    }

    private static String encodeQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        return query.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&", "?", ""));
    }

    static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String readKey(String file) {
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read demo.http.api-key-file", failure);
        }
    }
}
