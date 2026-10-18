package com.pigeoncookie.dsmonitor;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.pigeoncookie.dsmonitor.core.Prefs;
import com.pigeoncookie.dsmonitor.data.Alerts;

/** Single-activity shell: header, three pages, bottom navigation. */
public class MainActivity extends Activity implements PageHost {

    private static final int REQ_AVATAR = 41;
    private static final int REQ_NOTIFICATION = 42;

    private final Page[] pages = new Page[3];
    private final View[] navItems = new View[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];

    private int current = 0;
    private int activeLoads = 0;
    private ImageButton btnReload;
    private ObjectAnimator spinner;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.wrapTheme(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Alerts.ensureChannel(this);

        btnReload = findViewById(R.id.btn_reload);
        navItems[0] = findViewById(R.id.nav_dashboard);
        navItems[1] = findViewById(R.id.nav_usage);
        navItems[2] = findViewById(R.id.nav_profile);
        navIcons[0] = findViewById(R.id.nav_icon_dashboard);
        navIcons[1] = findViewById(R.id.nav_icon_usage);
        navIcons[2] = findViewById(R.id.nav_icon_profile);
        navLabels[0] = findViewById(R.id.nav_label_dashboard);
        navLabels[1] = findViewById(R.id.nav_label_usage);
        navLabels[2] = findViewById(R.id.nav_label_profile);

        pages[0] = new DashboardPage(this, findViewById(R.id.page_dashboard));
        pages[1] = new UsagePage(this, findViewById(R.id.page_usage));
        pages[2] = new ProfilePage(this, findViewById(R.id.page_profile));

        for (int i = 0; i < navItems.length; i++) {
            final int index = i;
            navItems[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
        }
        btnReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点一次重载：余额页与用量页的数据一起刷新（两页共享同一套平台数据）
                boolean any = false;
                for (int i = 0; i < pages.length; i++) {
                    Page page = pages[i];
                    if (page == null) {
                        continue;
                    }
                    if (page instanceof com.pigeoncookie.dsmonitor.DashboardPage
                            || page instanceof com.pigeoncookie.dsmonitor.UsagePage) {
                        page.refresh();
                        any = true;
                    }
                }
                if (!any && pages[current] != null) {
                    pages[current].refresh();
                }
            }
        });
        spinner = ObjectAnimator.ofFloat(btnReload, "rotation", 0f, 360f);
        spinner.setDuration(900L);
        spinner.setRepeatCount(ObjectAnimator.INFINITE);
        spinner.setInterpolator(new LinearInterpolator());

        selectTab(Prefs.lastTab(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Page page = pages[current];
        if (page != null) {
            page.onShown();
        }
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void selectTab(int index) {
        if (index < 0 || index >= pages.length || pages[index] == null) {
            return;
        }
        current = index;
        Prefs.setLastTab(this, index);
        for (int i = 0; i < pages.length; i++) {
            pages[i].root().setVisibility(i == index ? View.VISIBLE : View.GONE);
            int tint = Ui.color(this, i == index ? R.color.ds_accent : R.color.ds_text_3);
            navIcons[i].setColorFilter(tint);
            navLabels[i].setTextColor(tint);
        }
        // the reload button lives on the two data pages only
        btnReload.setVisibility(index == 2 ? View.INVISIBLE : View.VISIBLE);
        pages[index].onShown();
    }

    @Override
    public void beginLoad() {
        activeLoads++;
        if (activeLoads == 1 && spinner != null && !spinner.isStarted()) {
            spinner.start();
        }
    }

    @Override
    public void endLoad() {
        activeLoads = Math.max(0, activeLoads - 1);
        if (activeLoads == 0 && spinner != null) {
            spinner.cancel();
            btnReload.setRotation(0f);
        }
    }

    @Override
    public void toast(String message) {
        if (message != null && !message.isEmpty()) {
            Ui.toast(this, message);
        }
    }

    @Override
    public void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择头像"), REQ_AVATAR);
        } catch (RuntimeException e) {
            toast("没有可用的图片选择器");
        }
    }

    @Override
    public void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATION);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 点图表以外的地方就关掉柱状图弹窗
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && pages[1] instanceof UsagePage) {
            ((UsagePage) pages[1]).dismissTooltipsOutside(ev.getRawX(), ev.getRawY());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AVATAR && resultCode == RESULT_OK && data != null && data.getData() != null
                && pages[2] instanceof ProfilePage) {
            ((ProfilePage) pages[2]).handleAvatarResult(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION && grantResults.length > 0
                && grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("未授予通知权限，余额预警将只在应用内提示");
        }
    }
}
