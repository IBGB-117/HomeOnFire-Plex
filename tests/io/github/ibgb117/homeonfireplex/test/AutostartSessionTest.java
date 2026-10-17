// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.ibgb117.homeonfireplex.test;

import java.lang.reflect.Field;
import java.util.Random;
import static io.github.ibgb117.homeonfireplex.test.AutostartSession.Action.*;
import static io.github.ibgb117.homeonfireplex.test.AutostartSession.Status.*;
import static io.github.ibgb117.homeonfireplex.test.AutostartSession.Surface.*;

/** Deterministic tests with fake monotonic time and durable-state/process replacement. */
public final class AutostartSessionTest {
    private static int tests;
    private static final String PLEX = "com.plexapp.android";
    private static final class Rig {
        AutostartSession session;
        AutostartSession.State saved;
        int writes;
        Rig() { attach(new AutostartSession.State()); }
        void attach(AutostartSession.State state) {
            session = new AutostartSession(state, s -> { saved = copy(s); writes++; }, text -> {});
        }
        void restart() { attach(copy(saved)); }
        void boot(boolean awake) {
            session.boot("boot:1", true, 0, awake);
            session.configure(true, PLEX);
        }
        void success(long time) {
            eq(WAIT, session.check(time, TARGET));
            eq(COMPLETE, session.check(time + AutostartSession.CONFIRM_MS, TARGET));
        }
    }
    private static AutostartSession.State copy(AutostartSession.State source) {
        try {
            AutostartSession.State dest = new AutostartSession.State();
            for (Field f : AutostartSession.State.class.getDeclaredFields()) f.set(dest, f.get(source));
            return dest;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void yes(boolean value) { if (!value) throw new AssertionError("Expected true"); }
    private static void test(String name, Runnable body) {
        body.run(); tests++; System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        test("first enable is not a boot", () -> {
            Rig r = new Rig(); r.session.boot("boot:1", false, 10, true);
            eq(IDLE, r.session.state.status); eq(NONE, r.session.check(1000, HOME));
        });
        test("late first real boot broadcast starts once", () -> {
            Rig r = new Rig(); r.session.boot("boot:1", false, 0, true);
            r.session.boot("boot:1", true, 50, true); r.session.boot("boot:1", true, 80, true);
            eq(1L, r.session.state.serial); eq(ACTIVE, r.session.state.status);
        });
        test("duplicate boot and screen-on coalesce", () -> {
            Rig r = new Rig(); r.boot(false); r.session.boot("boot:1", true, 30, false);
            r.session.power(true, 100); r.session.power(true, 200);
            eq(1L, r.session.state.serial); eq(ACTIVE, r.session.state.status);
        });
        test("new boot inferred on reconnect but not repeated", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000); r.restart();
            r.session.boot("boot:2", false, 0, true);
            r.session.boot("boot:2", true, 100, true);
            eq(2L, r.session.state.serial); eq(ACTIVE, r.session.state.status);
        });
        test("wake requires observed sleep", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000);
            for (int i = 0; i < 20; i++) r.session.power(true, 5000 + i);
            eq(1L, r.session.state.serial); eq(SUCCEEDED, r.session.state.status);
            r.session.power(false, 6000); r.session.power(false, 7000);
            r.session.power(true, 8000); r.session.power(true, 9000);
            eq(2L, r.session.state.serial);
        });
        test("active old wake is replaced by next real wake", () -> {
            Rig r = new Rig(); r.boot(true); r.session.power(false, 2000);
            r.session.power(true, 3000); eq(2L, r.session.state.serial);
            eq(0, r.session.state.attempts); eq(3750L, r.session.state.nextAttemptAt);
        });
        test("standby survives process replacement", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000);
            r.session.power(false, 3000); r.restart(); r.session.boot("boot:1", false, 5000, true);
            r.session.power(true, 5000); eq(2L, r.session.state.serial);
        });
        test("no launch while asleep", () -> {
            Rig r = new Rig(); r.boot(false);
            eq(NONE, r.session.check(300000, HOME)); eq(0, r.session.state.attempts);
        });
        test("foreground dwell required, command alone is not success", () -> {
            Rig r = new Rig(); r.boot(true); eq(LAUNCH, r.session.check(750, HOME));
            eq(ACTIVE, r.session.state.status); eq(WAIT, r.session.check(800, TARGET));
            eq(WAIT, r.session.check(1799, TARGET)); eq(COMPLETE, r.session.check(1800, TARGET));
        });
        test("success is permanent until a new lifecycle", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000);
            for (long t = 3000; t < 600000; t += 1000) {
                eq(NONE, r.session.check(t, HOME)); eq(NONE, r.session.check(t, OTHER));
                r.session.boot("boot:1", true, t, true); r.session.power(true, t);
            }
            eq(SUCCEEDED, r.session.state.status); eq(1L, r.session.state.serial);
        });
        test("completed state persists across service and process restarts", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000); r.restart();
            r.session.boot("boot:1", false, 4000, true);
            eq(NONE, r.session.check(800000, HOME)); eq(SUCCEEDED, r.session.state.status);
        });
        test("unknown window interrupts proof", () -> {
            Rig r = new Rig(); r.boot(true); r.session.check(1000, TARGET);
            r.session.check(1500, UNKNOWN); eq(WAIT, r.session.check(2000, TARGET));
            eq(COMPLETE, r.session.check(3000, TARGET));
        });
        test("foreground candidate is not trusted after reconnect", () -> {
            Rig r = new Rig(); r.boot(true); r.session.check(1000, TARGET); r.restart();
            eq(WAIT, r.session.check(2000, TARGET)); eq(COMPLETE, r.session.check(3000, TARGET));
        });
        test("Home after target candidate respects user escape", () -> {
            Rig r = new Rig(); r.boot(true); r.session.check(1000, TARGET);
            eq(NONE, r.session.check(1100, HOME)); eq(CANCELLED, r.session.state.status);
        });
        test("other app and Settings cancel rather than get displaced", () -> {
            Rig r = new Rig(); r.boot(true); eq(NONE, r.session.check(800, OTHER));
            eq(NONE, r.session.check(300000, HOME)); r.restart();
            eq(CANCELLED, r.session.state.status);
        });
        test("manual key cancels before queued launch", () -> {
            Rig r = new Rig(); r.boot(true); r.session.userAction("D-pad");
            eq(NONE, r.session.check(750, HOME)); eq(CANCELLED, r.session.state.status);
        });
        test("manual wake key before screen-on is respected", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000); r.session.power(false, 3000);
            r.session.userAction("D-pad wakes device"); r.restart(); r.session.power(true, 4000);
            r.session.configure(true, PLEX); eq(CANCELLED, r.session.state.status);
            eq(NONE, r.session.check(5000, HOME));
        });
        test("manual input during locked boot does not rearm first screen-on", () -> {
            Rig r = new Rig(); r.boot(false); r.session.userAction("Back");
            r.session.power(true, 1000); eq(1L, r.session.state.serial);
            eq(CANCELLED, r.session.state.status);
        });
        test("manual wake cancellation is atomic in every persisted snapshot", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000); r.session.power(false, 3000);
            r.session.userAction("navigation wakes device");
            AutostartSession guarded = new AutostartSession(copy(r.saved), snapshot -> {
                yes(snapshot.status != ACTIVE);
            }, message -> {});
            guarded.power(true, 4000);
            eq(CANCELLED, guarded.state.status);
        });
        test("failed launch commands remain exponentially throttled", () -> {
            Rig r = new Rig(); r.boot(true); eq(LAUNCH, r.session.check(750, HOME));
            for (long t = 751; t < 1750; t++) eq(WAIT, r.session.check(t, HOME));
            eq(LAUNCH, r.session.check(1750, HOME));
            eq(3750L, r.session.state.nextAttemptAt); r.restart();
            eq(WAIT, r.session.check(2000, HOME)); eq(LAUNCH, r.session.check(3750, HOME));
            eq(7750L, r.session.state.nextAttemptAt);
        });
        test("session retries beyond five minutes and has no fixed lifetime", () -> {
            Rig r = new Rig(); r.boot(true);
            long[] times = {750, 91000, 301000, 3600000, 86400000, 604800000};
            for (long time : times) eq(LAUNCH, r.session.check(time, HOME));
            eq(ACTIVE, r.session.state.status); r.success(604801000);
        });
        test("interval cap avoids busy loops without ending session", () -> {
            long previous = 0;
            for (int i = 1; i < 200; i++) {
                long value = AutostartSession.interval(i);
                yes(value >= previous && value <= 60000); previous = value;
            }
            eq(60000L, previous);
            Rig r = new Rig(); r.boot(true);
            for (int i = 0; i < 1000; i++) r.session.nextCheckDelay(100000);
            eq(60000L, r.session.nextCheckDelay(100000)); eq(ACTIVE, r.session.state.status);
        });
        test("disable and target change cannot start another session", () -> {
            Rig r = new Rig(); r.boot(true); r.session.configure(false, PLEX);
            r.session.configure(true, PLEX); eq(CANCELLED, r.session.state.status);
            Rig changed = new Rig(); changed.boot(true); changed.session.configure(true, "another.app");
            eq(CANCELLED, changed.session.state.status);
        });
        test("empty target fails closed", () -> {
            Rig r = new Rig(); r.session.boot("boot:1", true, 0, true);
            r.session.configure(true, ""); eq(NONE, r.session.check(1000, HOME));
        });
        test("late boot after a completed first wake is not another session", () -> {
            Rig r = new Rig(); r.session.boot("boot:1", false, 0, false);
            r.session.power(true, 1000); r.session.configure(true, PLEX); r.success(2000);
            r.session.boot("boot:1", true, 5000, true);
            eq(1L, r.session.state.serial); eq(SUCCEEDED, r.session.state.status);
        });
        test("cooldown is persisted before returning launch permission", () -> {
            Rig r = new Rig(); r.boot(true); r.session.check(750, HOME);
            eq(1, r.saved.attempts); eq(1750L, r.saved.nextAttemptAt);
        });
        test("storage failure never returns launch permission", () -> {
            Rig r = new Rig(); r.boot(true);
            AutostartSession broken = new AutostartSession(copy(r.saved), s -> {
                throw new IllegalStateException("disk unavailable");
            }, text -> {});
            boolean refused = false;
            try { broken.check(750, HOME); } catch (IllegalStateException expected) { refused = true; }
            yes(refused);
        });
        test("foreground policy requires current visible focused target", () -> {
            eq(TARGET, ForegroundPolicy.classify(PLEX, PLEX, true, true, false, false));
            eq(UNKNOWN, ForegroundPolicy.classify(PLEX, PLEX, false, true, false, false));
            eq(UNKNOWN, ForegroundPolicy.classify(PLEX, PLEX, true, false, false, false));
            eq(OTHER, ForegroundPolicy.classify(PLEX, "another.app", true, true, false, false));
            eq(SYSTEM_SETTINGS, ForegroundPolicy.classify(PLEX, "com.amazon.tv.settings.v2", true, true, false, false));
            eq(UNKNOWN, ForegroundPolicy.classify(PLEX, "android", true, true, false, false));
        });
        test("Home without window events needs live Home evidence", () -> {
            String home = "com.amazon.tv.launcher";
            eq(HOME, ForegroundPolicy.classify(PLEX, home, true, true, true, false));
            eq(UNKNOWN, ForegroundPolicy.classify(PLEX, home, true, true, false, false));
            eq(OTHER, ForegroundPolicy.classify(PLEX, home, true, true, false, true));
            eq(UNKNOWN, ForegroundPolicy.classify(PLEX, home, true, false, true, false));
            yes(ForegroundPolicy.isHomeViewId(home + ":id/nav-tab-movies_and_tv_home_tab"));
            yes(!ForegroundPolicy.isHomeViewId(home + ":id/nav-tab-apps_for_you_tab"));
        });
        test("observed Fire OS boot Settings waits instead of cancelling session", () -> {
            Rig r = new Rig();
            r.session.boot("boot:12", false, 27782043, true);
            r.session.boot("boot:13", false, 21815, true);
            r.session.configure(true, PLEX);
            eq(WAIT, r.session.check(23366, SYSTEM_SETTINGS));
            eq(ACTIVE, r.session.state.status); eq(0, r.session.state.attempts);
            r.session.boot("boot:13", true, 69137, true);
            eq(WAIT, r.session.check(69140, SYSTEM_SETTINGS));
            eq(1L, r.session.state.serial);
            eq(LAUNCH, r.session.check(70000, HOME));
            r.success(71000);
            eq(NONE, r.session.check(73000, SYSTEM_SETTINGS));
            eq(NONE, r.session.check(74000, HOME));
        });
        test("boot Settings wait has no expiry and never launches over Settings", () -> {
            Rig r = new Rig(); r.boot(true);
            for (long t : new long[]{1000, 91000, 301000, 86400000}) {
                eq(WAIT, r.session.check(t, SYSTEM_SETTINGS));
                r.session.nextCheckDelay(t);
            }
            eq(ACTIVE, r.session.state.status); eq(0, r.session.state.attempts);
            r.restart(); eq(WAIT, r.session.check(86401000, SYSTEM_SETTINGS));
            eq(LAUNCH, r.session.check(86402000, HOME));
        });
        test("manual navigation while boot Settings is shown always cancels", () -> {
            Rig r = new Rig(); r.boot(true); r.session.check(1000, SYSTEM_SETTINGS);
            r.session.userAction("manual Back"); r.restart();
            eq(NONE, r.session.check(2000, HOME)); eq(CANCELLED, r.session.state.status);
            r.session.boot("boot:1", true, 3000, true);
            eq(NONE, r.session.check(4000, HOME));
        });
        test("Settings restored on wake retains foreground priority", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000);
            r.session.power(false, 3000); r.session.power(true, 4000);
            r.session.configure(true, PLEX);
            eq(NONE, r.session.check(5000, SYSTEM_SETTINGS));
            eq(CANCELLED, r.session.state.status); eq(0, r.session.state.attempts);
        });
        test("first safe Home ends boot exemption even before first launch", () -> {
            Rig r = new Rig(); r.boot(true);
            eq(WAIT, r.session.check(100, HOME));
            yes(r.saved.homeObserved); eq(0, r.saved.attempts);
            r.restart();
            eq(NONE, r.session.check(200, SYSTEM_SETTINGS));
            eq(CANCELLED, r.session.state.status);
        });
        test("Settings after an existing target candidate is never boot preparation", () -> {
            Rig r = new Rig(); r.boot(true); r.session.check(1000, TARGET);
            eq(NONE, r.session.check(1100, SYSTEM_SETTINGS));
            eq(CANCELLED, r.session.state.status);
        });
        test("boot Settings exception never covers Amazon Shop or other apps", () -> {
            eq(OTHER, ForegroundPolicy.classify(PLEX, "com.amazon.shoptv.client", true, true, false, false));
            eq(OTHER, ForegroundPolicy.classify(PLEX, "com.amazon.tv.settings.v2.fake", true, true, false, false));
            eq(OTHER, ForegroundPolicy.classify(PLEX, "io.github.ibgb117.homeonfireplex.test", true, true, false, false));
            Rig r = new Rig(); r.boot(true); r.session.check(1000, SYSTEM_SETTINGS);
            eq(NONE, r.session.check(2000, OTHER)); eq(CANCELLED, r.session.state.status);
        });
        test("recorded successful wake remains a single prompt launch", () -> {
            Rig r = new Rig(); r.boot(true); r.success(1000);
            r.session.power(false, 721577); r.session.power(true, 726849);
            r.session.configure(true, PLEX);
            eq(WAIT, r.session.check(727392, HOME));
            eq(LAUNCH, r.session.check(727668, HOME));
            eq(WAIT, r.session.check(728161, TARGET));
            eq(COMPLETE, r.session.check(729191, TARGET));
            eq(1, r.session.state.attempts);
            eq(NONE, r.session.check(800000, HOME));
            eq(NONE, r.session.check(900000, SYSTEM_SETTINGS));
        });
        test("generated duplicate event sequences never rearm terminal state", () -> {
            Random random = new Random(117);
            for (int run = 0; run < 100; run++) {
                Rig r = new Rig(); r.boot(true);
                if (random.nextBoolean()) r.success(1000); else r.session.userAction("manual");
                AutostartSession.Status terminal = r.session.state.status;
                for (int i = 0; i < 100; i++) {
                    long time = 3000L + i * 1000;
                    switch (random.nextInt(5)) {
                        case 0: r.session.boot("boot:1", true, time, true); break;
                        case 1: r.session.power(true, time); break;
                        case 2: r.restart(); break;
                        case 3: r.session.configure(true, PLEX); break;
                        default: eq(NONE, r.session.check(time, HOME));
                    }
                    eq(terminal, r.session.state.status); eq(1L, r.session.state.serial);
                }
            }
        });
        System.out.println("Executed " + tests + " tests; all passed (including 10,000 generated events)");
    }
}
