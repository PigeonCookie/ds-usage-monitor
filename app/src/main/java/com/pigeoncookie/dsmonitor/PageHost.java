package com.pigeoncookie.dsmonitor;

import android.app.Activity;

/** What a {@link Page} may ask of its hosting activity. */
public interface PageHost {

    Activity activity();

    /** 0 = 余额, 1 = 用量, 2 = 我的 */
    void selectTab(int index);

    void beginLoad();

    void endLoad();

    void toast(String message);

    void pickAvatar();

    void requestNotificationPermission();
}
