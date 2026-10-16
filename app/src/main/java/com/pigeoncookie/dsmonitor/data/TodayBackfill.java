package com.pigeoncookie.dsmonitor.data;

import android.content.Context;

import com.pigeoncookie.dsmonitor.core.DeepSeekClient;
import com.pigeoncookie.dsmonitor.core.SampleStore;
import com.pigeoncookie.dsmonitor.core.UsageData;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches the finished hours of "today" straight from the platform into {@link HourlyCache}.
 *
 * <p>Local sampling can only ever see the time the app was running, and turning samples into hourly
 * buckets needs deltas that smear across gaps. Instead, each finished hour is requested as its very
 * own window {@code [H:00, H+1:00)} — the same bucket the console shows — so a bucket can neither
 * lose traffic nor borrow it from a neighbour.
 *
 * <p>Every hour is fetched once per day. A sanity check guards the whole cache: if the hourly windows
 * do not behave (for instance the endpoint answers every window with the same whole day), the summed
 * hours would overshoot the day's own total and the cache is disabled instead of being trusted.
 */
public final class TodayBackfill {

    private static final int MAX_PER_RUN = 24;

    public interface Progress {
        void onProgress(int done, int total);
    }

    private TodayBackfill() {
    }

    /** True while some finished hour of today is missing from the platform cache. */
    public static boolean needed(Context c) {
        if (HourlyCache.disabled(c)) {
            return false;
        }
        LinkedHashMap<Integer, HourlyCache.Hour> cached = HourlyCache.load(c);
        int currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        for (int h = 0; h < currentHour; h++) {
            if (!cached.containsKey(h)) {
                return true;
            }
        }
        return false;
    }

    /** Blocking; call from a background thread. */
    public static void run(Context c, String token, Progress progress) {
        if (token == null || token.isEmpty() || HourlyCache.disabled(c)) {
            return;
        }
        Calendar now = Calendar.getInstance();
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        long midnight = midnight(now);
        double dayTokens = 0;
        List<SampleStore.Sample> today = SampleStore.today(c);
        for (SampleStore.Sample s : today) {
            dayTokens = Math.max(dayTokens, s.tokens);
        }
        if (dayTokens <= 0) {
            // no local samples (background sampling off): take the day's own total as the reference
            try {
                long[] window = DeepSeekClient.todayWindow();
                dayTokens = DeepSeekClient.fetchExportRange(token, window[0], window[1]).sumAll().tokens;
            } catch (DeepSeekClient.ApiException ignored) {
                dayTokens = 0;
            }
        }

        LinkedHashMap<Integer, HourlyCache.Hour> cached = HourlyCache.load(c);
        int budget = MAX_PER_RUN;
        double fetchedTokens = 0;
        boolean changed = false;
        for (int h = 0; h < currentHour && budget > 0; h++) {
            if (cached.containsKey(h)) {
                fetchedTokens += cached.get(h).tokens;
                continue;
            }
            long start = midnight / 1000L + h * 3600L;
            long end = start + 3600L;
            if (progress != null) {
                progress.onProgress(h, currentHour);
            }
            try {
                UsageData data = DeepSeekClient.fetchExportRange(token, start, end);
                UsageData.Slice slice = data.sumAll();
                HourlyCache.Hour hour = new HourlyCache.Hour();
                hour.cost = slice.cost;
                hour.tokens = slice.tokens;
                hour.requests = slice.requests;
                hour.hitTokens = slice.hitTokens;
                hour.missTokens = slice.missTokens;
                hour.outTokens = slice.outTokens;
                fill(hour.keys, data.itemStatsAll(UsageData.DIM_KEY));
                fill(hour.models, data.itemStatsAll(UsageData.DIM_MODEL));
                fetchedTokens += hour.tokens;
                if (dayTokens > 0 && fetchedTokens > dayTokens * 1.05d + 10) {
                    // the endpoint ignored the window — do not trust any of it
                    HourlyCache.disable(c);
                    return;
                }
                HourlyCache.put(c, h, hour);
                changed = true;
                budget--;
            } catch (DeepSeekClient.ApiException e) {
                break;
            }
        }
        if (changed) {
            // report the finished hours so the caller can repaint
            if (progress != null) {
                progress.onProgress(currentHour, currentHour);
            }
        }
    }

    private static void fill(LinkedHashMap<String, double[]> target, Map<String, double[]> stats) {
        for (Map.Entry<String, double[]> e : stats.entrySet()) {
            double[] s = e.getValue(); // [cost, hit, miss, out, requests]
            double tokens = (s.length > SampleStore.STAT_OUT ? s[SampleStore.STAT_HIT]
                    + s[SampleStore.STAT_MISS] + s[SampleStore.STAT_OUT] : 0d);
            target.put(e.getKey(), new double[]{
                    s[SampleStore.STAT_COST], tokens, s[SampleStore.STAT_REQUESTS],
                    s[SampleStore.STAT_HIT], s[SampleStore.STAT_MISS], s[SampleStore.STAT_OUT]});
        }
    }

    private static long midnight(Calendar reference) {
        Calendar cal = (Calendar) reference.clone();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static String localDate() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
