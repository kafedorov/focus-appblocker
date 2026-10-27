package ru.focusguard.core;

import java.time.LocalDateTime;

/** Session boundaries, consent tokens and native browser-address selection regressions. */
public final class SessionAndBrowserChecks {
    private static int count;
    private static void check(boolean value, String name) { count++; if (!value) throw new AssertionError(name); }
    private static LocalDateTime at(String value) { return LocalDateTime.parse(value); }
    public static int run() { count = 0; daily(); confirmation(); browser(); return count; }
    private static void daily() {
        LocalDateTime noon = at("2026-10-27T12:00:00");
        var preview = DailySessionPolicy.evaluate(noon, 1000, 1, null, false);
        check(preview.status() == DailySessionPolicy.Status.AVAILABLE && preview.state() == null && preview.remainingMillis() == 1_800_000, "YouTube preview never starts the day");
        for (int i = 0; i < 20; i++) {
            var waiting = DailySessionPolicy.evaluate(noon.plusSeconds(i), 1000 + i * 1000L, 1, null, false);
            check(waiting.state() == null && waiting.remainingMillis() == 1_800_000, "time spent in confirmation is free");
        }
        var first = DailySessionPolicy.evaluate(noon, 1000, 1, null, true);
        check(first.status() == DailySessionPolicy.Status.ACTIVE && first.remainingMillis() == 1_800_000, "confirmation starts full thirty minutes");
        var reopened = DailySessionPolicy.evaluate(noon.plusMinutes(10), 601000, 1, first.state(), false);
        check(reopened.status() == DailySessionPolicy.Status.ACTIVE && reopened.remainingMillis() == 1_200_000, "reentry/browser shares remaining daily session");
        check(DailySessionPolicy.evaluate(noon.plusMinutes(29), 1_801_000, 1, first.state(), false).status() == DailySessionPolicy.Status.EXHAUSTED, "monotonic budget expires even with clock drift");
        check(DailySessionPolicy.evaluate(noon.plusMinutes(30), 1_801_000, 1, first.state(), true).status() == DailySessionPolicy.Status.EXHAUSTED, "confirm cannot restart exhausted day");
        check(DailySessionPolicy.evaluate(noon.plusHours(4), 1_801_000, 1, first.state(), false).status() == DailySessionPolicy.Status.EXHAUSTED, "new calendar hour does not renew YouTube");
        check(DailySessionPolicy.evaluate(noon.plusMinutes(1), 2000, 2, first.state(), false).status() == DailySessionPolicy.Status.EXHAUSTED, "reboot consumes started day");
        check(DailySessionPolicy.evaluate(noon, 500, 1, first.state(), false).status() == DailySessionPolicy.Status.EXHAUSTED, "elapsed rollback is not new time");
        check(DailySessionPolicy.evaluate(noon.minusDays(1), 1_801_000, 1, first.state(), true).status() == DailySessionPolicy.Status.EXHAUSTED, "date rollback cannot grant another day");
        var tomorrow = DailySessionPolicy.evaluate(at("2026-10-28T00:00:00"), 2_000_000, 1, first.state(), false);
        check(tomorrow.status() == DailySessionPolicy.Status.AVAILABLE && tomorrow.state().equals(first.state()), "next day is a new unstarted offer");
        check(DailySessionPolicy.evaluate(at("2026-10-28T00:00:00"), 2_000_000, 1, first.state(), true).remainingMillis() == 1_800_000, "tomorrow can start another thirty minutes");
        var late = DailySessionPolicy.evaluate(at("2026-10-27T23:55:00"), 1000, 1, null, false);
        check(late.remainingMillis() == 300_000 && late.state() == null, "offer shows only time left before midnight");
        var lateStart = DailySessionPolicy.evaluate(at("2026-10-27T23:55:00"), 1000, 1, null, true);
        check(DailySessionPolicy.evaluate(at("2026-10-27T23:59:59"), 300000, 1, lateStart.state(), false).remainingMillis() == 1000, "late session ends at midnight");
        check(DailySessionPolicy.evaluate(at("2026-10-28T00:00:00"), 301000, 1, lateStart.state(), false).status() == DailySessionPolicy.Status.AVAILABLE, "midnight needs fresh confirmation");
    }
    private static void confirmation() {
        var now = at("2026-10-27T12:20:00");
        var vk = SessionGate.hourly("vk", 420, 1140, 10, now, 1000, 1, null);
        var tg = SessionGate.hourly("telegram", 420, 1140, 10, now, 1000, 1, null);
        check(vk.status() == SessionGate.Status.AVAILABLE && vk.remainingMillis() == 600_000, "messenger has an unstarted offer");
        check(SessionGate.canConfirm(vk, SessionGate.hourly("vk", 420, 1140, 10, now.plusSeconds(45), 46000, 1, null)), "same unstarted hour can confirm later");
        check(!SessionGate.canConfirm(vk, tg), "old VK overlay cannot start Telegram");
        check(!SessionGate.canConfirm(vk, SessionGate.hourly("vk", 420, 1140, 10, now.plusHours(1), 1000, 1, null)), "stale hour requires refreshed offer");
        check(!SessionGate.canConfirm(null, vk) && !SessionGate.canConfirm(vk, null), "no confirmation for blocked or unrestricted foreground");
        var state = SessionPolicy.evaluate(420, 1140, 10, now, 1000, 1, null, true).state();
        var active = SessionGate.hourly("vk", 420, 1140, 10, now.plusMinutes(2), 121000, 1, state);
        check(active.status() == SessionGate.Status.ACTIVE && active.remainingMillis() == 480_000, "existing active session enters without another offer");
        check(!SessionGate.canConfirm(vk, active), "double click cannot restart active timer");
        var spent = SessionGate.hourly("vk", 420, 1140, 10, now.plusMinutes(10), 601000, 1, state);
        check(spent.status() == SessionGate.Status.BLOCKED && !SessionGate.canConfirm(vk, spent), "spent hour never grants access by old button");
        check(SessionGate.hourly("vk", 420, 1140, 10, at("2026-10-27T19:00:00"), 1, 1, null).status() == SessionGate.Status.FREE, "outside schedule needs no confirmation");
        var hourEnd = SessionGate.hourly("vk", 420, 1140, 10, at("2026-10-27T12:58:00"), 1, 1, null);
        check(hourEnd.remainingMillis() == 120_000 && hourEnd.nextWindow().equals(at("2026-10-27T13:00:00")), "offer states actual time before new hour");
        var scheduleEnd = SessionGate.hourly("vk", 450, 1155, 10, at("2026-10-27T19:10:00"), 1, 1, null);
        check(scheduleEnd.remainingMillis() == 300_000 && scheduleEnd.nextWindow().equals(at("2026-10-27T19:15:00")), "nonhour schedule end is next free window");
        check(SessionGate.hourly("vk", 1320, 360, 10, at("2026-10-27T23:58:00"), 1, 1, null).remainingMillis() == 120_000, "overnight schedule respects calendar hour");
        var daily = SessionGate.daily(now, 1000, 1, null);
        check(daily.nextWindow().equals(at("2026-10-28T00:00:00")), "daily offer states next calendar day");
        check(!SessionGate.canConfirm(daily, SessionGate.daily(now.plusDays(1), 1000, 1, null)), "stale midnight offer cannot silently start tomorrow");
        check(SessionGate.canConfirm(daily, SessionGate.daily(now.plusHours(1), 1000, 1, null)), "YouTube offer same day survives hour change");
        var weekday = SessionGate.weekly(now, 1, 1, null);
        check(weekday.status() == SessionGate.Status.BLOCKED && weekday.nextWindow().equals(at("2026-10-31T00:00:00")), "LANGAME weekday closed until Saturday");
        var saturday = SessionGate.weekly(at("2026-10-31T12:00:00"), 1, 1, null);
        check(saturday.status() == SessionGate.Status.AVAILABLE && saturday.remainingMillis() == 600_000 && saturday.nextWindow().equals(at("2026-11-07T00:00:00")), "Saturday offer states next Saturday");
        check(!SessionGate.canConfirm(saturday, SessionGate.weekly(at("2026-11-07T12:00:00"), 1, 1, null)), "stale weekly confirmation rejected");
        check(!SessionGate.canConfirm(saturday, SessionGate.weekly(at("2026-11-01T00:00:00"), 1, 1, null)), "Saturday confirmation cannot start on Sunday");
        check(SessionGate.weekly(at("2026-10-31T23:59:00"), 1, 1, null).remainingMillis() == 60000, "LANGAME prompt shows short session near midnight");
    }
    private static BrowserAddress.Element bar(String id, String text, String description, boolean document, boolean toolbar, boolean tabs, int top) {
        return new BrowserAddress.Element(id, "android.widget.TextView", text, description, true, false, document, toolbar, tabs, 120, top, 480, top + 50);
    }
    private static BrowserPage read(String pkg, BrowserAddress.Element element) { return BrowserAddress.read(pkg, element, 0, 52, 590, 1220); }
    private static void browser() {
        String pkg = "com.yandex.browser";
        var footer = bar("", "somehost.somesite.com", null, false, false, false, 1060);
        var found = read(pkg, footer);
        check(found != null && found.host().equals("somehost.somesite.com"), "Yandex screenshot bottom host without resource id");
        check(new DomainRules("somesite.*\n*.somesite.*").blocks(found.host()), "Yandex extracted host reaches wildcard rules");
        for (String id : new String[]{"bro_omnibar_address", "bro_omnibar_address_title", "bro_omnibox_text", "omnibox_text", "url_bar_text"})
            check(read(pkg, bar(pkg + ":id/" + id, "example.com", null, false, false, false, 100)) != null, "Yandex address variants: " + id);
        check(read(pkg, bar("", "example.com", null, false, true, false, 100)) != null, "native toolbar ancestry fallback");
        check(read(pkg, bar("", "example.com", null, false, false, false, 300)) == null, "unknown page-area text is not address evidence");
        check(read("com.android.chrome", footer) == null, "geometry fallback limited to Yandex");
        check(read(pkg, bar("url_bar", "example.com", null, true, false, false, 1060)) == null, "document text cannot impersonate URL id");
        check(read(pkg, bar("", "example.com", null, false, false, true, 1060)) == null, "tab previews are not current addresses");
        var hidden = new BrowserAddress.Element("url_bar", "android.widget.TextView", "example.com", null, false, false, false, true, false, 0, 0, 500, 50);
        check(read(pkg, hidden) == null, "invisible URL excluded");
        var typing = new BrowserAddress.Element("url_bar", "android.widget.EditText", "example.com", null, true, true, false, true, false, 0, 0, 500, 50);
        check(read(pkg, typing) == null, "typed but unnavigated URL excluded");
        var nativeChrome = read("com.android.chrome", bar("com.android.chrome:id/url_bar", "example.com", null, false, false, false, 100));
        check(nativeChrome != null && nativeChrome.host().equals("example.com"), "Chrome existing address unchanged");
        var fullPath = read(pkg, bar("bro_omnibar_address", "m.youtube.com", "Адрес: https://m.youtube.com/shorts/123", false, true, false, 1060));
        check(fullPath != null && fullPath.hasPagePath() && fullPath.shortsDecision(true, false, true) == BrowserPage.ShortsDecision.BLOCK_SHORTS, "native description restores full Shorts path");
        check(read(pkg, bar("url_bar", "Ссылка на example.com в тексте", null, false, true, false, 1060)) == null, "no arbitrary prose extraction");
        check(BrowserAddress.documentRoot("android.webkit.WebView", "") && BrowserAddress.documentRoot("org.chromium.content.browser.ContentView", ""), "document roots are skipped before traversal");
        check(!BrowserAddress.documentRoot("android.widget.FrameLayout", "android:id/content"), "activity content does not hide native chrome");
        check(BrowserAddress.toolbarId("com.yandex.browser:id/bro_omnibar") && BrowserAddress.tabListId("com.yandex.browser:id/tab_grid"), "toolbar and tab ancestry identified");
        check(read("com.yandex.browser.beta", footer) != null && read("com.yandex.browser.alpha", footer) != null, "Yandex release variants");
        var shortPage = BrowserPage.parse("m.youtube.com");
        check(BrowserAddress.prefer(shortPage, BrowserPage.parse("m.youtube.com/watch?v=123")).hasPagePath(), "full address preferred for same origin");
        check(BrowserAddress.prefer(shortPage, BrowserPage.parse("unrelated.com")).youtube(), "conflicting native labels do not silently replace first origin");
        var watch = BrowserPage.parse("youtube.com/watch?v=123");
        var fullscreen = BrowserAddress.fullscreenContext(watch, true, false, false, false);
        check(fullscreen != null && fullscreen.youtube() && !fullscreen.hasPagePath(), "hidden fullscreen keeps last known YouTube origin without claiming old path");
        check(fullscreen.shortsDecision(true, false, true) == BrowserPage.ShortsDecision.BLOCK_HIDDEN_ADDRESS, "fullscreen fallback does not weaken strict Shorts policy");
        check(BrowserAddress.fullscreenContext(watch, true, true, false, false) == null, "editing address does not reuse old origin");
        check(BrowserAddress.fullscreenContext(watch, true, false, true, false) == null, "visible native toolbar without address is not assumed fullscreen");
        check(BrowserAddress.fullscreenContext(watch, true, false, false, true) == null, "tab overview cannot inherit YouTube restriction");
        check(BrowserAddress.fullscreenContext(watch, false, false, false, false) == null, "native settings do not inherit YouTube restriction");
        check(BrowserAddress.fullscreenContext(BrowserPage.parse("example.com"), true, false, false, false) == null, "unrelated domain cannot establish YouTube fullscreen context");
        check(BrowserAddress.fullscreenContext(null, true, false, false, false) == null, "unknown fullscreen origin is not fabricated");
    }
}
