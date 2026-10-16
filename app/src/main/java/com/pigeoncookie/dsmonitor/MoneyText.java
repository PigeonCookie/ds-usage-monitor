package com.pigeoncookie.dsmonitor;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.pigeoncookie.dsmonitor.core.Fmt;

/**
 * Money text that rolls from the previous value to the new one and plays a highlight when the
 * balance drops (colour flash + pulse), so a decrease is impossible to miss.
 */
public class MoneyText extends TextView {

    private double shown = Double.NaN;
    private ValueAnimator animator;
    private int normalColor;
    private boolean warnOnRise;

    public MoneyText(Context context) {
        super(context);
        init();
    }

    public MoneyText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MoneyText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        normalColor = getCurrentTextColor();
    }

    public double shownValue() {
        return shown;
    }

    /**
     * Flip the meaning of the highlight: with this on, a <em>rise</em> flashes the warn colour
     * (used by 累计消费, where growth means money spent).
     */
    public void setWarnOnRise(boolean value) {
        warnOnRise = value;
    }

    public void setAmount(double value, boolean animate) {
        setAmount(value, animate, null, null);
    }

    public void setAmount(double value, boolean animate, Runnable onDrop) {
        setAmount(value, animate, onDrop, null);
    }

    /**
     * @param onDrop run after the count-down finishes when the value decreased
     * @param onRise run after the count-up finishes when the value increased
     */
    public void setAmount(final double value, boolean animate, final Runnable onDrop,
            final Runnable onRise) {
        final double from = Double.isNaN(shown) ? value : shown;
        shown = value;
        if (!animate || from == value || Double.isNaN(from)) {
            setText(Fmt.money(value));
            return;
        }
        if (animator != null) {
            animator.cancel();
        }
        final boolean drop = value < from;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Math.min(900L, 280L + (long) (Math.abs(value - from) * 600L)));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float p = (Float) a.getAnimatedValue();
                setText(Fmt.money(from + (value - from) * p));
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                setText(Fmt.money(value));
                if (drop && onDrop != null) {
                    onDrop.run();
                } else if (!drop && onRise != null) {
                    onRise.run();
                }
            }
        });
        animator.start();
        playChangeEffect(drop);
    }

    private void playChangeEffect(boolean drop) {
        if (warnOnRise) {
            // 累计消费：只有上涨才闪暖橙；数据回落（重新扫描/校准）不闪绿色，避免误读成"好消息"
            if (!drop) {
                flash(Ui.color(getContext(), R.color.ds_warn), 900L);
            }
            return;
        }
        flash(Ui.color(getContext(), drop ? R.color.ds_warn : R.color.ds_rise), drop ? 900L : 1100L);
    }

    private void flash(int color, long duration) {
        ObjectAnimator animator = ObjectAnimator.ofArgb(this, "textColor", normalColor, color, normalColor);
        animator.setDuration(duration);
        animator.start();
        animate().scaleX(1.07f).scaleY(1.07f).setDuration(170L).withEndAction(new Runnable() {
            @Override
            public void run() {
                animate().scaleX(1f).scaleY(1f).setDuration(240L).start();
            }
        }).start();
    }
}
