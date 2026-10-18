package com.pigeoncookie.dsmonitor;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.pigeoncookie.dsmonitor.core.Balance;
import com.pigeoncookie.dsmonitor.core.Bg;
import com.pigeoncookie.dsmonitor.core.DeepSeekClient;
import com.pigeoncookie.dsmonitor.core.Fmt;
import com.pigeoncookie.dsmonitor.core.Prefs;
import com.pigeoncookie.dsmonitor.core.SampleStore;
import com.pigeoncookie.dsmonitor.core.UsageData;
import com.pigeoncookie.dsmonitor.core.UsageRepo;
import com.pigeoncookie.dsmonitor.data.Alerts;

/** Page 1 — balance dashboard. */
public class DashboardPage extends Page {

    private final MoneyText moneyTotal;
    private final MoneyText moneyTotalAll;
    private final MoneyText moneyCumulative;
    private final MoneyText moneyGranted;
    private final TextView badgeAlert;
    private final TextView balanceMeta;
    private final TextView cumulativeMeta;
    private final com.pigeoncookie.dsmonitor.MoneyText todayCost;
    private final TextView sourceLine;
    private final TextView updatedLine;
    private final TextView sampleLine;
    private final FrameLayout cardBalance;

    private boolean firstRender = true;

    public DashboardPage(MainActivity host, View root) {
        super(host, root);
        moneyTotal = root.findViewById(R.id.money_total);
        moneyTotalAll = root.findViewById(R.id.money_total_all);
        moneyCumulative = root.findViewById(R.id.money_cumulative);
        moneyGranted = root.findViewById(R.id.money_granted);
        badgeAlert = root.findViewById(R.id.badge_alert);
        balanceMeta = root.findViewById(R.id.balance_meta);
        cumulativeMeta = root.findViewById(R.id.cumulative_meta);
        todayCost = root.findViewById(R.id.today_cost);
        sourceLine = root.findViewById(R.id.source_line);
        updatedLine = root.findViewById(R.id.updated_line);
        sampleLine = root.findViewById(R.id.sample_line);
        cardBalance = root.findViewById(R.id.card_balance);
        // 累计消费上涨代表花钱，闪暖橙色
        moneyCumulative.setWarnOnRise(true);

        root.findViewById(R.id.btn_topup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTopUp();
            }
        });
        View.OnClickListener toProfile = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.selectTab(2);
            }
        };
        root.findViewById(R.id.btn_alert_setup).setOnClickListener(toProfile);
        bindInfo(root.findViewById(R.id.info_balance), "充值余额",
                "调用 API 会消耗余额，如有疑问可以查看官方文档。",
                "查看文档", "https://static.deepseek.com/faq/index.html?lang=zh#/category/4");
        bindInfo(root.findViewById(R.id.info_granted), "赠送余额",
                "赠送余额有有效期，可在这里查看每一笔的有效期。",
                "查看有效期", "https://platform.deepseek.com/transactions?granted");
        badgeAlert.setOnClickListener(toProfile);
        sourceLine.setOnClickListener(toProfile);
    }

    @Override
    public void onShown() {
        updateStaticInfo();
        updateAlertBadge();
        applyCumulative();
        long at = Prefs.lastBalanceAt(host);
        if (System.currentTimeMillis() - at > 60_000L) {
            refresh();
        }
    }

    @Override
    public void refresh() {
        final String apiKey = Prefs.apiKey(host);
        final String token = Prefs.userToken(host);
        if (apiKey.isEmpty()) {
            showNoKey();
        } else {
            host.beginLoad();
            Bg.run(new Runnable() {
                @Override
                public void run() {
                    Balance balance = null;
                    String error = null;
                    try {
                        balance = DeepSeekClient.fetchBalance(apiKey);
                    } catch (DeepSeekClient.ApiException e) {
                        error = e.getMessage();
                        Prefs.setOffline(host, true);
                    }
                    if (!token.isEmpty()) {
                        try {
                            long[] window = DeepSeekClient.todayWindow();
                            UsageData today = DeepSeekClient.fetchExportRange(token, window[0], window[1]);
                            UsageData.Slice total = today.sumAll();
                            SampleStore.append(host, total.cost, total.tokens, total.requests,
                                    total.hitTokens, total.missTokens, total.outTokens,
                                    today.itemStatsAll(UsageData.DIM_KEY),
                                    today.itemStatsAll(UsageData.DIM_MODEL));
                        } catch (DeepSeekClient.ApiException ignored) {
                            // the usage page will surface the error
                        }
                    }
                    final Balance result = balance;
                    final String failure = error;
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            host.endLoad();
                            if (result != null) {
                                applyBalance(result);
                            } else if (failure != null) {
                                sourceLine.setText("刷新失败：" + failure);
                            }
                            updateStaticInfo();
                        }
                    });
                }
            });
        }
        refreshCumulative();
    }

    private void refreshCumulative() {
        final String token = Prefs.userToken(host);
        if (token.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (Prefs.cumulative(host) >= 0 && now - Prefs.cumulativeAt(host) < 5L * 60L * 1000L) {
            return;
        }
        Bg.run(new Runnable() {
            @Override
            public void run() {
                try {
                    final double total = DeepSeekClient.fetchAllTimeCost(token, 36);
                    // consumption only grows: never overwrite a larger cached value with a partial scan
                    Prefs.setCumulative(host, Math.max(Prefs.cumulative(host), total), now);
                    Prefs.setCumulativeDelta(host, 0);
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            applyCumulative();
                        }
                    });
                } catch (DeepSeekClient.ApiException ignored) {
                    // keep whatever we showed before
                }
            }
        });
    }

    private void applyBalance(Balance b) {
        double previous = Prefs.lastBalance(host);
        final double delta = previous > 0 ? b.total - previous : 0;
        // 充值余额 = topped_up（与开放平台一致，扣费先扣赠送，所以它可能半天不动）
        moneyTotal.setAmount(b.toppedUp, !firstRender);
        // 总余额才是真正随扣费变化的数字：由它带动翻滚、变色与浮标
        moneyTotalAll.setAmount(b.total, !firstRender, new Runnable() {
            @Override
            public void run() {
                if (delta < -0.0001d) {
                    showChangeChip(-delta, false);
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (delta > 0.0001d) {
                    showChangeChip(delta, true);
                }
            }
        });
        moneyGranted.setAmount(b.granted, !firstRender);
        // 赠送余额为 0 时整张卡片不显示
        android.view.View grantedCard = root.findViewById(R.id.card_granted);
        if (grantedCard != null) {
            grantedCard.setVisibility(b.granted > 0.0001d
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        firstRender = false;

        TextView currency = root.findViewById(R.id.currency_total);
        currency.setText(b.currency);
        ((TextView) root.findViewById(R.id.currency_cumulative)).setText(b.currency);
        ((TextView) root.findViewById(R.id.currency_granted)).setText(b.currency);

        if (previous > 0 && b.total > previous) {
            Prefs.addTopup(host, b.total - previous);
        }
        if (delta < -0.0001d) {
            // keep 累计消费 moving in step with the balance drop, without waiting for the next
            // platform scan
            Prefs.addCumulativeDelta(host, -delta);
        }
        if (Prefs.baseTotal(host) <= 0) {
            Prefs.setBaseTotal(host, b.total);
        }
        Prefs.setLastBalance(host, b.total, b.fetchedAt == 0 ? System.currentTimeMillis() : b.fetchedAt);

        Prefs.setOffline(host, false);
        StringBuilder meta = new StringBuilder();
        if (Prefs.offline(host)) {
            meta.append("⚠ 离线，显示上次数据 · ");
        }
        meta.append("余额可用：").append(b.available ? "是" : "否");
        if (Math.abs(delta) > 0.0001d) {
            meta.append(" · 较上次 ").append(delta > 0 ? "+" : "−").append("¥").append(Fmt.money2(Math.abs(delta)));
        }
        balanceMeta.setText(meta.toString());

        applyCumulative();
        appendTodayCost(b);
        updateAlertBadge();
        maybeAlert(b.total);
    }


    /** 今日花费单独一行（仿照“总余额”的版式），显示在累计消费金额下方。 */
    private void appendTodayCost(Balance b) {
        double today = 0;
        try {
            UsageData data = UsageRepo.data;
            if (data != null && data.days != null) {
                UsageData.Day d = data.days.get(DeepSeekClient.isoToday());
                if (d != null) {
                    today = d.total.cost;
                }
            }
        } catch (Throwable ignored) {
            today = 0;
        }
        if (todayCost != null) {
            todayCost.setAmount(Math.max(0, today), !firstRender);
        }
        TextView todayCurrency = root.findViewById(R.id.today_currency);
        if (todayCurrency != null) {
            todayCurrency.setText(b.currency);
        }
    }


    /** 点 ⓘ 弹出说明，并给一个跳官方页面的按钮。 */
    private void bindInfo(android.view.View view, final String title, final String message,
                          final String action, final String url) {
        if (view == null) {
            return;
        }
        view.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                new android.app.AlertDialog.Builder(host)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(action, (dialog, which) -> openUrl(url))
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
    }

    private void openUrl(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
        } catch (Throwable t) {
            host.toast("没有可用的浏览器");
        }
    }

    private void applyCumulative() {
        String token = Prefs.userToken(host);
        double platform = Prefs.cumulative(host);
        double delta = Math.max(0, Prefs.cumulativeDelta(host));
        double displayed = moneyCumulative.shownValue();
        if (!token.isEmpty() && platform >= 0) {
            double value = platform + delta;
            // 累计消费只增不减：数据回落时保持原值，避免莫名回退
            if (!Double.isNaN(displayed) && value < displayed) {
                value = displayed;
            }
            moneyCumulative.setAmount(value, !firstRender);
            cumulativeMeta.setText(delta > 0.004d
                    ? "平台累计 ¥" + Fmt.money2(platform) + " + 本机实时 ¥" + Fmt.money2(delta)
                    : "来自平台用量接口，跨月累计");
            return;
        }
        double base = Prefs.baseTotal(host);
        double topups = Prefs.topupSum(host);
        double current = moneyTotal.shownValue();
        if (Double.isNaN(current)) {
            cumulativeMeta.setText("等待首次余额数据");
            return;
        }
        double estimate = Math.max(0, base + topups - current);
        if (!Double.isNaN(displayed) && estimate < displayed) {
            estimate = displayed;
        }
        moneyCumulative.setAmount(estimate, !firstRender);
        cumulativeMeta.setText("本机估算：初始余额 + 充值 − 当前余额。填入平台 User Token 可显示官方累计。");
    }

    private void maybeAlert(double total) {
        double threshold = Prefs.alertThreshold(host);
        if (Prefs.alertEnabled(host) && total <= threshold) {
            if (!Prefs.notifiedLow(host)) {
                Alerts.notifyLowBalance(host, total, threshold);
                Prefs.setNotifiedLow(host, true);
            }
        } else if (total > threshold) {
            if (Prefs.notifiedLow(host)) {
                Alerts.cancel(host);
            }
            Prefs.setNotifiedLow(host, false);
        }
    }

    private void updateAlertBadge() {
        boolean on = Prefs.alertEnabled(host);
        badgeAlert.setText(on ? R.string.alert_on : R.string.alert_off);
        badgeAlert.setBackgroundResource(on ? R.drawable.bg_badge : R.drawable.bg_badge_off);
        badgeAlert.setTextColor(Ui.color(host, on ? R.color.ds_badge_fg : R.color.ds_badge_off_fg));
    }

    private void updateStaticInfo() {
        String token = Prefs.userToken(host);
        sourceLine.setText(token.isEmpty()
                ? "数据来源：官方余额接口 api.deepseek.com/user/balance（点击填写密钥）"
                : "数据来源：官方余额接口 + 平台用量接口（platform.deepseek.com）");
        long at = Prefs.lastBalanceAt(host);
        updatedLine.setText(at > 0 ? "最近刷新：" + Fmt.dateTime(at) : "尚未刷新");
        sampleLine.setText("本机采样：今日 " + SampleStore.today(host).size() + " 条 · 累计 "
                + SampleStore.count(host) + " 条");
        if (Prefs.offline(host)) {
            balanceMeta.setText("⚠ 离线：以下为 " + (at > 0 ? Fmt.dateTime(at) : "上次") + " 的数据");
        }
    }

    /** Floating "−¥0.42" / "+¥20.00" chip that drifts up and fades out when the balance moves. */
    private void showChangeChip(double amount, boolean rise) {
        if (cardBalance == null || amount <= 0.0001d) {
            return;
        }
        final TextView chip = new TextView(host);
        chip.setText((rise ? "+¥" : "−¥") + Fmt.money2(amount));
        chip.setTextSize(12f);
        chip.setTextColor(Ui.color(host, rise ? R.color.ds_rise : R.color.ds_warn));
        chip.setBackgroundResource(R.drawable.bg_chip);
        int px = Ui.dp(host, 10);
        int py = Ui.dp(host, 4);
        chip.setPadding(px, py, px, py);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = Ui.dp(host, 14);
        lp.rightMargin = Ui.dp(host, 18);
        chip.setAlpha(0f);
        cardBalance.addView(chip, lp);
        chip.animate().alpha(1f).setDuration(140L).withEndAction(new Runnable() {
            @Override
            public void run() {
                chip.animate().translationY(-Ui.dp(host, 30)).alpha(0f).setDuration(rise ? 1500L : 1100L)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                cardBalance.removeView(chip);
                            }
                        }).start();
            }
        }).start();
    }

    private void showNoKey() {
        moneyTotal.setText("¥--.--");
        moneyTotalAll.setText("¥--.--");
        moneyCumulative.setText("¥--.--");
        moneyGranted.setText("¥--.--");
        balanceMeta.setText("尚未配置 API Key");
        sourceLine.setText("点击此处前往「我的」填写 DeepSeek API Key（仅保存在本机）");
    }

    private void openTopUp() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://platform.deepseek.com/top_up"));
            host.startActivity(intent);
        } catch (RuntimeException e) {
            host.toast("没有可用的浏览器");
        }
    }
}
