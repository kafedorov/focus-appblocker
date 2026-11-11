package ru.focusguard.core;

import java.time.LocalDateTime;

public final class MorningPolicy {
    public static boolean firstUnlock(LocalDateTime now, int startMinute, long recordedDay) {
        return now.toLocalDate().toEpochDay() > recordedDay && now.getHour() * 60 + now.getMinute() >= startMinute;
    }
    public static long remaining(long duration, long sinceUnlock) {
        return Math.max(0, duration - Math.max(0, sinceUnlock));
    }
}
