package com.pigeoncookie.dsmonitor.core;

/** Process-wide cache so switching tabs or chips does not re-hit the network. */
public final class UsageRepo {

    public static final long TTL_MS = 3 * 60 * 1000L;

    /** Loaded usage for the current range key (all keys/models; filtering happens client side). */
    public static UsageData data;
    public static String rangeKey = "";
    public static long at;
    /** False when only the month endpoints answered, i.e. no per-API-key split available. */
    public static boolean byKeyAvailable;
    public static String error = "";
    /** “今天 + 全部 Key”的消费合计，由用量页写入，供余额页复用，保证两处数字一致。 */
    public static double todayCostAll = -1;
    public static String todayCostDay = "";

    private UsageRepo() {
    }

    public static void clear() {
        data = null;
        rangeKey = "";
        at = 0;
        byKeyAvailable = false;
        error = "";
    }

    public static boolean fresh(String key, long now) {
        return data != null && key.equals(rangeKey) && now - at < TTL_MS;
    }
}
