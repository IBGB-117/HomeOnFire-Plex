// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.ibgb117.homeonfireplex.test;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

/** Records boot readiness; only the bound accessibility service dispatches launches. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        if (!boot && !Intent.ACTION_USER_UNLOCKED.equals(action)) return;
        if (HijackService.autostartBootSignal(boot, action)) return;
        if (!boot) return;
        AutostartStore store = new AutostartStore(context);
        AutostartSession session = new AutostartSession(store.read(), store,
                message -> AutostartDiagnostics.log(context, message));
        boolean awake = ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive();
        session.boot(AutostartStore.bootIdentity(context), true, SystemClock.elapsedRealtime(), awake);
    }
}
