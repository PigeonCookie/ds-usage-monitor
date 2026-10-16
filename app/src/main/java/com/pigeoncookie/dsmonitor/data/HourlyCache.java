package com.pigeoncookie.dsmonitor.data;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-hour usage of one local day, fetched straight from the platform.
 *
 * <p>Flat, self-describing lines — one per hour and item, so nothing depends on nested syntax:
 * <pre>hour|kind|name|cost|tokens|requests|hit|miss|out</pre>
 * where {@code kind} is {@code total}, {@code key} or {@code model} (a total line is written for
 * every hour as well). Every value is the export window {@code [H:00, H+1:00)} of that hour, which is
 * exactly the bucket the console shows.
 */
public final class HourlyCache {

    /** Field indices inside a six-value row {@code [cost, tokens, requests, hit, miss, out]}. */
    public static final int COST = 0;
    public static final int TOKENS = 1;
    public static final int REQUESTS = 2;
    public static final int HIT = 3;
    public static final int MISS = 4;
    public static final int OUT = 5;
    public static final int FIELDS = 6;

    /** Bumped whenever the layout changes, so a stale file is ignored instead of misread. */
    private static final int FORMAT = 3;

    public static final class Hour {
        public int hour;
        public double cost;
        public long tokens;
        public long requests;
        public long hitTokens;
        public long missTokens;
        public long outTokens;
        /** True when the local sampling chain was patchy for this hour (value comes from the platform). */
        public boolean approx;
        public final LinkedHashMap<String, double[]> keys = new LinkedHashMap<>();
        public final LinkedHashMap<String, double[]> models = new LinkedHashMap<>();

        public double field(int field) {
            switch (field) {
                case REQUESTS:
                    return requests;
                case HIT:
                    return hitTokens;
                case MISS:
                    return missTokens;
                case OUT:
                    return outTokens;
                case TOKENS:
                    return tokens;
                default:
                    return cost;
            }
        }

        public double item(int dimension, String name, int field) {
            double[] v = (dimension == 1 ? keys : models).get(name);
            return v == null || field >= v.length ? 0d : v[field];
        }
    }

    private HourlyCache() {
    }

    /** Local calendar day, e.g. 2026-10-16. */
    public static String day() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static File file(Context c) {
        return file(c, day());
    }

    private static File file(Context c, String isoDay) {
        return new File(c.getFilesDir(), "hourly-v" + FORMAT + "-" + isoDay + ".jsonl");
    }

    public static synchronized LinkedHashMap<Integer, Hour> load(Context c) {
        return load(c, day());
    }

    /** Same, for any local day ({@code yyyy-MM-dd}). */
    public static synchronized LinkedHashMap<Integer, Hour> load(Context c, String isoDay) {
        LinkedHashMap<Integer, Hour> out = new LinkedHashMap<>();
        File f = file(c, isoDay);
        if (!f.exists()) {
            return out;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            return parse(text.toString());
        } catch (Exception ignored) {
            // a broken cache only costs one refetch
        } finally {
            close(reader);
        }
        return out;
    }

    /** Parses the on-disk text (pure, so it can be unit tested without a device). */
    public static LinkedHashMap<Integer, Hour> parse(String text) {
        LinkedHashMap<Integer, Hour> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String[] p = line.split("\\|", -1);
            if (p.length < 9) {
                continue;
            }
            try {
                int hour = Integer.parseInt(p[0].trim());
                Hour h = out.get(hour);
                if (h == null) {
                    h = new Hour();
                    h.hour = hour;
                    out.put(hour, h);
                }
                String kind = p[1];
                double[] values = new double[]{
                        Double.parseDouble(p[3].trim()), Double.parseDouble(p[4].trim()),
                        Double.parseDouble(p[5].trim()), Double.parseDouble(p[6].trim()),
                        Double.parseDouble(p[7].trim()), Double.parseDouble(p[8].trim())};
                if ("total".equals(kind)) {
                    if (p.length > 9) {
                        h.approx = "1".equals(p[9].trim());
                    }
                    h.cost = values[COST];
                    h.tokens = (long) values[TOKENS];
                    h.requests = (long) values[REQUESTS];
                    h.hitTokens = (long) values[HIT];
                    h.missTokens = (long) values[MISS];
                    h.outTokens = (long) values[OUT];
                } else if ("key".equals(kind)) {
                    h.keys.put(p[2], values);
                } else if ("model".equals(kind)) {
                    h.models.put(p[2], values);
                }
            } catch (NumberFormatException ignored) {
                // skip a malformed line, keep the rest of the day
            }
        }
        return out;
    }

    public static synchronized void put(Context c, int hour, Hour value) {
        put(c, day(), hour, value);
    }

    public static synchronized void put(Context c, String isoDay, int hour, Hour value) {
        LinkedHashMap<Integer, Hour> all = load(c, isoDay);
        value.hour = hour;
        all.put(hour, value);
        write(c, isoDay, all);
    }

    public static synchronized void clear(Context c) {
        clear(c, day());
    }

    public static synchronized void clear(Context c, String isoDay) {
        File f = file(c, isoDay);
        if (f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }

    /** True when the cache holds at least one hour and was not disabled by the sanity check. */
    public static boolean usable(Context c) {
        return usable(c, day());
    }

    public static boolean usable(Context c, String isoDay) {
        return !disabled(c, isoDay) && !load(c, isoDay).isEmpty();
    }

    private static File flagFile(Context c) {
        return flagFile(c, day());
    }

    private static File flagFile(Context c, String isoDay) {
        return new File(c.getFilesDir(), "hourly-off-" + isoDay);
    }

    public static boolean disabled(Context c) {
        return disabled(c, day());
    }

    public static boolean disabled(Context c, String isoDay) {
        // 1.2.6/55 removed the day-wide disable: any flag left over from an older build is dropped
        // here, otherwise it would keep blocking today's hourly fill and the view would stay sampled.
        File stale = flagFile(c, isoDay);
        if (stale.exists()) {
            if (!stale.delete()) {
                stale.deleteOnExit();
            }
            return false;
        }
        return false;
    }

    public static void disable(Context c) {
        disable(c, day());
    }

    public static void disable(Context c, String isoDay) {
        clear(c, isoDay);
        try {
            if (!flagFile(c, isoDay).createNewFile()) {
                flagFile(c, isoDay).setLastModified(System.currentTimeMillis());
            }
        } catch (Exception ignored) {
            // worst case the sanity check runs again on the next refresh
        }
    }

    /**
     * Deletes the cache files of days older than {@code keepDays} (one file is written per day, so
     * without this they would pile up in the app's private directory forever).
     */
    public static synchronized void prune(Context c, int keepDays) {
        File dir = c.getFilesDir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - keepDays * 24L * 3600_000L;
        String prefix = "hourly-v" + FORMAT + "-";
        for (File f : files) {
            String name = f.getName();
            boolean hourly = name.startsWith(prefix) && name.endsWith(".jsonl");
            boolean flag = name.startsWith("hourly-off-");
            if (!hourly && !flag) {
                continue;
            }
            String stamp = hourly
                    ? name.substring(prefix.length(), name.length() - ".jsonl".length())
                    : name.substring("hourly-off-".length());
            if (dayMillis(stamp) < cutoff) {
                if (!f.delete()) {
                    f.deleteOnExit();
                }
            }
        }
    }

    private static long dayMillis(String isoDay) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(Integer.parseInt(isoDay.substring(0, 4)), Integer.parseInt(isoDay.substring(5, 7)) - 1,
                    Integer.parseInt(isoDay.substring(8, 10)), 0, 0, 0);
            return cal.getTimeInMillis();
        } catch (RuntimeException e) {
            return Long.MAX_VALUE; // an unparsable name is left alone
        }
    }

    /** Diagnostic string: the raw per-hour numbers this app fetched from the platform. */
    public static String summary(Context c) {
        LinkedHashMap<Integer, Hour> all = load(c);
        List<Map.Entry<Integer, Hour>> list = new ArrayList<>(all.entrySet());
        list.sort(new java.util.Comparator<Map.Entry<Integer, Hour>>() {
            @Override
            public int compare(Map.Entry<Integer, Hour> a, Map.Entry<Integer, Hour> b) {
                return Integer.compare(b.getKey(), a.getKey());
            }
        });
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<Integer, Hour> e : list) {
            if (i++ > 0) {
                sb.append(" · ");
            }
            Hour h = e.getValue();
            sb.append(String.format(Locale.US, "%02d:00", e.getKey()))
                    .append("=").append(h.tokens)
                    .append("(命中").append(h.hitTokens).append("/未命中").append(h.missTokens)
                    .append("/输出").append(h.outTokens).append(")")
                    .append(h.keys.size() + "Key");
            if (i >= 5) {
                break;
            }
        }
        return sb.toString();
    }

    private static void write(Context c, LinkedHashMap<Integer, Hour> all) {
        write(c, day(), all);
    }

    private static void write(Context c, String isoDay, LinkedHashMap<Integer, Hour> all) {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file(c, isoDay)), StandardCharsets.UTF_8);
            writer.write(format(all));
        } catch (Exception ignored) {
            // keep whatever was on disk
        } finally {
            close(writer);
        }
    }

    /** Serialises the cache (pure, so it can be unit tested without a device). */
    public static String format(LinkedHashMap<Integer, Hour> all) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Hour> e : all.entrySet()) {
            Hour h = e.getValue();
            head(sb, h.hour, "total", "");
            sb.append(h.cost).append('|').append(h.tokens).append('|').append(h.requests)
                    .append('|').append(h.hitTokens).append('|').append(h.missTokens)
                    .append('|').append(h.outTokens).append('|').append(h.approx ? 1 : 0)
                    .append('\n');
            for (Map.Entry<String, double[]> item : h.keys.entrySet()) {
                itemLine(sb, h.hour, "key", item);
            }
            for (Map.Entry<String, double[]> item : h.models.entrySet()) {
                itemLine(sb, h.hour, "model", item);
            }
        }
        return sb.toString();
    }

    private static void head(StringBuilder sb, int hour, String kind, String name) {
        sb.append(hour).append('|').append(kind).append('|')
                .append(name.replace('|', '_').replace('\n', '_')).append('|');
    }

    private static void itemLine(StringBuilder sb, int hour, String kind, Map.Entry<String, double[]> item) {
        double[] v = item.getValue();
        head(sb, hour, kind, item.getKey());
        sb.append(v[COST]).append('|').append((long) v[TOKENS]).append('|').append((long) v[REQUESTS])
                .append('|').append((long) v[HIT]).append('|').append((long) v[MISS])
                .append('|').append((long) v[OUT]).append('\n');
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }
}
