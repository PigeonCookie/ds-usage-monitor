package com.pigeoncookie.dsmonitor;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pigeoncookie.dsmonitor.core.Bg;
import com.pigeoncookie.dsmonitor.core.DeepSeekClient;
import com.pigeoncookie.dsmonitor.core.Fmt;
import com.pigeoncookie.dsmonitor.core.Prefs;
import com.pigeoncookie.dsmonitor.core.SampleStore;
import com.pigeoncookie.dsmonitor.core.UsageData;
import com.pigeoncookie.dsmonitor.core.UsageRepo;
import com.pigeoncookie.dsmonitor.data.HourlyCache;
import com.pigeoncookie.dsmonitor.data.TodayBackfill;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Page 2 — usage detail: three stacked bar charts on a single screen.
 *
 * <p>The 模型 / API Key switch chooses the split dimension; the chip next to it multi-selects the
 * items of that dimension (empty selection = all). Both, plus the range, are persisted so they
 * survive a restart.
 */
public class UsagePage extends Page {

    private static final int RANGE_TODAY = 0;
    private static final int RANGE_7D = 1;
    private static final int RANGE_30D = 2;
    private static final int RANGE_MONTH = 3;
    private static final int RANGE_CUSTOM = 4;
    private static final int RANGE_YESTERDAY = 5;
    /** 0 = the hourly view shows today, -1 = it shows yesterday. */
    private int viewDayOffset;
    /** ISO date of the day the hourly view shows (its own platform hourly cache). */
    private String viewDayIso;
    /** Hour that is still running (today) or 23 for a finished day. */
    private int viewRunningHour = 23;
    /** Platform data for the hour that is still running (its own window, may be partial). */
    private UsageData runningHour;
    /** Hours whose platform value is kept while the local sampling chain was patchy. */
    private boolean[] approxHours;
    private static final int MAX_SERIES = 6;
    private static final int MAX_LEGEND = 4;
    private static final int MAX_DAYS = 120;

    private final TextView chipRange;
    private final TextView chipKey;
    private final TextView usageHint;
    private final TextView statCost;
    private final TextView statRequests;
    private final TextView statTokens;
    private final TextView chartCostTotal;
    private final TextView chartReqTotal;
    private final TextView chartTokensTotal;
    private final TextView chartReqNote;
    private final TextView chartTokensNote;
    private final TextView segModel;
    private final TextView segKey;
    private final BarChartView chartCost;
    private final BarChartView chartReq;
    private final BarChartView chartTokens;
    private final LinearLayout costLegend;

    private int range;
    private int dim;
    private String customFrom = "";
    private String customTo = "";
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private boolean loading;
    private long lastLoadAt;

    // per-render state used by the tap tooltips
    private String[] bucketDates;
    private boolean hourly = true;
    private float[][] costStack;
    private List<String> costItems = new ArrayList<>();
    private int[] costColors;
    private float[] reqValues;
    private float[][] tokenStack;

    private static final class Spec {
        int range;
        String from;
        String to;
        String cacheKey;
        List<String> dates;
    }

    public UsagePage(MainActivity host, View root) {
        super(host, root);
        chipRange = root.findViewById(R.id.chip_range);
        chipKey = root.findViewById(R.id.chip_key);
        usageHint = root.findViewById(R.id.usage_hint);
        statCost = root.findViewById(R.id.stat_cost);
        statRequests = root.findViewById(R.id.stat_requests);
        statTokens = root.findViewById(R.id.stat_tokens);
        chartCostTotal = root.findViewById(R.id.chart_cost_total);
        chartReqTotal = root.findViewById(R.id.chart_req_total);
        chartTokensTotal = root.findViewById(R.id.chart_tokens_total);
        chartReqNote = root.findViewById(R.id.chart_req_note);
        chartTokensNote = root.findViewById(R.id.chart_tokens_note);
        segModel = root.findViewById(R.id.seg_model);
        segKey = root.findViewById(R.id.seg_total);
        chartCost = root.findViewById(R.id.chart_cost_view);
        chartReq = root.findViewById(R.id.chart_req_view);
        chartTokens = root.findViewById(R.id.chart_tokens_view);
        costLegend = root.findViewById(R.id.cost_legend);

        range = Prefs.usageRange(host);
        dim = Prefs.usageDim(host);
        customFrom = Prefs.usageFrom(host);
        customTo = Prefs.usageTo(host);
        loadSelection();
        if (range == RANGE_CUSTOM && (customFrom.isEmpty() || customTo.isEmpty())) {
            range = RANGE_TODAY;
        }

        chipRange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRangeDialog();
            }
        });
        chipKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showItemDialog();
            }
        });
        root.findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                range = RANGE_TODAY;
                selected.clear();
                persist();
                applyChips();
                load(true);
            }
        });
        root.findViewById(R.id.btn_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExportDialog();
            }
        });
        segModel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDim(UsageData.DIM_MODEL);
            }
        });
        segKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDim(UsageData.DIM_KEY);
            }
        });

        applyChips();
        applySegment();
        wireTooltips();
    }

    /** Every chart answers a tap with a popup: period, total and the per-series breakdown. */
    private void wireTooltips() {
        chartCost.setTooltipSource(new BarChartView.TooltipSource() {
            @Override
            public BarChartView.Tooltip tooltip(int index) {
                BarChartView.Tooltip tip = new BarChartView.Tooltip();
                tip.title = titleAt(index);
                double sum = barTotal(costStack, index);
                tip.total = Fmt.money(sum);
                boolean any = false;
                if (costStack != null && costColors != null && costItems.size() == costStack.length) {
                    for (int k = 0; k < costStack.length; k++) {
                        if (index >= costStack[k].length || costStack[k][index] <= 0) {
                            continue;
                        }
                        any = true;
                        tip.add(shortName(costItems.get(k)), Fmt.money(costStack[k][index]),
                                costColors[Math.min(k, costColors.length - 1)]);
                    }
                }
                if (!any) {
                    tip.add("消费金额", Fmt.money(sum), Ui.color(host, R.color.ds_chart));
                }
                return tip;
            }
        });
        chartReq.setTooltipSource(new BarChartView.TooltipSource() {
            @Override
            public BarChartView.Tooltip tooltip(int index) {
                BarChartView.Tooltip tip = new BarChartView.Tooltip();
                tip.title = titleAt(index);
                long value = reqValues != null && index < reqValues.length ? (long) reqValues[index] : 0;
                tip.total = Fmt.group(value);
                tip.add("请求次数", Fmt.group(value), Ui.color(host, R.color.ds_chart));
                return tip;
            }
        });
        chartTokens.setTooltipSource(new BarChartView.TooltipSource() {
            @Override
            public BarChartView.Tooltip tooltip(int index) {
                BarChartView.Tooltip tip = new BarChartView.Tooltip();
                tip.title = titleAt(index);
                long total = 0;
                if (tokenStack != null) {
                    for (float[] series : tokenStack) {
                        if (index < series.length) {
                            total += (long) series[index];
                        }
                    }
                }
                tip.total = Fmt.group(total);
                int[] colors = tokenColors();
                if (tokenStack != null && tokenStack.length >= 3) {
                    // 堆叠顺序是 [输出, 未命中, 命中]，弹窗按 命中/未命中/输出 读回去
                    if (approxHours != null && index < approxHours.length && approxHours[index]) {
                tip.add("本机未采全，以官网为准", "", colors[2]);
            }
            tip.add("输入（命中缓存）", Fmt.group((long) tokenStack[2][index]), colors[2]);
                    tip.add("输入（未命中缓存）", Fmt.group((long) tokenStack[1][index]), colors[1]);
                    tip.add("输出", Fmt.group((long) tokenStack[0][index]), colors[0]);
                } else {
                    tip.add("Tokens", Fmt.group(total), colors[2]);
                }
                return tip;
            }
        });
    }

    private String titleAt(int index) {
        if (hourly) {
            return Fmt.hourLabel(index) + " ~ " + Fmt.hourLabel(index + 1);
        }
        if (bucketDates != null && index < bucketDates.length) {
            return bucketDates[index];
        }
        return "";
    }

    private int tokenColorFor(int tokenType) {
        switch (tokenType) {
            case UsageData.TOKEN_HIT:
                return Ui.color(host, R.color.ds_tok_hit);
            case UsageData.TOKEN_MISS:
                return Ui.color(host, R.color.ds_tok_miss);
            default:
                return Ui.color(host, R.color.ds_tok_out);
        }
    }

    /** Stack order is bottom→top: output, cache-miss, cache-hit (matches the console chart). */
    private int[] tokenColors() {
        return new int[]{
                tokenColorFor(UsageData.TOKEN_OUT),
                tokenColorFor(UsageData.TOKEN_MISS),
                tokenColorFor(UsageData.TOKEN_HIT)};
    }

    /** Coloured inline legend for the stacked token chart (replaces the plain model summary). */
    private void setTokenNote(boolean stacked, String modelSummary) {
        if (!stacked) {
            chartTokensNote.setText(modelSummary);
            return;
        }
        String[] labels = {"命中", "未命中", "输出"};
        int[] colors = {tokenColorFor(UsageData.TOKEN_HIT), tokenColorFor(UsageData.TOKEN_MISS),
                tokenColorFor(UsageData.TOKEN_OUT)};
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            int start = sb.length();
            sb.append("●").append(labels[i]);
            sb.setSpan(new ForegroundColorSpan(colors[i]), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        chartTokensNote.setText(sb);
    }

    /** Today's tokens split by type for the stacked chart, or the plain total when unavailable. */
    private float[][] tokenSeriesToday() {
        float[][] series = new float[][]{
                toFloats(SampleStore.hourlyTokenTotals(host, UsageData.TOKEN_OUT)),
                toFloats(SampleStore.hourlyTokenTotals(host, UsageData.TOKEN_MISS)),
                toFloats(SampleStore.hourlyTokenTotals(host, UsageData.TOKEN_HIT))};
        if (allZero(series)) {
            return new float[][]{toFloats(SampleStore.hourly(host, SampleStore.FIELD_TOKENS))};
        }
        return series;
    }

    /**
     * Hourly values taken from the platform cache: every finished hour is exactly the window
     * {@code [H:00, H+1:00)}, and the hour still running gets whatever the day total has left.
     */
    private float[] platformHours(java.util.LinkedHashMap<Integer, HourlyCache.Hour> cache,
            int dimension, java.util.List<String> items, int field, double dayTotal) {
        int currentHour = viewRunningHour;
        float[] out = new float[24];
        double finished = 0;
        boolean any = false;
        for (java.util.Map.Entry<Integer, HourlyCache.Hour> entry : cache.entrySet()) {
            int hour = entry.getKey();
            if (hour < 0 || hour > 23 || hour >= currentHour) {
                continue;
            }
            double value = 0;
            if (items == null || items.isEmpty()) {
                value = field == HourlyCache.COST ? entry.getValue().cost
                        : field == HourlyCache.REQUESTS ? entry.getValue().requests
                        : entry.getValue().tokens;
            } else {
                for (String item : items) {
                    value += entry.getValue().item(dimension, item, field);
                }
            }
            out[hour] = (float) value;
            finished += value;
            any = true;
        }
        if (!any || finished <= 0) {
            // nothing usable for the selected items — fall back to the sampled history
            return null;
        }
        out[currentHour] = (float) Math.max(0, dayTotal - finished);
        return out;
    }

    /**
     * Per-hour 命中/未命中/输出 taken from the platform cache, in the stack order
     * {@code [输出, 未命中, 命中]}; the hour still running takes the day total minus the finished
     * hours. Returns null when the cached hours carry no token-kind data.
     */
    private float[][] platformTokenSplit(
            java.util.LinkedHashMap<Integer, HourlyCache.Hour> cache, int dimension,
            java.util.List<String> items, UsageData.Slice stat) {
        int currentHour = viewRunningHour;
        float[][] out = new float[3][24];
        double[] finished = new double[3];
        boolean any = false;
        for (java.util.Map.Entry<Integer, HourlyCache.Hour> entry : cache.entrySet()) {
            int hour = entry.getKey();
            if (hour < 0 || hour > 23 || hour >= currentHour) {
                continue;
            }
            double hit = 0;
            double miss = 0;
            double outs = 0;
            if (items == null || items.isEmpty()) {
                hit = entry.getValue().field(HourlyCache.HIT);
                miss = entry.getValue().field(HourlyCache.MISS);
                outs = entry.getValue().field(HourlyCache.OUT);
            } else {
                for (String item : items) {
                    hit += entry.getValue().item(dimension, item, HourlyCache.HIT);
                    miss += entry.getValue().item(dimension, item, HourlyCache.MISS);
                    outs += entry.getValue().item(dimension, item, HourlyCache.OUT);
                }
            }
            if (hit + miss + outs <= 0) {
                continue;
            }
            out[0][hour] = (float) outs;
            out[1][hour] = (float) miss;
            out[2][hour] = (float) hit;
            finished[0] += outs;
            finished[1] += miss;
            finished[2] += hit;
            any = true;
        }
        if (!any) {
            return null;
        }
        if (finished[0] + finished[1] + finished[2] <= 0) {
            return null; // no kind data for the selected items — keep the ratio based split
        }
        out[0][currentHour] = (float) Math.max(0, stat.outTokens - finished[0]);
        out[1][currentHour] = (float) Math.max(0, stat.missTokens - finished[1]);
        out[2][currentHour] = (float) Math.max(0, stat.hitTokens - finished[2]);
        return out;
    }

    /** Days between two ISO dates (inclusive), capped. */
    private static java.util.List<String> isoDays(String from, String to, int cap) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(from.substring(0, 4)), Integer.parseInt(from.substring(5, 7)) - 1,
                Integer.parseInt(from.substring(8, 10)), 0, 0, 0);
        java.util.Calendar end = java.util.Calendar.getInstance();
        end.clear();
        end.set(Integer.parseInt(to.substring(0, 4)), Integer.parseInt(to.substring(5, 7)) - 1,
                Integer.parseInt(to.substring(8, 10)), 0, 0, 0);
        while (!c.after(end) && out.size() < cap) {
            out.add(String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)));
            c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /**
     * Range view: every local day fetched on its own window, so a bar is a local day — matching the
     * console — instead of a UTC day shifted by the time-zone offset. Short ranges only, longer ones
     * fall back to the single UTC-aligned request.
     */
    private UsageData fetchLocalDays(String token, String from, String to)
            throws DeepSeekClient.ApiException {
        java.util.List<String> days = isoDays(from, to, 62);
        if (days.isEmpty()) {
            long[] window = DeepSeekClient.utcWindow(from, to);
            return DeepSeekClient.fetchExportRange(token, window[0], window[1]);
        }
        UsageData all = new UsageData();
        boolean any = false;
        for (int i = 0; i < days.size(); i++) {
            final String day = days.get(i);
            final int done = i;
            Bg.ui(new Runnable() {
                @Override
                public void run() {
                    usageHint.setText("正在加载 " + day + "（" + (done + 1) + "/" + days.size() + " 天）");
                }
            });
            try {
                all.addDayAs(day, DeepSeekClient.fetchLocalDay(host, token, day));
                any = true;
            } catch (DeepSeekClient.ApiException ignored) {
                // skip a day that failed, keep the rest of the range
            }
        }
        if (!any) {
            long[] window = DeepSeekClient.utcWindow(from, to);
            return DeepSeekClient.fetchExportRange(token, window[0], window[1]);
        }
        return all;
    }

    private static float[] scaleOf(float[] src, double factor) {
        float[] out = new float[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = (float) (src[i] * factor);
        }
        return out;
    }

    private static double seriesTotal(float[][] series) {
        double sum = 0;
        if (series != null) {
            for (float[] row : series) {
                for (float v : row) {
                    sum += v;
                }
            }
        }
        return sum;
    }

    /**
     * The platform only reports per-day numbers, so the hourly shape comes from local samples while
     * the day total comes from the export. If the two disagree (samples taken across a UTC-day
     * boundary can include a neighbour day), rescale the hourly series so it adds up to the card
     * value — the chart, the tooltips and the cards then always tell the same story.
     */
    private static boolean calibrate(float[][] series, double target) {
        double raw = seriesTotal(series);
        if (raw <= 0 || target <= 0) {
            return false;
        }
        double tolerance = Math.max(0.005d, target * 0.015d);
        if (Math.abs(raw - target) <= tolerance) {
            return false;
        }
        double factor = target / raw;
        for (float[] row : series) {
            for (int i = 0; i < row.length; i++) {
                row[i] = (float) (row[i] * factor);
            }
        }
        return true;
    }

    private static double barTotal(float[][] stack, int index) {
        double sum = 0;
        if (stack != null) {
            for (float[] series : stack) {
                if (index < series.length) {
                    sum += series[index];
                }
            }
        }
        return sum;
    }

    @Override
    public void onShown() {
        applyChips();
        applySegment();
        if (System.currentTimeMillis() - lastLoadAt > 60_000L) {
            load(false);
        } else {
            render();
        }
    }

    @Override
    public void refresh() {
        load(true);
    }

    /** Called by the activity on every touch: taps outside a chart close its tooltip. */
    public void dismissTooltipsOutside(float rawX, float rawY) {
        if (!isInside(chartCost, rawX, rawY)) {
            chartCost.clearSelection();
        }
        if (!isInside(chartReq, rawX, rawY)) {
            chartReq.clearSelection();
        }
        if (!isInside(chartTokens, rawX, rawY)) {
            chartTokens.clearSelection();
        }
    }

    private static boolean isInside(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getWidth() == 0) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX <= location[0] + view.getWidth()
                && rawY >= location[1] && rawY <= location[1] + view.getHeight();
    }

    // ------------------------------------------------------------------ state

    private void loadSelection() {
        selected.clear();
        for (String s : Prefs.usageSelection(host, dim).split("\n")) {
            if (!s.trim().isEmpty()) {
                selected.add(s.trim());
            }
        }
    }

    private void persist() {
        Prefs.setUsageRange(host, range);
        Prefs.setUsageDim(host, dim);
        Prefs.setUsageSelection(host, dim, join(selected));
        Prefs.setUsageCustom(host, customFrom, customTo);
    }

    private static String join(Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private void setDim(int newDim) {
        if (newDim == dim) {
            return;
        }
        Prefs.setUsageSelection(host, dim, join(selected));
        dim = newDim;
        loadSelection();
        persist();
        applySegment();
        applyChips();
        render();
    }

    private void applySegment() {
        boolean model = dim == UsageData.DIM_MODEL;
        segModel.setBackgroundResource(model ? R.drawable.bg_segment_on : 0);
        segModel.setTextColor(Ui.color(host, model ? R.color.ds_seg_on_fg : R.color.ds_seg_fg));
        segKey.setBackgroundResource(model ? 0 : R.drawable.bg_segment_on);
        segKey.setTextColor(Ui.color(host, model ? R.color.ds_seg_fg : R.color.ds_seg_on_fg));
    }

    private void applyChips() {
        chipRange.setText(host.getString(R.string.filter_time) + " · " + rangeLabel());
        chipKey.setText(dimLabel() + " · " + selectionLabel());
    }

    private String dimLabel() {
        return host.getString(dim == UsageData.DIM_MODEL ? R.string.filter_model : R.string.filter_key);
    }

    private String selectionLabel() {
        if (selected.isEmpty()) {
            return host.getString(R.string.filter_all);
        }
        List<String> list = new ArrayList<>(selected);
        if (list.size() == 1) {
            return list.get(0);
        }
        return shortName(list.get(0)) + " 等 " + list.size() + " 项";
    }

    private String rangeLabel() {
        switch (range) {
            case RANGE_YESTERDAY:
                return "昨天";
            case RANGE_7D:
                return host.getString(R.string.range_7d);
            case RANGE_30D:
                return host.getString(R.string.range_30d);
            case RANGE_MONTH:
                return host.getString(R.string.range_month);
            case RANGE_CUSTOM:
                return Fmt.shortDate(customFrom) + " - " + Fmt.shortDate(customTo);
            default:
                return host.getString(R.string.range_today);
        }
    }

    // ------------------------------------------------------------------ range spec

    private Spec spec() {
        Spec s = new Spec();
        s.range = range;
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        String to = iso(now);
        switch (range) {
            case RANGE_7D:
                s.from = iso(shiftDays(now, -6));
                s.to = to;
                break;
            case RANGE_30D:
                s.from = iso(shiftDays(now, -29));
                s.to = to;
                break;
            case RANGE_MONTH:
                Calendar first = (Calendar) now.clone();
                first.set(Calendar.DAY_OF_MONTH, 1);
                s.from = iso(first);
                s.to = to;
                break;
            case RANGE_CUSTOM:
                s.from = customFrom;
                s.to = customTo;
                break;
            case RANGE_YESTERDAY: {
                Calendar yesterday = Calendar.getInstance();
                yesterday.add(Calendar.DAY_OF_MONTH, -1);
                s.from = String.format(java.util.Locale.US, "%04d-%02d-%02d",
                        yesterday.get(Calendar.YEAR), yesterday.get(Calendar.MONTH) + 1,
                        yesterday.get(Calendar.DAY_OF_MONTH));
                s.to = s.from;
                break;
            }
            default:
                s.from = null;
                s.to = null;
                break;
        }
        if (s.range == RANGE_TODAY) {
            Calendar local = Calendar.getInstance();
            s.cacheKey = "today:" + String.format(Locale.US, "%04d-%02d-%02d", local.get(Calendar.YEAR),
                    local.get(Calendar.MONTH) + 1, local.get(Calendar.DAY_OF_MONTH));
        } else {
            s.cacheKey = s.range + ":" + s.from + ":" + s.to;
            s.dates = datesBetween(s.from, s.to);
        }
        return s;
    }

    private static List<String> datesBetween(String from, String to) {
        List<String> out = new ArrayList<>();
        Calendar c = parseIso(from);
        Calendar end = parseIso(to);
        if (c == null || end == null) {
            return out;
        }
        while (!c.after(end) && out.size() < MAX_DAYS) {
            out.add(iso(c));
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    private static Calendar shiftDays(Calendar base, int days) {
        Calendar c = (Calendar) base.clone();
        c.add(Calendar.DAY_OF_MONTH, days);
        return c;
    }

    private static Calendar parseIso(String iso) {
        if (iso == null || iso.length() < 10) {
            return null;
        }
        try {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1,
                    Integer.parseInt(iso.substring(8, 10)));
            return c;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String iso(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    // ------------------------------------------------------------------ loading

    private void load(final boolean force) {
        final String token = Prefs.userToken(host);
        if (token.isEmpty()) {
            render();
            return;
        }
        if (loading) {
            return;
        }
        final Spec spec = spec();
        long now = System.currentTimeMillis();
        if (!force && UsageRepo.fresh(spec.cacheKey, now)) {
            render();
            return;
        }
        loading = true;
        host.beginLoad();
        usageHint.setText("正在加载用量数据…");
        Bg.run(new Runnable() {
            @Override
            public void run() {
                UsageData data = null;
                String error = null;
                boolean byKey = false;
                try {
                    if (spec.range == RANGE_YESTERDAY) {
                        data = DeepSeekClient.fetchLocalDay(host, token, spec.from);
                        byKey = true;
                        runningHour = null;
        approxHours = null;
                        // 昨天的逐时同样按小时向平台取（24 次，一次后落盘缓存）
                        if (TodayBackfill.needed(host, spec.from, 23)) {
                            final String dayLabel = spec.from;
                            TodayBackfill.fill(host, token, spec.from, 23, new TodayBackfill.Progress() {
                                @Override
                                public void onProgress(final int done, final int total) {
                                    Bg.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            usageHint.setText("正在补齐 " + dayLabel + " 逐时数据… "
                                                    + done + "/24");
                                        }
                                    });
                                }
                            });
                        }
                    } else if (spec.range == RANGE_TODAY) {
                        long[] window = DeepSeekClient.todayWindow();
                        data = DeepSeekClient.fetchExportRange(token, window[0], window[1]);
                        byKey = true;
                        try {
                            long[] cur = DeepSeekClient.currentHourWindow();
                            runningHour = DeepSeekClient.fetchExportRange(token, cur[0], cur[1]);
                        } catch (DeepSeekClient.ApiException ignored) {
                            runningHour = null;
                        }
                        // 补齐"打开应用之前"的那几个小时，否则整天会堆在第一根柱子里
                        if (TodayBackfill.needed(host)) {
                            TodayBackfill.run(host, token, new TodayBackfill.Progress() {
                                @Override
                                public void onProgress(final int done, final int total) {
                                    Bg.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            usageHint.setText("正在补齐今天逐时数据… " + done + "/" + total);
                                        }
                                    });
                                }
                            });
                        }
                    } else {
                        try {
                            data = fetchLocalDays(token, spec.from, spec.to);
                            byKey = true;
                        } catch (DeepSeekClient.ApiException exportFailed) {
                            data = fetchMonths(token, spec.from, spec.to);
                            byKey = false;
                        }
                    }
                } catch (DeepSeekClient.ApiException e) {
                    error = e.getMessage();
                }
                final UsageData result = data;
                final String failure = error;
                final boolean keySplit = byKey;
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        lastLoadAt = System.currentTimeMillis();
                        host.endLoad();
                        UsageRepo.data = result;
                        UsageRepo.rangeKey = spec.cacheKey;
                        UsageRepo.at = System.currentTimeMillis();
                        UsageRepo.byKeyAvailable = keySplit;
                        UsageRepo.error = failure == null ? "" : failure;
                        render();
                    }
                });
            }
        });
    }

    private static UsageData fetchMonths(String token, String from, String to)
            throws DeepSeekClient.ApiException {
        UsageData acc = new UsageData();
        Calendar c = parseIso(from);
        Calendar end = parseIso(to);
        if (c == null || end == null) {
            return acc;
        }
        int guard = 0;
        while (!c.after(end) && guard++ < 6) {
            acc.merge(DeepSeekClient.fetchUsageMonth(token, c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1));
            c.add(Calendar.MONTH, 1);
        }
        return acc;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        applyChips();
        applySegment();
        if (Prefs.userToken(host).isEmpty()) {
            renderNoToken();
            return;
        }
        UsageData data = UsageRepo.data;
        if (data == null) {
            renderEmpty(UsageRepo.error.isEmpty() ? "暂无数据，点击左上角重载" : UsageRepo.error);
            return;
        }
        viewDayOffset = range == RANGE_YESTERDAY ? -1 : 0;
        viewDayIso = DeepSeekClient.isoToday();
        viewRunningHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (viewDayOffset != 0) {
            Calendar back = Calendar.getInstance();
            back.add(Calendar.DAY_OF_MONTH, viewDayOffset);
            viewDayIso = String.format(java.util.Locale.US, "%04d-%02d-%02d", back.get(Calendar.YEAR),
                    back.get(Calendar.MONTH) + 1, back.get(Calendar.DAY_OF_MONTH));
            viewRunningHour = 23;
        }
        if (range == RANGE_TODAY || range == RANGE_YESTERDAY) {
            renderToday(data);
        } else {
            renderDays(data);
        }
    }

    private void renderNoToken() {
        costStack = null;
        costItems = new ArrayList<>();
        costColors = null;
        reqValues = null;
        tokenStack = null;
        bucketDates = null;
        statCost.setText("¥0.00");
        statRequests.setText("0");
        statTokens.setText("0");
        chartCostTotal.setText("¥0.00");
        chartReqTotal.setText("0");
        chartTokensTotal.setText("0");
        chartReqNote.setText("");
        chartTokensNote.setText("");
        String hint = "未配置平台 User Token，API Key 只能查询余额";
        usageHint.setText(hint);
        updateLegend(null, null);
        chartCost.setAxisMode(BarChartView.AXIS_MONEY);
        chartCost.setEmptyText("在「我的」填入平台 User Token");
        chartReq.setAxisMode(BarChartView.AXIS_COUNT);
        chartReq.setEmptyText(hint);
        chartTokens.setAxisMode(BarChartView.AXIS_COUNT);
        chartTokens.setEmptyText(hint);
        chartCost.setBars(null, hourTicks());
        chartReq.setBars(null, null);
        chartTokens.setBars(null, null);
    }

    private void renderEmpty(String message) {
        costStack = null;
        costItems = new ArrayList<>();
        costColors = null;
        reqValues = null;
        tokenStack = null;
        bucketDates = null;
        usageHint.setText(message);
        chartCost.setAxisMode(BarChartView.AXIS_MONEY);
        chartCost.setEmptyText(message);
        chartReq.setAxisMode(BarChartView.AXIS_COUNT);
        chartReq.setEmptyText(message);
        chartTokens.setAxisMode(BarChartView.AXIS_COUNT);
        chartTokens.setEmptyText(message);
        chartCost.setBars(null, null);
        chartReq.setBars(null, null);
        chartTokens.setBars(null, null);
        updateLegend(null, null);
    }

    private void renderToday(UsageData data) {
        List<String> available = availableItems(data);
        List<String> matched = matchedItems(available);
        List<String> effective = effectiveItems(matched);
        UsageData.Slice stat = sumOf(data, matched, true);

        statCost.setText(Fmt.money(stat.cost));
        statRequests.setText(Fmt.group(stat.requests));
        statTokens.setText(Fmt.group(stat.tokens));
        chartCostTotal.setText(Fmt.money(stat.cost));
        chartReqTotal.setText(Fmt.group(stat.requests));
        chartTokensTotal.setText(Fmt.group(stat.tokens));

        String[] ticks = hourTicks();
        chartCost.setAxisMode(BarChartView.AXIS_MONEY);
        chartCost.setEmptyText("暂无逐时数据");
        chartReq.setAxisMode(BarChartView.AXIS_COUNT);
        chartReq.setEmptyText("暂无逐时数据");
        chartTokens.setAxisMode(BarChartView.AXIS_COUNT);
        chartTokens.setEmptyText("暂无逐时数据");

        int[] colors = palette(effective.size());
        boolean perItem = !matched.isEmpty() && SampleStore.hasItemStats(host, dim);
        // 平台逐时优先（单选时每个桶就是平台该小时的窗口值，和官网同一口径）
        java.util.LinkedHashMap<Integer, HourlyCache.Hour> platform =
                viewDayIso != null && HourlyCache.usable(host, viewDayIso)
                        ? HourlyCache.load(host, viewDayIso) : null;
        if (platform != null) {
            approxHours = new boolean[24];
            for (java.util.Map.Entry<Integer, HourlyCache.Hour> e : platform.entrySet()) {
                if (e.getKey() >= 0 && e.getKey() < 24) {
                    approxHours[e.getKey()] = e.getValue().approx;
                }
            }
        }
        float[][] series = null;
        boolean fromPlatform = false;
        if (platform != null) {
            if (effective.size() > 1) {
                // 多选：平台逐时也要按项拆成多条系列（每项自己的当日合计用来补正在走的小时）
                float[][] multi = new float[effective.size()][24];
                double[] shares = new double[effective.size()];
                double shareSum = 0;
                boolean anyItem = false;
                for (int i = 0; i < effective.size(); i++) {
                    java.util.List<String> one = java.util.Collections.singletonList(effective.get(i));
                    // 已结束的小时按 Key 从平台缓存取；正在走的小时统一在下面按比例分配
                    float[] hours = platformHours(platform, dim, one, HourlyCache.COST, 0);
                    if (hours != null) {
                        multi[i] = hours;
                        anyItem = true;
                    }
                    shares[i] = Math.max(0, data.sliceAll(dim, effective.get(i)).cost);
                    shareSum += shares[i];
                }
                if (anyItem) {
                    // 选区合计 − 各 Key 已结束小时之和 = 正在走的小时，再按各 Key 当日合计比例拆分
                    double finished = 0;
                    for (int i = 0; i < multi.length; i++) {
                        for (int h = 0; h < viewRunningHour && h < 24; h++) {
                            finished += multi[i][h];
                        }
                    }
                    if (runningHour != null) {
                        // 平台对"当前小时自己的窗口"会给进行中的实值：总额与分 Key 都直接用真的
                        for (int i = 0; i < multi.length; i++) {
                            multi[i][viewRunningHour] = (float)
                                    runningHour.sliceAll(dim, effective.get(i)).cost;
                        }
                    } else {
                        double rest = Math.max(0, stat.cost - finished);
                        for (int i = 0; i < multi.length; i++) {
                            double share = shareSum > 0 ? shares[i] / shareSum : 1.0 / multi.length;
                            multi[i][viewRunningHour] = (float) (rest * share);
                        }
                    }
                    series = multi;
                    fromPlatform = true;
                }
            } else {
                float[] hours = platformHours(platform, dim, effective, HourlyCache.COST, stat.cost);
                if (hours != null && !allZero(new float[][]{hours})) {
                    if (runningHour != null && !effective.isEmpty()) {
                        hours[viewRunningHour] = (float)
                                runningHour.sliceAll(dim, effective.get(0)).cost;
                    }
                    series = new float[][]{hours};
                    fromPlatform = true;
                }
            }
        }
        if (series == null && perItem && !effective.isEmpty()) {
            series = new float[effective.size()][24];
            for (int i = 0; i < effective.size(); i++) {
                series[i] = toFloats(SampleStore.hourlyFor(host, viewDayOffset, dim,
                        java.util.Collections.singletonList(effective.get(i)), SampleStore.KIND_COST));
            }
        }
        boolean fellBack = false;
        if (series == null || allZero(series)) {
            series = new float[][]{toFloats(SampleStore.hourlyFor(host, viewDayOffset, dim, null,
                    SampleStore.KIND_COST))};
            fellBack = true;
            fromPlatform = false;
        }
        boolean calibrated = false;
        if (perItem && !fellBack && !fromPlatform) {
            calibrated |= calibrate(series, stat.cost);
        }
        if (series.length > 1) {
            chartCost.setStacked(series, colors, ticks);
        } else {
            chartCost.setBars(series[0], ticks);
        }
        updateLegend(fellBack ? null : effective, colors);

        hourly = true;
        costStack = series;
        costItems = fellBack ? new ArrayList<String>() : new ArrayList<>(effective);
        costColors = fellBack ? new int[]{Ui.color(host, R.color.ds_chart)} : colors;

        float[] platformRequests = platform == null ? null
                : platformHours(platform, dim, effective, HourlyCache.REQUESTS, stat.requests);
        reqValues = platformRequests != null ? platformRequests
                : toFloats(SampleStore.hourlyFor(host, viewDayOffset, dim, perItem ? matched : null,
                SampleStore.KIND_REQUESTS));

        // 命中/未命中/输出 的比例取自当日合计（导出接口，口径与官网一致），逐时总量取自本机采样；
        // 这样即使历史采样是旧规则写入的，拆分也会立刻正确
        double hitTokens = stat.hitTokens;
        double missTokens = stat.missTokens;
        double outTokens = stat.outTokens;
        double typeSum = hitTokens + missTokens + outTokens;
        // 逐时总量必须也只统计选中的项（之前误用了采样里的全量合计）
        float[] platformTokens = platform == null ? null
                : platformHours(platform, dim, effective, HourlyCache.TOKENS, stat.tokens);
        float[] hourlyTokens;
        if (platformTokens != null) {
            hourlyTokens = platformTokens;
        } else {
            hourlyTokens = toFloats(SampleStore.hourlyFor(host, viewDayOffset, dim,
                    perItem ? matched : null, SampleStore.KIND_TOKENS));
        }
        float[][] platformSplit = platform == null ? null
                : platformTokenSplit(platform, dim, effective, stat);
        if (platformSplit != null) {
            // 平台逐时自带每小时的三种 token 拆分，直接用，不再按当日比例平摊
            tokenStack = platformSplit;
        } else if (typeSum > 0 && !allZero(new float[][]{hourlyTokens})) {
            tokenStack = new float[][]{
                    scaleOf(hourlyTokens, outTokens / typeSum),
                    scaleOf(hourlyTokens, missTokens / typeSum),
                    scaleOf(hourlyTokens, hitTokens / typeSum)};
        } else if (viewDayOffset == 0) {
            tokenStack = tokenSeriesToday();
        } else {
            // yesterday without any local samples: show an empty chart instead of today's shape
            tokenStack = new float[3][24];
        }
        if (perItem) {
            calibrated |= calibrate(new float[][]{reqValues}, stat.requests);
            calibrated |= calibrate(tokenStack, stat.tokens);
        }
        // 当前这一小时：请求数与三种 Token 同样取平台"这一小时窗口"的实值（消费金额已在上面处理）
        if (runningHour != null && platform != null) {
            double req = 0;
            double hit = 0;
            double miss = 0;
            double outs = 0;
            if (effective.isEmpty()) {
                UsageData.Slice all = runningHour.sumAll();
                req = all.requests;
                hit = all.hitTokens;
                miss = all.missTokens;
                outs = all.outTokens;
            } else {
                for (String item : effective) {
                    UsageData.Slice one = runningHour.sliceAll(dim, item);
                    req += one.requests;
                    hit += one.hitTokens;
                    miss += one.missTokens;
                    outs += one.outTokens;
                }
            }
            if (reqValues != null && viewRunningHour < reqValues.length) {
                reqValues[viewRunningHour] = (float) req;
            }
            if (tokenStack != null && tokenStack.length >= 3
                    && viewRunningHour < tokenStack[0].length) {
                tokenStack[0][viewRunningHour] = (float) outs;
                tokenStack[1][viewRunningHour] = (float) miss;
                tokenStack[2][viewRunningHour] = (float) hit;
            }
        }
        chartReq.setBars(reqValues, ticks);
        if (tokenStack.length > 1) {
            chartTokens.setStacked(tokenStack, tokenColors(), ticks);
        } else {
            chartTokens.setBars(tokenStack[0], ticks);
        }
        chartReqNote.setText(fromPlatform || platformRequests != null
                ? "逐时（平台逐时 " + (platform == null ? 0 : platform.size()) + " 小时）"
                : "逐时（本机采样）");
        setTokenNote(tokenStack.length > 1, modelSummary(data));

        int samples = SampleStore.today(host).size();
        StringBuilder hint = new StringBuilder();
        int hours = range == RANGE_YESTERDAY ? 24 : Math.max(1, viewRunningHour + 1);
        hint.append("时均 ¥").append(Fmt.money2(stat.cost / hours)).append("/时");
        if (!selected.isEmpty()) {
            hint.append(" · 已选 ").append(selected.size()).append(" 项");
        }
        if (samples < 2) {
            hint.append(" · 平台逐时取不到时，逐时曲线改用本机采样（可在「我的」开启后台采样）");
        } else if (!perItem && !selected.isEmpty()) {
            hint.append(" · 本次采样尚未按").append(dimLabel()).append("拆分，图表先按合计显示");
        } else {
            hint.append(" · 本机采样 ").append(samples).append(" 次");
        }
        if (calibrated) {
            hint.append(" · 逐时已按今日合计校准");
        }
        hint.append(" · 更新 ").append(clock());
        usageHint.setText(hint.toString());
    }

    /** Current wall clock as HH:mm. */
    private static String clock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    private void renderDays(UsageData data) {
        Spec spec = spec();
        List<String> dates = spec.dates == null ? new ArrayList<String>() : spec.dates;
        if (dates.isEmpty()) {
            renderEmpty("时间范围无效");
            return;
        }
        List<String> available = availableItems(data);
        List<String> matched = matchedItems(available);
        List<String> effective = effectiveItems(matched);
        UsageData.Slice stat = sumOf(data, matched, false);

        statCost.setText(Fmt.money(stat.cost));
        statRequests.setText(Fmt.group(stat.requests));
        statTokens.setText(Fmt.group(stat.tokens));
        chartCostTotal.setText(Fmt.money(stat.cost));
        chartReqTotal.setText(Fmt.group(stat.requests));
        chartTokensTotal.setText(Fmt.group(stat.tokens));

        String[] ticks = sparseDayTicks(dates);
        chartCost.setAxisMode(BarChartView.AXIS_MONEY);
        chartCost.setEmptyText("暂无数据");
        chartReq.setAxisMode(BarChartView.AXIS_COUNT);
        chartReq.setEmptyText("暂无数据");
        chartTokens.setAxisMode(BarChartView.AXIS_COUNT);
        chartTokens.setEmptyText("暂无数据");

        boolean perItem = !matched.isEmpty() && (dim == UsageData.DIM_MODEL || UsageRepo.byKeyAvailable);
        float[] tokens = new float[dates.size()];
        float[] requests = new float[dates.size()];
        float[] hit = new float[dates.size()];
        float[] miss = new float[dates.size()];
        float[] out = new float[dates.size()];
        if (perItem) {
            for (String item : matched) {
                for (int i = 0; i < dates.size(); i++) {
                    UsageData.Day d = data.days.get(dates.get(i));
                    UsageData.Slice s = d == null ? null : d.dimOrNull(dim, item);
                    if (s == null) {
                        continue;
                    }
                    tokens[i] += s.tokens;
                    requests[i] += s.requests;
                    hit[i] += s.hitTokens;
                    miss[i] += s.missTokens;
                    out[i] += s.outTokens;
                }
            }
        } else {
            for (int i = 0; i < dates.size(); i++) {
                UsageData.Day d = data.days.get(dates.get(i));
                if (d == null) {
                    continue;
                }
                tokens[i] = d.total.tokens;
                requests[i] = d.total.requests;
                hit[i] = d.total.hitTokens;
                miss[i] = d.total.missTokens;
                out[i] = d.total.outTokens;
            }
        }

        int[] colors = palette(effective.size());
        boolean keyModeWithoutKeys = dim == UsageData.DIM_KEY && !UsageRepo.byKeyAvailable;
        hourly = false;
        bucketDates = dates.toArray(new String[0]);
        reqValues = requests;
        boolean anyType = !allZero(new float[][]{hit, miss, out});
        tokenStack = anyType
                ? new float[][]{out, miss, hit}
                : new float[][]{tokens};
        if (!effective.isEmpty() && !keyModeWithoutKeys) {
            float[][] series = new float[effective.size()][dates.size()];
            for (int i = 0; i < dates.size(); i++) {
                UsageData.Day d = data.days.get(dates.get(i));
                if (d == null) {
                    continue;
                }
                for (int k = 0; k < effective.size(); k++) {
                    UsageData.Slice s = d.dimOrNull(dim, effective.get(k));
                    if (s != null) {
                        series[k][i] = (float) s.cost;
                    }
                }
            }
            costStack = series;
            costItems = new ArrayList<>(effective);
            costColors = colors;
            if (series.length > 1) {
                chartCost.setStacked(series, colors, ticks);
            } else {
                chartCost.setBars(series[0], ticks);
            }
            updateLegend(effective, colors);
        } else {
            float[] costs = new float[dates.size()];
            for (int i = 0; i < dates.size(); i++) {
                UsageData.Day d = data.days.get(dates.get(i));
                if (d != null) {
                    costs[i] = (float) d.total.cost;
                }
            }
            costStack = new float[][]{costs};
            costItems = new ArrayList<>();
            costColors = new int[]{Ui.color(host, R.color.ds_chart)};
            chartCost.setBars(costs, ticks);
            updateLegend(null, null);
        }
        chartReq.setBars(requests, ticks);
        if (tokenStack.length > 1) {
            chartTokens.setStacked(tokenStack, tokenColors(), ticks);
        } else {
            chartTokens.setBars(tokenStack[0], ticks);
        }
        chartReqNote.setText(rangeLabel() + " · 按天 · 已加载 " + data.days().size() + " 天");
        setTokenNote(tokenStack.length > 1, modelSummary(data));

        String note = keyModeWithoutKeys ? " · 该区间无法按 API Key 拆分（已按合计显示）" : "";
        usageHint.setText(rangeLabel() + " · 日均 ¥" + Fmt.money2(stat.cost / Math.max(1, dates == null || dates.isEmpty() ? 1 : dates.size())) + "/天 · 已选 " + (selected.isEmpty() ? "全部" : selected.size() + " 项") + " · 更新 " + clock());
    }

    private String selectedSummary() {
        if (selected.isEmpty()) {
            return "全部" + dimLabel();
        }
        return "已选 " + selected.size() + " 项";
    }

    // ------------------------------------------------------------------ item helpers

    /** Items of the active dimension present in the current range, biggest cost first. */
    private List<String> availableItems(UsageData data) {
        List<String> out = new ArrayList<>();
        if (range == RANGE_TODAY) {
            for (Map.Entry<String, Double> e : SampleStore.todayItemTotals(host, dim)) {
                out.add(e.getKey());
            }
            // whatever the platform reports but the local samples have not seen yet (new key, or
            // background sampling switched off) must still be selectable
            for (Map.Entry<String, Double> e : data.itemTotals(dim, null, null)) {
                if (!out.contains(e.getKey())) {
                    out.add(e.getKey());
                }
            }
        } else {
            Spec spec = spec();
            for (Map.Entry<String, Double> e : data.itemTotals(dim, spec.from, spec.to)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Selection intersected with what the range actually contains (uncapped). */
    private List<String> matchedItems(List<String> available) {
        if (selected.isEmpty()) {
            return new ArrayList<>(available);
        }
        List<String> hits = new ArrayList<>();
        for (String item : available) {
            if (selected.contains(item)) {
                hits.add(item);
            }
        }
        return hits.isEmpty() ? new ArrayList<>(available) : hits;
    }

    /** Series actually drawn (capped so the chart stays readable). */
    private List<String> effectiveItems(List<String> matched) {
        if (matched.size() > MAX_SERIES) {
            return matched.subList(0, MAX_SERIES);
        }
        return matched;
    }

    private UsageData.Slice sumOf(UsageData data, List<String> matched, boolean all) {
        if (selected.isEmpty()) {
            return all ? data.sumAll() : data.sumRange(spec().from, spec().to);
        }
        UsageData.Slice slice = new UsageData.Slice();
        for (String item : matched) {
            slice.add(all ? data.sliceAll(dim, item) : data.slice(dim, item, spec().from, spec().to));
        }
        return slice;
    }

    private boolean splitAvailableToday(List<String> effective) {
        if (dim == UsageData.DIM_MODEL) {
            return SampleStore.hasItemStats(host, dim);
        }
        return SampleStore.hasItemStats(host, dim);
    }

    private static boolean allZero(float[][] series) {
        for (float[] s : series) {
            for (float v : s) {
                if (v > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private int[] palette(int n) {
        int[] base = new int[]{
                Ui.color(host, R.color.ds_chart),
                Ui.color(host, R.color.ds_chart_2),
                Ui.color(host, R.color.ds_chart_3),
                Ui.color(host, R.color.ds_chart_4),
                Ui.color(host, R.color.ds_chart_5),
                Ui.color(host, R.color.ds_chart_6)};
        int[] out = new int[Math.max(1, n)];
        for (int i = 0; i < out.length; i++) {
            out[i] = base[i % base.length];
        }
        return out;
    }

    private void updateLegend(List<String> items, int[] colors) {
        costLegend.removeAllViews();
        if (items == null || items.size() < 2 || colors == null) {
            costLegend.setVisibility(View.GONE);
            return;
        }
        costLegend.setVisibility(View.VISIBLE);
        int shown = Math.min(items.size(), MAX_LEGEND);
        for (int i = 0; i < shown; i++) {
            TextView chip = new TextView(host);
            chip.setText("● " + legendName(items.get(i)));
            chip.setTextSize(10f);
            chip.setTextColor(colors[i % colors.length]);
            chip.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(host, 8);
            costLegend.addView(chip, lp);
        }
        if (items.size() > shown) {
            TextView more = new TextView(host);
            more.setText("+" + (items.size() - shown));
            more.setTextSize(10f);
            more.setTextColor(Ui.color(host, R.color.ds_text_3));
            costLegend.addView(more);
        }
    }

    private static String legendName(String name) {
        if (name == null) {
            return "";
        }
        return name.length() > 7 ? name.substring(0, 6) + "…" : name;
    }

    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        return name.length() > 14 ? name.substring(0, 13) + "…" : name;
    }

    private static String modelSummary(UsageData data) {
        if (data == null) {
            return "";
        }
        LinkedHashSet<String> models = data.models();
        if (models.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String m : models) {
            if (i++ > 0) {
                sb.append(" · ");
            }
            sb.append(shortName(m));
            if (i >= 2) {
                break;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ dialogs

    private void showRangeDialog() {
        final String[] items = new String[]{
                host.getString(R.string.range_today),
                "昨天",
                host.getString(R.string.range_7d),
                host.getString(R.string.range_30d),
                host.getString(R.string.range_month),
                host.getString(R.string.range_custom)};
        final int[] ranges = new int[]{RANGE_TODAY, RANGE_YESTERDAY, RANGE_7D, RANGE_30D,
                RANGE_MONTH, RANGE_CUSTOM};
        new AlertDialog.Builder(host)
                .setTitle(R.string.filter_time)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= ranges.length) {
                            return;
                        }
                        if (ranges[which] != RANGE_CUSTOM) {
                            range = ranges[which];
                            viewDayOffset = range == RANGE_YESTERDAY ? -1 : 0;
                            persist();
                            load(true);
                            return;
                        }
                        if (ranges[which] == RANGE_CUSTOM) {
                            DateRangeDialog.show(host, customFrom, customTo,
                                    new DateRangeDialog.Listener() {
                                        @Override
                                        public void onPicked(String from, String to) {
                                            customFrom = from;
                                            customTo = to;
                                            range = RANGE_CUSTOM;
                                            persist();
                                            applyChips();
                                            load(true);
                                        }
                                    });
                            return;
                        }
                        range = which;
                        persist();
                        applyChips();
                        load(true);
                    }
                })
                .show();
    }

    private void showItemDialog() {
        final List<String> items = availableItems(UsageRepo.data == null ? new UsageData() : UsageRepo.data);
        if (items.isEmpty()) {
            host.toast(dim == UsageData.DIM_KEY
                    ? "暂无可用 API Key（该时间范围可能无法按 Key 拆分）"
                    : "暂无可用模型，请先重载");
            return;
        }
        final boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            checked[i] = selected.contains(items.get(i));
        }
        new AlertDialog.Builder(host)
                .setTitle(dimLabel() + "（可多选，不选=全部）")
                .setMultiChoiceItems(items.toArray(new String[0]), checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selected.clear();
                        for (int i = 0; i < items.size(); i++) {
                            if (checked[i]) {
                                selected.add(items.get(i));
                            }
                        }
                        persist();
                        applyChips();
                        render();
                    }
                })
                .setNeutralButton(R.string.filter_all, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selected.clear();
                        persist();
                        applyChips();
                        render();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showExportDialog() {
        final String csv = buildCsv();
        String preview = csv.length() > 900 ? csv.substring(0, 900) + "\n…" : csv;
        new AlertDialog.Builder(host)
                .setTitle("导出 CSV（" + rangeLabel() + "）")
                .setMessage(preview)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(csv);
                    }
                })
                .setNeutralButton("保存文件", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveCsv(csv);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String buildCsv() {
        StringBuilder sb = new StringBuilder();
        UsageData data = UsageRepo.data;
        if (range == RANGE_TODAY) {
            sb.append("hour,cost_cny,tokens,requests\n");
            float[] cost = toFloats(SampleStore.hourly(host, SampleStore.FIELD_COST));
            float[] tokens = toFloats(SampleStore.hourly(host, SampleStore.FIELD_TOKENS));
            float[] requests = toFloats(SampleStore.hourly(host, SampleStore.FIELD_REQUESTS));
            for (int i = 0; i < 24; i++) {
                sb.append(Fmt.hourLabel(i)).append(',')
                        .append(Fmt.money2(cost[i])).append(',')
                        .append((long) tokens[i]).append(',')
                        .append((long) requests[i]).append('\n');
            }
            return sb.toString();
        }
        Spec spec = spec();
        List<String> effective = effectiveItems(matchedItems(
                availableItems(data == null ? new UsageData() : data)));
        sb.append("date,cost_cny,tokens,requests");
        for (String item : effective) {
            sb.append(',').append(csvCell(item));
        }
        sb.append('\n');
        if (data != null && spec.dates != null) {
            for (String date : spec.dates) {
                UsageData.Day d = data.days.get(date);
                sb.append(date).append(',')
                        .append(Fmt.money2(d == null ? 0 : d.total.cost)).append(',')
                        .append(d == null ? 0 : d.total.tokens).append(',')
                        .append(d == null ? 0 : d.total.requests);
                for (String item : effective) {
                    UsageData.Slice s = d == null ? null : d.dimOrNull(dim, item);
                    sb.append(',').append(Fmt.money2(s == null ? 0 : s.cost));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("deepseek-usage", text));
            host.toast("已复制 CSV 到剪贴板");
        }
    }

    private void saveCsv(String text) {
        File dir = host.getExternalFilesDir("export");
        if (dir == null) {
            dir = host.getFilesDir();
        }
        File out = new File(dir, "deepseek-usage-" + System.currentTimeMillis() + ".csv");
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
            writer.write(text);
            writer.flush();
            host.toast("已保存到 " + out.getAbsolutePath());
        } catch (IOException e) {
            host.toast("保存失败：" + e.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    // ------------------------------------------------------------------ misc

    private static float[] toFloats(double[] in) {
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (float) in[i];
        }
        return out;
    }

    private static String[] hourTicks() {
        // same tick layout as the console chart: 00:00 / 08:00 / 15:00 / 23:00
        String[] ticks = new String[24];
        ticks[0] = "00:00";
        ticks[8] = "08:00";
        ticks[15] = "15:00";
        ticks[23] = "23:00";
        return ticks;
    }

    private static String[] sparseDayTicks(List<String> dates) {
        String[] ticks = new String[dates.size()];
        if (dates.isEmpty()) {
            return ticks;
        }
        ticks[0] = Fmt.shortDate(dates.get(0));
        int mid = dates.size() / 2;
        if (mid > 0 && mid < dates.size() - 1) {
            ticks[mid] = Fmt.shortDate(dates.get(mid));
        }
        if (dates.size() > 1) {
            ticks[dates.size() - 1] = Fmt.shortDate(dates.get(dates.size() - 1));
        }
        return ticks;
    }
}
