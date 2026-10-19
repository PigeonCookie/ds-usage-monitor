package com.pigeoncookie.dsmonitor.data;

import android.content.Context;

import com.pigeoncookie.dsmonitor.core.DeepSeekClient;
import com.pigeoncookie.dsmonitor.core.LocalDayCache;
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
    /** 同一时间只允许一个回填在跑，避免连点刷新时两次 fill 并发写同一份缓存。 */
    private static volatile boolean FILLING;

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

    /** True while some finished hour of the given day is missing from the platform cache. */
    public static boolean needed(Context c, String isoDay, int lastHour) {
        if (HourlyCache.disabled(c, isoDay)) {
            return false;
        }
        LinkedHashMap<Integer, HourlyCache.Hour> cached = HourlyCache.load(c, isoDay);
        for (int h = 0; h <= lastHour; h++) {
            if (!cached.containsKey(h)) {
                return true;
            }
        }
        return false;
    }

    /** Blocking; fills today up to the hour that just ended. */
    public static void run(Context c, String token, Progress progress) {
        if (FILLING) {
            return;                       // 上一次回填还没结束，忽略这次
        }
        FILLING = true;
        try {
            runLocked(c, token, progress);
        } finally {
            FILLING = false;
        }
    }

    private static void runLocked(Context c, String token, Progress progress) {
        fill(c, token, localDate(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY) - 1, progress);
    }

    /**
     * Fetches every hour {@code 0..lastHour} of one local day as its own window and stores it, so a
     * past day gets exactly the same per-hour buckets as today. Hours already cached are skipped.
     */
    public static void fill(Context c, String token, String isoDay, int lastHour, Progress progress) {
        if (token == null || token.isEmpty() || HourlyCache.disabled(c, isoDay) || lastHour < 0) {
            return;
        }
        Calendar now = Calendar.getInstance();
        int currentHour = lastHour + 1;
        long midnight = dayStart(isoDay);
        double dayTokens = 0;
        List<SampleStore.Sample> today = SampleStore.today(c);
        for (SampleStore.Sample s : today) {
            dayTokens = Math.max(dayTokens, s.tokens);
        }
        if (dayTokens <= 0 || !isoDay.equals(localDate())) {
            // reference total of the day being filled (also for a past day)
            try {
                long[] window = isoDay.equals(localDate())
                        ? DeepSeekClient.todayWindow() : DeepSeekClient.localDayWindow(isoDay);
                dayTokens = DeepSeekClient.fetchExportRange(token, window[0], window[1]).sumAll().tokens;
            } catch (DeepSeekClient.ApiException ignored) {
                dayTokens = 0;
            }
        }

        LinkedHashMap<Integer, HourlyCache.Hour> cached = HourlyCache.load(c, isoDay);
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
                if (hour.tokens > 0 && (double) hour.tokens > dayTokens * 1.05d) {
                    // 本机采样只是参考：链断了就直接认官网，没断档也只标记存疑，绝不弃用整天
                    boolean broken = gapBroken(c);
                    hour.approx = !broken;
                    android.util.Log.w("DSMonitor", "hour=" + h + " off=" + hour.tokens
                            + " local=" + dayTokens + " broken=" + broken);
                }
                HourlyCache.put(c, isoDay, h, hour);
                changed = true;
                budget--;
            } catch (DeepSeekClient.ApiException e) {
                // 某一小时取失败不要影响其它小时（否则缓存会留下"半天空洞"）
                android.util.Log.w("DSMonitor", "fill hour=" + h + " failed: " + e.getMessage());
                continue;
            }
        }
        if (changed) {
            // report the finished hours so the caller can repaint
            if (progress != null) {
                progress.onProgress(currentHour, currentHour);
            }
        }
        preheat(c, token);
        // housekeeping: the per-day cache files of previous days serve no purpose any more
        HourlyCache.prune(c, 7);
    }

    /**
     * Tops up the local day cache a few days per run (oldest first), so the last month stays
     * viewable even when the platform API is unreachable. Silently stops on the first failure.
     */
    private static void preheat(Context c, String token) {
        int budget = 3;
        for (int back = 30; back >= 1 && budget > 0; back--) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -back);
            String iso = String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            if (LocalDayCache.get(c, iso) != null) {
                continue;
            }
            try {
                DeepSeekClient.fetchLocalDay(c, token, iso);
                budget--;
            } catch (DeepSeekClient.ApiException e) {
                return;
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

    /**
     * True when today's samples have a hole longer than 20 minutes (or none at all): the local
     * estimate is then no yardstick for the platform's numbers.
     */
    private static boolean gapBroken(Context c) {
        long last = 0;
        java.util.List<SampleStore.Sample> samples = SampleStore.today(c);
        if (samples.isEmpty()) {
            return true;
        }
        for (SampleStore.Sample s : samples) {
            if (last > 0 && s.t - last > 20L * 60L * 1000L) {
                return true;
            }
            last = s.t;
        }
        return false;
    }

    /** Local midnight of an ISO day in epoch millis. */
    private static long dayStart(String isoDay) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(Integer.parseInt(isoDay.substring(0, 4)), Integer.parseInt(isoDay.substring(5, 7)) - 1,
                Integer.parseInt(isoDay.substring(8, 10)), 0, 0, 0);
        return cal.getTimeInMillis();
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
