package ru.focusguard.core;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

/** Read-only offers. Only an explicit, still-current confirmation may start a session. */
public final class SessionGate {
    public enum Status { FREE, AVAILABLE, ACTIVE, BLOCKED }
    public record Access(String key, Status status, long slot, long remainingMillis, LocalDateTime nextWindow) {}
    public static Access hourly(String key, int start, int end, int minutes, LocalDateTime now,
                                long elapsed, int boot, SessionPolicy.State old) {
        SessionPolicy.Result result = SessionPolicy.evaluate(start, end, minutes, now, elapsed, boot, old, false);
        long slot = now.toLocalDate().toEpochDay() * 24 + now.getHour();
        LocalDateTime boundary = now.withMinute(0).withSecond(0).withNano(0).plusHours(1);
        if (start != end) {
            LocalDateTime endAt = now.toLocalDate().atStartOfDay().plusMinutes(end);
            if (!endAt.isAfter(now)) endAt = endAt.plusDays(1);
            if (endAt.isBefore(boundary)) boundary = endAt;
        }
        Status status = switch (result.status()) {
            case OUTSIDE -> Status.FREE;
            case AVAILABLE -> Status.AVAILABLE;
            case ACTIVE -> Status.ACTIVE;
            case EXHAUSTED -> Status.BLOCKED;
        };
        return new Access(key, status, slot,
            Math.min(result.remainingMillis(), Duration.between(now, boundary).toMillis()), boundary);
    }
    public static Access daily(LocalDateTime now, long elapsed, int boot, DailySessionPolicy.State old) {
        DailySessionPolicy.Result result = DailySessionPolicy.evaluate(now, elapsed, boot, old, false);
        Status status = switch (result.status()) {
            case AVAILABLE -> Status.AVAILABLE; case ACTIVE -> Status.ACTIVE; case EXHAUSTED -> Status.BLOCKED;
        };
        return new Access("youtube", status, now.toLocalDate().toEpochDay(), result.remainingMillis(), now.toLocalDate().plusDays(1).atStartOfDay());
    }
    public static Access weekly(LocalDateTime now, long elapsed, int boot, WeeklySessionPolicy.State old) {
        WeeklySessionPolicy.Result result = WeeklySessionPolicy.evaluate(now, elapsed, boot, old, false);
        Status status = switch (result.status()) {
            case AVAILABLE -> Status.AVAILABLE; case ACTIVE -> Status.ACTIVE;
            case EXHAUSTED, DAY_BLOCKED -> Status.BLOCKED;
        };
        long slot = now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1L).toEpochDay();
        LocalDateTime next = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).atStartOfDay();
        return new Access("langame", status, slot, result.remainingMillis(), next);
    }
    public static boolean canConfirm(Access offered, Access current) {
        return offered != null && current != null && offered.status() == Status.AVAILABLE
            && current.status() == Status.AVAILABLE && offered.key().equals(current.key()) && offered.slot() == current.slot();
    }
}
