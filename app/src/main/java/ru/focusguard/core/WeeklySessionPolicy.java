package ru.focusguard.core;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One continuous ten-minute session, only on Saturday, using the device's local calendar. */
public final class WeeklySessionPolicy {
    public static final long BUDGET_MILLIS = 10 * 60_000L;
    public enum Status { DAY_BLOCKED, AVAILABLE, ACTIVE, EXHAUSTED }
    public record State(long weekStart, long elapsedStart, int boot) {}
    public record Result(Status status, State state, long remainingMillis) {}

    public static Result evaluate(LocalDateTime now, long elapsed, int boot, State old, boolean begin) {
        if (now.getDayOfWeek() != DayOfWeek.SATURDAY) return new Result(Status.DAY_BLOCKED, old, 0);
        LocalDate date = now.toLocalDate();
        long week = date.minusDays(date.getDayOfWeek().getValue() - 1L).toEpochDay();
        long untilSunday = Duration.between(now, date.plusDays(1).atStartOfDay()).toMillis();
        if (old != null && week < old.weekStart()) return new Result(Status.EXHAUSTED, old, 0);
        State state = old;
        if (state == null || week > state.weekStart()) {
            if (!begin) return new Result(Status.AVAILABLE, old, Math.min(BUDGET_MILLIS, untilSunday));
            state = new State(week, elapsed, boot);
        }
        long remaining = state.boot() != boot || elapsed < state.elapsedStart() ? 0
            : Math.min(untilSunday, Math.max(0, BUDGET_MILLIS - (elapsed - state.elapsedStart())));
        return new Result(remaining > 0 ? Status.ACTIVE : Status.EXHAUSTED, state, remaining);
    }
}
