package com.pigeoncookie.dsmonitor.data;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.pigeoncookie.dsmonitor.core.Bg;
import com.pigeoncookie.dsmonitor.core.Balance;
import com.pigeoncookie.dsmonitor.core.DeepSeekClient;
import com.pigeoncookie.dsmonitor.core.Prefs;
import com.pigeoncookie.dsmonitor.core.SampleStore;
import com.pigeoncookie.dsmonitor.core.UsageData;

/** Background sampler: records today's cumulative usage so the per-hour chart fills in. */
public class SampleJobService extends JobService {

    public static final int JOB_ID = 4711;
    /** JobScheduler's minimum periodic interval; more samples means a sharper hourly curve. */
    private static final long INTERVAL_MS = 15L * 60L * 1000L;
    /** Let the system slide the run by up to this much instead of skipping/drifting it. */
    private static final long FLEX_MS = 5L * 60L * 1000L;

    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, SampleJobService.class))
                .setPersisted(true)
                .setPeriodic(INTERVAL_MS, FLEX_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        try {
            js.schedule(info);
        } catch (RuntimeException ignored) {
            // some OEM builds reject periodic jobs; the app still samples on every refresh
        }
    }

    public static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            js.cancel(JOB_ID);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Bg.run(new Runnable() {
            @Override
            public void run() {
                try {
                    sample(getApplicationContext());
                } catch (Exception ignored) {
                    // background work must never surface a crash
                } finally {
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            jobFinished(params, false);
                        }
                    });
                }
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** Records one sample and fires the balance alert when needed. */
    public static void sample(Context c) {
        String token = Prefs.userToken(c);
        if (!token.isEmpty()) {
            try {
                long[] window = DeepSeekClient.todayWindow();
                UsageData today = DeepSeekClient.fetchExportRange(token, window[0], window[1]);
                UsageData.Slice total = today.sumAll();
                SampleStore.append(c, total.cost, total.tokens, total.requests,
                        total.hitTokens, total.missTokens, total.outTokens,
                        today.itemStatsAll(UsageData.DIM_KEY),
                        today.itemStatsAll(UsageData.DIM_MODEL));
            } catch (Exception ignored) {
                // keep the previous history
            }
        }
        String key = Prefs.apiKey(c);
        if (key.isEmpty()) {
            return;
        }
        try {
            Balance b = DeepSeekClient.fetchBalance(key);
            double prev = Prefs.lastBalance(c);
            if (prev > 0 && b.total > prev) {
                Prefs.addTopup(c, b.total - prev);
            }
            if (Prefs.baseTotal(c) <= 0) {
                Prefs.setBaseTotal(c, b.total);
            }
            Prefs.setLastBalance(c, b.total, System.currentTimeMillis());
            double threshold = Prefs.alertThreshold(c);
            if (Prefs.alertEnabled(c) && b.total <= threshold) {
                if (!Prefs.notifiedLow(c)) {
                    Alerts.notifyLowBalance(c, b.total, threshold);
                    Prefs.setNotifiedLow(c, true);
                }
            } else if (b.total > threshold) {
                Prefs.setNotifiedLow(c, false);
                Alerts.cancel(c);
            }
        } catch (Exception ignored) {
            // offline is fine
        }
    }
}
