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
    /** 刚才那次平台导出拿到的“今日全部 Key 合计”，-1 表示本次没取到。 */
    private double freshTodayCost = -1;
    /** 今日花费只增不减：记住已显示的日期，跨天才允许回落。 */
    private String todayFloorDay = "";
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
        // 今日花费与累计消费同义：上涨即“又花钱了”，一起闪橙色
        if (todayCost != null) {
            todayCost.setWarnOnRise(true);
        }

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
                    double platformToday = -1;
                    if (!token.isEmpty()) {
                        try {
                            long[] window = DeepSeekClient.todayWindow();
                            UsageData today = DeepSeekClient.fetchExportRange(token, window[0], window[1]);
                            // 按官网口径：把所有 API Key 的今日消费逐个相加（只走一个维度，不会翻倍）
                            double sum = 0;
                            int counted = 0;
                            for (String keyName : today.itemStatsAll(UsageData.DIM_KEY).keySet()) {
                                sum += today.sliceAll(UsageData.DIM_KEY, keyName).cost;
                                counted++;
                            }
                            platformToday = counted > 0 ? sum : -1;
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
                    final double todayCost = platformToday;
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            host.endLoad();
                            if (todayCost >= 0) {
                                freshTodayCost = todayCost;
                            }
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
        boolean hasGift = b.granted > 0.0001d;
        if (grantedCard != null) {
            grantedCard.setVisibility(hasGift
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        // 赠送余额为 0 时，总余额 == 充值余额，这一行没有信息量：直接隐藏并让卡片收缩
        android.view.View totalRow = root.findViewById(R.id.total_all_row);
        if (totalRow != null) {
            totalRow.setVisibility(hasGift
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

        // 先算今日再折叠“本机实时增量”，两个数字才会同步跳动
        appendTodayCost(b);
        applyCumulative();
        updateAlertBadge();
        maybeAlert(b.total);
    }


    /** 今日花费单独一行（仿照“总余额”的版式），显示在累计消费金额下方。 */
    /** 今天已经花掉多少：优先用量页那份数据（与卡片同源），其次本次刷新拉到的合计。 */
    private double daySpentSoFar() {
        // 只用用量页那份数据：它就是“所有 API Key 今日消费之和”，与卡片完全同一个数
        return UsageRepo.data != null ? UsageRepo.data.sumAll().cost : -1;
    }

    private void appendTodayCost(Balance b) {
        double spent = daySpentSoFar();
        double realtime = Math.max(0, Prefs.cumulativeDelta(host));
        double today;
        if (spent >= 0) {
            // 直接按官网口径：所有 API Key 今日消费之和 + 尚未反映到平台的本机实时增量
            today = spent + realtime;
            if (todayCost != null && !firstRender && today < todayCost.shownValue()) {
                today = todayCost.shownValue();          // 当天只增不减
            }
        } else {
            // 还没有用量数据（没打开过用量页）：退回“累计 − 基线”的近似算法
            String iso = DeepSeekClient.isoToday();
            double shown = moneyCumulative == null ? 0 : moneyCumulative.shownValue();
            double base = Prefs.cumulativeBase(host);
            if (!iso.equals(Prefs.cumulativeBaseDay(host)) || base < 0) {
                base = Math.max(0, shown);
                Prefs.setCumulativeBase(host, base, iso);
            }
            today = Math.max(0, shown - base);
        }
        if (todayCost != null) {
            todayCost.setAmount(today, !firstRender);
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
