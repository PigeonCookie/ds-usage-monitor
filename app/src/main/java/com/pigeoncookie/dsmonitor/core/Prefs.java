package com.pigeoncookie.dsmonitor.core;

import android.content.Context;
import android.content.SharedPreferences;
/** SharedPreferences-backed settings store. */
public final class Prefs {

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_DAY = 1;
    public static final int THEME_NIGHT = 2;

    private static final String FILE = "dsmonitor";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Optional custom User-Agent for the platform endpoints ("" = built-in default). */
    public static String userAgent(Context c) {
        return sp(c).getString("user_agent", "");
    }

    public static void setUserAgent(Context c, String value) {
        sp(c).edit().putString("user_agent", value == null ? "" : value.trim()).apply();
    }

    public static String apiKey(Context c) {
        return secret(c, "api_key");
    }

    public static void setApiKey(Context c, String v) {
        putSecret(c, "api_key", v);
    }

    public static String userToken(Context c) {
        return secret(c, "user_token");
    }

    public static void setUserToken(Context c, String v) {
        putSecret(c, "user_token", v);
    }

    /**
     * Reads a secret through {@link SecretBox}; a plain-text value written by an older build is
     * re-saved encrypted on the spot, so existing users migrate without touching anything.
     */
    private static String secret(Context c, String key) {
        String raw = sp(c).getString(key, "");
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith(SecretBox.PREFIX)) {
            String plain = SecretBox.open(raw.substring(SecretBox.PREFIX.length()));
            return plain == null ? "" : plain;
        }
        putSecret(c, key, raw);
        // SharedPreferences 会把上一版文件留成 .bak：再写一次把那份也轮换成密文，
        // 避免旧明文以备份文件的形式继续躺在应用私有目录里。
        sp(c).edit().putLong("secret_migrated_at", System.currentTimeMillis()).apply();
        return raw.trim();
    }

    private static void putSecret(Context c, String key, String v) {
        String clean = v == null ? "" : v.trim();
        String sealed = SecretBox.seal(clean);
        sp(c).edit().putString(key, sealed == null ? clean : SecretBox.PREFIX + sealed).apply();
    }

    public static String nickname(Context c) {
        return sp(c).getString("nickname", "");
    }

    public static void setNickname(Context c, String v) {
        sp(c).edit().putString("nickname", v == null ? "" : v).apply();
    }

    public static String avatarPath(Context c) {
        return sp(c).getString("avatar", "");
    }

    public static void setAvatarPath(Context c, String v) {
        sp(c).edit().putString("avatar", v == null ? "" : v).apply();
    }

    public static int themeMode(Context c) {
        return sp(c).getInt("theme", THEME_NIGHT);
    }

    public static void setThemeMode(Context c, int v) {
        sp(c).edit().putInt("theme", v).apply();
    }

    public static boolean alertEnabled(Context c) {
        return sp(c).getBoolean("alert_on", false);
    }

    public static void setAlertEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("alert_on", v).apply();
    }

    public static double alertThreshold(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("alert_threshold", Double.doubleToLongBits(10d)));
    }

    public static void setAlertThreshold(Context c, double v) {
        sp(c).edit().putLong("alert_threshold", Double.doubleToLongBits(v)).apply();
    }

    public static boolean hourlySampling(Context c) {
        return sp(c).getBoolean("sampling", false);
    }

    public static void setHourlySampling(Context c, boolean v) {
        sp(c).edit().putBoolean("sampling", v).apply();
    }

    public static boolean notifiedLow(Context c) {
        return sp(c).getBoolean("notified_low", false);
    }

    public static void setNotifiedLow(Context c, boolean v) {
        sp(c).edit().putBoolean("notified_low", v).apply();
    }

    // ---- UI state that must survive a theme switch and an app restart ----

    public static int lastTab(Context c) {
        return sp(c).getInt("last_tab", 0);
    }

    public static void setLastTab(Context c, int v) {
        sp(c).edit().putInt("last_tab", v).apply();
    }

    public static int usageRange(Context c) {
        return sp(c).getInt("usage_range", 0);
    }

    public static void setUsageRange(Context c, int v) {
        sp(c).edit().putInt("usage_range", v).apply();
    }

    public static int usageDim(Context c) {
        return sp(c).getInt("usage_dim", UsageData.DIM_MODEL);
    }

    public static void setUsageDim(Context c, int v) {
        sp(c).edit().putInt("usage_dim", v).apply();
    }

    public static String usageSelection(Context c, int dim) {
        return sp(c).getString(dim == 1 ? "usage_selection_key" : "usage_selection_model", "");
    }

    public static void setUsageSelection(Context c, int dim, String v) {
        sp(c).edit().putString(dim == 1 ? "usage_selection_key" : "usage_selection_model",
                v == null ? "" : v).apply();
    }

    public static String usageFrom(Context c) {
        return sp(c).getString("usage_from", "");
    }

    public static String usageTo(Context c) {
        return sp(c).getString("usage_to", "");
    }

    public static void setUsageCustom(Context c, String from, String to) {
        sp(c).edit().putString("usage_from", from == null ? "" : from)
                .putString("usage_to", to == null ? "" : to).apply();
    }

    /** Per-day record of which hours of "today" were already fetched from the platform. */
    public static String backfillState(Context c) {
        return sp(c).getString("backfill_state", "");
    }

    public static void setBackfillState(Context c, String state) {
        sp(c).edit().putString("backfill_state", state == null ? "" : state).apply();
    }

    // ---- balance history used for the local (API-key only) cost estimate ----

    public static double lastBalance(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("last_balance", Double.doubleToLongBits(-1d)));
    }

    public static long lastBalanceAt(Context c) {
        return sp(c).getLong("last_balance_at", 0L);
    }

    /** True when the most recent balance request failed (the shown value is stale). */
    public static boolean offline(Context c) {
        return sp(c).getBoolean("offline", false);
    }

    public static void setOffline(Context c, boolean value) {
        sp(c).edit().putBoolean("offline", value).apply();
    }

    public static void setLastBalance(Context c, double v, long at) {
        sp(c).edit()
                .putLong("last_balance", Double.doubleToLongBits(v))
                .putLong("last_balance_at", at)
                .apply();
    }

    public static double baseTotal(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("base_total", Double.doubleToLongBits(0d)));
    }

    public static void setBaseTotal(Context c, double v) {
        sp(c).edit().putLong("base_total", Double.doubleToLongBits(v)).apply();
    }

    public static double topupSum(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("topup_sum", Double.doubleToLongBits(0d)));
    }

    public static void addTopup(Context c, double delta) {
        sp(c).edit().putLong("topup_sum", Double.doubleToLongBits(topupSum(c) + delta)).apply();
    }

    public static double cumulative(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("cumulative", Double.doubleToLongBits(-1d)));
    }

    public static long cumulativeAt(Context c) {
        return sp(c).getLong("cumulative_at", 0L);
    }

    public static void setCumulative(Context c, double v, long at) {
        sp(c).edit()
                .putLong("cumulative", Double.doubleToLongBits(v))
                .putLong("cumulative_at", at)
                .apply();
    }

    /** “今天 + 全部 Key”的消费合计（用量页写入，余额页复用，跨进程保留）。 */
    public static double todayCostAll(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("today_cost_all",
                Double.doubleToLongBits(-1d)));
    }

    public static String todayCostDay(Context c) {
        return sp(c).getString("today_cost_day", "");
    }

    public static void setTodayCostAll(Context c, double v, String isoDay) {
        sp(c).edit()
                .putLong("today_cost_all", Double.doubleToLongBits(v))
                .putString("today_cost_day", isoDay == null ? "" : isoDay)
                .apply();
    }

    /** 今日零点时的累计消费，用来把“今日”从累计里减出来。 */
    public static double cumulativeBase(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("cumulative_base_v2",
                Double.doubleToLongBits(-1d)));
    }

    public static String cumulativeBaseDay(Context c) {
        return sp(c).getString("cumulative_base_day_v2", "");
    }

    public static void setCumulativeBase(Context c, double v, String isoDay) {
        sp(c).edit()
                .putLong("cumulative_base_v2", Double.doubleToLongBits(v))
                .putString("cumulative_base_day_v2", isoDay == null ? "" : isoDay)
                .apply();
    }

    /** Balance drops observed locally since the last platform cumulative fetch. */
    public static double cumulativeDelta(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("cumulative_delta", Double.doubleToLongBits(0d)));
    }

    public static void setCumulativeDelta(Context c, double v) {
        sp(c).edit().putLong("cumulative_delta", Double.doubleToLongBits(v)).apply();
    }

    public static void addCumulativeDelta(Context c, double v) {
        if (v != 0) {
            setCumulativeDelta(c, cumulativeDelta(c) + v);
        }
    }
}
