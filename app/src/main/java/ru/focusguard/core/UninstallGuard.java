package ru.focusguard.core;

import java.util.List;
import java.util.Locale;

/** Targets Focus removal and its own app-info page; never all of Settings. */
public final class UninstallGuard {
    public enum Decision { ALLOW, UNINSTALL, APP_INFO }
    public record Label(String text, boolean actionable) {}
    public static boolean systemPackage(String pkg) {
        return "com.android.settings".equals(pkg) || installerPackage(pkg)
            || "com.android.permissioncontroller".equals(pkg) || "com.google.android.permissioncontroller".equals(pkg);
    }
    private static boolean installerPackage(String pkg) {
        return "com.android.packageinstaller".equals(pkg) || "com.google.android.packageinstaller".equals(pkg)
            || "com.samsung.android.packageinstaller".equals(pkg);
    }
    public static Decision evaluate(String pkg, List<Label> labels) {
        Scan scan = new Scan(pkg);
        for (Label label : labels) scan.add(label);
        return scan.decision();
    }
    /** Incremental so accessibility scanning can stop before reading the entire tree. */
    public static final class Scan {
        private final String pkg;
        private boolean own, prompt, uninstall, appInfo;
        public Scan(String pkg) { this.pkg = pkg; }
        public void add(Label label) {
            String text = label.text() == null ? "" : label.text().trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
            own |= text.equals("фокус") || text.equals("ru.focusguard")
                || text.contains("«фокус»") || text.contains("\"фокус\"");
            prompt |= text.contains("удалить это приложение") || text.contains("хотите удалить")
                || text.contains("удалить приложение «фокус»") || text.contains("удалить «фокус»")
                || text.contains("uninstall this app") || text.contains("do you want to uninstall");
            appInfo |= text.equals("о приложении") || text.equals("сведения о приложении")
                || text.equals("информация о приложении") || text.equals("app info") || text.equals("application info");
            uninstall |= label.actionable() && (text.equals("удалить") || text.equals("uninstall"));
        }
        public Decision decision() {
            if (!systemPackage(pkg) || !own) return Decision.ALLOW;
            if (prompt || (installerPackage(pkg) && uninstall)) return Decision.UNINSTALL;
            if ("com.android.settings".equals(pkg) && appInfo && uninstall) return Decision.APP_INFO;
            return Decision.ALLOW;
        }
    }
}
