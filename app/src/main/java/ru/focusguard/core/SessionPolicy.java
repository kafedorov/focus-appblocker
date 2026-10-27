package ru.focusguard.core;

import java.time.LocalDateTime;

/** One continuous session per local calendar hour, independently per messenger. */
public final class SessionPolicy {
    public enum Status { OUTSIDE, AVAILABLE, ACTIVE, EXHAUSTED }
    public record State(long hour, long elapsedStart, int boot) {}
    public record Result(Status status, State state, long remainingMillis) {}
    public static Result evaluate(int startMinute, int endMinute, int minutes, LocalDateTime now,
                                  long elapsed, int boot, State old, boolean begin) {
        int minute = now.getHour() * 60 + now.getMinute();
        boolean scheduled = startMinute == endMinute || (startMinute < endMinute
            ? minute >= startMinute && minute < endMinute : minute >= startMinute || minute < endMinute);
        if (!scheduled) return new Result(Status.OUTSIDE, old, 0);
        long hour = now.toLocalDate().toEpochDay() * 24 + now.getHour();
        State state = old;
        // A wall-clock rollback cannot buy a fresh slot. After reboot an already-used slot is spent.
        if (state == null || hour > state.hour()) {
            if (!begin) return new Result(Status.AVAILABLE, state, minutes * 60_000L);
            state = new State(hour, elapsed, boot);
        }
        long remaining = state.boot() != boot || elapsed < state.elapsedStart() ? 0
            : Math.max(0, minutes * 60_000L - (elapsed - state.elapsedStart()));
        return new Result(remaining > 0 ? Status.ACTIVE : Status.EXHAUSTED, state, remaining);
    }
}
