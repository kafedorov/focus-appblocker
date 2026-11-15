package ru.focusguard.core;

import java.util.Locale;

/** Local screen recognition only: does not claim permission to change Android's clock. */
public final class TimeSettingsGuard {
    public static final String SETTING = "time_guard";
    public static boolean automaticReady(int time, int zone) { return time == 1 && zone == 1; }
    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
    private static boolean title(String value) {
        return value.equals("дата и время") || value.equals("date & time") || value.equals("date and time")
            || value.equals("часовой пояс") || value.equals("выберите часовой пояс")
            || value.equals("time zone") || value.equals("select time zone");
    }
    public static boolean entry(CharSequence value) {
        String s = text(value);
        return title(s) || s.equals("автоопределение времени") || s.equals("автоматический часовой пояс")
            || s.equals("установить дату") || s.equals("установить время")
            || s.equals("automatic date & time") || s.equals("automatic time zone")
            || s.equals("set date") || s.equals("set time");
    }
    public static final class Scan {
        private final String pkg;
        private boolean heading, nativeScreen, autoTime, autoZone, manualDate, manualTime;
        public Scan(String pkg) { this.pkg = pkg; }
        public void heading(CharSequence value) { heading |= title(text(value)); }
        public void windowClass(CharSequence value) {
            String s = text(value);
            nativeScreen |= s.contains("datetimesettings") || s.contains("datepicker") || s.contains("timepicker")
                || s.contains("zonepicker") || s.contains("timezonesettings") || s.contains("timezonepicker");
        }
        public void resource(String id) {
            nativeScreen |= id.equals("date_picker") || id.equals("datepicker")
                || id.equals("time_picker") || id.equals("timepicker");
        }
        public void add(CharSequence value) {
            String s = text(value);
            autoTime |= s.equals("автоопределение времени") || s.equals("automatic date & time")
                || s.equals("set time automatically") || s.equals("use network-provided time");
            autoZone |= s.equals("автоматический часовой пояс") || s.equals("automatic time zone")
                || s.equals("set time zone automatically") || s.equals("use network-provided time zone");
            manualDate |= s.equals("установить дату") || s.equals("set date");
            manualTime |= s.equals("установить время") || s.equals("set time");
        }
        public boolean blocks() {
            return "com.android.settings".equals(pkg) && (heading || nativeScreen || autoTime && autoZone || manualDate && manualTime);
        }
    }
}
