// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.ibgb117.homeonfireplex.test;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;

/** Only lifecycle metadata is kept in direct-boot storage, never Plex credentials. */
final class AutostartStore implements AutostartSession.Persistence {
    private final SharedPreferences sp;

    AutostartStore(Context context) {
        Context storage = Build.VERSION.SDK_INT >= 24
                ? context.createDeviceProtectedStorageContext() : context;
        sp = storage.getSharedPreferences("plex_autostart_session", Context.MODE_PRIVATE);
    }

    AutostartSession.State read() {
        AutostartSession.State s = new AutostartSession.State();
        s.boot = sp.getString("boot", "");
        s.serial = sp.getLong("serial", 0);
        try {
            s.status = AutostartSession.Status.valueOf(sp.getString("status", "IDLE"));
        } catch (IllegalArgumentException e) {
            // Corrupt state must never silently rearm a completed session.
            s.status = AutostartSession.Status.CANCELLED;
        }
        s.sleeping = sp.getBoolean("sleeping", false);
        s.bootAwaitingWake = sp.getBoolean("bootAwaitingWake", false);
        s.bootNotified = sp.getBoolean("bootNotified", false);
        s.manualWhileAsleep = sp.getBoolean("manualWhileAsleep", false);
        s.startedAt = sp.getLong("startedAt", 0);
        s.nextAttemptAt = sp.getLong("nextAttemptAt", 0);
        s.attempts = sp.getInt("attempts", 0);
        s.checks = sp.getInt("checks", 0);
        s.target = sp.getString("target", "");
        s.reason = sp.getString("reason", "baseline");
        return s;
    }

    @Override public void save(AutostartSession.State s) {
        // Small synchronous transaction: terminal state/cooldown must survive a kill.
        if (!sp.edit().putString("boot", s.boot).putLong("serial", s.serial)
                .putString("status", s.status.name()).putBoolean("sleeping", s.sleeping)
                .putBoolean("bootAwaitingWake", s.bootAwaitingWake)
                .putBoolean("bootNotified", s.bootNotified).putLong("startedAt", s.startedAt)
                .putBoolean("manualWhileAsleep", s.manualWhileAsleep)
                .putLong("nextAttemptAt", s.nextAttemptAt).putInt("attempts", s.attempts)
                .putInt("checks", s.checks).putString("target", s.target)
                .putString("reason", s.reason).commit()) {
            throw new IllegalStateException("Cannot persist autostart state; refusing to launch");
        }
    }

    static String bootIdentity(Context context) {
        try {
            String count = Settings.Global.getString(context.getContentResolver(), "boot_count");
            if (count != null && count.matches("[0-9]+")) return "boot:" + count;
        } catch (SecurityException ignored) { }
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id = reader.readLine();
            if (id != null && id.matches("[a-fA-F0-9-]{36}")) return "kernel:" + id;
        } catch (Exception ignored) { }
        Log.w(HijackService.TAG, "Autostart: stable boot identity unavailable; "
                + "boot recovery cannot be guaranteed; observed screen wake still works");
        // Fail closed rather than invent a new boot on every service reconnect.
        return "unavailable";
    }
}
