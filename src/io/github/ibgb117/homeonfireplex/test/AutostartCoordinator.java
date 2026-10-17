// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.ibgb117.homeonfireplex.test;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Main-looper adapter. No alarms, jobs, permanent polling, network or wakelocks. */
@SuppressWarnings("deprecation")
final class AutostartCoordinator {
    private final AccessibilityService service;
    private final Handler handler;
    private final AutostartSession session;
    private final DisplayManager displays;
    private Runnable pending;
    private long scheduledAt = Long.MAX_VALUE;
    private long lastCheckAt;
    private String foregroundPackage = "", foregroundClass = "";
    private String lastObservation = "";
    private long lastObservationAt;
    private boolean closed;

    AutostartCoordinator(AccessibilityService service, Handler handler) {
        this.service = service;
        this.handler = handler;
        AutostartStore store = new AutostartStore(service);
        session = new AutostartSession(store.read(), store, message -> log(message));
        displays = (DisplayManager) service.getSystemService(Context.DISPLAY_SERVICE);
    }

    void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        filter.addAction("android.intent.action.HDMI_PLUGGED");
        filter.addAction("android.media.action.HDMI_AUDIO_PLUG");
        if (Build.VERSION.SDK_INT >= 33) {
            service.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            service.registerReceiver(receiver, filter);
        }
        displays.registerDisplayListener(displayListener, handler);
        boot(false, "service connected");
    }

    void boot(boolean explicit, String source) {
        if (closed) return;
        boolean awake = awake();
        session.boot(AutostartStore.bootIdentity(service), explicit, now(), awake);
        log("source=" + source + " interactive=" + awake);
        power(awake);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("source=" + action + " interactive=" + awake());
            // SCREEN_OFF is authoritative even if PowerManager lags this broadcast.
            power(!Intent.ACTION_SCREEN_OFF.equals(action) && awake());
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { sampleDisplay(id); }
        @Override public void onDisplayRemoved(int id) { sampleDisplay(id); }
        @Override public void onDisplayChanged(int id) { sampleDisplay(id); }
    };

    private void sampleDisplay(int id) {
        if (id != Display.DEFAULT_DISPLAY) return;
        Display display = displays.getDisplay(id);
        log("source=default-display state=" + (display == null ? "missing" : display.getState())
                + " interactive=" + awake());
        power(awake());
    }

    private boolean awake() {
        PowerManager power = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        return power.isInteractive() && display != null && display.getState() != Display.STATE_OFF;
    }

    private void power(boolean awake) {
        if (closed) return;
        session.power(awake, now());
        if (!awake) {
            cancelPending();
            return;
        }
        schedule(750);
    }

    void key(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return;
        int code = event.getKeyCode();
        // These can be the wake trigger itself, not subsequent navigation.
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_WAKEUP
                || code == KeyEvent.KEYCODE_SLEEP) return;
        session.userAction("manual key " + code);
        cancelPending();
    }

    void event(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Conservative: do not fight touch, external controls or app navigation.
            cancel("interaction event " + type);
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
            String cls = event.getClassName() == null ? "" : event.getClassName().toString();
            if (!pkg.equals(foregroundPackage)) foregroundClass = "";
            foregroundPackage = pkg;
            if (!cls.startsWith("android.")) foregroundClass = cls;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            schedule(100);
        }
    }

    void cancel(String reason) {
        session.cancel(reason);
        cancelPending();
    }

    private void schedule(long delay) {
        if (closed || !session.active() || session.state.sleeping) return;
        delay = Math.max(delay, 250 - (now() - lastCheckAt));
        long at = now() + delay;
        if (pending != null && scheduledAt <= at) return;
        cancelPending();
        final long serial = session.state.serial;
        final String boot = session.state.boot;
        scheduledAt = at;
        pending = () -> {
            pending = null;
            scheduledAt = Long.MAX_VALUE;
            if (!closed && session.active() && session.state.serial == serial
                    && session.state.boot.equals(boot)) tick();
        };
        handler.postDelayed(pending, delay);
    }

    private void tick() {
        lastCheckAt = now();
        if (!awake()) {
            power(false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 24
                && !((UserManager) service.getSystemService(Context.USER_SERVICE)).isUserUnlocked()) {
            schedule(5_000);
            return;
        }
        Prefs prefs = new Prefs(service);
        session.configure(prefs.getLaunchOnBoot(), prefs.getTargetPackage());
        AutostartSession.Surface surface = surface(session.state.target);
        long time = now();
        String observation = "surface=" + surface + " package=" + observedPackage;
        if (!observation.equals(lastObservation) || time - lastObservationAt >= 60_000) {
            log("session=" + session.state.serial + " " + observation
                    + " elapsedMs=" + (time - session.state.startedAt));
            lastObservation = observation;
            lastObservationAt = time;
        }
        AutostartSession.Action action = session.check(time, surface);
        if (action == AutostartSession.Action.LAUNCH) launch(session.state.target);
        if (session.active()) schedule(session.nextCheckDelay(time));
    }

    private String observedPackage = "";

    private AutostartSession.Surface surface(String target) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        observedPackage = "";
        if (root == null) return AutostartSession.Surface.UNKNOWN;
        try {
            CharSequence pkg = root.getPackageName();
            observedPackage = pkg == null ? "" : pkg.toString();
            // Check the current focused application, not a stale event from Plex.
            boolean focusedApplication = false;
            List<AccessibilityWindowInfo> windows = service.getWindows();
            for (AccessibilityWindowInfo window : windows) {
                try {
                    if (window.isFocused() && window.getId() == root.getWindowId()
                            && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        focusedApplication = true;
                    }
                } finally { window.recycle(); }
            }
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            boolean home = false;
            boolean knownLauncherActivity = false;
            if (observedPackage.equals("com.amazon.tv.launcher")
                    || observedPackage.equals("com.amazon.tahoe")) {
                home = hasHomeMarker(root, 0, new int[]{120}) || (foregroundPackage.equals(observedPackage)
                        && (foregroundClass.equals("com.amazon.tv.launcher.ui.HomeActivity_vNext")
                            || foregroundClass.equals("com.amazon.tahoe.ftv.HomeActivity")));
                knownLauncherActivity = foregroundPackage.equals(observedPackage) && !foregroundClass.isEmpty();
            }
            return ForegroundPolicy.classify(target, observedPackage, root.isVisibleToUser() && !bounds.isEmpty(),
                    focusedApplication, home, knownLauncherActivity);
        } finally { root.recycle(); }
    }

    private static boolean hasHomeMarker(AccessibilityNodeInfo node, int depth, int[] budget) {
        if (depth > 14 || budget[0]-- <= 0) return false;
        String id = node.getViewIdResourceName();
        if (ForegroundPolicy.isHomeViewId(id)) return true;
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (hasHomeMarker(child, depth + 1, budget)) return true;
            } finally { child.recycle(); }
        }
        return false;
    }

    private void launch(String target) {
        Intent intent = service.getPackageManager().getLeanbackLaunchIntentForPackage(target);
        if (intent == null) intent = service.getPackageManager().getLaunchIntentForPackage(target);
        if (intent == null) {
            log("launch unavailable; retry remains throttled target=" + target);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            service.startActivity(intent);
            log("launch command accepted; awaiting foreground confirmation");
        } catch (RuntimeException exception) {
            log("launch failed: " + exception.getClass().getSimpleName());
        }
    }

    private void cancelPending() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
        scheduledAt = Long.MAX_VALUE;
    }

    String summary() {
        AutostartSession.State s = session.state;
        return "session " + s.serial + " / " + s.status + " / " + s.reason
                + " / attempts " + s.attempts + " / " + (s.sleeping ? "asleep" : "awake")
                + (s.bootSession && !s.bootReady && session.active() ? " / waiting for Home" : "");
    }

    void close() {
        if (closed) return;
        closed = true;
        cancelPending();
        service.unregisterReceiver(receiver);
        displays.unregisterDisplayListener(displayListener);
    }

    private void log(String message) { AutostartDiagnostics.log(service, message); }
    private static long now() { return SystemClock.elapsedRealtime(); }
}
