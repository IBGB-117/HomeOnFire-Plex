// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.ibgb117.homeonfireplex.test;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/** Bounded, local lifecycle log. No UI text, media URLs, account or token values. */
final class AutostartDiagnostics {
    private static SharedPreferences storage(Context context) {
        Context storage = Build.VERSION.SDK_INT >= 24
                ? context.createDeviceProtectedStorageContext() : context;
        return storage.getSharedPreferences("plex_autostart_diagnostics", Context.MODE_PRIVATE);
    }

    static synchronized void log(Context context, String message) {
        Log.i(HijackService.TAG, "Autostart " + message);
        SharedPreferences sp = storage(context);
        String text = sp.getString("recent", "") + System.currentTimeMillis() + " elapsed="
                + SystemClock.elapsedRealtime() + " " + message.replace('\n', ' ') + "\n";
        String[] lines = text.split("\n");
        StringBuilder bounded = new StringBuilder();
        for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) {
            bounded.append(lines[i]).append('\n');
        }
        String value = bounded.toString();
        if (value.length() > 32_768) value = value.substring(value.indexOf('\n', value.length() - 32_768) + 1);
        sp.edit().putString("recent", value).apply();
    }

    static String recent(Context context) { return storage(context).getString("recent", ""); }
}
