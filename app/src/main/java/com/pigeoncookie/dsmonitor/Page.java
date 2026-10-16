package com.pigeoncookie.dsmonitor;

import android.view.View;

/** One of the three tab pages. */
public abstract class Page {

    protected final MainActivity host;
    protected final View root;

    protected Page(MainActivity host, View root) {
        this.host = host;
        this.root = root;
    }

    public View root() {
        return root;
    }

    /** Called every time the page becomes visible. */
    public abstract void onShown();

    /** Called by the top-left reload button. */
    public abstract void refresh();
}
