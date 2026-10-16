package com.pigeoncookie.dsmonitor.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Background executor + main-thread hop. */
public final class Bg {

    public static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    private Bg() {
    }

    public static void run(Runnable r) {
        POOL.execute(r);
    }

    public static void ui(Runnable r) {
        MAIN.post(r);
    }
}
