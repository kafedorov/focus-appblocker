package ru.focusguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        Store store = new Store(context);
        try {
            android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
            boolean vpnActive = false;
            for (android.net.Network network : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) vpnActive = true;
            }
            if (!vpnActive && store.bool("vpn_wanted", false) && store.bool("enabled", true) && android.net.VpnService.prepare(context) == null)
                context.startForegroundService(new Intent(context, DnsVpnService.class));
        } catch (RuntimeException e) { store.setText("vpn_error", "После перезагрузки откройте Фокус для запуска защиты"); }
    }
}
