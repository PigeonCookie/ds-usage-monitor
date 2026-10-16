package com.pigeoncookie.dsmonitor.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregated platform usage, sliced per day and per day-dimension (API key / model).
 *
 * <p>Three sources feed the same structure:
 * <ul>
 *   <li>{@code /api/v0/usage/amount?month=&year=} — requests and tokens per day and per model;</li>
 *   <li>{@code /api/v0/usage/cost?month=&year=} — money per day and per model;</li>
 *   <li>{@code /api/v0/usage/export?start=&end=&tz=0} — CSV with per-day, per-model <em>and</em>
 *       per-API-key rows (the only source that can split by key).</li>
 * </ul>
 */
public final class UsageData {

    public static final int DIM_MODEL = 0;
    public static final int DIM_KEY = 1;

    /** Cost / tokens / requests triplet, with the token types kept apart for the stacked chart. */
    public static final class Slice {
        public double cost;
        public long tokens;
        public long requests;
        public long hitTokens;
        public long missTokens;
        public long outTokens;

        public void add(Slice o) {
            if (o == null) {
                return;
            }
            cost += o.cost;
            tokens += o.tokens;
            requests += o.requests;
            hitTokens += o.hitTokens;
            missTokens += o.missTokens;
            outTokens += o.outTokens;
        }

        public long tokenType(int which) {
            switch (which) {
                case TOKEN_HIT:
                    return hitTokens;
                case TOKEN_MISS:
                    return missTokens;
                default:
                    return outTokens;
            }
        }
    }

    public static final int TOKEN_HIT = 0;
    public static final int TOKEN_MISS = 1;
    public static final int TOKEN_OUT = 2;

    /** Classifies a usage type string from either endpoint into requests / hit / miss / output. */
    private static int classify(String type) {
        String t = type == null ? "" : type.toLowerCase();
        if (t.contains("request")) {
            return -1;
        }
        if (t.contains("hit")) {
            return TOKEN_HIT;
        }
        if (t.contains("miss")) {
            return TOKEN_MISS;
        }
        if (t.contains("response") || t.contains("output")) {
            return TOKEN_OUT;
        }
        return -2;
    }

    private static void addTokens(Slice slice, int kind, long amount) {
        slice.tokens += amount;
        switch (kind) {
            case TOKEN_HIT:
                slice.hitTokens += amount;
                break;
            case TOKEN_MISS:
                slice.missTokens += amount;
                break;
            case TOKEN_OUT:
                slice.outTokens += amount;
                break;
            default:
                break;
        }
    }

    public static final class Day {
        public String date;
        public final Slice total = new Slice();
        public final LinkedHashMap<String, Slice> byKey = new LinkedHashMap<>();
        public final LinkedHashMap<String, Slice> byModel = new LinkedHashMap<>();

        public Slice dim(int dimension, String item) {
            LinkedHashMap<String, Slice> map = dimension == DIM_KEY ? byKey : byModel;
            Slice s = map.get(item);
            if (s == null) {
                s = new Slice();
                map.put(item, s);
            }
            return s;
        }

        public Slice dimOrNull(int dimension, String item) {
            return (dimension == DIM_KEY ? byKey : byModel).get(item);
        }
    }

    public String currency = "CNY";
    public final TreeMap<String, Day> days = new TreeMap<>();
    public long fetchedAt;

    public Day day(String date) {
        String key = normalizeDate(date);
        if (key.isEmpty()) {
            return null;
        }
        Day d = days.get(key);
        if (d == null) {
            d = new Day();
            d.date = key;
            days.put(key, d);
        }
        return d;
    }

    public static String normalizeDate(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim().replace("-", "");
        if (s.length() >= 8) {
            return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
        }
        return "";
    }

    public void merge(UsageData other) {
        if (other == null) {
            return;
        }
        for (Map.Entry<String, Day> e : other.days.entrySet()) {
            Day src = e.getValue();
            Day dst = day(e.getKey());
            dst.total.add(src.total);
            mergeMap(dst.byKey, src.byKey);
            mergeMap(dst.byModel, src.byModel);
        }
        fetchedAt = Math.max(fetchedAt, other.fetchedAt);
    }

    private static void mergeMap(LinkedHashMap<String, Slice> dst, LinkedHashMap<String, Slice> src) {
        for (Map.Entry<String, Slice> e : src.entrySet()) {
            Slice s = dst.get(e.getKey());
            if (s == null) {
                s = new Slice();
                dst.put(e.getKey(), s);
            }
            s.add(e.getValue());
        }
    }

    // ------------------------------------------------------------------ queries

    public List<Day> range(String from, String to) {
        List<Day> out = new ArrayList<>();
        for (Day d : days.values()) {
            if (from != null && d.date.compareTo(from) < 0) {
                continue;
            }
            if (to != null && d.date.compareTo(to) > 0) {
                continue;
            }
            out.add(d);
        }
        return out;
    }

    /** All days (used for the "today" window, which may span two UTC dates). */
    public Slice sumAll() {
        Slice s = new Slice();
        for (Day d : days.values()) {
            s.add(d.total);
        }
        return s;
    }

    public Slice sumRange(String from, String to) {
        Slice s = new Slice();
        for (Day d : range(from, to)) {
            s.add(d.total);
        }
        return s;
    }

    /** Union of every API key seen (empty when only the month endpoints were used). */
    public LinkedHashSet<String> keys() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Day d : days.values()) {
            out.addAll(d.byKey.keySet());
        }
        return out;
    }

    public LinkedHashSet<String> models() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Day d : days.values()) {
            out.addAll(d.byModel.keySet());
        }
        return out;
    }

    /** Items of one dimension with their total cost, biggest first. */
    public List<Map.Entry<String, Double>> itemTotals(int dimension, String from, String to) {
        LinkedHashMap<String, Double> sums = new LinkedHashMap<>();
        for (Day d : range(from, to)) {
            LinkedHashMap<String, Slice> map = dimension == DIM_KEY ? d.byKey : d.byModel;
            for (Map.Entry<String, Slice> e : map.entrySet()) {
                Double prev = sums.get(e.getKey());
                sums.put(e.getKey(), (prev == null ? 0 : prev) + e.getValue().cost);
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

    // ------------------------------------------------------------------ month endpoints

    public void addAmountResponse(Object root) {
        Map<String, Object> biz = Json.firstMap(Json.at(root, "data.biz_data"));
        if (biz == null) {
            return;
        }
        for (Object dayObj : list(biz.get("days"))) {
            Day d = day(Json.strAt(dayObj, "date", ""));
            if (d == null) {
                continue;
            }
            for (Object modelObj : list(Json.at(dayObj, "data"))) {
                String model = Json.strAt(modelObj, "model", "");
                for (Object useObj : list(Json.at(modelObj, "usage"))) {
                    String type = Json.strAt(useObj, "type", "");
                    long n = Json.lngAt(useObj, "amount", 0);
                    int kind = classify(type);
                    if (kind == -1) {
                        d.total.requests += n;
                        if (!model.isEmpty()) {
                            d.dim(DIM_MODEL, model).requests += n;
                        }
                    } else {
                        addTokens(d.total, kind, n);
                        if (!model.isEmpty()) {
                            addTokens(d.dim(DIM_MODEL, model), kind, n);
                        }
                    }
                }
            }
        }
    }

    public void addCostResponse(Object root) {
        Map<String, Object> biz = Json.firstMap(Json.at(root, "data.biz_data"));
        if (biz == null) {
            return;
        }
        String cur = Json.strAt(biz, "currency", "");
        if (!cur.isEmpty()) {
            currency = cur;
        }
        for (Object dayObj : list(biz.get("days"))) {
            Day d = day(Json.strAt(dayObj, "date", ""));
            if (d == null) {
                continue;
            }
            for (Object modelObj : list(Json.at(dayObj, "data"))) {
                String model = Json.strAt(modelObj, "model", "");
                double sum = 0;
                for (Object useObj : list(Json.at(modelObj, "usage"))) {
                    if (!"REQUEST".equals(Json.strAt(useObj, "type", ""))) {
                        sum += Json.numAt(useObj, "amount", 0);
                    }
                }
                d.total.cost += sum;
                if (!model.isEmpty() && sum != 0) {
                    d.dim(DIM_MODEL, model).cost += sum;
                }
            }
        }
        // money reported only through the month-level total block (no per-day rows)
        double total = 0;
        for (Object modelObj : list(biz.get("total"))) {
            for (Object useObj : list(Json.at(modelObj, "usage"))) {
                if (!"REQUEST".equals(Json.strAt(useObj, "type", ""))) {
                    total += Json.numAt(useObj, "amount", 0);
                }
            }
        }
        double daySum = 0;
        for (Day d : days.values()) {
            daySum += d.total.cost;
        }
        monthTotalOnly = Math.max(0, total - daySum);
    }

    /** Money reported only through the month {@code total[]} block (no per-day rows). */
    public double monthTotalOnly;

    /** Whole-month money: per-day rows plus any month-level remainder. */
    public double monthCost() {
        double sum = monthTotalOnly;
        for (Day d : days.values()) {
            sum += d.total.cost;
        }
        return sum;
    }

    // ------------------------------------------------------------------ export CSV

    /** Usage-type totals from the last export parse (diagnostics). */
    public final LinkedHashMap<String, Double> typeAmounts = new LinkedHashMap<>();
    public final LinkedHashMap<String, Integer> typeKinds = new LinkedHashMap<>();

    /**
     * Parses an export CSV: one row per date × API key × model × usage type.
     *
     * <p>Row types are ranked by their own unit price instead of trusting the type string: cache-hit
     * input is by far the cheapest token, output the most expensive, cache-miss in between. The name
     * is only used as a fallback (and for single-type responses), so the 命中/未命中/输出 split stays
     * correct even if the export names its columns differently.
     */
    public void addExportCsv(String csv) {
        List<Map<String, String>> rows = DeepSeekClient.parseCsv(csv);

        LinkedHashMap<String, double[]> totals = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String type = trim(row.get("type"));
            if (type.isEmpty() || classify(type) == -1) {
                continue;
            }
            double amount = num(row.get("amount"));
            double[] agg = totals.get(type);
            if (agg == null) {
                agg = new double[2];
                totals.put(type, agg);
            }
            agg[0] += amount;
            agg[1] += num(row.get("price")) * amount;
        }
        // The platform names its token kinds explicitly; trust those names whenever they are all
        // recognised and fall back to the price ranking only for unknown vocabularies.
        boolean allNamed = !totals.isEmpty();
        for (String type : totals.keySet()) {
            int kind = classify(type);
            if (kind != TOKEN_HIT && kind != TOKEN_MISS && kind != TOKEN_OUT) {
                allNamed = false;
                break;
            }
        }
        LinkedHashMap<String, Integer> kinds = allNamed
                ? new LinkedHashMap<String, Integer>() : kindsFromPrices(totals);

        for (Map<String, String> row : rows) {
            String date = normalizeDate(row.get("utc_date"));
            if (date.isEmpty()) {
                date = normalizeDate(row.get("date"));
            }
            Day d = day(date);
            if (d == null) {
                continue;
            }
            String type = trim(row.get("type"));
            double amount = num(row.get("amount"));
            String model = trim(row.get("model"));
            String apiKey = trim(row.get("api_key_name"));
            Integer ranked = kinds.get(type);
            int kind = ranked != null ? ranked : classify(type);
            if (kind != -1) {
                Double prev = typeAmounts.get(type);
                typeAmounts.put(type, (prev == null ? 0 : prev) + amount);
                typeKinds.put(type, kind);
            }
            if (kind == -1) {
                d.total.requests += (long) amount;
                if (!apiKey.isEmpty()) {
                    d.dim(DIM_KEY, apiKey).requests += (long) amount;
                }
                if (!model.isEmpty()) {
                    d.dim(DIM_MODEL, model).requests += (long) amount;
                }
            } else {
                double cost = num(row.get("price")) * amount;
                d.total.cost += cost;
                addTokens(d.total, kind, (long) amount);
                if (!apiKey.isEmpty()) {
                    Slice s = d.dim(DIM_KEY, apiKey);
                    s.cost += cost;
                    addTokens(s, kind, (long) amount);
                }
                if (!model.isEmpty()) {
                    Slice s = d.dim(DIM_MODEL, model);
                    s.cost += cost;
                    addTokens(s, kind, (long) amount);
                }
            }
        }
    }

    /**
     * Adds another response under one local date. The export buckets rows by UTC day, so a range view
     * fetches every local day on its own window and merges it here — that is what makes the daily
     * bars line up with the console instead of being shifted by the UTC offset.
     */
    /** All parsed days, oldest first. */
    public java.util.Collection<Day> days() {
        return days.values();
    }

    public void addDayAs(String localDate, UsageData other) {
        if (other == null) {
            return;
        }
        Day target = day(localDate);
        for (Day source : other.days.values()) {
            addSlice(target.total, source.total);
            for (Map.Entry<String, Slice> e : source.byKey.entrySet()) {
                addSlice(target.dim(DIM_KEY, e.getKey()), e.getValue());
            }
            for (Map.Entry<String, Slice> e : source.byModel.entrySet()) {
                addSlice(target.dim(DIM_MODEL, e.getKey()), e.getValue());
            }
        }
    }

    private static void addSlice(Slice into, Slice from) {
        if (from == null) {
            return;
        }
        into.cost += from.cost;
        into.tokens += from.tokens;
        into.requests += from.requests;
        into.hitTokens += from.hitTokens;
        into.missTokens += from.missTokens;
        into.outTokens += from.outTokens;
    }

    /** Cheapest token type = cache hit, most expensive = output, anything between = cache miss. */
    private static LinkedHashMap<String, Integer> kindsFromPrices(LinkedHashMap<String, double[]> totals) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        List<String> priced = new ArrayList<>();
        for (Map.Entry<String, double[]> e : totals.entrySet()) {
            double[] v = e.getValue();
            if (v[0] > 0 && v[1] > 0) {
                priced.add(e.getKey());
            }
        }
        if (priced.size() < 2) {
            return out;
        }
        final LinkedHashMap<String, double[]> ref = totals;
        priced.sort(new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Double.compare(unitPrice(ref.get(a)), unitPrice(ref.get(b)));
            }
        });
        double cheapest = unitPrice(totals.get(priced.get(0)));
        double dearest = unitPrice(totals.get(priced.get(priced.size() - 1)));
        if (cheapest <= 0 || dearest <= cheapest * 1.05d) {
            return out; // prices carry no signal (e.g. everything free) — keep the name mapping
        }
        out.put(priced.get(0), TOKEN_HIT);
        out.put(priced.get(priced.size() - 1), TOKEN_OUT);
        for (int i = 1; i < priced.size() - 1; i++) {
            out.put(priced.get(i), TOKEN_MISS);
        }
        return out;
    }

    private static double unitPrice(double[] agg) {
        return agg == null || agg[0] <= 0 ? 0d : agg[1] / agg[0];
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    /** Per-day totals of the last export parse (diagnostics: shows which dates came back). */
    public String usageDateSummary() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Day d : days.values()) {
            if (i++ > 0) {
                sb.append(" · ");
            }
            sb.append(d.date).append("= tokens ").append(Fmt.group(d.total.tokens))
                    .append(" / ¥").append(Fmt.money2(d.total.cost));
            if (i >= 4) {
                break;
            }
        }
        return sb.toString();
    }

    /** Human readable type breakdown of the last export (shown by 测试连接). */
    public String usageTypeSummary() {
        List<Map.Entry<String, Double>> list = new ArrayList<>(typeAmounts.entrySet());
        list.sort(new java.util.Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Double> e : list) {
            if (i++ > 0) {
                sb.append(" · ");
            }
            sb.append(e.getKey()).append('=')
                    .append(Fmt.group((long) (double) e.getValue()))
                    .append('[').append(kindName(typeKinds.get(e.getKey()))).append(']');
            if (i >= 4) {
                break;
            }
        }
        return sb.toString();
    }

    private static String kindName(Integer kind) {
        if (kind == null) {
            return "?";
        }
        switch (kind) {
            case TOKEN_HIT:
                return "命中";
            case TOKEN_MISS:
                return "未命中";
            case TOKEN_OUT:
                return "输出";
            default:
                return "其他";
        }
    }

    /** Sum of one dimension item over the given window (used to size chart series). */
    public double itemCost(int dimension, String item, String from, String to) {
        double sum = 0;
        for (Day d : range(from, to)) {
            Slice s = d.dimOrNull(dimension, item);
            if (s != null) {
                sum += s.cost;
            }
        }
        return sum;
    }

    /** Cost per dimension item over a window (missing items are absent). */
    public LinkedHashMap<String, Double> itemCostMap(int dimension, String from, String to) {
        LinkedHashMap<String, Double> sums = new LinkedHashMap<>();
        for (Day d : range(from, to)) {
            LinkedHashMap<String, Slice> map = dimension == DIM_KEY ? d.byKey : d.byModel;
            for (Map.Entry<String, Slice> e : map.entrySet()) {
                Double prev = sums.get(e.getKey());
                sums.put(e.getKey(), (prev == null ? 0 : prev) + e.getValue().cost);
            }
        }
        return sums;
    }

    /** Cost per dimension item across every loaded day (used by the "today" window). */
    public LinkedHashMap<String, Double> itemCostMapAll(int dimension) {
        return itemCostMap(dimension, null, null);
    }

    /**
     * Per-item stat tuples {@code [cost, cacheHit, cacheMiss, output, requests]} — the shape the
     * local sampler stores so hourly charts can be split by the selected items.
     */
    public LinkedHashMap<String, double[]> itemStats(int dimension, String from, String to) {
        LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
        for (Day d : range(from, to)) {
            LinkedHashMap<String, Slice> map = dimension == DIM_KEY ? d.byKey : d.byModel;
            for (Map.Entry<String, Slice> e : map.entrySet()) {
                double[] stat = out.get(e.getKey());
                if (stat == null) {
                    stat = new double[SampleStore.STAT_COUNT];
                    out.put(e.getKey(), stat);
                }
                Slice s = e.getValue();
                stat[SampleStore.STAT_COST] += s.cost;
                stat[SampleStore.STAT_HIT] += s.hitTokens;
                stat[SampleStore.STAT_MISS] += s.missTokens;
                stat[SampleStore.STAT_OUT] += s.outTokens;
                stat[SampleStore.STAT_REQUESTS] += s.requests;
            }
        }
        return out;
    }

    public LinkedHashMap<String, double[]> itemStatsAll(int dimension) {
        return itemStats(dimension, null, null);
    }

    /** One dimension item's totals over a window (null window = every loaded day). */
    public Slice slice(int dimension, String item, String from, String to) {
        Slice out = new Slice();
        for (Day d : range(from, to)) {
            out.add(d.dimOrNull(dimension, item));
        }
        return out;
    }

    public Slice sliceAll(int dimension, String item) {
        return slice(dimension, item, null, null);
    }

    private static List<Object> list(Object o) {
        List<Object> l = Json.asList(o);
        return l == null ? new ArrayList<Object>() : l;
    }

    private static double num(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
