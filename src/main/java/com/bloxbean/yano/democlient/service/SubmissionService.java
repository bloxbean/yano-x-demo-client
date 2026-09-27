package com.bloxbean.yano.democlient.service;

import com.bloxbean.yano.democlient.chain.ChainModule;
import com.bloxbean.yano.democlient.chain.MemberDirectory;
import com.bloxbean.yano.democlient.chain.StateReader;
import com.bloxbean.yano.democlient.codec.Hex;
import com.bloxbean.yano.democlient.node.NodeGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Submits a server-built message through the acting member's node and follows it: admitted → finalized → outcome
 * (state at height h-1 vs h). The UI polls {@link #find(String)}.
 */
@ApplicationScoped
public class SubmissionService {
    private static final Logger LOG = Logger.getLogger(SubmissionService.class);
    private static final long FINALITY_TIMEOUT_MILLIS = 120_000;
    private static final long KEEP_MILLIS = 60 * 60_000;
    private static final int MAX_TRACKED = 5_000;

    private final NodeGateway gateway;
    private final MemberDirectory members;
    private final ObjectMapper json;
    private final Map<String, Submission> submissions = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight = new Semaphore(64);

    public SubmissionService(NodeGateway gateway, MemberDirectory members, ObjectMapper json) {
        this.gateway = gateway;
        this.members = members;
        this.json = json;
    }

    public enum Stage { SUBMITTING, ADMITTED, FINALIZED, EVALUATED, REFUSED, FAILED }

    @RegisterForReflection
    public record View(String id, String chainId, String commandId, String summary, String topic, String bodyHex,
                       String nodeId, String actingAs, String actor, Stage stage, String messageId, Long height,
                       Integer index, ChainModule.Outcome outcome, String error, List<Event> events) {
    }

    @RegisterForReflection
    public record Event(String at, String stage, String text) {
    }

    private static final class Submission {
        final String id = UUID.randomUUID().toString();
        final String chainId;
        final String commandId;
        final ChainModule.Prepared prepared;
        final NodeGateway.Target target;
        final String actingAs;
        final String actor;
        final long createdAt = System.currentTimeMillis();
        final List<Event> events = new ArrayList<>();
        volatile Stage stage = Stage.SUBMITTING;
        volatile String messageId;
        volatile Long height;
        volatile Integer index;
        volatile ChainModule.Outcome outcome;
        volatile String error;

        Submission(String chainId, String commandId, ChainModule.Prepared prepared, NodeGateway.Target target,
                   String actingAs, String actor) {
            this.chainId = chainId;
            this.commandId = commandId;
            this.prepared = prepared;
            this.target = target;
            this.actingAs = actingAs;
            this.actor = actor;
        }

        synchronized void event(Stage next, String text) {
            stage = next;
            events.add(new Event(Instant.now().toString(), next.name(), text));
        }

        synchronized View view() {
            return new View(id, chainId, commandId, prepared.summary(), prepared.topic(), Hex.encode(prepared.body()),
                    target.id(), actingAs, actor, stage, messageId, height, index, outcome, error, List.copyOf(events));
        }
    }

    public View start(ChainModule module, String commandId, ChainModule.Prepared prepared, NodeGateway.Target target,
                      String actingAs, String actor) {
        cleanup();
        Submission submission = new Submission(module.chainId(), commandId, prepared, target, actingAs, actor);
        submission.event(Stage.SUBMITTING, "Submitting through " + target.label());
        submissions.put(submission.id, submission);
        if (!inFlight.tryAcquire()) {
            submission.error = "The demo is busy; try again in a moment";
            submission.event(Stage.FAILED, submission.error);
            return submission.view();
        }
        workers.submit(() -> {
            try {
                run(submission);
            } catch (RuntimeException failure) {
                LOG.warnf("submission %s failed: %s", submission.id, failure.toString());
                submission.error = "Unexpected error while following the message";
                submission.event(Stage.FAILED, submission.error);
            } finally {
                inFlight.release();
            }
        });
        return submission.view();
    }

    public View find(String id) {
        Submission submission = submissions.get(id);
        return submission == null ? null : submission.view();
    }

    private void run(Submission s) {
        StateReader reader = new StateReader(gateway, json, s.target, s.chainId);
        Map<String, StateReader.Entry> snapshot = read(reader, s.prepared.probes(), LATEST);

        NodeGateway.NodeResponse response = gateway.submit(s.target, s.chainId, s.prepared.topic(),
                Hex.encode(s.prepared.body()));
        JsonNode body = parse(response.body());
        if (response.status() == 400) {
            s.error = body.path("code").asText(body.path("error").asText("rejected"));
            s.outcome = new ChainModule.Outcome(ChainModule.Status.REJECTED, "Refused at admission: " + s.error,
                    "The node validated the command before ordering it and refused it; nothing was recorded.",
                    List.of(), null);
            s.event(Stage.REFUSED, "Node refused the message (" + s.error + ")");
            return;
        }
        if (!response.ok() || !body.hasNonNull("messageId")) {
            s.error = response.status() == 429 ? "The chain's pending pool is full; try again shortly"
                    : "The node did not accept the message (HTTP " + response.status() + ")";
            s.event(Stage.FAILED, s.error);
            return;
        }
        s.messageId = body.get("messageId").asText();
        s.event(Stage.ADMITTED, "Admitted as message " + s.messageId.substring(0, 12) + "… and signed by "
                + s.actingAs);

        long deadline = System.currentTimeMillis() + FINALITY_TIMEOUT_MILLIS;
        JsonNode finalized = null;
        while (System.currentTimeMillis() < deadline) {
            NodeGateway.NodeResponse status = gateway.get(s.target,
                    "/api/v1/app-chain/chains/" + s.chainId + "/messages/" + s.messageId, Map.of());
            if (status.ok()) {
                finalized = parse(status.body());
                break;
            }
            sleep(1_000);
        }
        if (finalized == null) {
            s.error = "Not finalized within two minutes";
            s.event(Stage.FAILED, s.error);
            return;
        }
        s.height = finalized.path("height").asLong();
        s.index = finalized.path("index").asInt();
        String sender = finalized.path("sender").asText();
        s.event(Stage.FINALIZED, "Finalized in block " + s.height + " (position " + s.index + ") with a 4-of-5"
                + " finality certificate");

        Map<String, StateReader.Entry> before = read(reader, s.prepared.probes(), s.height - 1);
        if (before.values().stream().anyMatch(e -> "UNAVAILABLE".equals(e.presence()))) {
            before = snapshot;
        }
        Map<String, StateReader.Entry> after = read(reader, s.prepared.probes(), s.height);
        s.outcome = s.prepared.judge().judge(before, after,
                new ChainModule.Finalized(s.messageId, s.height, s.index, sender));
        s.event(Stage.EVALUATED, s.outcome.headline());
    }

    private static final long LATEST = -1;

    /** height LATEST = current tip; height 0 = before the first block (everything absent). */
    private static Map<String, StateReader.Entry> read(StateReader reader, List<ChainModule.Probe> probes, long height) {
        Map<String, StateReader.Entry> entries = new HashMap<>();
        for (ChainModule.Probe probe : probes) {
            entries.put(probe.label(), height == LATEST ? reader.latest(probe.key())
                    : height == 0 ? new StateReader.Entry("ABSENT", null, 0) : reader.entry(probe.key(), height));
        }
        return entries;
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception malformed) {
            return json.createObjectNode();
        }
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - KEEP_MILLIS;
        submissions.values().removeIf(s -> s.createdAt < cutoff);
        if (submissions.size() > MAX_TRACKED) {
            submissions.values().stream().sorted((a, b) -> Long.compare(a.createdAt, b.createdAt))
                    .limit(submissions.size() - MAX_TRACKED).map(s -> s.id).toList().forEach(submissions::remove);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
