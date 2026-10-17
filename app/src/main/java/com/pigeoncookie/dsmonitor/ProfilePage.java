package com.pigeoncookie.dsmonitor;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.pigeoncookie.dsmonitor.core.Balance;
import com.pigeoncookie.dsmonitor.core.Bg;
import com.pigeoncookie.dsmonitor.core.DeepSeekClient;
import com.pigeoncookie.dsmonitor.core.Fmt;
import com.pigeoncookie.dsmonitor.core.Prefs;
import com.pigeoncookie.dsmonitor.core.UsageData;
import com.pigeoncookie.dsmonitor.core.UsageRepo;
import com.pigeoncookie.dsmonitor.data.SampleJobService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Page 3 — profile: avatar/nickname, credentials, appearance, alerts. */
public class ProfilePage extends Page {

    private final ImageView avatar;
    private final EditText editNickname;
    private final EditText editApiKey;
    private final EditText editToken;
    private final EditText editThreshold;
    private final ImageView eyeKey;
    private final TextView testResult;
    private final Switch swAlert;
    private final Switch swSampling;
    private final TextView segDay;
    private final TextView segNight;
    private final TextView segSystem;

    private boolean keyVisible;
    private boolean binding;

    public ProfilePage(MainActivity host, View root) {
        super(host, root);
        avatar = root.findViewById(R.id.avatar);
        editNickname = root.findViewById(R.id.edit_nickname);
        editApiKey = root.findViewById(R.id.edit_apikey);
        editToken = root.findViewById(R.id.edit_token);
        editThreshold = root.findViewById(R.id.edit_threshold);
        eyeKey = root.findViewById(R.id.btn_eye_key);
        testResult = root.findViewById(R.id.test_result);
        swAlert = root.findViewById(R.id.sw_alert);
        swSampling = root.findViewById(R.id.sw_sampling);
        segDay = root.findViewById(R.id.seg_day);
        segNight = root.findViewById(R.id.seg_night);
        segSystem = root.findViewById(R.id.seg_system);

        Ui.circle(avatar);

        root.findViewById(R.id.btn_save_profile).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setNickname(host, editNickname.getText().toString().trim());
                host.toast("昵称已保存");
            }
        });
        root.findViewById(R.id.btn_pick_avatar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.pickAvatar();
            }
        });
        avatar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.pickAvatar();
            }
        });
        eyeKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keyVisible = !keyVisible;
                applyKeyVisibility();
            }
        });
        root.findViewById(R.id.btn_save_keys).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCredentials();
            }
        });
        root.findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });

        segDay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTheme(Prefs.THEME_DAY);
            }
        });
        segNight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTheme(Prefs.THEME_NIGHT);
            }
        });
        segSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTheme(Prefs.THEME_SYSTEM);
            }
        });

        swAlert.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (binding) {
                    return;
                }
                Prefs.setAlertEnabled(host, isChecked);
                Prefs.setNotifiedLow(host, false);
                if (isChecked) {
                    host.requestNotificationPermission();
                    host.toast("余额低于 ¥" + Fmt.money2(threshold()) + " 时提醒");
                }
            }
        });
        // 采样已并入余额预警：后台固定运行，界面不再提供开关
        Prefs.setHourlySampling(host, true);
        swSampling.setChecked(true);
        // 整行隐藏（切换键的父容器就是"标题 + 说明 + 开关"这一行）
        if (swSampling.getParent() instanceof View) {
            ((View) swSampling.getParent()).setVisibility(View.GONE);
        } else {
            swSampling.setVisibility(View.GONE);
        }
        SampleJobService.schedule(host);
        editThreshold.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Prefs.setAlertThreshold(host, parseDouble(s.toString(), 10d));
            }
        });
    }

    @Override
    public void onShown() {
        loadValues();
    }

    @Override
    public void refresh() {
        loadValues();
    }

    private void loadValues() {
        binding = true;
        String nickname = Prefs.nickname(host);
        if (!nickname.equals(editNickname.getText().toString())) {
            editNickname.setText(nickname);
        }
        editApiKey.setText(Prefs.apiKey(host));
        editToken.setText(Prefs.userToken(host));
        editThreshold.setText(Fmt.trim1(Prefs.alertThreshold(host)));
        swAlert.setChecked(Prefs.alertEnabled(host));
        swSampling.setChecked(Prefs.hourlySampling(host));
        binding = false;
        applyThemeSegment();
        applyKeyVisibility();
        loadAvatar();
        TextView version = root.findViewById(R.id.version_line);
version.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                final EditText input = new EditText(host);
                input.setText(Prefs.userAgent(host));
                input.setHint("留空 = 默认 UA");
                input.setSingleLine(true);
                new AlertDialog.Builder(host)
                        .setTitle("自定义 User-Agent（仅作用于平台接口）")
                        .setView(input)
                        .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Prefs.setUserAgent(host, input.getText().toString());
                                DeepSeekClient.setUserAgent(Prefs.userAgent(host));
                                host.toast("已保存，下次请求生效");
                            }
                        })
                        .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Prefs.setUserAgent(host, "");
                                DeepSeekClient.setUserAgent("");
                                host.toast("已恢复默认 UA");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            }
        });
        version.setText("版本 " + versionName() + " (" + versionCode() + ") · 无广告 · 仅本机保存密钥");
    }

    /** Build number; handy to confirm which APK is actually installed. */
    private int versionCode() {
        try {
            return host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String versionName() {
        try {
            PackageInfo info = host.getPackageManager().getPackageInfo(host.getPackageName(), 0);
            return info.versionName == null ? "1.0.0" : info.versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    private void applyKeyVisibility() {
        int type = keyVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        editApiKey.setInputType(type);
        editApiKey.setSelection(editApiKey.getText().length());
        eyeKey.setImageResource(keyVisible ? R.drawable.ic_visibility : R.drawable.ic_visibility_off);
    }

    private void applyThemeSegment() {
        int mode = Prefs.themeMode(host);
        styleSegment(segDay, mode == Prefs.THEME_DAY);
        styleSegment(segNight, mode == Prefs.THEME_NIGHT);
        styleSegment(segSystem, mode == Prefs.THEME_SYSTEM);
    }

    private void styleSegment(TextView view, boolean selected) {
        view.setBackgroundResource(selected ? R.drawable.bg_segment_on : 0);
        view.setTextColor(Ui.color(host, selected ? R.color.ds_seg_on_fg : R.color.ds_seg_fg));
    }

    private void setTheme(int mode) {
        if (Prefs.themeMode(host) == mode) {
            return;
        }
        Prefs.setThemeMode(host, mode);
        host.recreate();
    }

    private void saveCredentials() {
        Prefs.setApiKey(host, editApiKey.getText().toString());
        Prefs.setUserToken(host, editToken.getText().toString());
        Prefs.setAlertThreshold(host, threshold());
        UsageRepo.clear();
        host.toast("已保存到本机");
    }

    private double threshold() {
        return parseDouble(editThreshold.getText().toString(), 10d);
    }

    private static double parseDouble(String text, double def) {
        if (text == null) {
            return def;
        }
        try {
            String s = text.trim();
            return s.isEmpty() ? def : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void testConnection() {
        Prefs.setApiKey(host, editApiKey.getText().toString());
        Prefs.setUserToken(host, editToken.getText().toString());
        UsageRepo.clear();
        final String key = Prefs.apiKey(host);
        final String token = Prefs.userToken(host);
        if (key.isEmpty() && token.isEmpty()) {
            testResult.setText("请先填写 API Key 或平台 User Token");
            return;
        }
        testResult.setText("正在测试…");
        host.beginLoad();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                if (!key.isEmpty()) {
                    try {
                        Balance b = DeepSeekClient.fetchBalance(key);
                        sb.append("余额接口：OK（").append(b.currency).append(" ¥")
                                .append(Fmt.money2(b.total)).append("）");
                    } catch (DeepSeekClient.ApiException e) {
                        sb.append("余额接口：失败（").append(e.getMessage()).append("）");
                    }
                } else {
                    sb.append("余额接口：未配置 API Key");
                }
                if (!token.isEmpty()) {
                    try {
                        long[] window = DeepSeekClient.todayWindow();
                        UsageData today = DeepSeekClient.fetchExportRange(token, window[0], window[1]);
                        UsageData.Slice total = today.sumAll();
                        sb.append("\n用量接口：OK（今日 ¥").append(Fmt.money2(total.cost))
                                .append(" / ").append(total.requests).append(" 次 / ")
                                .append(Fmt.group(total.tokens)).append(" tokens");
                        int keys = today.keys().size();
                        if (keys > 0) {
                            sb.append("，可拆分 ").append(keys).append(" 个 API Key");
                        }
                        sb.append("）");
                        String types = today.usageTypeSummary();
                        if (!types.isEmpty()) {
                            sb.append("\n类型明细：").append(types);
                        }
                        String dates = today.usageDateSummary();
                        if (!dates.isEmpty()) {
                            sb.append("\n日期明细：").append(dates);
                        }
                        String hours = com.pigeoncookie.dsmonitor.data.HourlyCache.summary(host);
                        if (!hours.isEmpty()) {
                            sb.append("\n平台逐时：").append(hours);
                        }
                    } catch (DeepSeekClient.ApiException e) {
                        sb.append("\n用量接口：失败（").append(e.getMessage()).append("）");
                    }
                } else {
                    sb.append("\n用量接口：未配置平台 User Token（用量页将无数据）");
                }
                final String text = sb.toString();
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        host.endLoad();
                        testResult.setText(text);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ avatar

    private void loadAvatar() {
        String path = Prefs.avatarPath(host);
        if (path != null && !path.isEmpty()) {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp != null) {
                avatar.setImageBitmap(bmp);
                return;
            }
        }
        avatar.setImageResource(R.drawable.logo_mark);
    }

    /** Called from {@link MainActivity#onActivityResult}. */
    public void handleAvatarResult(Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = host.getContentResolver().openInputStream(uri);
            if (in == null) {
                host.toast("无法读取所选图片");
                return;
            }
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null) {
                host.toast("无法解码所选图片");
                return;
            }
            int side = Math.min(src.getWidth(), src.getHeight());
            int left = (src.getWidth() - side) / 2;
            int top = (src.getHeight() - side) / 2;
            Bitmap square = Bitmap.createBitmap(src, left, top, side, side);
            Bitmap scaled = Bitmap.createScaledBitmap(square, 256, 256, true);
            File file = new File(host.getFilesDir(), "avatar.png");
            out = new FileOutputStream(file);
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            Prefs.setAvatarPath(host, file.getAbsolutePath());
            avatar.setImageBitmap(scaled);
            host.toast("头像已更新");
        } catch (Exception e) {
            host.toast("头像保存失败：" + e.getMessage());
        } finally {
            close(in);
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
    }
}
