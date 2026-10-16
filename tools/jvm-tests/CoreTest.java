package com.pigeoncookie.dsmonitor.core;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Plain-JVM checks for the parsing/aggregation layer (no Android classes involved).
 * Run: javac --release 11 -cp android.jar -d out <core sources> CoreTest.java && java -cp out CoreTest
 */
public final class CoreTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testJsonBasics();
        testBalance();
        testMonthEndpoints();
        testExportTokensAndKeys();
        testPriceBasedTokenKinds();
        testHourlyCacheRoundTrip();
        testCsvParsing();
        testExportZip();
        testFormatting();
        testMerge();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ tests

    private static void testJsonBasics() {
        Object root = Json.parse("{\"a\":1,\"b\":\"44.68\",\"c\":[1,2,{\"d\":true}],\"e\":null}");
        eq(1d, Json.numAt(root, "a", 0), "json int");
        eq(44.68d, Json.numAt(root, "b", 0), "json numeric string");
        eq(true, Json.boolAt(root, "c.2.d", false), "json nested bool");
        eq(7d, Json.numAt(root, "missing.deep", 7), "json default");
        eq("he said \"hi\"\n", Json.str(Json.parse("\"he said \\\"hi\\\"\\n\""), ""), "json escapes");
    }

    private static void testBalance() {
        String body = "{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\","
                + "\"total_balance\":\"106.00\",\"granted_balance\":\"61.28\","
                + "\"topped_up_balance\":\"44.68\"}]}";
        Balance b = Balance.parse(Json.parse(body), 123L);
        notNull(b, "balance parsed");
        if (b == null) {
            return;
        }
        eq(true, b.available, "balance available");
        eq("CNY", b.currency, "balance currency");
        eq(106.00d, b.total, "balance total");
        eq(61.28d, b.granted, "balance granted");
        eq(44.68d, b.toppedUp, "balance topped up");
        eq(123L, b.fetchedAt, "balance fetchedAt");
    }

    private static void testMonthEndpoints() {
        String amount = "{\"code\":0,\"data\":{\"biz_data\":{\"total\":[{\"model\":\"deepseek-chat\","
                + "\"usage\":[{\"type\":\"REQUEST\",\"amount\":\"46\"},"
                + "{\"type\":\"PROMPT_CACHE_HIT_TOKEN\",\"amount\":\"700000\"},"
                + "{\"type\":\"PROMPT_CACHE_MISS_TOKEN\",\"amount\":\"200000\"},"
                + "{\"type\":\"RESPONSE_TOKEN\",\"amount\":\"125644\"}]}],"
                + "\"days\":[{\"date\":\"2026-03-08\",\"data\":[{\"model\":\"deepseek-chat\",\"usage\":["
                + "{\"type\":\"REQUEST\",\"amount\":\"46\"},"
                + "{\"type\":\"PROMPT_CACHE_HIT_TOKEN\",\"amount\":\"700000\"},"
                + "{\"type\":\"PROMPT_CACHE_MISS_TOKEN\",\"amount\":\"200000\"},"
                + "{\"type\":\"RESPONSE_TOKEN\",\"amount\":\"125644\"}]}]}]}}}";
        String cost = "{\"code\":0,\"data\":{\"biz_data\":[{\"currency\":\"CNY\",\"total\":["
                + "{\"model\":\"deepseek-chat\",\"usage\":[{\"type\":\"REQUEST\",\"amount\":\"46\"},"
                + "{\"type\":\"PROMPT_CACHE_HIT_TOKEN\",\"amount\":\"0.03\"},"
                + "{\"type\":\"PROMPT_CACHE_MISS_TOKEN\",\"amount\":\"0.28\"},"
                + "{\"type\":\"RESPONSE_TOKEN\",\"amount\":\"0.11\"}]}],"
                + "\"days\":[{\"date\":\"2026-03-08\",\"data\":[{\"model\":\"deepseek-chat\",\"usage\":["
                + "{\"type\":\"PROMPT_CACHE_MISS_TOKEN\",\"amount\":\"0.28\"},"
                + "{\"type\":\"RESPONSE_TOKEN\",\"amount\":\"0.11\"},"
                + "{\"type\":\"PROMPT_CACHE_HIT_TOKEN\",\"amount\":\"0.03\"}]}]}]}]}}";

        UsageData data = new UsageData();
        data.addAmountResponse(Json.parse(amount));
        data.addCostResponse(Json.parse(cost));

        UsageData.Slice total = data.sumAll();
        eq(46L, total.requests, "month requests");
        eq(1025644L, total.tokens, "month tokens");
        eq(700000L, total.hitTokens, "month cache-hit tokens");
        eq(200000L, total.missTokens, "month cache-miss tokens");
        eq(125644L, total.outTokens, "month output tokens");
        eq(0.42d, round2(data.monthCost()), "month cost");

        UsageData.Day day = data.days.get("2026-03-08");
        notNull(day, "day present");
        if (day != null) {
            eq(46L, day.total.requests, "day requests");
            eq(125644L, day.total.outTokens, "day output tokens");
            eq(0.42d, round2(day.total.cost), "day cost");
            UsageData.Slice model = day.dimOrNull(UsageData.DIM_MODEL, "deepseek-chat");
            notNull(model, "model slice present");
            if (model != null) {
                eq(0.42d, round2(model.cost), "model cost");
                eq(1025644L, model.tokens, "model tokens");
            }
        }
    }

    private static void testExportTokensAndKeys() {
        // unit prices follow the real product: cache hit < cache miss < output
        String csv = "api_key_name,type,amount,price,utc_date,model\n"
                + "cookie,input_cache_hit_tokens,100000,0.000001,20260308,deepseek-chat\n"
                + "cookie,input_cache_miss_tokens,40000,0.000004,20260308,deepseek-chat\n"
                + "cookie,output_tokens,20000,0.000012,20260308,deepseek-chat\n"
                + "cookie,request_count,12,0,20260308,deepseek-chat\n"
                + "other,output_tokens,5000,0.000012,20260308,deepseek-reasoner\n";
        UsageData data = new UsageData();
        data.addExportCsv(csv);

        UsageData.Slice total = data.sumAll();
        eq(165000L, total.tokens, "export tokens");
        eq(100000L, total.hitTokens, "export cache-hit");
        eq(40000L, total.missTokens, "export cache-miss");
        eq(25000L, total.outTokens, "export output");
        eq(12L, total.requests, "export requests");
        eq(0.56d, round2(total.cost), "export cost");

        UsageData.Slice cookie = data.sliceAll(UsageData.DIM_KEY, "cookie");
        eq(160000L, cookie.tokens, "per-key tokens");
        eq(0.50d, round2(cookie.cost), "per-key cost");
        eq(12L, cookie.requests, "per-key requests");

        UsageData.Slice reasoner = data.sliceAll(UsageData.DIM_MODEL, "deepseek-reasoner");
        eq(5000L, reasoner.tokens, "per-model tokens");
        eq(0.06d, round2(reasoner.cost), "per-model cost");

        eq(2, data.keys().size(), "key list size");
        eq(2, data.models().size(), "model list size");
        eq(true, data.keys().contains("cookie"), "key list content");

        // the first item of each dimension, ordered by cost
        List<Map.Entry<String, Double>> keys = data.itemTotals(UsageData.DIM_KEY, null, null);
        eq("cookie", keys.get(0).getKey(), "biggest key first");
        eq(0.50d, round2(keys.get(0).getValue()), "biggest key cost");
    }

    private static void testCsvParsing() {
        List<String> cells = DeepSeekClient.splitCsv("a,\"b,with,commas\",\"say \"\"hi\"\"\",42");
        eq(4, cells.size(), "csv cell count");
        eq("b,with,commas", cells.get(1), "csv quoted cell");
        eq("say \"hi\"", cells.get(2), "csv escaped quote");

        String csv = "api_key_name,type,amount,price,utc_date,model\n"
                + "cookie,request_count,46,0,20260308,deepseek-chat\n"
                + "cookie,input_cache_hit_tokens,100000,0.000001,20260308,deepseek-chat\n";
        List<Map<String, String>> rows = DeepSeekClient.parseCsv(csv);
        eq(2, rows.size(), "csv row count");
        eq("46", rows.get(0).get("amount"), "csv field value");
    }

    private static void testExportZip() throws Exception {
        String csv = "api_key_name,type,amount,price,utc_date,model\n"
                + "cookie,request_count,46,0,20260308,deepseek-chat\n"
                + "cookie,input_cache_hit_tokens,100000,0.000001,20260308,deepseek-chat\n"
                + "cookie,input_cache_miss_tokens,100000,0.000004,20260308,deepseek-chat\n"
                + "cookie,output_tokens,50000,0.000012,20260308,deepseek-chat\n";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.putNextEntry(new ZipEntry("cost_20260308.csv"));
        zos.write("api_key_name,type,amount,price\n".getBytes("UTF-8"));
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("amount_20260308.csv"));
        zos.write(csv.getBytes("UTF-8"));
        zos.closeEntry();
        zos.close();

        String picked = DeepSeekClient.readAmountCsv(bos.toByteArray());
        notNull(picked, "zip amount csv picked");
        if (picked == null) {
            return;
        }
        eq(true, picked.contains("input_cache_hit_tokens"), "zip picked the amount csv");

        UsageData data = new UsageData();
        data.addExportCsv(picked);
        UsageData.Slice total = data.sumAll();
        eq(46L, total.requests, "zip requests total");
        eq(250000L, total.tokens, "zip tokens total");
        eq(1.1d, round2(total.cost), "zip cost total");
    }

    private static void testPriceBasedTokenKinds() {
        // same three token types but with names the app has never seen: the unit price has to decide
        String csv = "api_key_name,type,amount,price,utc_date,model\n"
                + "cookie,cache_read,1000000,0.0000005,20260308,deepseek-chat\n"
                + "cookie,cache_write,40000,0.000004,20260308,deepseek-chat\n"
                + "cookie,completion,20000,0.000012,20260308,deepseek-chat\n"
                + "cookie,request_count,12,0,20260308,deepseek-chat\n";
        UsageData data = new UsageData();
        data.addExportCsv(csv);
        UsageData.Slice total = data.sumAll();
        eq(1000000L, total.hitTokens, "cheapest type -> cache hit");
        eq(40000L, total.missTokens, "middle type -> cache miss");
        eq(20000L, total.outTokens, "dearest type -> output");
        eq(1060000L, total.tokens, "unknown-named tokens still counted");
        eq(12L, total.requests, "requests from request_count");
        eq(true, data.usageTypeSummary().contains("命中"), "type summary labels the split");

        // a single token type cannot be split: keep the name based fallback (unknown -> total only)
        UsageData single = new UsageData();
        single.addExportCsv("api_key_name,type,amount,price,utc_date,model\n"
                + "cookie,output_tokens,500,0.000002,20260308,deepseek-chat\n");
        eq(500L, single.sumAll().tokens, "single type still totals");
        eq(500L, single.sumAll().outTokens, "single type keeps its name based kind");
    }


    private static void testHourlyCacheRoundTrip() {
        java.util.LinkedHashMap<Integer, com.pigeoncookie.dsmonitor.data.HourlyCache.Hour> map =
                new java.util.LinkedHashMap<>();
        com.pigeoncookie.dsmonitor.data.HourlyCache.Hour h =
                new com.pigeoncookie.dsmonitor.data.HourlyCache.Hour();
        h.hour = 13;
        h.cost = 1.29d;
        h.tokens = 2356855L;
        h.requests = 152L;
        h.hitTokens = 2000000L;
        h.missTokens = 300000L;
        h.outTokens = 56855L;
        h.keys.put("cookie", new double[]{1.29d, 2356855d, 152d, 2000000d, 300000d, 56855d});
        map.put(13, h);

        com.pigeoncookie.dsmonitor.data.HourlyCache.Hour h12 =
                new com.pigeoncookie.dsmonitor.data.HourlyCache.Hour();
        h12.hour = 12;
        h12.cost = 0.9d;
        h12.tokens = 263532L;
        h12.requests = 40L;
        h12.hitTokens = 205834L;
        h12.missTokens = 45345L;
        h12.outTokens = 12353L;
        h12.keys.put("cookie", new double[]{0.9d, 263532d, 40d, 205834d, 45345d, 12353d});
        map.put(12, h12);

        java.util.LinkedHashMap<Integer, com.pigeoncookie.dsmonitor.data.HourlyCache.Hour> back =
                com.pigeoncookie.dsmonitor.data.HourlyCache.parse(
                        com.pigeoncookie.dsmonitor.data.HourlyCache.format(map));
        eq(2, back.size(), "both hours survive the round trip");
        com.pigeoncookie.dsmonitor.data.HourlyCache.Hour r13 = back.get(13);
        eq(true, r13 != null, "hour 13 present");
        eq(2356855L, r13.tokens, "hour tokens survive");
        eq(152L, r13.requests, "hour requests survive");
        eq(2000000L, r13.hitTokens, "hour hit tokens survive");
        eq(56855L, r13.outTokens, "hour out tokens survive");
        eq(1.29d, round2(r13.cost), "hour cost survives");
        eq(2356855d, r13.item(1, "cookie", com.pigeoncookie.dsmonitor.data.HourlyCache.TOKENS),
                "per-key tokens survive (the item map must not be dropped)");
        eq(2000000d, r13.item(1, "cookie", com.pigeoncookie.dsmonitor.data.HourlyCache.HIT),
                "per-key hit tokens survive");
        eq(205834d, back.get(12).item(1, "cookie", com.pigeoncookie.dsmonitor.data.HourlyCache.HIT),
                "each hour keeps its own split");
    }

    private static void testFormatting() {
        eq("¥44.68", Fmt.money(44.678), "money rounding");
        eq("¥0.00", Fmt.money(0), "money zero");
        eq("¥1,234.50", Fmt.money(1234.5), "money grouping");
        eq("1,025,644", Fmt.group(1025644), "group digits");
        eq("800K", Fmt.compact(800000), "compact K");
        eq("1.5M", Fmt.compact(1500000), "compact M");
        eq("02-04", Fmt.shortDate("2026-02-04"), "short date");
        eq("24:00", Fmt.hourLabel(24), "hour label 24");
    }

    private static void testMerge() {
        UsageData a = new UsageData();
        a.addCostResponse(Json.parse("{\"data\":{\"biz_data\":[{\"currency\":\"CNY\",\"days\":["
                + "{\"date\":\"2026-02-28\",\"data\":[{\"model\":\"m1\",\"usage\":["
                + "{\"type\":\"RESPONSE_TOKEN\",\"amount\":\"0.5\"}]}]}]}]}}"));
        UsageData b = new UsageData();
        b.addCostResponse(Json.parse("{\"data\":{\"biz_data\":[{\"currency\":\"CNY\",\"days\":["
                + "{\"date\":\"2026-03-01\",\"data\":[{\"model\":\"m1\",\"usage\":["
                + "{\"type\":\"RESPONSE_TOKEN\",\"amount\":\"1.25\"}]}]}]}]}}"));
        a.merge(b);
        eq(2, a.days.size(), "merge keeps both days");
        eq(1.75d, round2(a.monthCost()), "merge sums cost");
        eq(1, a.range("2026-03-01", "2026-03-31").size(), "range filter");

        // money that only exists in the month-level total block must not be lost
        UsageData c = new UsageData();
        c.addCostResponse(Json.parse("{\"data\":{\"biz_data\":[{\"currency\":\"CNY\",\"total\":["
                + "{\"model\":\"m1\",\"usage\":[{\"type\":\"RESPONSE_TOKEN\",\"amount\":\"2.5\"}]}]}]}}"));
        eq(2.5d, round2(c.monthCost()), "month total fallback");
    }

    // ------------------------------------------------------------------ helpers

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    private static void eq(Object expected, Object actual, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        report(ok, what, expected, actual);
    }

    private static void eq(double expected, double actual, String what) {
        report(Math.abs(expected - actual) < 0.0001d, what, expected, actual);
    }

    private static void eq(long expected, long actual, String what) {
        report(expected == actual, what, expected, actual);
    }

    private static void eq(boolean expected, boolean actual, String what) {
        report(expected == actual, what, expected, actual);
    }

    private static void notNull(Object o, String what) {
        report(o != null, what, "non-null", o);
    }

    private static void report(boolean ok, String what, Object expected, Object actual) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what + "  expected=" + expected + " actual=" + actual);
        }
    }
}
