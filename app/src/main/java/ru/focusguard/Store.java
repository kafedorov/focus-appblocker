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

public final class Store {
    public static final String DEFAULT_RULES = "# Точный домен и поддомены задаются отдельно\npornhub.com\n*.pornhub.com\nxvideos.com\n*.xvideos.com\nxnxx.com\n*.xnxx.com\nxhamster.com\n*.xhamster.com\n";
    final SharedPreferences prefs;
    private final int bootCount;
    private final Set<String> failedSessionSlots = new HashSet<>();
    public Store(Context context) { prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE); bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0); }
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
    public SessionGate.Access access(String key) { return access(key, LocalDateTime.now(), SystemClock.elapsedRealtime()); }
    private SessionGate.Access access(String key, LocalDateTime now, long elapsed) {
        SessionGate.Access result = switch (key) {
            case "vk", "telegram" -> SessionGate.hourly(key, number("start", 420), number("end", 1140), number("minutes", 10), now, elapsed, boot(), hourlyState(key));
            case "youtube" -> SessionGate.daily(now, elapsed, boot(), dailyState());
            case "langame" -> SessionGate.weekly(now, elapsed, boot(), weeklyState());
            default -> throw new IllegalArgumentException("Unknown session group");
        };
        return failedSessionSlots.contains(result.key() + ":" + result.slot())
            ? new SessionGate.Access(result.key(), SessionGate.Status.BLOCKED, result.slot(), 0, result.nextWindow()) : result;
    }
    public boolean sessionSaveFailed(SessionGate.Access access) { return failedSessionSlots.contains(access.key() + ":" + access.slot()); }
    /** Called only by the confirmation button, after the foreground rules were rechecked. */
    public boolean confirmSession(SessionGate.Access offered) {
        LocalDateTime now = LocalDateTime.now(); long elapsed = SystemClock.elapsedRealtime();
        SessionGate.Access current = access(offered.key(), now, elapsed);
        if (!SessionGate.canConfirm(offered, current)) return false;
        SharedPreferences.Editor edit = prefs.edit();
        switch (offered.key()) {
            case "vk", "telegram" -> {
                SessionPolicy.State state = SessionPolicy.evaluate(number("start", 420), number("end", 1140), number("minutes", 10), now, elapsed, boot(), hourlyState(offered.key()), true).state();
                String prefix = "session_" + offered.key();
                edit.putLong(prefix + "_hour", state.hour()).putLong(prefix + "_start", state.elapsedStart()).putInt(prefix + "_boot", state.boot());
            }
            case "youtube" -> {
                DailySessionPolicy.State state = DailySessionPolicy.evaluate(now, elapsed, boot(), dailyState(), true).state();
                edit.putLong("daily_youtube_day", state.day()).putLong("daily_youtube_start", state.elapsedStart()).putInt("daily_youtube_boot", state.boot());
            }
            case "langame" -> {
                WeeklySessionPolicy.State state = WeeklySessionPolicy.evaluate(now, elapsed, boot(), weeklyState(), true).state();
                edit.putLong("weekly_langame_week", state.weekStart()).putLong("weekly_langame_start", state.elapsedStart()).putInt("weekly_langame_boot", state.boot());
            }
            default -> { return false; }
        }
        boolean saved = edit.commit();
        if (!saved) failedSessionSlots.add(offered.key() + ":" + offered.slot());
        return saved;
    }
    public boolean locked() {
        if (!bool("locked", false)) return false;
        if (prefs.contains("unlock_start")) {
            int boot = boot();
            if (prefs.getInt("unlock_boot", -1) != boot) {
                prefs.edit().putInt("unlock_boot", boot).putLong("unlock_start", SystemClock.elapsedRealtime()).commit();
            }
            if (unlockRemaining() == 0) { setBool("locked", false); prefs.edit().remove("unlock_start").apply(); return false; }
        }
        return true;
    }
    public long unlockRemaining() { return Math.max(0, 24 * 3_600_000L - (SystemClock.elapsedRealtime() - prefs.getLong("unlock_start", SystemClock.elapsedRealtime()))); }
    public boolean unlockRequested() { return prefs.contains("unlock_start"); }
    public void requestUnlock() {
        if (!unlockRequested()) prefs.edit().putLong("unlock_start", SystemClock.elapsedRealtime()).putInt("unlock_boot", boot()).commit();
    }
    public void lock() { prefs.edit().putBoolean("locked", true).remove("unlock_start").commit(); }
    public void unlock() { prefs.edit().putBoolean("locked", false).remove("unlock_start").commit(); }
}
