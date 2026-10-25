package com.github.xandergos.terraindiffusionmc.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Where Terra's generation time goes: named stages add their time here, and once a minute (while anything is being
 * generated) one log line lists them by total time, e.g.
 * {@code Terra timing (60 s): tile.wait 41.2 s (310x, 133 ms avg) | tile.model 9.1 s (12x) | ...}.
 * Stages starting with {@code tile.} run on the single inference thread (they add up to its busy time);
 * {@code tile.wait} is chunk threads waiting for a tile; {@code chunk.} stages run per chunk on the worker threads.
 * Off with {@code debug.timing=false}.
 */
public final class TerraPerf {
    private static final Logger LOG = LoggerFactory.getLogger("Terra");
    private static final long PERIOD_NS = 60_000_000_000L;

    private static final Map<String, LongAdder[]> STAGES = new ConcurrentHashMap<>();
    private static final AtomicLong periodStart = new AtomicLong(System.nanoTime());
    private static final boolean ON = com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig.timing();

    private TerraPerf() {
    }

    /** Start time for {@link #end}, or 0 when timing is off. */
    public static long start() {
        return ON ? System.nanoTime() : 0L;
    }

    /** Adds the time since {@code t0} (from {@link #start}) to {@code stage}. */
    public static void end(String stage, long t0) {
        if (t0 == 0L) return;
        long now = System.nanoTime();
        LongAdder[] a = STAGES.computeIfAbsent(stage, k -> new LongAdder[]{new LongAdder(), new LongAdder()});
        a[0].add(now - t0);
        a[1].increment();
        long p = periodStart.get();
        if (now - p >= PERIOD_NS && periodStart.compareAndSet(p, now)) report((now - p) / 1e9);
    }

    private static void report(double seconds) {
        List<String[]> rows = new ArrayList<>();
        List<Long> totals = new ArrayList<>();
        for (Map.Entry<String, LongAdder[]> e : STAGES.entrySet()) {
            long ns = e.getValue()[0].sumThenReset();
            long n = e.getValue()[1].sumThenReset();
            if (n == 0) continue;
            totals.add(ns);
            rows.add(new String[]{e.getKey(), String.format("%s %.1f s (%dx, %.1f ms avg)", e.getKey(), ns / 1e9, n, ns / 1e6 / n)});
        }
        if (rows.isEmpty()) return;
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Long.compare(totals.get(y), totals.get(x)));
        StringBuilder sb = new StringBuilder();
        for (int i : order) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(rows.get(i)[1]);
        }
        LOG.info("Terra timing ({} s): {}", Math.round(seconds), sb);
    }
}
