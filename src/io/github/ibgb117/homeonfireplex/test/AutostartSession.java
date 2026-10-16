// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.ibgb117.homeonfireplex.test;

/** Android-independent state machine. All times are monotonic elapsed milliseconds.
 * A terminal session can only be replaced by a new boot or an observed sleep/wake.
 */
final class AutostartSession {
    enum Status { IDLE, ACTIVE, SUCCEEDED, CANCELLED }
    enum Surface { TARGET, HOME, UNKNOWN, OTHER }
    enum Action { NONE, WAIT, LAUNCH, COMPLETE }
    static final long CONFIRM_MS = 1_000;
    static final long MAX_INTERVAL_MS = 60_000;

    static final class State {
        String boot = "";
        long serial, startedAt, nextAttemptAt;
        int attempts, checks;
        boolean sleeping, bootAwaitingWake, bootNotified, manualWhileAsleep;
        Status status = Status.IDLE;
        String reason = "baseline";
        String target = "";
    }

    interface Persistence { void save(State state); }
    interface Events { void log(String message); }
    final State state;
    private final Persistence persistence;
    private final Events events;
    // Never trust a foreground candidate across a process/service reconnection.
    private long targetSince = -1;

    AutostartSession(State state, Persistence persistence, Events events) {
        this.state = state;
        this.persistence = persistence;
        this.events = events;
    }

    void boot(String identity, boolean explicitBroadcast, long now, boolean awake) {
        if (identity.equals(state.boot)) {
            if (explicitBroadcast && !state.bootNotified) {
                state.bootNotified = true;
                if (state.status == Status.IDLE) {
                    begin("boot", now, !awake, false);
                }
                persist("boot broadcast coalesced");
            }
            return;
        }
        boolean previousBootKnown = !state.boot.isEmpty();
        state.boot = identity;
        state.manualWhileAsleep = false;
        state.bootNotified = explicitBroadcast;
        state.sleeping = !awake;
        if (explicitBroadcast || previousBootKnown) {
            begin("boot", now, !awake, false);
        } else {
            // Installing/enabling the service while already awake is not a boot.
            state.status = Status.IDLE;
            state.reason = "baseline";
            state.bootAwaitingWake = false;
        }
        persist("boot identity observed");
    }

    void power(boolean awake, long now) {
        if (!awake) {
            if (!state.sleeping) {
                state.sleeping = true;
                targetSince = -1;
                persist("sleep observed");
            }
            return;
        }
        if (!state.sleeping) return;
        state.sleeping = false;
        boolean manual = state.manualWhileAsleep;
        state.manualWhileAsleep = false;
        if (state.bootAwaitingWake) {
            state.bootAwaitingWake = false;
            if (manual && active()) {
                state.status = Status.CANCELLED;
                state.reason = "manual input during wake transition";
            }
            persist("first boot screen-on coalesced");
        } else {
            begin("wake", now, false, manual);
        }
    }

    private void begin(String source, long now, boolean awaitingWake, boolean manual) {
        state.serial++;
        state.status = manual ? Status.CANCELLED : Status.ACTIVE;
        state.startedAt = now;
        state.nextAttemptAt = now + 750; // Let the wake window settle, without a deadline.
        state.attempts = 0;
        state.checks = 0;
        state.target = "";
        state.reason = manual ? "manual input during wake transition" : source;
        state.bootAwaitingWake = awaitingWake;
        targetSince = -1;
        persist("session started: " + source);
    }

    boolean active() { return state.status == Status.ACTIVE; }

    void cancel(String reason) {
        if (!active()) return;
        state.status = Status.CANCELLED;
        state.reason = reason;
        targetSince = -1;
        persist("session cancelled: " + reason);
    }

    void userAction(String reason) {
        if (state.sleeping) {
            state.manualWhileAsleep = true;
            persist("manual input before screen-on");
        }
        cancel(reason);
    }

    /** A target is fixed for this session; changing settings never starts another one. */
    void configure(boolean enabled, String target) {
        if (!active()) return;
        if (!enabled || target == null || target.isEmpty()) {
            cancel("disabled or no target");
        } else if (!state.target.isEmpty() && !state.target.equals(target)) {
            cancel("target changed by user");
        } else if (state.target.isEmpty()) {
            state.target = target;
            persist("target configured: " + target);
        }
    }

    Action check(long now, Surface surface) {
        if (!active() || state.sleeping) return Action.NONE;
        if (surface == Surface.OTHER) {
            cancel("user/app owns foreground");
            return Action.NONE;
        }
        if (surface == Surface.TARGET) {
            if (targetSince < 0) targetSince = now;
            if (now - targetSince >= CONFIRM_MS) {
                state.status = Status.SUCCEEDED;
                state.reason = "target foreground confirmed";
                state.bootAwaitingWake = false;
                persist("session succeeded");
                return Action.COMPLETE;
            }
            return Action.WAIT;
        }
        // Leaving a candidate for Home can be a Home press we cannot observe directly.
        if (targetSince >= 0 && surface == Surface.HOME) {
            cancel("left target for Home before confirmation");
            return Action.NONE;
        }
        targetSince = -1;
        if (surface == Surface.HOME && !state.target.isEmpty() && now >= state.nextAttemptAt) {
            // Commit the cooldown BEFORE dispatch. A failed command or process death
            // must not create an unthrottled launch loop.
            if (state.attempts < Integer.MAX_VALUE) state.attempts++;
            state.nextAttemptAt = now + interval(state.attempts);
            persist("launch requested attempt=" + state.attempts);
            return Action.LAUNCH;
        }
        return Action.WAIT;
    }

    long nextCheckDelay(long now) {
        if (targetSince >= 0) return Math.max(100, CONFIRM_MS - (now - targetSince));
        if (state.checks < Integer.MAX_VALUE) state.checks++;
        long delay = interval(state.checks);
        if (state.nextAttemptAt > now) delay = Math.min(delay, state.nextAttemptAt - now);
        return Math.max(250, delay);
    }

    static long interval(int count) {
        // 1s, 2s, 4s, 8s, 16s, 32s, then 60s indefinitely.
        return Math.min(MAX_INTERVAL_MS, 1_000L << Math.min(Math.max(count - 1, 0), 6));
    }

    private void persist(String message) {
        persistence.save(state);
        events.log("session=" + state.boot + "/" + state.serial + " state=" + state.status
                + " " + message);
    }
}
