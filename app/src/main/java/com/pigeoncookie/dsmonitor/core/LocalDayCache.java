package com.pigeoncookie.dsmonitor.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk cache of finished local days, so a range view does not have to refetch history.
 *
 * <p>Flat lines, one per day and item: {@code iso|kind|name|cost|tokens|requests|hit|miss|out}
 * with {@code kind} being {@code total}, {@code key} or {@code model}. Past days never change, so
 * once a day has been fetched it is reused for every later range query.
 */
public final class LocalDayCache {

    private static final String FILE_NAME = "local-days.jsonl";
    private static final int MAX_DAYS = 60;

    private LocalDayCache() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    public static synchronized UsageData get(Context c, String iso) {
        LinkedHashMap<String, UsageData> all = load(c);
        return all.get(iso);
    }

    public static synchronized void put(Context c, String iso, UsageData data) {
        LinkedHashMap<String, UsageData> all = load(c);
        all.put(iso, data);
        while (all.size() > MAX_DAYS) {
            all.remove(all.keySet().iterator().next());
        }
        write(c, all);
    }

    public static synchronized LinkedHashMap<String, UsageData> load(Context c) {
        File f = file(c);
        if (!f.exists()) {
            return new LinkedHashMap<>();
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
            return new LinkedHashMap<>();
        } finally {
            close(reader);
        }
    }

    /** Pure parser: text → day → usage (testable without a device). */
    public static LinkedHashMap<String, UsageData> parse(String text) {
        LinkedHashMap<String, UsageData> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String[] p = line.split("\\|", -1);
            if (p.length < 9) {
                continue;
            }
            try {
                UsageData data = out.get(p[0]);
                if (data == null) {
                    data = new UsageData();
                    out.put(p[0], data);
                }
                UsageData.Day day = data.day(p[0]);
                double cost = Double.parseDouble(p[3].trim());
                long tokens = (long) Double.parseDouble(p[4].trim());
                long requests = (long) Double.parseDouble(p[5].trim());
                long hit = (long) Double.parseDouble(p[6].trim());
                long miss = (long) Double.parseDouble(p[7].trim());
                long outs = (long) Double.parseDouble(p[8].trim());
                UsageData.Slice slice;
                if ("total".equals(p[1])) {
                    slice = day.total;
                } else if ("key".equals(p[1])) {
                    slice = day.dim(UsageData.DIM_KEY, p[2]);
                } else if ("model".equals(p[1])) {
                    slice = day.dim(UsageData.DIM_MODEL, p[2]);
                } else {
                    continue;
                }
                slice.cost += cost;
                slice.tokens += tokens;
                slice.requests += requests;
                slice.hitTokens += hit;
                slice.missTokens += miss;
                slice.outTokens += outs;
            } catch (NumberFormatException ignored) {
                // skip a malformed line, keep the rest
            }
        }
        return out;
    }

    /** Pure serialiser for one day (testable without a device). */
    public static String format(String iso, UsageData data) {
        StringBuilder sb = new StringBuilder();
        if (data == null) {
            return "";
        }
        for (UsageData.Day day : data.days()) {
            line(sb, iso, "total", "", day.total);
            for (Map.Entry<String, UsageData.Slice> e : day.byKey.entrySet()) {
                line(sb, iso, "key", e.getKey(), e.getValue());
            }
            for (Map.Entry<String, UsageData.Slice> e : day.byModel.entrySet()) {
                line(sb, iso, "model", e.getKey(), e.getValue());
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String iso, String kind, String name, UsageData.Slice s) {
        sb.append(iso).append('|').append(kind).append('|')
                .append(name == null ? "" : name.replace('|', '_').replace('\n', '_')).append('|')
                .append(s.cost).append('|').append(s.tokens).append('|').append(s.requests)
                .append('|').append(s.hitTokens).append('|').append(s.missTokens)
                .append('|').append(s.outTokens).append('\n');
    }

    private static void write(Context c, LinkedHashMap<String, UsageData> all) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, UsageData> e : all.entrySet()) {
            sb.append(format(e.getKey(), e.getValue()));
        }
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file(c)), StandardCharsets.UTF_8);
            writer.write(sb.toString());
        } catch (Exception ignored) {
            // keep whatever was on disk
        } finally {
            close(writer);
        }
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
