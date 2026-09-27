package com.bloxbean.yano.democlient.web;

import com.bloxbean.yano.democlient.config.DemoConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding one-minute window for chain writes: per client and for the whole app. The demo cluster is public, so every
 * submission the app makes is bounded here; reads are not limited.
 */
@ApplicationScoped
public class RateLimiter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final DemoConfig config;
    private final Map<String, Deque<Long>> perClient = new ConcurrentHashMap<>();
    private final Deque<Long> global = new ArrayDeque<>();

    public RateLimiter(DemoConfig config) {
        this.config = config;
    }

    /** Records a write if allowed; returns seconds to wait (0 = allowed). */
    public synchronized long acquire(String client) {
        long now = System.currentTimeMillis();
        prune(global, now);
        if (global.size() >= config.rateLimit().globalPerMinute()) {
            return retryAfter(global, now);
        }
        if (perClient.size() > MAX_TRACKED_CLIENTS) {
            perClient.entrySet().removeIf(entry -> {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            });
        }
        Deque<Long> window = perClient.computeIfAbsent(client, ignored -> new ArrayDeque<>());
        prune(window, now);
        if (window.size() >= config.rateLimit().perClientPerMinute()) {
            return retryAfter(window, now);
        }
        window.addLast(now);
        global.addLast(now);
        return 0;
    }

    private static void prune(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
            window.removeFirst();
        }
    }

    private static long retryAfter(Deque<Long> window, long now) {
        return Math.max(1, (WINDOW_MILLIS - (now - window.peekFirst()) + 999) / 1000);
    }
}
