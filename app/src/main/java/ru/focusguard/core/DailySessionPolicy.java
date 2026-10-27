package ru.focusguard.core;

import java.time.Duration;
import java.time.LocalDateTime;

/** One continuous thirty-minute session per local day, shared by app and website. */
public final class DailySessionPolicy {
    public static final long BUDGET_MILLIS = 30 * 60_000L;
    public enum Status { AVAILABLE, ACTIVE, EXHAUSTED }
    public record State(long day, long elapsedStart, int boot) {}
    public record Result(Status status, State state, long remainingMillis) {}
    public static Result evaluate(LocalDateTime now, long elapsed, int boot, State old, boolean begin) {
        long day = now.toLocalDate().toEpochDay();
        long untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis();
        if (old != null && day < old.day()) return new Result(Status.EXHAUSTED, old, 0);
        State state = old;
        if (state == null || day > state.day()) {
            if (!begin) return new Result(Status.AVAILABLE, old, Math.min(BUDGET_MILLIS, untilMidnight));
            state = new State(day, elapsed, boot);
        }
        long remaining = state.boot() != boot || elapsed < state.elapsedStart() ? 0
            : Math.min(untilMidnight, Math.max(0, BUDGET_MILLIS - (elapsed - state.elapsedStart())));
        return new Result(remaining > 0 ? Status.ACTIVE : Status.EXHAUSTED, state, remaining);
    }
}
