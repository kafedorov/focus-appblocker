package ru.focusguard;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Upgrade compatibility only: releases rights granted by versions <= 0.1.3. */
public final class LegacyAccessCleanup {
    private static final AtomicBoolean running = new AtomicBoolean();
    public static void start(Context context) {
        Context app = context.getApplicationContext();
        Store store = new Store(app);
        if (store.bool("legacy_cleanup_complete", false) || !running.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { release(app, store); }
            catch (RuntimeException e) {
                store.setText("legacy_cleanup_error", "Не удалось снять старые системные ограничения. Перезапусти Фокус; если сообщение осталось, отключи прежние права в настройках Android.");
            } finally { running.set(false); }
        }, "legacy-access-cleanup").start();
    }
    @SuppressWarnings("deprecation")
    private static void release(Context context, Store store) {
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        ComponentName component = new ComponentName(context, GuardAdminReceiver.class);
        if (manager == null) throw new IllegalStateException("System policy service unavailable");
        if (manager.isDeviceOwnerApp(context.getPackageName())) {
            Set<String> remaining = store.set("suspended");
            for (String pkg : new HashSet<>(remaining)) {
                if (manager.setPackagesSuspended(component, new String[]{pkg}, false).length == 0) remaining.remove(pkg);
            }
            store.saveSet("suspended", remaining);
            if (!remaining.isEmpty()) throw new IllegalStateException("Old restrictions remain");
            manager.setUninstallBlocked(component, context.getPackageName(), false);
            manager.clearDeviceOwnerApp(context.getPackageName());
            if (manager.isDeviceOwnerApp(context.getPackageName())) throw new IllegalStateException("Old rights remain");
        }
        if (manager.isAdminActive(component)) manager.removeActiveAdmin(component);
        // Removal is asynchronous; retain the compatibility receiver until confirmed.
        for (int attempt = 0; manager.isAdminActive(component) && attempt < 10; attempt++) {
            try { Thread.sleep(300); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        if (manager.isAdminActive(component)) throw new IllegalStateException("Old rights remain");
        context.getPackageManager().setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        store.prefs.edit().remove("owner_protection").remove("owner_error").remove("suspended")
            .remove("legacy_cleanup_error").putBoolean("legacy_cleanup_complete", true).commit();
    }
}
