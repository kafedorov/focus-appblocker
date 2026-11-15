package ru.focusguard.core;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Steam Mobile is closed Friday through Sunday, using the device's local calendar. */
public final class SteamPolicy {
    public static final String PACKAGE = "com.valvesoftware.android.steam.community";
    public static final String SETTING = "steam_weekend";
    public record Result(boolean blocked, LocalDateTime nextChange) {}

    public static Result evaluate(String pkg, boolean enabled, LocalDateTime now) {
        if (!enabled || !PACKAGE.equals(pkg)) return null;
        LocalDate date = now.toLocalDate();
        int day = date.getDayOfWeek().getValue();
        boolean blocked = day >= 5;
        return new Result(blocked, date.plusDays(blocked ? 8 - day : 5 - day).atStartOfDay());
    }
}
