package com.pigeoncookie.dsmonitor;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Toast;

import com.pigeoncookie.dsmonitor.core.Prefs;

/** Small view/theme helpers shared by the pages. */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static int color(Context c, int res) {
        return c.getResources().getColor(res, c.getTheme());
    }

    public static void toast(Context c, CharSequence text) {
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
    }

    /** Clips the view to a circle — used for the avatar. */
    public static void circle(final View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setClipToOutline(true);
    }

    /** Resolves the in-app day/night choice into a configuration-wrapped context. */
    public static Context wrapTheme(Context base) {
        int mode = Prefs.themeMode(base);
        if (mode == Prefs.THEME_SYSTEM) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int night = mode == Prefs.THEME_NIGHT
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(cfg);
    }
}
