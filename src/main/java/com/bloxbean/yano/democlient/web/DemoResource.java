package com.bloxbean.yano.democlient.web;

import com.bloxbean.yano.democlient.actor.ActorDirectory;
import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.Specs;
import com.bloxbean.yano.democlient.chain.StateReader;
import com.bloxbean.yano.democlient.config.ConfigFiles;
import com.bloxbean.yano.democlient.config.DemoConfig;
import com.bloxbean.yano.democlient.node.NodeGateway;
import com.bloxbean.yano.democlient.service.ClusterService;
import com.bloxbean.yano.democlient.service.SubmissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Every endpoint does blocking node I/O, so requests run on virtual threads (no worker-pool exhaustion). */
@Path("/api/demo")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DemoResource {
    private static final String CHAIN_ID = "[a-z][a-z0-9-]{0,62}";

    private final DemoConfig config;
    private final NodeGateway gateway;
    private final MemberDirectory members;
    private final ClusterService cluster;
    private final SubmissionService submissions;
    private final RateLimiter rateLimiter;
    private final ActorDirectory actors;
    private final ConfigFiles configFiles;
    private final ObjectMapper json;
    private final Map<String, ChainModule> modules = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() / 1000 % 100_000);

    public DemoResource(DemoConfig config, NodeGateway gateway, MemberDirectory members, ClusterService cluster,
                        SubmissionService submissions, RateLimiter rateLimiter, ObjectMapper json,
                        ActorDirectory actors, ConfigFiles configFiles, Instance<ChainModule> all) {
        this.actors = actors;
        this.configFiles = configFiles;
        this.config = config;
        this.gateway = gateway;
        this.members = members;
        this.cluster = cluster;
        this.submissions = submissions;
        this.rateLimiter = rateLimiter;
        this.json = json;
        all.forEach(module -> modules.put(module.chainId(), module));
    }

    // ------------------------------------------------------------------ DTOs

    @RegisterForReflection
    public record NodeInfo(String id, String label, String host, boolean isDefault) {
    }

    @RegisterForReflection
    public record ClientConfig(String clusterName, List<NodeInfo> nodes, boolean customNodeEnabled,
                               String explorerTxUrl, int writesPerMinute, int threshold, int memberCount) {
    }

    @RegisterForReflection
    public record MemberInfo(String nodeId, String label, String memberKey, String shortKey) {
    }

    @RegisterForReflection
    public record ChainCard(Specs.Chain spec, ClusterService.ChainLive live, boolean supported) {
    }

    @RegisterForReflection
    public record SubmitRequest(String node, String actor, Map<String, String> fields) {
    }

    @RegisterForReflection
    public record LookupRequest(Map<String, String> fields) {
    }

    @RegisterForReflection
    public record Problem(String error) {
    }

    // ------------------------------------------------------------------ cluster

    @GET
    @Path("/config")
    public ClientConfig clientConfig() {
        String defaultId = gateway.defaultTarget().id();
        List<NodeInfo> nodes = gateway.members().stream()
                .map(t -> new NodeInfo(t.id(), t.label(), URI.create(t.url()).getHost(), t.id().equals(defaultId)))
                .toList();
        return new ClientConfig(config.clusterName(), nodes, config.customNode().enabled(), config.explorerTxUrl(),
                config.rateLimit().perClientPerMinute(), config.quorum().threshold(), config.quorum().members());
    }

    @GET
    @Path("/members")
    public List<MemberInfo> members() {
        return members.members().stream()
                .map(m -> new MemberInfo(m.nodeId(), m.label(), m.memberKey(), m.memberKey() == null ? null : m.shortKey()))
                .toList();
    }

    @GET
    @Path("/actors")
    public List<ActorDirectory.ActorInfo> actors() {
        return actors.all();
    }

    @GET
    @Path("/chains")
    public List<ChainCard> chains(@QueryParam("node") String node, @QueryParam("nodeUrl") String nodeUrl) {
        NodeGateway.Target target = gateway.resolve(node, nodeUrl);
        List<String> hosted = cluster.chainIds(target);
        List<String> ids = new ArrayList<>(modules.keySet());
        hosted.stream().filter(id -> !modules.containsKey(id)).forEach(ids::add);
        // Live status per chain in parallel (one node round trip each).
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<ClusterService.ChainLive>> lives = ids.stream()
                    .map(id -> executor.submit(() -> cluster.live(target, id))).toList();
            List<ChainCard> cards = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String chainId = ids.get(i);
                ChainModule module = modules.get(chainId);
                ClusterService.ChainLive live = lives.get(i).get();
                cards.add(module != null ? new ChainCard(module.spec(), live, true)
                        : new ChainCard(new Specs.Chain(chainId, chainId, "Available on the cluster; interactive demo coming soon",
                        "", "", "More chains", List.of(), true, "slate", List.of(), List.of()), live, false));
            }
            cards.sort(Comparator.comparing((ChainCard c) -> !c.supported()).thenComparing(c -> order(c.spec().id())));
            return cards;
        } catch (InterruptedException | java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("cannot load chains", failure);
        }
    }

    @GET
    @Path("/chains/{chainId}")
    public ChainCard chain(@PathParam("chainId") String chainId, @QueryParam("node") String node,
                           @QueryParam("nodeUrl") String nodeUrl) {
        ChainModule module = module(chainId);
        return new ChainCard(module.spec(), cluster.live(gateway.resolve(node, nodeUrl), chainId), true);
    }

    @GET
    @Path("/chains/{chainId}/agreement")
    public ClusterService.Agreement agreement(@PathParam("chainId") String chainId) {
        return cluster.agreement(chainIdOrThrow(chainId));
    }

    @GET
    @Path("/chains/{chainId}/activity")
    public List<ClusterService.ActivityItem> activity(@PathParam("chainId") String chainId,
                                                      @QueryParam("node") String node,
                                                      @QueryParam("nodeUrl") String nodeUrl,
                                                      @QueryParam("limit") Integer limit) {
        int bounded = limit == null ? 15 : Math.max(1, Math.min(limit, 40));
        return cluster.activity(gateway.resolve(node, nodeUrl), modules.get(chainIdOrThrow(chainId)), chainId, bounded);
    }

    @GET
    @Path("/chains/{chainId}/proof/{keyHex}")
    public Response proof(@PathParam("chainId") String chainId, @PathParam("keyHex") String keyHex,
                          @QueryParam("node") String node, @QueryParam("nodeUrl") String nodeUrl) {
        if (!keyHex.matches("(?:[0-9a-f]{2}){1,256}")) {
            return bad("key must be lowercase hex");
        }
        ClusterService.Proof proof = cluster.proof(gateway.resolve(node, nodeUrl), chainIdOrThrow(chainId), keyHex);
        return proof == null ? Response.status(502).entity(new Problem("proof unavailable")).build() : Response.ok(proof).build();
    }

    // ------------------------------------------------------------------ configuration

    /** The cluster's public app-chain files from demo.config-dir; the UI slices them per chain. */
    @GET
    @Path("/config-files")
    public ConfigFiles.Listing configFiles() {
        return configFiles.listing();
    }

    /** The configuration parts of a chain's live status, from the chosen node (works for every hosted chain). */
    @GET
    @Path("/chains/{chainId}/live-config")
    public Response liveConfig(@PathParam("chainId") String chainId, @QueryParam("node") String node,
                               @QueryParam("nodeUrl") String nodeUrl) {
        JsonNode live = cluster.liveConfig(gateway.resolve(node, nodeUrl), chainIdOrThrow(chainId));
        return live == null ? Response.status(502).entity(new Problem("node status unavailable")).build()
                : Response.ok(live).build();
    }

    // ------------------------------------------------------------------ commands

    @GET
    @Path("/chains/{chainId}/commands/{commandId}/mock")
    public Map<String, String> mock(@PathParam("chainId") String chainId, @PathParam("commandId") String commandId,
                                    @QueryParam("node") String node, @HeaderParam("X-Demo-Session") String session) {
        ChainModule module = module(chainId);
        String actingKey = members.byNode(node == null ? gateway.defaultTarget().id() : node)
                .map(MemberDirectory.Member::memberKey).orElse(null);
        return module.mock(commandId, new ChainModule.MockContext(sessionTag(session), sequence.incrementAndGet(),
                actingKey, members));
    }

    @POST
    @Path("/chains/{chainId}/commands/{commandId}")
    public Response submit(@PathParam("chainId") String chainId, @PathParam("commandId") String commandId,
                           SubmitRequest request, @Context HttpServerRequest http) {
        ChainModule module = module(chainId);
        Specs.Command command = module.spec().commands().stream().filter(c -> c.id().equals(commandId)).findFirst()
                .orElseThrow(() -> new NotFoundException("unknown command"));
        if (module.spec().readOnly()) {
            return bad("this chain is read-only in the demo");
        }
        if (request == null) {
            return bad("missing request body");
        }
        NodeGateway.Target target = gateway.resolve(request.node(), null);
        MemberDirectory.Member acting = members.byNode(target.id())
                .filter(m -> m.memberKey() != null)
                .orElse(null);
        if (acting == null) {
            return Response.status(503).entity(new Problem("member " + target.label() + " is not reachable right now")).build();
        }
        if (command.personaKind() == Specs.PersonaKind.ACTOR
                && (request.actor() == null || !command.actors().contains(request.actor()) || actors.find(request.actor()).isEmpty())) {
            return bad("choose one of " + String.join(", ", command.actors()) + " to sign this command");
        }
        ChainModule.Prepared prepared;
        try {
            prepared = module.prepare(commandId, request.fields(),
                    new ChainModule.CommandContext(acting.memberKey(), request.actor(), members,
                            new StateReader(gateway, json, target, chainId)));
        } catch (ChainModule.InvalidInput invalid) {
            return bad(invalid.getMessage());
        }
        long wait = rateLimiter.acquire(clientAddress(http));
        if (wait > 0) {
            return Response.status(429).header("Retry-After", wait)
                    .entity(new Problem("Easy there — the public demo allows " + config.rateLimit().perClientPerMinute()
                            + " submissions per minute. Try again in " + wait + " s.")).build();
        }
        String actor = command.personaKind() == Specs.PersonaKind.ACTOR
                ? actors.find(request.actor()).map(a -> a.label() + " (" + a.role() + ", " + a.organization() + ")").orElse(null)
                : null;
        return Response.accepted(submissions.start(module, commandId, prepared, target, acting.label(), actor)).build();
    }

    @GET
    @Path("/submissions/{id}")
    public Response submission(@PathParam("id") String id) {
        SubmissionService.View view = submissions.find(id);
        return view == null ? Response.status(404).entity(new Problem("unknown submission")).build() : Response.ok(view).build();
    }

    @POST
    @Path("/chains/{chainId}/lookups/{lookupId}")
    public Response lookup(@PathParam("chainId") String chainId, @PathParam("lookupId") String lookupId,
                           @QueryParam("node") String node, @QueryParam("nodeUrl") String nodeUrl,
                           LookupRequest request) {
        ChainModule module = module(chainId);
        NodeGateway.Target target = gateway.resolve(node, nodeUrl);
        try {
            return Response.ok(module.lookup(lookupId, request == null ? Map.of() : request.fields(),
                    new StateReader(gateway, json, target, chainId), members)).build();
        } catch (ChainModule.InvalidInput invalid) {
            return bad(invalid.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private ChainModule module(String chainId) {
        ChainModule module = modules.get(chainIdOrThrow(chainId));
        if (module == null) {
            throw new NotFoundException("chain not available in the demo");
        }
        return module;
    }

    /** Catalog order: the story builds from simple to composite. */
    private static final List<String> ORDER = List.of("orders-chain", "registry-chain", "balances-chain",
            "documents-chain", "approvals-chain", "workflow-chain", "document-review-chain", "roles-chain",
            "authenticated-map-chain", "authenticated-map-jmt-chain", "registry-audit-chain", "approval-audit-chain",
            "payments-chain", "payment-chain-settlement", "cardano-history-chain");

    private static int order(String chainId) {
        int index = ORDER.indexOf(chainId);
        return index < 0 ? ORDER.size() : index;
    }

    private static String chainIdOrThrow(String chainId) {
        if (chainId == null || !chainId.matches(CHAIN_ID)) {
            throw new NotFoundException("invalid chain id");
        }
        return chainId;
    }

    private static String sessionTag(String session) {
        return session != null && session.matches("[a-z0-9]{4,8}") ? session : "anon";
    }

    /** Cloudflare → Traefik → app: CF-Connecting-IP is the visitor; fall back to the proxied remote address. */
    private static String clientAddress(HttpServerRequest http) {
        String cf = http.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        return http.remoteAddress() == null ? "unknown" : http.remoteAddress().hostAddress();
    }

    private static Response bad(String message) {
        return Response.status(400).entity(new Problem(message)).build();
    }
}
