package com.pigeoncookie.dsmonitor.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Local sampling history.
 *
 * <p>The export endpoint only reports whole days, so the "today / per hour" view is built from
 * cumulative samples this app records on every refresh (and every hour when background sampling is
 * enabled). Every sample keeps, besides the totals, a per-API-key and per-model stat tuple
 * {@code [cost, cacheHit, cacheMiss, output, requests]}, so the hourly charts can be split by the
 * items selected in the header (and stay consistent with the summary cards).
 */
public final class SampleStore {

    public static final int FIELD_COST = 0;
    public static final int FIELD_REQUESTS = 1;
    public static final int FIELD_TOKENS = 2;

    /** Indices inside a sample's per-item stat tuple. */
    public static final int STAT_COST = 0;
    public static final int STAT_HIT = 1;
    public static final int STAT_MISS = 2;
    public static final int STAT_OUT = 3;
    public static final int STAT_REQUESTS = 4;
    public static final int STAT_COUNT = 5;

    private static final String FILE_NAME = "samples.jsonl";
    private static final long KEEP_MS = 15L * 24 * 3600 * 1000;
    private static final int MAX_LINES = 1200;
    /** Samples older than this are dropped: the cumulative figure stays within a week. */
    private static final long MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    public static final class Sample {
        public long t;
        public double cost;
        public long tokens;
        public long requests;
        public long hitTokens;
        public long missTokens;
        public long outTokens;
        public final LinkedHashMap<String, double[]> modelStats = new LinkedHashMap<>();
        public final LinkedHashMap<String, double[]> keyStats = new LinkedHashMap<>();

        public double value(int field) {
            switch (field) {
                case FIELD_REQUESTS:
                    return requests;
                case FIELD_TOKENS:
                    return tokens;
                default:
                    return cost;
            }
        }

        public long tokenType(int which) {
            switch (which) {
                case UsageData.TOKEN_HIT:
                    return hitTokens;
                case UsageData.TOKEN_MISS:
                    return missTokens;
                default:
                    return outTokens;
            }
        }

        private LinkedHashMap<String, double[]> stats(int dimension) {
            return dimension == UsageData.DIM_KEY ? keyStats : modelStats;
        }

        public double itemStat(int dimension, String item, int stat) {
            double[] s = stats(dimension).get(item);
            return s == null || stat >= s.length ? 0d : s[stat];
        }

        public double itemTokens(int dimension, String item, int tokenType) {
            return itemStat(dimension, item, STAT_HIT + tokenType);
        }

        public LinkedHashSet<String> items(int dimension) {
            return new LinkedHashSet<>(stats(dimension).keySet());
        }
    }

    private SampleStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    public static synchronized void append(Context c, double cost, long tokens, long requests,
            long hitTokens, long missTokens, long outTokens,
            Map<String, double[]> keyStats, Map<String, double[]> modelStats) {
        List<Sample> list = load(c);
        Sample s = new Sample();
        s.t = System.currentTimeMillis();
        s.cost = cost;
        s.tokens = tokens;
        s.requests = requests;
        s.hitTokens = hitTokens;
        s.missTokens = missTokens;
        s.outTokens = outTokens;
        if (keyStats != null) {
            s.keyStats.putAll(keyStats);
        }
        if (modelStats != null) {
            s.modelStats.putAll(modelStats);
        }
        list.add(s);
        pruneByAge(list);
        while (list.size() > MAX_LINES) {
            list.remove(0);
        }
        write(c, list);
    }

    /** Inserts a sample at a specific timestamp, keeping the file ordered by time. */
    public static synchronized void insert(Context c, long timestamp, double cost, long tokens,
            long requests, long hitTokens, long missTokens, long outTokens,
            Map<String, double[]> keyStats, Map<String, double[]> modelStats) {
        List<Sample> list = load(c);
        Sample s = new Sample();
        s.t = timestamp;
        s.cost = cost;
        s.tokens = tokens;
        s.requests = requests;
        s.hitTokens = hitTokens;
        s.missTokens = missTokens;
        s.outTokens = outTokens;
        if (keyStats != null) {
            s.keyStats.putAll(keyStats);
        }
        if (modelStats != null) {
            s.modelStats.putAll(modelStats);
        }
        int at = list.size();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).t > timestamp) {
                at = i;
                break;
            }
        }
        list.add(at, s);
        pruneByAge(list);
        while (list.size() > MAX_LINES) {
            list.remove(0);
        }
        write(c, list);
    }

    public static synchronized List<Sample> load(Context c) {
        List<Sample> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) {
            return out;
        }
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Object o = Json.parse(line);
                Sample s = new Sample();
                s.t = Json.lngAt(o, "t", 0);
                s.cost = Json.numAt(o, "c", 0);
                s.tokens = Json.lngAt(o, "k", 0);
                s.requests = Json.lngAt(o, "r", 0);
                s.hitTokens = Json.lngAt(o, "h", 0);
                s.missTokens = Json.lngAt(o, "m", 0);
                s.outTokens = Json.lngAt(o, "o", 0);
                readStats(Json.at(o, "ks"), s.keyStats);
                readStats(Json.at(o, "ms"), s.modelStats);
                readStats(Json.at(o, "kc"), s.keyStats, STAT_COST); // v1.1.x samples: cost only
                readStats(Json.at(o, "mc"), s.modelStats, STAT_COST);
                if (s.t >= cutoff) {
                    out.add(s);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // a corrupt history must never break the UI
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
        return out;
    }

    private static void readStats(Object o, LinkedHashMap<String, double[]> into) {
        readStats(o, into, -1);
    }

    /** @param onlyStat when >= 0 the value is a plain number written at that stat index */
    private static void readStats(Object o, LinkedHashMap<String, double[]> into, int onlyStat) {
        Map<String, Object> map = Json.asMap(o);
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> e : map.entrySet()) {
            double[] target = into.get(e.getKey());
            if (target == null) {
                target = new double[STAT_COUNT];
                into.put(e.getKey(), target);
            }
            if (onlyStat >= 0) {
                target[onlyStat] += Json.num(e.getValue(), 0);
                continue;
            }
            List<Object> values = Json.asList(e.getValue());
            if (values == null) {
                continue;
            }
            for (int i = 0; i < values.size() && i < STAT_COUNT; i++) {
                target[i] += Json.num(values.get(i), 0);
            }
        }
    }

    public static synchronized int count(Context c) {
        return load(c).size();
    }

    public static synchronized Sample latest(Context c) {
        List<Sample> list = load(c);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    public static synchronized List<Sample> today(Context c) {
        return samplesOn(c, 0);
    }

    /** Drops samples older than a week (oldest first, the list is chronological). */
    private static void pruneByAge(List<Sample> list) {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        while (!list.isEmpty() && list.get(0).t < cutoff) {
            list.remove(0);
        }
    }

    /** Samples of one local day: {@code dayOffset} 0 = today, -1 = yesterday, … */
    public static synchronized List<Sample> samplesOn(Context c, int dayOffset) {
        List<Sample> out = new ArrayList<>();
        Calendar now = Calendar.getInstance();
        now.add(Calendar.DAY_OF_MONTH, dayOffset);
        for (Sample s : load(c)) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(s.t);
            if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Kinds accepted by {@link #hourlyFor}. */
    public static final int KIND_COST = 0;
    public static final int KIND_REQUESTS = 1;
    public static final int KIND_TOKENS = 2;
    public static final int KIND_HIT = 3;
    public static final int KIND_MISS = 4;
    public static final int KIND_OUT = 5;

    /**
     * 24 hourly buckets of one local day ({@code dayOffset} 0 = today, -1 = yesterday) for the given
     * items and kind. Sums the selected items first, then applies the same delta spreading as today.
     */
    public static double[] hourlyFor(Context c, final int dayOffset, final int dimension,
            final List<String> items, final int kind) {
        return hourlySeries(c, dayOffset, new Value[]{new Value() {
            @Override
            public double get(Sample s) {
                if (items == null || items.isEmpty()) {
                    switch (kind) {
                        case KIND_REQUESTS:
                            return s.requests;
                        case KIND_TOKENS:
                            return s.tokens;
                        case KIND_HIT:
                            return s.hitTokens;
                        case KIND_MISS:
                            return s.missTokens;
                        case KIND_OUT:
                            return s.outTokens;
                        default:
                            return s.cost;
                    }
                }
                double sum = 0;
                for (String item : items) {
                    switch (kind) {
                        case KIND_REQUESTS:
                            sum += s.itemStat(dimension, item, STAT_REQUESTS);
                            break;
                        case KIND_TOKENS:
                            sum += s.itemStat(dimension, item, STAT_HIT)
                                    + s.itemStat(dimension, item, STAT_MISS)
                                    + s.itemStat(dimension, item, STAT_OUT);
                            break;
                        case KIND_HIT:
                            sum += s.itemStat(dimension, item, STAT_HIT);
                            break;
                        case KIND_MISS:
                            sum += s.itemStat(dimension, item, STAT_MISS);
                            break;
                        case KIND_OUT:
                            sum += s.itemStat(dimension, item, STAT_OUT);
                            break;
                        default:
                            sum += s.itemStat(dimension, item, STAT_COST);
                            break;
                    }
                }
                return sum;
            }
        }});
    }

    /** Reads one value out of a sample (used to build an hourly series). */
    private interface Value {
        double get(Sample s);
    }

    /**
     * Builds a 24-slot hourly series from the cumulative samples of today.
     *
     * <p>The increase between two samples is spread over the hours it actually covers, weighted by
     * how much of each hour that interval contains. Dumping the whole increase into the hour of the
     * later sample is what made a gap (app closed over lunch, sampling stopped) inflate one bucket
     * and leave the neighbouring ones empty.
     */
    private static double[] hourlySeries(Context c, Value[] values) {
        return hourlySeries(c, 0, values);
    }

    private static double[] hourlySeries(Context c, int dayOffset, Value[] values) {
        double[] out = new double[24];
        double[] prev = new double[values.length];
        long prevT = 0;
        for (Sample s : samplesOn(c, dayOffset)) {
            for (int k = 0; k < values.length; k++) {
                double v = values[k].get(s);
                double delta = v - prev[k];
                if (delta < 0) {
                    delta = v; // day rolled over / counter reset
                }
                if (delta != 0) {
                    // 首个采样之前的时段本来就没有观测值，绝不能把这段增量摊到 0 点之后，
                    // 否则会凭空造出凌晨几个小时的小柱子（官网那几小时其实是 0）。
                    spread(out, prevT == 0 ? s.t : prevT, s.t, delta);
                }
                prev[k] = v;
            }
            prevT = s.t;
        }
        return out;
    }

    /** Distributes a delta over the hours covered by [fromMs, toMs), weighted by overlap. */
    private static void spread(double[] out, long fromMs, long toMs, double delta) {
        if (delta == 0) {
            return;
        }
        if (toMs <= fromMs) {
            out[hourOf(toMs)] += delta;
            return;
        }
        int first = hourOf(fromMs);
        int last = hourOf(toMs - 1);
        if (first == last) {
            out[first] += delta;
            return;
        }
        long midnight = midnightOf(toMs);
        double total = toMs - fromMs;
        for (int h = first; h <= last && h < out.length; h++) {
            long hourStart = midnight + h * 3600_000L;
            long overlap = Math.min(hourStart + 3600_000L, toMs) - Math.max(hourStart, fromMs);
            if (overlap > 0) {
                out[h] += delta * overlap / total;
            }
        }
    }

    private static long midnightOf(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 24 hourly buckets for "today", derived from cumulative deltas between samples. */
    public static double[] hourly(Context c, final int field) {
        return hourlySeries(c, new Value[]{new Value() {
            @Override
            public double get(Sample s) {
                return s.value(field);
            }
        }});
    }

    /** 24 hourly buckets for one token type across all keys (fallback when no item split exists). */
    public static double[] hourlyTokenTotals(Context c, final int tokenType) {
        return hourlySeries(c, new Value[]{new Value() {
            @Override
            public double get(Sample s) {
                return s.tokenType(tokenType);
            }
        }});
    }

    /** 24 hourly buckets for one token type (0 hit, 1 miss, 2 out) across the given items. */
    public static double[] hourlyTokens(Context c, final int dimension, final List<String> items,
            final int tokenType) {
        if (items == null || items.isEmpty()) {
            return new double[24];
        }
        return hourlySeries(c, new Value[]{new Value() {
            @Override
            public double get(Sample s) {
                double sum = 0;
                for (String item : items) {
                    sum += s.itemTokens(dimension, item, tokenType);
                }
                return sum;
            }
        }});
    }

    /** 24 hourly request buckets across the given items. */
    public static double[] hourlyRequests(Context c, final int dimension, final List<String> items) {
        if (items == null || items.isEmpty()) {
            return new double[24];
        }
        return hourlySeries(c, new Value[]{new Value() {
            @Override
            public double get(Sample s) {
                double sum = 0;
                for (String item : items) {
                    sum += s.itemStat(dimension, item, STAT_REQUESTS);
                }
                return sum;
            }
        }});
    }

    /** 24 hourly cost buckets across the given items. */
    public static double[] hourlyCost(Context c, final int dimension, final List<String> items) {
        if (items == null || items.isEmpty()) {
            return new double[24];
        }
        return hourlySeries(c, new Value[]{new Value() {
            @Override
            public double get(Sample s) {
                double sum = 0;
                for (String item : items) {
                    sum += s.itemStat(dimension, item, STAT_COST);
                }
                return sum;
            }
        }});
    }

    /** Items (keys or models) seen in today's samples with their total cost, biggest first. */
    public static List<Map.Entry<String, Double>> todayItemTotals(Context c, int dimension) {
        LinkedHashMap<String, Double> sums = new LinkedHashMap<>();
        for (Sample s : today(c)) {
            for (Map.Entry<String, double[]> e : s.stats(dimension).entrySet()) {
                Double prev = sums.get(e.getKey());
                sums.put(e.getKey(), (prev == null ? 0 : prev) + e.getValue()[STAT_COST]);
            }
        }
        List<Map.Entry<String, Double>> out = new ArrayList<>(sums.entrySet());
        out.sort(new java.util.Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });
        return out;
    }

    /** True when today's samples carry per-item data (i.e. they were written by v1.2+). */
    public static boolean hasItemStats(Context c, int dimension) {
        for (Sample s : today(c)) {
            if (!s.stats(dimension).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int hourOf(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    private static void write(Context c, List<Sample> list) {
        StringBuilder sb = new StringBuilder();
        for (Sample s : list) {
            sb.append("{\"t\":").append(s.t)
                    .append(",\"c\":").append(s.cost)
                    .append(",\"k\":").append(s.tokens)
                    .append(",\"r\":").append(s.requests)
                    .append(",\"h\":").append(s.hitTokens)
                    .append(",\"m\":").append(s.missTokens)
                    .append(",\"o\":").append(s.outTokens);
            if (!s.keyStats.isEmpty()) {
                sb.append(",\"ks\":").append(statsToJson(s.keyStats));
            }
            if (!s.modelStats.isEmpty()) {
                sb.append(",\"ms\":").append(statsToJson(s.modelStats));
            }
            sb.append("}\n");
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(c), false);
            OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write(sb.toString());
            w.flush();
        } catch (IOException ignored) {
            // best effort
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    private static String statsToJson(LinkedHashMap<String, double[]> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, double[]> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            double[] v = e.getValue();
            sb.append(Json.quote(e.getKey())).append(":[");
            for (int i = 0; i < STAT_COUNT; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(i < v.length ? v[i] : 0);
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }
}
