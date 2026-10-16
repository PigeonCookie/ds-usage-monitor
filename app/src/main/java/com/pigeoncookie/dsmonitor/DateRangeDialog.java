package com.pigeoncookie.dsmonitor;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * Month calendar range picker, styled after the console's date filter: month header with arrows,
 * a Sunday-first weekday row, tapped start/end days circled and the days between them banded.
 */
public final class DateRangeDialog {

    public interface Listener {
        void onPicked(String from, String to);
    }

    private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};

    private DateRangeDialog() {
    }

    public static void show(Activity ctx, String from, String to, Listener listener) {
        new Picker(ctx, from, to, listener).show();
    }

    private static final class Cell {
        FrameLayout box;
        View band;
        TextView label;
    }

    private static final class Picker implements View.OnClickListener {

        private final Activity ctx;
        private final Listener listener;
        private final Calendar cursor = Calendar.getInstance();
        private final Cell[][] cells = new Cell[6][7];
        private final TextView monthTitle;
        private final TextView confirm;
        private final TextView[] arrows = new TextView[2];

        private Dialog dialog;
        private String start;
        private String end;
        private String today;

        Picker(Activity ctx, String from, String to, Listener listener) {
            this.ctx = ctx;
            this.listener = listener;
            this.start = from;
            this.end = to;
            this.today = iso(Calendar.getInstance());
            this.monthTitle = new TextView(ctx);
            this.confirm = new TextView(ctx);
        }

        void show() {
            dialog = new Dialog(ctx);
            dialog.setContentView(build());
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            dialog.show();
            window = dialog.getWindow();
            if (window != null) {
                window.setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            Calendar from = parse(start);
            if (from != null) {
                cursor.setTimeInMillis(from.getTimeInMillis());
            }
            render();
            updateConfirm();
        }

        private static Calendar parse(String iso) {
            if (iso == null || iso.length() < 10) {
                return null;
            }
            try {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1,
                        Integer.parseInt(iso.substring(8, 10)));
                return c;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private View build() {
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.bg_card);
            int pad = Ui.dp(ctx, 16);
            root.setPadding(pad, pad, pad, Ui.dp(ctx, 10));

            TextView title = new TextView(ctx);
            title.setText(R.string.pick_range);
            title.setTextSize(15f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.color(ctx, R.color.ds_text));
            root.addView(title);

            TextView hint = new TextView(ctx);
            hint.setText(R.string.pick_range_hint);
            hint.setTextSize(11f);
            hint.setTextColor(Ui.color(ctx, R.color.ds_text_3));
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = Ui.dp(ctx, 4);
            root.addView(hint, hintLp);

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerLp.topMargin = Ui.dp(ctx, 12);
            headerLp.bottomMargin = Ui.dp(ctx, 6);
            root.addView(header, headerLp);

            arrows[0] = arrow("\u2039");
            header.addView(arrows[0], new LinearLayout.LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 36)));
            monthTitle.setGravity(Gravity.CENTER);
            monthTitle.setTextSize(14f);
            monthTitle.setTextColor(Ui.color(ctx, R.color.ds_text));
            header.addView(monthTitle, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            arrows[1] = arrow("\u203A");
            header.addView(arrows[1], new LinearLayout.LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 36)));

            LinearLayout weekRow = new LinearLayout(ctx);
            weekRow.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(weekRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)));
            for (String name : WEEKDAYS) {
                TextView w = new TextView(ctx);
                w.setText(name);
                w.setTextSize(12f);
                w.setGravity(Gravity.CENTER);
                w.setTextColor(Ui.color(ctx, R.color.ds_text_3));
                weekRow.addView(w, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }

            for (int r = 0; r < cells.length; r++) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 44)));
                for (int c = 0; c < 7; c++) {
                    Cell cell = new Cell();
                    cell.box = new FrameLayout(ctx);
                    cell.band = new View(ctx);
                    cell.band.setBackgroundResource(R.drawable.bg_cal_band);
                    cell.band.setVisibility(View.INVISIBLE);
                    FrameLayout.LayoutParams bandLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 36));
                    bandLp.gravity = Gravity.CENTER;
                    cell.box.addView(cell.band, bandLp);
                    cell.label = new TextView(ctx);
                    cell.label.setGravity(Gravity.CENTER);
                    cell.label.setTextSize(14f);
                    cell.label.setTextColor(Ui.color(ctx, R.color.ds_text));
                    FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                            Ui.dp(ctx, 36), Ui.dp(ctx, 36));
                    labelLp.gravity = Gravity.CENTER;
                    cell.box.addView(cell.label, labelLp);
                    cell.box.setOnClickListener(this);
                    row.addView(cell.box, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                    cells[r][c] = cell;
                }
            }

            LinearLayout footer = new LinearLayout(ctx);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            footerLp.topMargin = Ui.dp(ctx, 14);
            root.addView(footer, footerLp);

            TextView cancel = new TextView(ctx);
            cancel.setText(R.string.cancel);
            cancel.setTextSize(13f);
            cancel.setTextColor(Ui.color(ctx, R.color.ds_text_2));
            cancel.setBackgroundResource(R.drawable.bg_chip);
            cancel.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 9), Ui.dp(ctx, 18), Ui.dp(ctx, 9));
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            footer.addView(cancel);

            confirm.setText(R.string.confirm);
            confirm.setTextSize(13f);
            confirm.setTypeface(Typeface.DEFAULT_BOLD);
            confirm.setTextColor(Ui.color(ctx, R.color.ds_pill_fg));
            confirm.setBackgroundResource(R.drawable.bg_pill);
            confirm.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 9), Ui.dp(ctx, 22), Ui.dp(ctx, 9));
            confirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (start == null) {
                        return;
                    }
                    listener.onPicked(start, end == null ? start : end);
                    dialog.dismiss();
                }
            });
            LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            confirmLp.leftMargin = Ui.dp(ctx, 10);
            footer.addView(confirm, confirmLp);

            return root;
        }

        private TextView arrow(String glyph) {
            TextView v = new TextView(ctx);
            v.setText(glyph);
            v.setTextSize(18f);
            v.setGravity(Gravity.CENTER);
            v.setTextColor(Ui.color(ctx, R.color.ds_text_2));
            v.setBackgroundResource(R.drawable.bg_icon_btn);
            v.setOnClickListener(this);
            return v;
        }

        @Override
        public void onClick(View v) {
            if (v == arrows[0]) {
                cursor.add(Calendar.MONTH, -1);
                render();
                return;
            }
            if (v == arrows[1]) {
                cursor.add(Calendar.MONTH, 1);
                render();
                return;
            }
            Object tag = v.getTag();
            if (!(tag instanceof String)) {
                return;
            }
            String iso = (String) tag;
            if (start == null || end != null) {
                start = iso;
                end = null;
            } else if (iso.compareTo(start) < 0) {
                start = iso;
                end = null;
            } else {
                end = iso;
            }
            render();
            updateConfirm();
        }

        private void updateConfirm() {
            boolean ready = start != null;
            confirm.setEnabled(ready);
            confirm.setAlpha(ready ? 1f : 0.45f);
        }

        private void render() {
            Calendar first = (Calendar) cursor.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int firstDow = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
            int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
            monthTitle.setText(String.format(Locale.US, "%d年%d月",
                    first.get(Calendar.YEAR), first.get(Calendar.MONTH) + 1));
            arrows[1].setAlpha(isCurrentMonth(first) ? 0.35f : 1f);

            for (int i = 0; i < cells.length * 7; i++) {
                Cell cell = cells[i / 7][i % 7];
                int dayNum = i - firstDow + 1;
                if (dayNum < 1 || dayNum > days) {
                    cell.label.setText("");
                    cell.box.setTag(null);
                    cell.label.setBackgroundResource(0);
                    cell.label.setTextColor(Ui.color(ctx, R.color.ds_text_3));
                    cell.band.setVisibility(View.INVISIBLE);
                    continue;
                }
                Calendar d = (Calendar) first.clone();
                d.set(Calendar.DAY_OF_MONTH, dayNum);
                String iso = iso(d);
                cell.label.setText(String.valueOf(dayNum));
                cell.box.setTag(iso);

                boolean isStart = iso.equals(start);
                boolean isEnd = iso.equals(end);
                boolean inRange = start != null && end != null
                        && iso.compareTo(start) > 0 && iso.compareTo(end) < 0;
                boolean future = iso.compareTo(today) > 0;

                cell.band.setVisibility(inRange ? View.VISIBLE : View.INVISIBLE);
                if (isStart || isEnd) {
                    cell.label.setBackgroundResource(R.drawable.bg_cal_selected);
                    cell.label.setTextColor(Ui.color(ctx, R.color.ds_pill_fg));
                    cell.label.setTypeface(Typeface.DEFAULT_BOLD);
                } else if (iso.equals(today)) {
                    cell.label.setBackgroundResource(R.drawable.bg_cal_today);
                    cell.label.setTextColor(Ui.color(ctx, R.color.ds_accent));
                    cell.label.setTypeface(Typeface.DEFAULT_BOLD);
                } else {
                    cell.label.setBackgroundResource(0);
                    cell.label.setTextColor(Ui.color(ctx, future ? R.color.ds_text_3 : R.color.ds_text));
                    cell.label.setTypeface(Typeface.DEFAULT);
                }
            }
        }

        private static boolean isCurrentMonth(Calendar first) {
            Calendar now = Calendar.getInstance();
            return first.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && first.get(Calendar.MONTH) == now.get(Calendar.MONTH);
        }

        private static String iso(Calendar c) {
            return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        }
    }
}
