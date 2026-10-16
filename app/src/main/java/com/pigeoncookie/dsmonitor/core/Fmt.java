package com.pigeoncookie.dsmonitor.core;

import java.util.Calendar;
import java.util.Locale;

/** Locale-independent number/time formatting (no DecimalFormat, so tests are stable). */
public final class Fmt {

    private Fmt() {
    }

    public static String money(double v) {
        return "¥" + money2(v);
    }

    public static String money2(double v) {
        boolean neg = v < 0;
        double abs = Math.abs(v);
        long cents = Math.round(abs * 100.0);
        return (neg ? "-" : "") + group(cents / 100) + "." + pad2((int) (cents % 100));
    }

    public static String group(long v) {
        boolean neg = v < 0;
        String s = Long.toString(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
            if (++count % 3 == 0 && i > 0) {
                sb.append(',');
            }
        }
        return (neg ? "-" : "") + sb.reverse();
    }

    /** 1,025,644 / 12.4K / 1.5M style labels used on chart axes. */
    public static String compact(double v) {
        double abs = Math.abs(v);
        if (abs >= 1_000_000_000d) {
            return trim1(v / 1_000_000_000d) + "B";
        }
        if (abs >= 1_000_000d) {
            return trim1(v / 1_000_000d) + "M";
        }
        if (abs >= 1_000d) {
            return trim1(v / 1_000d) + "K";
        }
        if (abs >= 10) {
            return String.valueOf(Math.round(v));
        }
        if (abs == 0) {
            return "0";
        }
        return trim1(v);
    }

    public static String trim1(double v) {
        if (Math.abs(v) >= 100) {
            return String.valueOf(Math.round(v));
        }
        String s = String.format(Locale.US, "%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    public static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** 09:58:31 in the device time zone. */
    public static String time(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return pad2(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad2(c.get(Calendar.MINUTE)) + ":"
                + pad2(c.get(Calendar.SECOND));
    }

    public static String dateTime(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 " + time(ms);
    }

    public static String hourLabel(int hour) {
        return pad2(hour) + ":00";
    }

    /** "2026-02-04" or "20260204" → "02-04". */
    public static String shortDate(String date) {
        if (date == null) {
            return "";
        }
        String d = date.replace("-", "");
        if (d.length() >= 8) {
            return d.substring(4, 6) + "-" + d.substring(6, 8);
        }
        return date;
    }

    public static int dayOfMonth(String date) {
        String d = date == null ? "" : date.replace("-", "");
        if (d.length() >= 8) {
            try {
                return Integer.parseInt(d.substring(6, 8));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return trim1(bytes / 1024d) + " KB";
        }
        return trim1(bytes / (1024d * 1024d)) + " MB";
    }
}
