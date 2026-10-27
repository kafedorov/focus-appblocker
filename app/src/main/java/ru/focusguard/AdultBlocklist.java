package ru.focusguard;

import android.content.Context;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import ru.focusguard.core.SuffixDomains;

public final class AdultBlocklist {
    private static volatile SuffixDomains domains;
    private static volatile String error = "";
    private static boolean loading;
    public static synchronized void warmUp(Context context) {
        if (loading || domains != null) return;
        loading = true; Context app = context.getApplicationContext();
        new Thread(() -> {
            try (InputStreamReader reader = new InputStreamReader(app.getAssets().open("nsfw-domains.txt"), StandardCharsets.UTF_8)) {
                domains = new SuffixDomains(reader);
            } catch (Exception e) { error = "Локальная база сайтов не загрузилась"; }
        }, "adult-domains").start();
    }
    public static boolean blocks(String host) { SuffixDomains list = domains; return list != null && list.blocks(host); }
    public static String status() { return domains != null ? "загружено " + domains.size() + " доменов" : error.isEmpty() ? "загружается" : error; }
}
