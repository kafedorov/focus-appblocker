package ru.focusguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.telecom.TelecomManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AppCatalog {
    public static final String LANGAME_PACKAGE = "com.f5computers.langame_aggregator";
    public record App(String pkg, String label, boolean game, boolean system) {}
    public static List<App> launchable(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>(); List<App> list = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            ApplicationInfo ai = info.activityInfo.applicationInfo;
            if (!seen.add(ai.packageName) || ai.packageName.equals(context.getPackageName())) continue;
            list.add(new App(ai.packageName, String.valueOf(ai.loadLabel(pm)), isGame(ai), isSystem(ai)));
        }
        list.sort(Comparator.comparing(App::label, String.CASE_INSENSITIVE_ORDER)); return list;
    }
    public static boolean isGame(ApplicationInfo ai) { return ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0; }
    public static boolean isSystem(ApplicationInfo ai) { return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0; }
    public static void baseline(Context context, Store store) {
        Set<String> known = new HashSet<>(); for (App app : launchable(context)) known.add(app.pkg());
        store.saveSet("baseline", known); store.setBool("initialized", true);
    }
    public static boolean critical(Context context, String pkg) {
        if (pkg.equals(context.getPackageName()) || pkg.equals("com.android.settings") || pkg.equals("com.android.systemui")) return true;
        PackageManager pm = context.getPackageManager();
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null && pkg.equals(home.activityInfo.packageName)) return true;
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null && pkg.equals(telecom.getDefaultDialerPackage())) return true;
        String keyboard = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return keyboard != null && keyboard.startsWith(pkg + "/");
    }
    public static String reason(Context context, Store store, String pkg) {
        if (critical(context, pkg)) return null;
        if (store.set("blocked").contains(pkg)) return "Приложение добавлено в список блокировки";
        // A scheduled app must remain launchable so the screen service can start its session.
        // Its weekly rule takes priority over automatic quarantine/category blocking, not manual denial.
        if (isLangame(pkg) && store.bool("langame_weekly", true)) return null;
        if (store.set("allowed").contains(pkg)) return null;
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (store.bool("games", true) && isGame(ai)) return "Игры заблокированы";
            if (store.bool("quarantine", false) && !isSystem(ai) && !store.set("baseline").contains(pkg)) return "Новое приложение ожидает разрешения";
        } catch (PackageManager.NameNotFoundException ignored) { /* package removed */ }
        return null;
    }
    public static String group(String pkg) {
        if (pkg.equals("org.telegram.messenger") || pkg.equals("org.telegram.messenger.web") || pkg.equals("org.thunderdog.challegram")) return "telegram";
        if (pkg.equals("com.vkontakte.android") || pkg.equals("com.vk.im")) return "vk";
        return null;
    }
    public static boolean isLangame(String pkg) { return LANGAME_PACKAGE.equals(pkg); }
}
