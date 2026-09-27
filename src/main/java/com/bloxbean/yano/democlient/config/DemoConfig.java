package com.bloxbean.yano.democlient.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Deployment configuration. Node URLs are never compiled in: they come from an external
 * application.yml / environment (see config/example-application.yml).
 */
@ConfigMapping(prefix = "demo")
public interface DemoConfig {

    /** Shown in the UI header. */
    @WithDefault("Yano X demo cluster")
    String clusterName();

    /** Cluster members the backend may talk to. The first entry with default=true (or the first) is the default. */
    List<Node> nodes();

    CustomNode customNode();

    RateLimit rateLimit();

    Http http();

    /** Cardano explorer link template for anchor / settlement transactions; {tx} is replaced. */
    @WithDefault("https://preprod.cardanoscan.io/transaction/{tx}")
    String explorerTxUrl();

    interface Node {
        /** Stable id used by the UI, e.g. node-0. */
        String id();

        /** Human label, e.g. the member organisation. */
        String label();

        /** Base URL of the node's public API (no trailing slash). */
        String url();

        @WithDefault("false")
        boolean isDefault();
    }

    interface CustomNode {
        /** Let a user enter their own node URL in the UI. Off by default (server-side requests to user URLs). */
        @WithDefault("false")
        boolean enabled();

        /** Only https URLs whose host matches this pattern are accepted; private/loopback addresses are always refused. */
        @WithDefault("[a-z0-9.-]+")
        String hostPattern();
    }

    interface RateLimit {
        /** Chain writes per client (IP) per minute. */
        @WithDefault("20")
        int perClientPerMinute();

        /** Chain writes for the whole app per minute. */
        @WithDefault("120")
        int globalPerMinute();
    }

    interface Http {
        @WithDefault("yano-x-demo-client/0.1")
        String userAgent();

        @WithDefault("10s")
        Duration timeout();

        /** Optional X-API-Key for node reads that require it; read from a file, never logged. */
        Optional<String> apiKeyFile();
    }
}
