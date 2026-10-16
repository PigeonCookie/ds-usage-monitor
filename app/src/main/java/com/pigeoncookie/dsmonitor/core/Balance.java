package com.pigeoncookie.dsmonitor.core;

import java.util.List;

/** Result of GET https://api.deepseek.com/user/balance */
public final class Balance {

    public boolean available;
    public String currency = "CNY";
    public double total;
    public double granted;
    public double toppedUp;
    public long fetchedAt;

    public static Balance parse(Object root, long now) {
        if (Json.asMap(root) == null) {
            return null;
        }
        Balance b = new Balance();
        b.available = Json.boolAt(root, "is_available", false);
        b.fetchedAt = now;
        List<Object> infos = Json.asList(Json.at(root, "balance_infos"));
        if (infos != null) {
            Object pick = null;
            for (Object o : infos) {
                String cur = Json.strAt(o, "currency", "");
                if ("CNY".equalsIgnoreCase(cur)) {
                    pick = o;
                    break;
                }
                if (pick == null) {
                    pick = o;
                }
            }
            if (pick != null) {
                String cur = Json.strAt(pick, "currency", "CNY");
                b.currency = cur.isEmpty() ? "CNY" : cur;
                b.total = Json.numAt(pick, "total_balance", 0);
                b.granted = Json.numAt(pick, "granted_balance", 0);
                b.toppedUp = Json.numAt(pick, "topped_up_balance", 0);
            }
        }
        return b;
    }
}
