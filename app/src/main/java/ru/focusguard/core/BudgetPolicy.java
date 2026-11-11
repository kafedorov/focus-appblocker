package ru.focusguard.core;

import java.time.Duration;
import java.time.LocalDateTime;

/** Reserves the chosen time once; leaving the app never pauses or refunds it. */
public final class BudgetPolicy {
    public static final long MINUTE = 60_000;
    public record State(long slot, long spent, long deadline, int boot) {}

    public static long limit(String key) {
        return switch (key) {
            case "vk" -> 5 * MINUTE;
            case "telegram" -> 10 * MINUTE;
            case "youtube" -> 30 * MINUTE;
            default -> throw new IllegalArgumentException("Unknown budget: " + key);
        };
    }
    public static long slot(String key, LocalDateTime now) {
        long day = now.toLocalDate().toEpochDay();
        return key.equals("telegram") ? day * 24 + now.getHour() : day;
    }
    public static SessionGate.Access access(String key, LocalDateTime now, long elapsed, int boot, State state) {
        long slot = slot(key, now);
        int opening = key.equals("vk") ? 10 : key.equals("youtube") ? 8 : 0;
        var day = now.toLocalDate();
        var opens = day.atTime(opening, 0);
        var end = switch (key) {
            case "vk" -> day.atTime(20, 0);
            case "telegram" -> now.withMinute(0).withSecond(0).withNano(0).plusHours(1);
            default -> day.plusDays(1).atStartOfDay();
        };
        var next = key.equals("telegram") ? end : day.plusDays(1).atTime(opening, 0);
        if (state != null && slot < state.slot()) return blocked(key, slot, next);
        if (now.isBefore(opens)) return blocked(key, slot, opens);
        if (!now.isBefore(end)) return blocked(key, slot, next);
        long budget = limit(key);
        long available = budget;
        long window = Duration.between(now, end).toMillis();
        if (state != null && state.slot() == slot) {
            available = Math.max(0, budget - state.spent());
            long active = state.boot() == boot ? Math.min(window, state.deadline() - elapsed) : 0;
            if (active > 0) return new SessionGate.Access(key, SessionGate.Status.ACTIVE, slot, active, next, available);
        }
        return available == 0 || window <= 0 ? blocked(key, slot, next)
            : new SessionGate.Access(key, SessionGate.Status.AVAILABLE, slot, Math.min(available, window), next, available);
    }
    private static SessionGate.Access blocked(String key, long slot, LocalDateTime next) {
        return new SessionGate.Access(key, SessionGate.Status.BLOCKED, slot, 0, next, 0);
    }
    public static State reserve(SessionGate.Access offered, SessionGate.Access current, long amount, long elapsed, int boot) {
        if (!SessionGate.canConfirm(offered, current) || amount <= 0 || amount > current.remainingMillis()) return null;
        return new State(current.slot(), limit(current.key()) - current.budgetRemainingMillis() + amount, elapsed + amount, boot);
    }
}
