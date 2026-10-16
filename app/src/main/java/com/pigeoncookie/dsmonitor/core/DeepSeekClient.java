package com.pigeoncookie.dsmonitor.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DeepSeek endpoints used by the app.
 *
 * <ul>
 *   <li>{@code api.deepseek.com/user/balance} — official, authenticated with the API key.</li>
 *   <li>{@code platform.deepseek.com/api/v0/usage/export} — CSV (zip) export; the only source with
 *       per-API-key rows, used for every chart range.</li>
 *   <li>{@code platform.deepseek.com/api/v0/usage/{amount,cost}} — the console's monthly usage API,
 *       used as a fallback (and for the all-time total) when the export route fails.</li>
 * </ul>
 */
public final class DeepSeekClient {

    public static final String BALANCE_URL = "https://api.deepseek.com/user/balance";
    public static final String AMOUNT_URL = "https://platform.deepseek.com/api/v0/usage/amount";
    public static final String COST_URL = "https://platform.deepseek.com/api/v0/usage/cost";
    public static final String EXPORT_URL = "https://platform.deepseek.com/api/v0/usage/export";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String REFERER = "https://platform.deepseek.com/usage";
    private static final int TIMEOUT_MS = 20000;
    private static final int EXPORT_TIMEOUT_MS = 30000;
    private static final int MAX_JSON = 4 * 1024 * 1024;
    private static final int MAX_ZIP = 64 * 1024 * 1024;

    public static final class ApiException extends Exception {
        public final int status;

        public ApiException(String message) {
            this(message, 0);
        }

        public ApiException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    private DeepSeekClient() {
    }

    private static Map<String, String> headers(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer " + token);
        h.put("user-agent", userAgent().isEmpty() ? UA : userAgent());
        h.put("referer", REFERER);
        h.put("accept", "application/json, text/plain, */*");
        return h;
    }

    // ------------------------------------------------------------------ balance

    public static Balance fetchBalance(String apiKey) throws ApiException {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            throw new ApiException("未配置 API Key");
        }
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer " + key);
        try {
            Http.Res res = Http.get(BALANCE_URL, h, TIMEOUT_MS, MAX_JSON);
            if (res.code == 401 || res.code == 403) {
                throw new ApiException("API Key 无效或已失效", res.code);
            }
            if (!res.ok()) {
                throw new ApiException("余额接口返回 HTTP " + res.code, res.code);
            }
            Balance b = Balance.parse(Json.parse(res.text()), System.currentTimeMillis());
            if (b == null) {
                throw new ApiException("余额接口返回内容无法解析");
            }
            return b;
        } catch (IOException e) {
            throw new ApiException(netMessage(e));
        } catch (IllegalArgumentException e) {
            throw new ApiException("余额接口返回内容无法解析");
        }
    }

    /** Custom User-Agent for every request; empty means the built-in default. */
    private static String customUserAgent = "";

    public static void setUserAgent(String value) {
        customUserAgent = value == null ? "" : value.trim();
    }

    /** The User-Agent to send (may be empty: the HTTP layer then keeps its own default). */
    public static String userAgent() {
        return customUserAgent;
    }

    // ------------------------------------------------------------------ usage windows

    /** {@code [local midnight, next full hour]} in epoch seconds — "today" in the device zone. */
    public static long[] todayWindow() {
        Calendar c = Calendar.getInstance();
        Calendar midnight = (Calendar) c.clone();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long start = midnight.getTimeInMillis() / 1000L;
        long end = ((c.getTimeInMillis() / 3600000L) + 1L) * 3600L;
        if (end <= start) {
            end = start + 3600L;
        }
        return new long[]{start, end};
    }

    /**
     * UTC-day aligned window covering {@code from..to} (both {@code yyyy-MM-dd}); the export
     * endpoint only accepts windows ≤ 24h or aligned to whole UTC days.
     */
    public static long[] utcWindow(String from, String to) {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar c = Calendar.getInstance(utc);
        c.clear();
        int y = Integer.parseInt(from.substring(0, 4));
        int m = Integer.parseInt(from.substring(5, 7));
        int d = Integer.parseInt(from.substring(8, 10));
        c.set(y, m - 1, d, 0, 0, 0);
        long start = c.getTimeInMillis() / 1000L;
        c.clear();
        c.set(Integer.parseInt(to.substring(0, 4)), Integer.parseInt(to.substring(5, 7)) - 1,
                Integer.parseInt(to.substring(8, 10)), 0, 0, 0);
        long end = c.getTimeInMillis() / 1000L + 24L * 3600L;
        return new long[]{start, end};
    }

    /** {@code [local midnight of isoDay, local midnight of the next day]} in epoch seconds. */
    public static long[] localDayWindow(String isoDay) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(isoDay.substring(0, 4)), Integer.parseInt(isoDay.substring(5, 7)) - 1,
                Integer.parseInt(isoDay.substring(8, 10)), 0, 0, 0);
        long start = c.getTimeInMillis() / 1000L;
        c.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{start, c.getTimeInMillis() / 1000L};
    }

    /** Today in the device zone as {@code yyyy-MM-dd}. */
    public static String isoToday() {
        Calendar c = Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** The local hour that is still running, as an export window {@code [H:00, H+1:00]}. */
    public static long[] currentHourWindow() {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long from = start.getTimeInMillis() / 1000L;
        return new long[]{from, from + 3600L};
    }

    /** One local day; finished days are cached on disk because their numbers never change. */
    public static UsageData fetchLocalDay(android.content.Context c, String token, String isoDay)
            throws ApiException {
        boolean today = isoDay.equals(isoToday());
        if (!today) {
            UsageData cached = LocalDayCache.get(c, isoDay);
            if (cached != null) {
                return cached;
            }
        }
        long[] window = localDayWindow(isoDay);
        UsageData data = fetchExportRange(token, window[0], window[1]);
        if (!today) {
            LocalDayCache.put(c, isoDay, data);
        }
        return data;
    }

    /** One export request → per-day / per-key / per-model usage for the window. */
    public static UsageData fetchExportRange(String token, long startSec, long endSec)
            throws ApiException {
        requireToken(token);
        String url = EXPORT_URL + "?start=" + startSec + "&end=" + endSec + "&tz=0";
        try {
            Http.Res res = Http.get(url, headers(token), EXPORT_TIMEOUT_MS, MAX_ZIP);
            if (res.code == 401 || res.code == 403) {
                throw new ApiException("平台 User Token 无效或已过期", res.code);
            }
            if (!res.ok()) {
                throw new ApiException("导出接口返回 HTTP " + res.code, res.code);
            }
            String csv = readAmountCsv(res.body);
            if (csv == null) {
                throw new ApiException("导出内容中没有可解析的 CSV");
            }
            UsageData data = new UsageData();
            data.fetchedAt = System.currentTimeMillis();
            data.addExportCsv(csv);
            return data;
        } catch (IOException e) {
            throw new ApiException(netMessage(e));
        }
    }

    // ------------------------------------------------------------------ month endpoints

    public static UsageData fetchUsageMonth(String token, int year, int month) throws ApiException {
        requireToken(token);
        String query = "?month=" + month + "&year=" + year;
        try {
            Http.Res amountRes = Http.get(AMOUNT_URL + query, headers(token), TIMEOUT_MS, MAX_JSON);
            Http.Res costRes = Http.get(COST_URL + query, headers(token), TIMEOUT_MS, MAX_JSON);
            Object amountRoot = body(amountRes, "用量接口");
            Object costRoot = body(costRes, "消费接口");
            if (Json.firstMap(Json.at(amountRoot, "data.biz_data")) == null
                    && Json.firstMap(Json.at(costRoot, "data.biz_data")) == null) {
                throw new ApiException("用量接口返回内容缺少 data.biz_data");
            }
            UsageData data = new UsageData();
            data.fetchedAt = System.currentTimeMillis();
            data.addAmountResponse(amountRoot);
            data.addCostResponse(costRoot);
            return data;
        } catch (IOException e) {
            throw new ApiException(netMessage(e));
        } catch (IllegalArgumentException e) {
            throw new ApiException("用量接口返回内容无法解析");
        }
    }

    /**
     * All-time spend. Tries one wide export request first (whole UTC days), then falls back to
     * walking months back to front; a failing month ends the walk instead of discarding everything.
     */
    public static double fetchAllTimeCost(String token, int maxMonths) throws ApiException {
        requireToken(token);
        try {
            long[] window = utcWindow(isoUtcDays(-Math.max(31, maxMonths * 31)), isoUtcDays(0));
            UsageData data = fetchExportRange(token, window[0], window[1]);
            double total = data.sumAll().cost;
            if (total > 0) {
                return total;
            }
        } catch (ApiException ignored) {
            // fall through to the month walk
        }
        Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH) + 1;
        double total = 0;
        int emptyStreak = 0;
        for (int i = 0; i < maxMonths; i++) {
            try {
                Http.Res res = Http.get(COST_URL + "?month=" + month + "&year=" + year,
                        headers(token), TIMEOUT_MS, MAX_JSON);
                Object root = body(res, "消费接口");
                UsageData tmp = new UsageData();
                tmp.addCostResponse(root);
                double monthCost = tmp.monthCost();
                total += monthCost;
                if (monthCost <= 0.0000001) {
                    if (++emptyStreak >= 3) {
                        break;
                    }
                } else {
                    emptyStreak = 0;
                }
            } catch (IOException e) {
                throw new ApiException(netMessage(e));
            } catch (IllegalArgumentException | ApiException e) {
                break; // partial history is still useful; the caller keeps the larger value
            }
            if (--month == 0) {
                month = 12;
                year--;
            }
        }
        if (total <= 0) {
            throw new ApiException("累计消费统计失败");
        }
        return total;
    }

    private static String isoUtcDays(int offsetDays) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.add(Calendar.DAY_OF_MONTH, offsetDays);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    // ------------------------------------------------------------------ helpers

    private static void requireToken(String token) throws ApiException {
        if (token == null || token.trim().isEmpty()) {
            throw new ApiException("未配置平台 User Token");
        }
    }

    private static Object body(Http.Res res, String what) throws ApiException {
        if (res.code == 401 || res.code == 403) {
            throw new ApiException("平台 User Token 无效或已过期", res.code);
        }
        if (!res.ok()) {
            throw new ApiException(what + "返回 HTTP " + res.code, res.code);
        }
        Object root = Json.parse(res.text());
        Map<String, Object> map = Json.asMap(root);
        if (map != null && map.containsKey("code") && Json.numAt(map, "code", 0) != 0) {
            if (Json.numAt(map, "code", 0) == 401 || Json.numAt(map, "code", 0) == 403) {
                throw new ApiException("平台 User Token 无效或已过期");
            }
            throw new ApiException(Json.strAt(map, "msg", Json.strAt(map, "message", "接口返回错误")));
        }
        return root;
    }

    private static String netMessage(IOException e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? "网络请求失败" : ("网络请求失败：" + msg);
    }

    /** Pulls the {@code *amount*.csv} entry out of the export zip (falls back to any CSV). */
    static String readAmountCsv(byte[] zipBytes) throws IOException {
        if (zipBytes.length < 4 || zipBytes[0] != 'P' || zipBytes[1] != 'K') {
            String text = new String(zipBytes, java.nio.charset.StandardCharsets.UTF_8);
            return text.contains(",") ? text : null;
        }
        String fallback = null;
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        try {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName() == null ? "" : entry.getName().toLowerCase();
                if (!name.endsWith(".csv")) {
                    continue;
                }
                String text = new String(readEntry(zin), java.nio.charset.StandardCharsets.UTF_8);
                if (name.contains("amount")) {
                    return text;
                }
                if (fallback == null) {
                    fallback = text;
                }
            }
        } finally {
            try {
                zin.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
        return fallback;
    }

    private static byte[] readEntry(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Quote-aware CSV parse with a header row. */
    static List<Map<String, String>> parseCsv(String text) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (text == null) {
            return rows;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length < 2) {
            return rows;
        }
        List<String> header = splitCsv(lines[0].replace("\uFEFF", ""));
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> values = splitCsv(line);
            Map<String, String> row = new HashMap<>();
            for (int j = 0; j < header.size(); j++) {
                row.put(header.get(j), j < values.size() ? values.get(j) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
