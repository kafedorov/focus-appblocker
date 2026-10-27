package ru.focusguard.core;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Random;

/** Dependency-free behavioral tests, runnable on a JDK without an Android device. */
public final class CoreChecks {
    private static int count;
    private static void check(boolean condition, String name) { count++; if (!condition) throw new AssertionError(name); }
    private static LocalDateTime time(String value) { return LocalDateTime.parse("2026-10-26T" + value); }
    private static SessionPolicy.Result session(String time, long elapsed, int boot, SessionPolicy.State old, boolean begin) {
        return SessionPolicy.evaluate(420, 1140, 10, time(time), elapsed, boot, old, begin);
    }
    public static void main(String[] args) {
        DomainRules rules = new DomainRules("# comment\nexample.com\n*.example.com\n*adult*\nfoo?.net\nпример.рф");
        check(rules.blocks("example.com"), "exact host");
        check(rules.blocks("A.B.EXAMPLE.COM."), "subdomains, case and trailing dot");
        check(!rules.blocks("example.com.evil.org"), "suffix attack");
        check(!rules.blocks("notexample.com"), "host boundaries");
        check(rules.blocks("myadultsite.org"), "infix glob");
        check(rules.blocks("fooa.net") && !rules.blocks("fooaa.net"), "single char glob");
        check(rules.blocks("xn--e1afmkfd.xn--p1ai"), "IDN");
        check(!new DomainRules("*.example.com").blocks("example.com"), "wildcard root explicit");
        check(!rules.blocks(""), "empty hostname");
        DomainRules allZones = new DomainRules("myname.*\n*.myname.*");
        for (String host : new String[]{"myname.ru", "myname.com", "myname.xyz", "myname.co.uk", "www.myname.ru", "a.b.myname.example"})
            check(allZones.blocks(host), "custom name in any zone: " + host);
        check(!allZones.blocks("notmyname.com") && !allZones.blocks("mynameother.ru"), "all zones keeps name boundary");
        DomainRules subZones = new DomainRules("*.myname.*");
        check(subZones.blocks("www.myname.ru") && subZones.blocks("a.myname.com"), "wildcard on both sides");
        check(!subZones.blocks("myname.ru"), "leading wildcard requires subdomain");
        check(new DomainRules("*.пример.*").blocks("www.пример.рф"), "custom IDN name with any zone");
        check(new DomainRules("*myname*").blocks("myname.com") && new DomainRules("*myname*").blocks("notmyname.ru"), "substring wildcard is intentionally broader");

        BrowserPage customTab = BrowserPage.parse("m.youtube.com");
        check(customTab != null && customTab.youtube() && !customTab.hasPagePath(), "custom tab origin only");
        check(customTab.shortsDecision(true, false, true) == BrowserPage.ShortsDecision.BLOCK_HIDDEN_ADDRESS, "strict mode closes screenshot case");
        check(customTab.shortsDecision(true, false, false) == BrowserPage.ShortsDecision.ALLOW, "strict mode optional");
        check(customTab.shortsDecision(false, true, true) == BrowserPage.ShortsDecision.ALLOW, "Shorts toggle disables fallback");
        check(customTab.shortsDecision(true, true, false) == BrowserPage.ShortsDecision.BLOCK_SHORTS, "visible player works without URL path");
        for (String url : new String[]{"https://m.youtube.com/shorts/123", "youtube.com/shorts", "www.youtube.com/shorts/abc?feature=share", "https://youtube.com/%73horts/test"}) {
            check(BrowserPage.parse(url).shortsDecision(true, false, true) == BrowserPage.ShortsDecision.BLOCK_SHORTS, "Shorts URL: " + url);
        }
        for (String url : new String[]{"https://youtube.com/watch?v=abc", "m.youtube.com/results?search_query=shorts", "https://youtube.com/shortstory", "https://example.com/shorts/a", "https://youtube.com.evil.org/shorts/a"}) {
            check(BrowserPage.parse(url).shortsDecision(true, false, true) == BrowserPage.ShortsDecision.ALLOW, "ordinary or unrelated page: " + url);
        }
        check(BrowserPage.parse("https://youtube.com/").shortsDecision(true, false, true) == BrowserPage.ShortsDecision.BLOCK_HIDDEN_ADDRESS, "origin slash is also ambiguous");
        check(BrowserPage.parse("https://youtube.com/watch?v=abc").shortsDecision(true, true, true) == BrowserPage.ShortsDecision.BLOCK_SHORTS, "player evidence blocks even watch URL");
        check(BrowserPage.parse("пример.рф/page").host().equals("xn--e1afmkfd.xn--p1ai"), "browser IDN host");
        for (String invalidUrl : new String[]{"search query", "Shorts", "file://youtube.com/shorts/123", "https://youtube.com@evil.org/shorts/a", "https://[bad"})
            check(BrowserPage.parse(invalidUrl) == null, "invalid address rejected: " + invalidUrl);
        check(BrowserPage.shortsPlayerId("com.google.android.youtube:id/reel_recycler"), "native player marker");
        check(BrowserPage.shortsPlayerId("shorts-player"), "web player marker");
        check(!BrowserPage.shortsPlayerId("shorts_thumbnail"), "Shorts thumbnails are not player evidence");
        check(BrowserPage.selectedShortsLabel(true, "Shorts, вкладка, 2 из 5"), "selected Shorts tab");
        check(BrowserPage.selectedShortsLabel(true, "Shorts"), "selected Shorts text");
        check(!BrowserPage.selectedShortsLabel(false, "Shorts"), "unselected Shorts tab on normal feed");
        try {
            SuffixDomains suffix = new SuffixDomains(new java.io.StringReader("# test\nexample.com\nother.org\n"));
            check(suffix.size() == 2 && suffix.blocks("a.b.example.com"), "list subdomains");
            check(!suffix.blocks("notexample.com") && !suffix.blocks("example.com.evil.org"), "list host boundary");
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        for (String invalid : new String[]{"https://example.com", "example.com/path", "foo..com", "foo bar", "[abc].net"}) {
            boolean throwsError = false;
            try { new DomainRules(invalid); } catch (IllegalArgumentException e) { throwsError = true; }
            check(throwsError, "invalid rule " + invalid);
        }
        check(session("06:59", 1000, 1, null, true).status() == SessionPolicy.Status.OUTSIDE, "before window");
        check(session("19:00", 1000, 1, null, true).status() == SessionPolicy.Status.OUTSIDE, "end exclusive");
        var preview = session("07:00", 1000, 1, null, false);
        check(preview.status() == SessionPolicy.Status.AVAILABLE && preview.state() == null, "preview does not consume");
        var first = session("07:05", 1000, 1, null, true);
        check(first.remainingMillis() == 600_000, "full session");
        check(session("07:10", 301000, 1, first.state(), true).remainingMillis() == 300_000, "continuous elapsed timer");
        check(session("07:15", 601000, 1, first.state(), true).status() == SessionPolicy.Status.EXHAUSTED, "ends at exact budget");
        check(session("07:40", 2101000, 1, first.state(), true).status() == SessionPolicy.Status.EXHAUSTED, "cannot reopen in same hour");
        check(session("08:00", 3301000, 1, first.state(), true).remainingMillis() == 600_000, "fresh calendar hour");
        check(session("07:10", 2000, 2, first.state(), true).status() == SessionPolicy.Status.EXHAUSTED, "reboot cannot refresh used slot");
        check(session("07:10", 500, 1, first.state(), true).status() == SessionPolicy.Status.EXHAUSTED, "monotonic rollback");
        check(session("07:06", 1000, 1, null, true).remainingMillis() == 600_000, "independent second messenger");
        var future = session("09:00", 1000, 1, null, true);
        check(session("08:30", 601000, 1, future.state(), true).status() == SessionPolicy.Status.EXHAUSTED, "clock rollback cannot reset slot");
        check(SessionPolicy.evaluate(1320, 360, 10, time("23:00"), 1000, 1, null, true).status() == SessionPolicy.Status.ACTIVE, "overnight window evening");
        check(SessionPolicy.evaluate(1320, 360, 10, time("05:00"), 1000, 1, null, true).status() == SessionPolicy.Status.ACTIVE, "overnight window morning");
        check(SessionPolicy.evaluate(1320, 360, 10, time("12:00"), 1000, 1, null, true).status() == SessionPolicy.Status.OUTSIDE, "overnight window daytime");
        check(SessionPolicy.evaluate(0, 0, 10, time("12:00"), 1000, 1, null, true).status() == SessionPolicy.Status.ACTIVE, "all day window");
        weeklyChecks();
        uninstallGuardChecks();
        count += SessionAndBrowserChecks.run();
        byte[] query = dns("example.com"); byte[] ip = packet(query);
        DnsPacket.Query parsed = DnsPacket.parse(ip, ip.length);
        check(parsed != null && parsed.host().equals("example.com"), "DNS extraction");
        byte[] denied = DnsPacket.error(query, 3);
        check((denied[3] & 15) == 3 && denied[6] == 0 && denied[10] == 0, "NXDOMAIN and section counts");
        check(DnsPacket.matches(query, denied), "response question validation");
        byte[] reply = DnsPacket.reply(parsed, denied);
        check(DnsPacket.checksum(reply, 0, 20) == 0, "IPv4 response checksum");
        check(reply[12] == ip[16] && reply[16] == ip[12], "reversed IPs");
        check((reply[21] & 255) == 53 && (reply[22] & 255) == 0xc0, "reversed UDP ports");
        denied[0] ^= 1; check(!DnsPacket.matches(query, denied), "reject foreign transaction");
        byte[] bad = ip.clone(); bad[6] = 0x20; check(DnsPacket.parse(bad, bad.length) == null, "reject fragments");
        bad = ip.clone(); bad[40] = (byte)0xc0; check(DnsPacket.parse(bad, bad.length) == null, "reject compressed query labels");
        check(DnsPacket.parse(ip, 12) == null, "reject truncated IP");
        Random random = new Random(821);
        for (int i = 0; i < 10_000; i++) { byte[] bytes = new byte[random.nextInt(256)]; random.nextBytes(bytes); DnsPacket.parse(bytes, bytes.length); }
        check(true, "10000 malformed packets do not crash parser");
        if (args.length > 0) {
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))) {
                SuffixDomains bundled = new SuffixDomains(reader);
                check(bundled.size() == 90_159, "complete bundled blocklist loads");
                check(bundled.blocks("pornhub.com") && bundled.blocks("www.pornhub.com"), "bundled category lookup");
                check(!bundled.blocks("developer.android.com") && !bundled.blocks("example.com"), "normal hosts outside category");
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }
        System.out.println("PASS: " + count + " behavioral checks, plus 10000 malformed packets");
    }
    private static WeeklySessionPolicy.Result weekly(String time, long elapsed, int boot, WeeklySessionPolicy.State old, boolean begin) {
        return WeeklySessionPolicy.evaluate(LocalDateTime.parse(time), elapsed, boot, old, begin);
    }
    private static UninstallGuard.Label ui(String text, boolean action) { return new UninstallGuard.Label(text, action); }
    private static void uninstallGuardChecks() {
        var target = java.util.Arrays.asList(ui("Фокус", false), ui("Хотите удалить это приложение?", false), ui("OK", true));
        check(UninstallGuard.evaluate("com.android.packageinstaller", target) == UninstallGuard.Decision.UNINSTALL, "own confirmation with OK");
        check(UninstallGuard.evaluate("com.samsung.android.packageinstaller", java.util.Arrays.asList(ui("Фокус", false), ui("Удалить", true))) == UninstallGuard.Decision.UNINSTALL, "Samsung uninstall button");
        check(UninstallGuard.evaluate("com.google.android.packageinstaller", target) == UninstallGuard.Decision.UNINSTALL, "Google installer");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Фокус", false), ui("О приложении", false), ui("Удалить", true))) == UninstallGuard.Decision.APP_INFO, "own app-info intercepted before confirmation");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Фокус", false), ui("Сведения о приложении", false), ui("Удалить", true))) == UninstallGuard.Decision.APP_INFO, "Samsung alternate app-info title");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Фокус", false), ui("App info", false), ui("Uninstall", true))) == UninstallGuard.Decision.APP_INFO, "English app info");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("LANGAME", false), ui("О приложении", false), ui("Удалить", true))) == UninstallGuard.Decision.ALLOW, "other app-info is accessible");
        check(UninstallGuard.evaluate("com.android.packageinstaller", java.util.Arrays.asList(ui("LANGAME", false), ui("Хотите удалить это приложение?", false), ui("OK", true))) == UninstallGuard.Decision.ALLOW, "other removal accessible");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Удалить приложение «Фокус»?", false))) == UninstallGuard.Decision.UNINSTALL, "quoted title recognized before buttons appear");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Удалить «Фокус»?", false))) == UninstallGuard.Decision.UNINSTALL, "short quoted confirmation");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Фокусирование", false), ui("Хотите удалить это приложение?", false))) == UninstallGuard.Decision.ALLOW, "similar name is not Focus");
        check(UninstallGuard.evaluate("com.android.chrome", target) == UninstallGuard.Decision.ALLOW, "website text cannot trigger guard");
        check(UninstallGuard.evaluate("ru.focusguard", target) == UninstallGuard.Decision.ALLOW, "recovery always accessible");
        check(UninstallGuard.evaluate("com.android.permissioncontroller", java.util.Arrays.asList(ui("Фокус", false), ui("Разрешить", true), ui("Запретить", true))) == UninstallGuard.Decision.ALLOW, "permission consent accessible");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Фокус", false), ui("Разрешения", false), ui("Удалить", true))) == UninstallGuard.Decision.ALLOW, "unrelated settings need explicit app-info context");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Фокус", false), ui("О приложении", false), ui("Удалить", false))) == UninstallGuard.Decision.ALLOW, "disabled uninstall control insufficient for app-info");
        check(UninstallGuard.evaluate("com.android.packageinstaller", java.util.Arrays.asList(ui("Фокус", false))) == UninstallGuard.Decision.ALLOW, "app label alone insufficient");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("Фокус", false), ui("Do you want to uninstall this app?", false))) == UninstallGuard.Decision.UNINSTALL, "English confirmation");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui("ru.focusguard", false), ui("Хотите удалить это приложение?", false))) == UninstallGuard.Decision.UNINSTALL, "package label fallback");
        check(UninstallGuard.evaluate("com.android.settings", java.util.Arrays.asList(ui(null, true), ui("", false))) == UninstallGuard.Decision.ALLOW, "empty labels safe");
        var incremental = new UninstallGuard.Scan("com.android.settings");
        incremental.add(ui("О приложении", false)); incremental.add(ui("Удалить", true));
        check(incremental.decision() == UninstallGuard.Decision.ALLOW, "streaming scan waits for own identity");
        incremental.add(ui("Фокус", false));
        check(incremental.decision() == UninstallGuard.Decision.APP_INFO, "streaming scan identifies before remaining nodes");
        incremental.add(ui("Хотите удалить это приложение?", false));
        check(incremental.decision() == UninstallGuard.Decision.UNINSTALL, "confirmation takes priority over background app-info");
        for (var labels : java.util.Arrays.asList(target, java.util.Arrays.asList(ui("Удалить", true), ui("Хотите удалить это приложение?", false), ui("Фокус", false))))
            check(UninstallGuard.evaluate("com.android.packageinstaller", labels) == UninstallGuard.Decision.UNINSTALL, "node order independent");
    }
    private static void weeklyChecks() {
        for (String date : new String[]{"2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-11-01"}) {
            var denied = weekly(date + "T12:00", 1000, 1, null, true);
            check(denied.status() == WeeklySessionPolicy.Status.DAY_BLOCKED && denied.state() == null, "Langame denies non-Saturday without consuming: " + date);
        }
        var preview = weekly("2026-10-31T10:00", 1000, 1, null, false);
        check(preview.status() == WeeklySessionPolicy.Status.AVAILABLE && preview.state() == null, "Langame preview does not consume session");
        var first = weekly("2026-10-31T10:00", 1000, 1, null, true);
        check(first.status() == WeeklySessionPolicy.Status.ACTIVE && first.remainingMillis() == 600_000, "Langame first Saturday launch");
        check(weekly("2026-10-31T10:02", 121000, 1, first.state(), true).remainingMillis() == 480_000, "Langame reopening does not restart timer");
        check(weekly("2026-10-31T10:10", 601000, 1, first.state(), true).status() == WeeklySessionPolicy.Status.EXHAUSTED, "Langame exact ten-minute deadline");
        check(weekly("2026-10-31T11:00", 3_601000, 1, first.state(), true).status() == WeeklySessionPolicy.Status.EXHAUSTED, "new hour cannot reset weekly session");
        check(weekly("2026-10-31T23:00", 46_801000, 1, first.state(), true).status() == WeeklySessionPolicy.Status.EXHAUSTED, "second session same Saturday denied");
        var sunday = weekly("2026-11-01T00:00", 50_401000, 1, first.state(), true);
        check(sunday.status() == WeeklySessionPolicy.Status.DAY_BLOCKED && sunday.state().equals(first.state()), "Sunday keeps consumed state");
        var next = weekly("2026-11-07T00:00", 60_001000, 1, first.state(), true);
        check(next.status() == WeeklySessionPolicy.Status.ACTIVE && next.remainingMillis() == 600_000, "next Saturday refreshes session");
        check(next.state().weekStart() - first.state().weekStart() == 7, "weekly key is a seven-day calendar interval");
        var nextPreview = weekly("2026-11-07T12:00", 70_001000, 1, first.state(), false);
        check(nextPreview.status() == WeeklySessionPolicy.Status.AVAILABLE && nextPreview.state().equals(first.state()), "next week preview does not overwrite prior state");
        check(weekly("2026-10-31T10:02", 2000, 2, first.state(), true).status() == WeeklySessionPolicy.Status.EXHAUSTED, "reboot cannot buy another weekly session");
        check(weekly("2026-11-07T12:00", 2000, 2, first.state(), true).status() == WeeklySessionPolicy.Status.ACTIVE, "next week still available after reboot");
        check(weekly("2026-10-31T10:02", 500, 1, first.state(), true).status() == WeeklySessionPolicy.Status.EXHAUSTED, "monotonic clock rollback denied");
        check(weekly("2026-10-31T09:00", 601000, 1, first.state(), true).status() == WeeklySessionPolicy.Status.EXHAUSTED, "wall-clock rollback cannot extend used session");
        check(weekly("2026-10-31T12:00", 60_002000, 1, next.state(), true).status() == WeeklySessionPolicy.Status.EXHAUSTED, "calendar week rollback denied");
        var late = weekly("2026-10-31T23:55", 1000, 1, null, true);
        check(late.status() == WeeklySessionPolicy.Status.ACTIVE && late.remainingMillis() == 300_000, "late Saturday session capped at midnight");
        check(weekly("2026-10-31T23:59:59", 300000, 1, late.state(), true).remainingMillis() == 1000, "one second before Sunday");
        check(weekly("2026-11-01T00:00", 301000, 1, late.state(), true).status() == WeeklySessionPolicy.Status.DAY_BLOCKED, "unfinished session does not enter Sunday");
        check(weekly("2026-10-31T00:00", 1000, 1, null, true).remainingMillis() == 600_000, "Saturday midnight opens window");
        check(weekly("2026-10-31T23:59", 1000, 1, null, false).remainingMillis() == 60_000, "late preview shows only available Saturday time");
        check(weekly("2026-11-21T12:00", 1000, 2, first.state(), true).remainingMillis() == 600_000, "unused weeks do not accumulate time");
        var yearEnd = weekly("2026-12-26T12:00", 1000, 1, null, true);
        check(weekly("2027-01-02T12:00", 80_000000, 1, yearEnd.state(), true).remainingMillis() == 600_000, "week reset across year boundary");
    }
    private static byte[] dns(String host) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.writeBytes(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0});
        for (String part : host.split("\\.")) { out.write(part.length()); out.writeBytes(part.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        out.writeBytes(new byte[]{0,0,1,0,1}); return out.toByteArray();
    }
    private static byte[] packet(byte[] dns) {
        byte[] ip = new byte[28 + dns.length]; ip[0] = 0x45; ip[2] = (byte)(ip.length >>> 8); ip[3] = (byte)ip.length;
        ip[8] = 64; ip[9] = 17; ip[12] = 10; ip[15] = 1; ip[16] = 10; ip[19] = 2;
        ip[20] = (byte)0xc0; ip[21] = 0x01; ip[23] = 53; ip[24] = (byte)((8 + dns.length) >>> 8); ip[25] = (byte)(8 + dns.length);
        System.arraycopy(dns,0,ip,28,dns.length); return ip;
    }
}
