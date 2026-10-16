package com.pigeoncookie.dsmonitor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.pigeoncookie.dsmonitor.core.Fmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal dependency-free bar chart with the visual language of the DeepSeek console charts:
 * left-aligned value labels on three horizontal grid lines, pill-shaped bars, sparse x labels.
 *
 * <p>Supports a single series and a grouped multi-series mode (used for the per-model money chart).
 */
public class BarChartView extends View {

    public static final int AXIS_MONEY = 0;
    public static final int AXIS_COUNT = 1;

    /** One line of a bar tooltip. */
    public static final class Row {
        public final String label;
        public final String value;
        public final int color;

        public Row(String label, String value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    /** Tapped-bar popup content, supplied by the page that owns the data. */
    public static final class Tooltip {
        public String title = "";
        public String total = "";
        public final List<Row> rows = new ArrayList<>();

        public Tooltip add(String label, String value, int color) {
            rows.add(new Row(label, value, color));
            return this;
        }
    }

    public interface TooltipSource {
        Tooltip tooltip(int barIndex);
    }

    private TooltipSource tooltipSource;
    private int selectedBar = -1;
    private RectF tooltipRect;
    private int barCount;
    private float slotWidth;
    private float plotLeft;
    private float plotBaseY;
    private float plotTop;
    private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipDash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path barClip = new Path();
    private final Path barPath = new Path();

    private float[] values;
    private float[][] groups;
    private int[] groupColors;
    private boolean stacked;
    private String[] ticks;
    private String emptyText = "";
    private int axisMode = AXIS_MONEY;
    private float fraction = 1f;
    private ValueAnimator animator;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public BarChartView(Context context) {
        super(context);
        init();
    }

    public BarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BarChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setColor(Ui.color(getContext(), R.color.ds_chart));
        gridPaint.setColor(Ui.color(getContext(), R.color.ds_chart_grid));
        gridPaint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 0.7f)));
        labelPaint.setColor(Ui.color(getContext(), R.color.ds_chart_axis));
        labelPaint.setTextSize(sp(10f));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        hintPaint.setColor(Ui.color(getContext(), R.color.ds_text_3));
        hintPaint.setTextSize(sp(11f));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        tipBg.setColor(Ui.color(getContext(), R.color.ds_card_alt));
        tipStroke.setColor(Ui.color(getContext(), R.color.ds_stroke));
        tipStroke.setStyle(Paint.Style.STROKE);
        tipStroke.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 0.8f)));
        tipText.setColor(Ui.color(getContext(), R.color.ds_text));
        tipDash.setColor(Ui.color(getContext(), R.color.ds_chart_axis));
        tipDash.setAlpha(110);
        tipDash.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 0.9f)));
    }

    public void setTooltipSource(TooltipSource source) {
        tooltipSource = source;
    }

    public void clearSelection() {
        selectedBar = -1;
        tooltipRect = null;
        invalidate();
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    public void setAxisMode(int mode) {
        axisMode = mode;
        invalidate();
    }

    public void setBars(float[] data, String[] tickLabels) {
        values = data;
        groups = null;
        stacked = false;
        ticks = tickLabels;
        selectedBar = -1;
        tooltipRect = null;
        playAnimation();
    }

    public void setGrouped(float[][] data, int[] colors, String[] tickLabels) {
        groups = data;
        groupColors = colors;
        values = null;
        stacked = false;
        ticks = tickLabels;
        selectedBar = -1;
        tooltipRect = null;
        playAnimation();
    }

    /** Stacked bars: every bar is the sum of its series, drawn bottom-up. */
    public void setStacked(float[][] data, int[] colors, String[] tickLabels) {
        groups = data;
        groupColors = colors;
        values = null;
        stacked = true;
        ticks = tickLabels;
        selectedBar = -1;
        tooltipRect = null;
        playAnimation();
    }

    public void setEmptyText(String text) {
        emptyText = text == null ? "" : text;
        invalidate();
    }

    public void playAnimation() {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(460L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                fraction = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean grouped = groups != null && groups.length > 0;
        // series-major input: groups[series][barIndex]
        int count = grouped ? groups[0].length : (values == null ? 0 : values.length);

        float labelH = labelPaint.getTextSize();
        float bottomPad = labelH + Ui.dp(getContext(), 8f);
        float topPad = labelH;
        float chartH = h - bottomPad - topPad;
        if (chartH <= 0) {
            return;
        }

        double max = 0;
        if (grouped) {
            for (int i = 0; i < count; i++) {
                double sum = 0;
                for (float[] series : groups) {
                    if (i < series.length) {
                        sum += Math.max(0, series[i]);
                    }
                }
                max = Math.max(max, sum);
            }
        } else if (values != null) {
            for (float v : values) {
                max = Math.max(max, v);
            }
        }
        double nice = niceMax(max);

        String topLabel = axisLabel(nice);
        String midLabel = axisLabel(nice / 2d);
        // Fixed left gutter: every chart reserves the same space for the value labels, so the three
        // charts of the usage page share one identical time axis and their bars line up.
        float needed = Math.max(labelPaint.measureText(topLabel),
                Math.max(labelPaint.measureText(midLabel), labelPaint.measureText("0")))
                + Ui.dp(getContext(), 9f);
        float chartLeft = Math.max(needed, Ui.dp(getContext(), 40f));
        if (chartLeft > w * 0.34f) {
            chartLeft = w * 0.34f;
        }
        float chartRight = w;
        float chartW = chartRight - chartLeft;
        float baseY = topPad + chartH;

        float[] ys = {baseY, topPad + chartH / 2f, topPad};
        String[] texts = {"0", midLabel, topLabel};
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i < 3; i++) {
            canvas.drawLine(chartLeft, ys[i], chartRight, ys[i], gridPaint);
            float baseline = i == 0 ? ys[i] - Ui.dp(getContext(), 3f) : ys[i] + labelH * 0.34f;
            canvas.drawText(texts[i], chartLeft - Ui.dp(getContext(), 5f), baseline, labelPaint);
        }
        labelPaint.setTextAlign(Paint.Align.LEFT);

        if (count == 0 || max <= 0) {
            if (!emptyText.isEmpty()) {
                canvas.drawText(emptyText, chartLeft + chartW / 2f, topPad + chartH / 2f, hintPaint);
            }
            return;
        }

        float slot = chartW / count;
        barCount = count;
        slotWidth = slot;
        plotLeft = chartLeft;
        plotBaseY = baseY;
        plotTop = topPad;
        if (grouped && stacked) {
            float barW = Math.max(Ui.dp(getContext(), 2f), Math.min(slot * 0.62f, Ui.dp(getContext(), 22f)));
            for (int i = 0; i < count; i++) {
                double sum = 0;
                for (float[] series : groups) {
                    if (i < series.length) {
                        sum += Math.max(0, series[i]);
                    }
                }
                if (sum <= 0) {
                    continue;
                }
                float totalH = (float) (sum / nice * chartH) * fraction;
                if (totalH < 1f) {
                    totalH = 1f;
                }
                float cx = chartLeft + slot * i + slot / 2f;
                float left = cx - barW / 2f;
                float right = left + barW;
                // one rounded pill for the whole bar; the segments are painted inside it, so the
                // stack reads as a single bar instead of a string of beads
                barClip.reset();
                float radius = Math.min(barW / 2f, totalH);
                float barTop = baseY - totalH;
                barClip.moveTo(left, baseY);
                barClip.lineTo(left, barTop + radius);
                barClip.quadTo(left, barTop, left + radius, barTop);
                barClip.lineTo(right - radius, barTop);
                barClip.quadTo(right, barTop, right, barTop + radius);
                barClip.lineTo(right, baseY);
                barClip.close();
                int save = canvas.save();
                canvas.clipPath(barClip);
                float cursor = baseY;
                for (int s = 0; s < groups.length; s++) {
                    float v = i < groups[s].length ? groups[s][i] : 0f;
                    if (v <= 0) {
                        continue;
                    }
                    if (groupColors != null && s < groupColors.length) {
                        barPaint.setColor(groupColors[s]);
                    }
                    float barH = (float) (v / nice * chartH) * fraction;
                    canvas.drawRect(left, cursor - barH, right, cursor, barPaint);
                    cursor -= barH;
                }
                canvas.restoreToCount(save);
            }
            barPaint.setColor(Ui.color(getContext(), R.color.ds_chart));
        } else if (grouped) {
            int seriesCount = Math.max(1, groups.length);
            float barW = Math.max(Ui.dp(getContext(), 1.5f),
                    Math.min(slot * 0.66f / seriesCount, Ui.dp(getContext(), 14f)));
            float gap = seriesCount > 1 ? Math.min(Ui.dp(getContext(), 1.5f), barW * 0.25f) : 0f;
            for (int i = 0; i < count; i++) {
                float groupW = seriesCount * barW + Math.max(0, seriesCount - 1) * gap;
                float startX = chartLeft + slot * i + (slot - groupW) / 2f;
                for (int s = 0; s < groups.length; s++) {
                    float v = i < groups[s].length ? groups[s][i] : 0f;
                    if (v <= 0) {
                        continue;
                    }
                    if (groupColors != null && s < groupColors.length) {
                        barPaint.setColor(groupColors[s]);
                    }
                    float barH = (float) (v / nice * chartH) * fraction;
                    float left = startX + s * (barW + gap);
                    drawBar(canvas, left, baseY - barH, left + barW, baseY, barW);
                }
            }
            barPaint.setColor(Ui.color(getContext(), R.color.ds_chart));
        } else {
            float barW = Math.max(Ui.dp(getContext(), 2f), Math.min(slot * 0.62f, Ui.dp(getContext(), 22f)));
            barPaint.setColor(Ui.color(getContext(), R.color.ds_chart));
            for (int i = 0; i < count; i++) {
                if (values[i] <= 0) {
                    continue;
                }
                float barH = (float) (values[i] / nice * chartH) * fraction;
                float cx = chartLeft + slot * i + slot / 2f;
                drawBar(canvas, cx - barW / 2f, baseY - barH, cx + barW / 2f, baseY, barW);
            }
        }

        // x axis labels
        if (ticks != null) {
            for (int i = 0; i < count && i < ticks.length; i++) {
                if (ticks[i] == null) {
                    continue;
                }
                float cx = chartLeft + slot * i + slot / 2f;
                float tw = labelPaint.measureText(ticks[i]);
                float x = cx - tw / 2f;
                x = Math.max(0, Math.min(x, w - tw));
                canvas.drawText(ticks[i], x, h - Ui.dp(getContext(), 1f), labelPaint);
            }
        }

        if (selectedBar >= 0 && selectedBar < count && tooltipSource != null) {
            Tooltip tip = tooltipSource.tooltip(selectedBar);
            if (tip != null) {
                drawTooltip(canvas, tip, selectedBar);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (tooltipSource == null || barCount <= 0 || slotWidth <= 0f) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                float tx = event.getX();
                float ty = event.getY();
                boolean insidePopup = tooltipRect != null && tooltipRect.contains(tx, ty);
                int index = (int) ((tx - plotLeft) / slotWidth);
                boolean onBar = !insidePopup && tx >= plotLeft && index >= 0 && index < barCount
                        && barValue(index) > 0f;
                if (insidePopup || !onBar || index == selectedBar) {
                    dismissTooltip();
                } else {
                    selectedBar = index;
                    performClick();
                    invalidate();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void dismissTooltip() {
        if (selectedBar >= 0 || tooltipRect != null) {
            selectedBar = -1;
            tooltipRect = null;
            invalidate();
        }
    }

    /** Total value of one bar (0 means nothing was drawn there). */
    private float barValue(int index) {
        if (groups != null && groups.length > 0) {
            float sum = 0;
            for (float[] series : groups) {
                if (index >= 0 && index < series.length) {
                    sum += series[index];
                }
            }
            return sum;
        }
        return values != null && index >= 0 && index < values.length ? values[index] : 0f;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Tapped-bar popup: dashed guide, period title, total and one row per series. */
    private void drawTooltip(Canvas canvas, Tooltip tip, int index) {
        float w = getWidth();
        float cx = plotLeft + slotWidth * index + slotWidth / 2f;
        tipDash.setPathEffect(new DashPathEffect(new float[]{
                Ui.dp(getContext(), 3f), Ui.dp(getContext(), 3f)}, 0f));
        canvas.drawLine(cx, plotTop, cx, plotBaseY, tipDash);
        tipDash.setPathEffect(null);

        float pad = Ui.dp(getContext(), 12f);
        float titleSize = sp(11.5f);
        float rowSize = sp(11f);
        float valueSize = sp(11.5f);
        float rowH = Ui.dp(getContext(), 17f);

        tipText.setTextSize(titleSize);
        tipText.setFakeBoldText(false);
        float titleW = tipText.measureText(tip.title);
        float totalW = tipText.measureText(tip.total);
        float labelW = 0;
        float valueW = 0;
        tipText.setTextSize(rowSize);
        for (Row row : tip.rows) {
            labelW = Math.max(labelW, Ui.dp(getContext(), 16f) + tipText.measureText(row.label));
        }
        tipText.setTextSize(valueSize);
        for (Row row : tip.rows) {
            valueW = Math.max(valueW, tipText.measureText(row.value));
        }
        float contentW = Math.max(titleW + Ui.dp(getContext(), 18f) + totalW,
                Math.max(labelW + Ui.dp(getContext(), 18f) + valueW, Ui.dp(getContext(), 120f)));
        float cardW = Math.min(w - Ui.dp(getContext(), 16f), pad * 2f + contentW);
        float cardH = pad + titleSize * 1.5f + Ui.dp(getContext(), 4f)
                + tip.rows.size() * rowH + pad - Ui.dp(getContext(), 5f);
        float left = Math.max(Ui.dp(getContext(), 6f),
                Math.min(w - cardW - Ui.dp(getContext(), 6f), cx - cardW / 2f));
        float top = plotTop + (plotBaseY - plotTop - cardH) / 2f;
        top = Math.max(Ui.dp(getContext(), 2f), top);

        rect.set(left, top, left + cardW, top + cardH);
        if (tooltipRect == null) {
            tooltipRect = new RectF();
        }
        tooltipRect.set(rect);
        canvas.drawRoundRect(rect, Ui.dp(getContext(), 14f), Ui.dp(getContext(), 14f), tipBg);
        canvas.drawRoundRect(rect, Ui.dp(getContext(), 14f), Ui.dp(getContext(), 14f), tipStroke);

        float textY = top + pad + titleSize;
        tipText.setTextSize(titleSize);
        tipText.setFakeBoldText(true);
        tipText.setTextAlign(Paint.Align.LEFT);
        tipText.setColor(Ui.color(getContext(), R.color.ds_text));
        canvas.drawText(tip.title, left + pad, textY, tipText);
        tipText.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(tip.total, left + cardW - pad, textY, tipText);

        float rowY = textY + Ui.dp(getContext(), 8f) + rowH / 2f;
        for (Row row : tip.rows) {
            float square = Ui.dp(getContext(), 10f);
            if (row.color != 0) {
                tipText.setColor(row.color);
                rect.set(left + pad, rowY - square, left + pad + square, rowY);
                canvas.drawRoundRect(rect, Ui.dp(getContext(), 2.5f), Ui.dp(getContext(), 2.5f), tipText);
            }
            tipText.setTextSize(rowSize);
            tipText.setFakeBoldText(false);
            tipText.setColor(Ui.color(getContext(), R.color.ds_text_2));
            tipText.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(row.label, left + pad + Ui.dp(getContext(), 16f), rowY, tipText);
            tipText.setTextSize(valueSize);
            tipText.setColor(Ui.color(getContext(), R.color.ds_text));
            tipText.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(row.value, left + cardW - pad, rowY, tipText);
            rowY += rowH;
        }
        tipText.setFakeBoldText(false);
        tipText.setTextAlign(Paint.Align.LEFT);
    }

    /** Flat bottom, rounded top — same silhouette as the console charts. */
    private void drawBar(Canvas canvas, float left, float top, float right, float bottom, float width) {
        if (bottom - top < 1f) {
            bottom = top + 1f;
        }
        float r = Math.min(width / 2f, Math.max(1f, bottom - top));
        barPath.reset();
        barPath.moveTo(left, bottom);
        barPath.lineTo(left, top + r);
        barPath.quadTo(left, top, left + r, top);
        barPath.lineTo(right - r, top);
        barPath.quadTo(right, top, right, top + r);
        barPath.lineTo(right, bottom);
        barPath.close();
        canvas.drawPath(barPath, barPaint);
    }

    public static double niceMax(double v) {
        if (v <= 0) {
            return 1d;
        }
        double exp = Math.pow(10, Math.floor(Math.log10(v)));
        double f = v / exp;
        double nice;
        if (f <= 1d) {
            nice = 1d;
        } else if (f <= 1.2d) {
            nice = 1.2d;
        } else if (f <= 1.5d) {
            nice = 1.5d;
        } else if (f <= 2d) {
            nice = 2d;
        } else if (f <= 2.5d) {
            nice = 2.5d;
        } else if (f <= 3d) {
            nice = 3d;
        } else if (f <= 4d) {
            nice = 4d;
        } else if (f <= 5d) {
            nice = 5d;
        } else if (f <= 7.5d) {
            nice = 7.5d;
        } else {
            nice = 10d;
        }
        return nice * exp;
    }

    private String axisLabel(double v) {
        if (axisMode == AXIS_MONEY) {
            if (v > 0 && v < 10d) {
                return String.format(java.util.Locale.US, "%.2f", v);
            }
            return Fmt.compact(v);
        }
        return Fmt.compact(v);
    }
}
