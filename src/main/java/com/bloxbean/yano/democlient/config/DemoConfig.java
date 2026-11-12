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

    /** Named people with Ed25519 keys (server-side only). */
    List<Actor> actors();

    RateLimit rateLimit();

    /** The cluster's consensus quorum, shown in texts and used to read finality certificates. */
    Quorum quorum();

    Http http();

    /** Optional "On Cardano" features: validators that act on anchored app-ledger facts. Off unless enabled. */
    Cardano cardano();

    /**
     * Directory with the cluster's public app-chain files (sealed {@code *.chain}, {@code *.draft.yaml}, bindings and
     * role plans), shown in each chain's Configuration tab. Any layout: an `appchain new/seal` folder, a showcase.sh
     * instance directory or a node's apps/ directory. Unset: the tab shows the live view and accepts uploads.
     */
    Optional<String> configDir();

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

    interface Actor {
        /** Actor id as registered on chain, e.g. issuer-a. */
        String id();

        /** Display name, e.g. "Ines (issuer, Acme Manufacturing)". */
        String label();

        String organization();

        String role();

        String keyId();

        /**
         * showcase-formula: seed = sha256("yano-showcase-demo-actor:" + id) — the public release demo actors;
         * file: 32-byte seed as 64 hex characters in seed-file (0600).
         */
        @WithDefault("showcase-formula")
        String seedSource();

        Optional<String> seedFile();
    }

    interface CustomNode {
        /** Let a user enter their own node URL in the UI. Off by default (server-side requests to user URLs). */
        @WithDefault("false")
        boolean enabled();

        /** Only https URLs whose host matches this pattern are accepted; private/loopback addresses are always refused. */
        @WithDefault("[a-z0-9.-]+")
        String hostPattern();
    }

    interface Quorum {
        /** Members whose signatures finalize a block (the sealed threshold). */
        @WithDefault("4")
        int threshold();

        /** Members of the cluster. */
        @WithDefault("5")
        int members();

        /** "4 of 5" */
        default String text() {
            return threshold() + " of " + members();
        }
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

    interface Cardano {
        /** Turns the On Cardano panels and the Anchor & mint page on. Needs a funded wallet. */
        @WithDefault("false")
        boolean enabled();

        /** File holding the demo wallet's recovery phrase (testnets only). Never logged, never sent to browsers. */
        Optional<String> walletMnemonicFile();

        /**
         * Create a new testnet wallet in {@code wallet-mnemonic-file} (0600) on start when the file does not exist.
         * Only the address is logged; fund it, then use it as is.
         */
        @WithDefault("false")
        boolean createWallet();

        /** Where registries, claimed sets and escrows are kept (the claimed-set roots are also on chain). */
        @WithDefault("data/cardano-state.json")
        String stateFile();

        /**
         * Operator actions (open registries and seasons, populate, force-anchor) need this token in the
         * X-Demo-Operator header. Unset: operator actions are open, which is fine on a laptop, not on the internet.
         */
        Optional<String> operatorTokenFile();

        /** Optional node admin API key (X-API-Key) for "Anchor now" through the node's admin force-anchor. */
        Optional<String> adminApiKeyFile();

        /** Cardano transactions the server will build and pay for, per hour (all visitors together). */
        @WithDefault("40")
        int transactionsPerHour();

        /** ADA locked per delivery escrow. */
        @WithDefault("10")
        int escrowAda();

        /** The anchor schedule the cluster was sealed with (blocks), for the "blocks to the next anchor" hint. */
        @WithDefault("30")
        int anchorEveryBlocks();

        @WithDefault("document-review-chain")
        String certificatesChain();

        @WithDefault("document-release")
        String releasePolicy();

        @WithDefault("orders-chain")
        String escrowChain();

        @WithDefault("balances-chain")
        String rewardsChain();
    }
}
