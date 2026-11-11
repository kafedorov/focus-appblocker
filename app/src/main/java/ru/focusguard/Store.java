package ru.focusguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import ru.focusguard.core.SessionPolicy;
import ru.focusguard.core.WeeklySessionPolicy;
import ru.focusguard.core.DailySessionPolicy;
import ru.focusguard.core.SessionGate;
import ru.focusguard.core.BudgetPolicy;
import ru.focusguard.core.MorningPolicy;

public final class Store {
    public static final String DEFAULT_RULES = "# Точный домен и поддомены задаются отдельно\npornhub.com\n*.pornhub.com\nxvideos.com\n*.xvideos.com\nxnxx.com\n*.xnxx.com\nxhamster.com\n*.xhamster.com\n";
    final SharedPreferences prefs;
    private final int bootCount;
    private final Set<String> failedSessionSlots = new HashSet<>();
    public Store(Context context) {
        prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE);
        bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        // An update cancels old waiting requests while preserving the actual lock state.
        if (prefs.contains("unlock_start") || prefs.contains("unlock_boot")) {
            prefs.edit().remove("unlock_start").remove("unlock_boot").apply();
        }
    }
    public boolean bool(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }
    public void setBool(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    public String text(String key, String fallback) { return prefs.getString(key, fallback); }
    public void setText(String key, String value) { prefs.edit().putString(key, value).apply(); }
    public int number(String key, int fallback) { return prefs.getInt(key, fallback); }
    public void setNumber(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    public Set<String> set(String key) { return new HashSet<>(prefs.getStringSet(key, Collections.emptySet())); }
    public void saveSet(String key, Set<String> values) { prefs.edit().putStringSet(key, new HashSet<>(values)).commit(); }
    public int boot() { return bootCount; }
    private SessionPolicy.State hourlyState(String group) {
        String prefix = "session_" + group;
        return !prefs.contains(prefix + "_hour") ? null : new SessionPolicy.State(
            prefs.getLong(prefix + "_hour", 0), prefs.getLong(prefix + "_start", 0), prefs.getInt(prefix + "_boot", 0));
    }
    private WeeklySessionPolicy.State weeklyState() {
        return !prefs.contains("weekly_langame_week") ? null : new WeeklySessionPolicy.State(
            prefs.getLong("weekly_langame_week", 0), prefs.getLong("weekly_langame_start", 0), prefs.getInt("weekly_langame_boot", 0));
    }
    private DailySessionPolicy.State dailyState() {
        return !prefs.contains("daily_youtube_day") ? null : new DailySessionPolicy.State(
            prefs.getLong("daily_youtube_day", 0), prefs.getLong("daily_youtube_start", 0), prefs.getInt("daily_youtube_boot", 0));
    }
    private BudgetPolicy.State budgetState(String key, LocalDateTime now, long elapsed) {
        String prefix = "budget_" + key;
        if (prefs.contains(prefix + "_slot")) return new BudgetPolicy.State(prefs.getLong(prefix + "_slot", 0),
            prefs.getLong(prefix + "_spent", 0), prefs.getLong(prefix + "_deadline", 0), prefs.getInt(prefix + "_boot", 0));

        long slot = BudgetPolicy.slot(key, now);
        long limit = BudgetPolicy.limit(key);
        long oldSlot;
        long remaining;
        if (key.equals("youtube")) {
            DailySessionPolicy.State old = dailyState();
            if (old == null || old.day() < slot) return null;
            oldSlot = old.day();
            remaining = SessionGate.daily(now, elapsed, boot(), old).remainingMillis();
        } else {
            SessionPolicy.State old = hourlyState(key);
            if (old == null) return null;
            oldSlot = key.equals("vk") ? Math.floorDiv(old.hour(), 24) : old.hour();
            if (oldSlot < slot) return null;
            long oldLimit = number("minutes", 10) * BudgetPolicy.MINUTE;
            remaining = old.boot() == boot() && elapsed >= old.elapsedStart() && old.hour() == BudgetPolicy.slot("telegram", now)
                ? Math.max(0, Math.min(oldLimit, limit) - (elapsed - old.elapsedStart())) : 0;
        }
        // A legacy confirmation reserved the whole slot, even when there is still active time.
        return new BudgetPolicy.State(oldSlot, limit, elapsed + remaining, boot());
    }
    public SessionGate.Access access(String key) { return access(key, LocalDateTime.now(), SystemClock.elapsedRealtime()); }
    private SessionGate.Access access(String key, LocalDateTime now, long elapsed) {
        SessionGate.Access result = key.equals("langame") ? SessionGate.weekly(now, elapsed, boot(), weeklyState())
            : BudgetPolicy.access(key, now, elapsed, boot(), budgetState(key, now, elapsed));
        LocalDateTime morning = key.equals("langame") ? null : morningEnd(now, elapsed);
        if (morning != null) {
            LocalDateTime next = result.status() == SessionGate.Status.BLOCKED && result.nextWindow().isAfter(morning)
                ? result.nextWindow() : morning;
            return new SessionGate.Access(key, SessionGate.Status.BLOCKED, result.slot(), 0, next, result.budgetRemainingMillis());
        }
        return sessionSaveFailed(result)
            ? new SessionGate.Access(key, SessionGate.Status.BLOCKED, result.slot(), 0, result.nextWindow(), 0) : result;
    }
    public boolean sessionSaveFailed(SessionGate.Access access) { return failedSessionSlots.contains(access.key() + ":" + access.slot()); }
    /** Called only after rechecking the foreground and the chosen duration. */
    public boolean confirmSession(SessionGate.Access offered, long amount) {
        LocalDateTime now = LocalDateTime.now(); long elapsed = SystemClock.elapsedRealtime();
        SessionGate.Access current = access(offered.key(), now, elapsed);
        if (!SessionGate.canConfirm(offered, current)) return false;
        SharedPreferences.Editor edit = prefs.edit();
        if (offered.key().equals("langame")) {
            WeeklySessionPolicy.State state = WeeklySessionPolicy.evaluate(now, elapsed, boot(), weeklyState(), true).state();
            edit.putLong("weekly_langame_week", state.weekStart()).putLong("weekly_langame_start", state.elapsedStart()).putInt("weekly_langame_boot", state.boot());
        } else {
            BudgetPolicy.State state = BudgetPolicy.reserve(offered, current, amount < 0 ? Math.min(offered.remainingMillis(), current.remainingMillis()) : amount, elapsed, boot());
            if (state == null) return false;
            String prefix = "budget_" + offered.key();
            edit.putLong(prefix + "_slot", state.slot()).putLong(prefix + "_spent", state.spent())
                .putLong(prefix + "_deadline", state.deadline()).putInt(prefix + "_boot", state.boot());
        }
        boolean saved = edit.commit();
        if (!saved) failedSessionSlots.add(offered.key() + ":" + offered.slot());
        return saved;
    }
    public void recordMorningUnlock() {
        LocalDateTime now = LocalDateTime.now();
        long day = now.toLocalDate().toEpochDay();
        if (!bool("morning_enabled", true)
            || !MorningPolicy.firstUnlock(now, number("morning_start", 360), prefs.getLong("morning_day", Long.MIN_VALUE))) return;
        prefs.edit().putLong("morning_day", day).putLong("morning_elapsed", SystemClock.elapsedRealtime())
            .putLong("morning_wall", System.currentTimeMillis()).putInt("morning_boot", boot()).apply();
    }
    public LocalDateTime morningEnd() { return morningEnd(LocalDateTime.now(), SystemClock.elapsedRealtime()); }
    private LocalDateTime morningEnd(LocalDateTime now, long elapsed) {
        if (!bool("morning_enabled", true) || !prefs.contains("morning_day")) return null;
        long delay = number("morning_minutes", 30) * BudgetPolicy.MINUTE;
        long since = prefs.getInt("morning_boot", -1) == boot()
            ? elapsed - prefs.getLong("morning_elapsed", elapsed)
            : System.currentTimeMillis() - prefs.getLong("morning_wall", 0);
        long left = MorningPolicy.remaining(delay, since);
        return left <= 0 ? null : now.plusNanos(left * 1_000_000L);
    }
    public boolean locked() { return bool("locked", false); }
    public void lock() { prefs.edit().putBoolean("locked", true).commit(); }
    public void unlock() { prefs.edit().putBoolean("locked", false).commit(); }
}
