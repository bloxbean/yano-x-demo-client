package com.bloxbean.yano.democlient.cardano;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Long actions (writes, transactions) run one at a time in the background, with a readable step log. */
final class Jobs {
    public interface Log {
        void step(String text);
    }

    public interface Work {
        JsonNode run(Log log) throws Exception;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Cardano transactions share the wallet's UTxOs, so they run one at a time; app-ledger writes run apart. */
    private final ExecutorService transactions = Executors.newSingleThreadExecutor();
    private final ExecutorService ledger = Executors.newSingleThreadExecutor();
    private final Map<String, String> unique = new java.util.HashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    private static final class Job {
        final String id;
        final String title;
        final List<String> log = new ArrayList<>();
        volatile String state = "queued";
        volatile JsonNode result;
        volatile String error;

        Job(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    /** A Cardano transaction job. */
    public String start(String title, Work work) {
        return submit(transactions, title, work);
    }

    /** An app-ledger job (writes, waiting for anchors). */
    public String startLedger(String title, Work work) {
        return submit(ledger, title, work);
    }

    /** One job per key at a time: a second request returns the running job. */
    public synchronized String startUnique(String key, String title, Work work) {
        String running = unique.get(key);
        if (running != null) {
            Job job = jobs.get(running);
            if (job != null && ("queued".equals(job.state) || "running".equals(job.state))) {
                return running;
            }
        }
        String id = submit(ledger, title, work);
        unique.put(key, id);
        return id;
    }

    private synchronized String submit(ExecutorService executor, String title, Work work) {
        Job job = new Job("job-" + ids.incrementAndGet(), title);
        jobs.put(job.id, job);
        while (jobs.size() > 60) {
            jobs.remove(jobs.keySet().iterator().next());
        }
        executor.submit(() -> {
            job.state = "running";
            Log log = text -> {
                synchronized (job.log) {
                    job.log.add(LocalTime.now().format(TIME) + "  " + text);
                }
            };
            try {
                job.result = work.run(log);
                job.state = "done";
            } catch (Throwable failure) {
                job.error = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                log.step("✗ " + job.error);
                job.state = "failed";
            }
        });
        return job.id;
    }

    public synchronized ObjectNode view(String id) {
        Job job = jobs.get(id);
        return job == null ? null : json(job);
    }

    public synchronized ArrayNode recent() {
        ArrayNode list = Json.MAPPER.createArrayNode();
        List<Job> all = new ArrayList<>(jobs.values());
        for (int i = all.size() - 1; i >= Math.max(0, all.size() - 12); i--) {
            list.add(json(all.get(i)));
        }
        return list;
    }

    private static ObjectNode json(Job job) {
        ObjectNode o = Json.object();
        o.put("id", job.id);
        o.put("title", job.title);
        o.put("state", job.state);
        ArrayNode log = o.putArray("log");
        synchronized (job.log) {
            job.log.forEach(log::add);
        }
        if (job.result != null) {
            o.set("result", job.result);
        }
        if (job.error != null) {
            o.put("error", job.error);
        }
        return o;
    }
}
