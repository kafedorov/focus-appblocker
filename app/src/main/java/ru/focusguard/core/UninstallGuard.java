package ru.focusguard.core;

import java.util.List;
import java.util.Locale;

/** Targets Focus removal and its own app-info page; never all of Settings. */
public final class UninstallGuard {
    public enum Decision { ALLOW, UNINSTALL, FORCE_STOP, APP_INFO, ACCESSIBILITY, DISABLE_ACCESSIBILITY }
    public record Label(String text, boolean actionable) {}
    public static boolean settingsPackage(String pkg) { return "com.android.settings".equals(pkg); }
    public static boolean systemPackage(String pkg) {
        return settingsPackage(pkg) || installerPackage(pkg)
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
    private static final String FOCUS_SERVICE = "фокус — блокировка отвлечений";
    public static boolean focusAppName(CharSequence label) {
        String text = normalize(label);
        return text.equals("фокус") || text.equals("ru.focusguard");
    }
    public static boolean focusServiceName(CharSequence label) {
        String text = normalize(label);
        return text.equals(FOCUS_SERVICE) || text.equals("фокус — блокировка отвлече...")
            || text.equals("фокус — блокировка отвлече…");
    }
    public static boolean forceStopControl(CharSequence label) {
        String text = normalize(label);
        return text.equals("остановить") || text.equals("принудительно остановить") || text.equals("force stop");
    }
    public static boolean cancelControl(CharSequence label) {
        String text = normalize(label);
        return text.equals("отмена") || text.equals("cancel");
    }
    private static String normalize(CharSequence label) {
        return label == null ? "" : label.toString().trim().toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replace('\n', ' ').replace('\r', ' ').replace('\u00a0', ' ');
    }
    /** Incremental so accessibility scanning can stop before reading the entire tree. */
    public static final class Scan {
        private final String pkg;
        private boolean own, prompt, uninstall, appInfo;
        private boolean ownAppName, permissionsRow, storageOrDataRow, forceStopButton;
        private boolean forceStopTitle, forceStopWarning, dialogWindow, cancelLabel;
        private boolean focusService, focusShortcut, switchControl, serviceList, disableServicePrompt;
        public Scan(String pkg) { this.pkg = pkg; }
        public void markSwitch() { switchControl = true; }
        public void markDialogWindow() { dialogWindow = true; }
        public void add(Label label) {
            String text = normalize(label.text());
            ownAppName |= focusAppName(text);
            own |= ownAppName || text.contains("«фокус»") || text.contains("\"фокус\"");
            prompt |= text.contains("удалить это приложение") || text.contains("хотите удалить")
                || text.contains("удалить приложение «фокус»") || text.contains("удалить «фокус»")
                || text.contains("uninstall this app") || text.contains("do you want to uninstall");
            appInfo |= text.equals("о приложении") || text.equals("сведения о приложении")
                || text.equals("информация о приложении") || text.equals("app info") || text.equals("application info");
            uninstall |= label.actionable() && (text.equals("удалить") || text.equals("uninstall"));
            permissionsRow |= text.equals("разрешения") || text.equals("разрешения приложения") || text.equals("permissions");
            storageOrDataRow |= text.equals("память") || text.equals("хранилище") || text.equals("использование данных")
                || text.equals("мобильные данные") || text.equals("storage") || text.equals("data usage");
            forceStopButton |= label.actionable() && forceStopControl(text);
            forceStopTitle |= text.equals("принудительно остановить") || text.equals("принудительно остановить?")
                || text.equals("остановить приложение?") || text.equals("force stop") || text.equals("force stop?");
            forceStopWarning |= (text.contains("принудительная остановка приложения") && text.contains("может привести к ошибкам"))
                || text.contains("if you force stop an app");
            cancelLabel |= cancelControl(text);
            focusService |= focusServiceName(text);
            focusShortcut |= text.startsWith("ярлык " + FOCUS_SERVICE) || text.equals(FOCUS_SERVICE + " shortcut");
            serviceList |= text.equals("установленные приложения") || text.equals("installed apps")
                || text.equals("installed services") || text.equals("установленные службы");
            // One UI may put the message in the accessibility label of the entire sheet.
            disableServicePrompt |= text.equals("выключить " + FOCUS_SERVICE + "?")
                || !label.actionable() && text.contains(FOCUS_SERVICE)
                && (text.startsWith("отключить ") || text.startsWith("остановить ")
                    || text.startsWith("прекратить работу ") || text.startsWith("stop ") || text.startsWith("turn off "));
        }
        public boolean isForceStopDialog() {
            return settingsPackage(pkg) && (forceStopWarning || forceStopTitle && (dialogWindow || cancelLabel));
        }
        public boolean isAccessibilityScreen() {
            return settingsPackage(pkg) && !serviceList && (focusShortcut || focusService && switchControl);
        }
        public boolean isAppInfoScreen() {
            return settingsPackage(pkg) && (appInfo || permissionsRow && storageOrDataRow && forceStopButton);
        }
        public Decision decision() {
            if (!systemPackage(pkg)) return Decision.ALLOW;
            if (own && (prompt || installerPackage(pkg) && uninstall)) return Decision.UNINSTALL;
            if (settingsPackage(pkg) && disableServicePrompt) return Decision.DISABLE_ACCESSIBILITY;
            if (isForceStopDialog()) return Decision.FORCE_STOP;
            if (isAccessibilityScreen()) return Decision.ACCESSIBILITY;
            if (!own) return Decision.ALLOW;
            // Some Settings windows do not expose their toolbar title. Require the actual name
            // and app-specific rows before treating those windows as the Focus app-info page.
            if (isAppInfoScreen() && (appInfo || ownAppName)) return Decision.APP_INFO;
            return Decision.ALLOW;
        }
    }
}
