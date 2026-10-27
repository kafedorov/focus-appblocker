package ru.focusguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import ru.focusguard.core.DnsPacket;
import ru.focusguard.core.DomainRules;

public final class DnsVpnService extends VpnService {
    public static volatile boolean running;
    private ParcelFileDescriptor tunnel;
    private Thread reader;
    private ThreadPoolExecutor pool;
    private Store store;
    private volatile boolean stopping;
    private String rulesText = "";
    private DomainRules rules = new DomainRules("");
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        store = new Store(this);
        AdultBlocklist.warmUp(this);
        if (intent != null && "STOP".equals(intent.getAction())) { store.setBool("vpn_wanted", false); stopSelf(); return START_NOT_STICKY; }
        if (!store.bool("enabled", true)) { stopSelf(); return START_NOT_STICKY; }
        notifyForeground();
        if (reader == null) {
            stopping = false;
            pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.DiscardPolicy());
            reader = new Thread(this::runTunnel, "focus-dns"); reader.start();
        }
        return START_STICKY;
    }
    private void notifyForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("dns", "DNS-фильтр", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "dns").setSmallIcon(R.drawable.ic_shield).setContentTitle("Фокус · DNS-фильтр")
            .setContentText("Фильтрация доменов активна. Другой VPN несовместим.").setContentIntent(open).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
    }
    private void runTunnel() {
        try {
            // Only this synthetic DNS address enters TUN. IPv6 and normal traffic bypass it.
            tunnel = new Builder().setSession("Фокус — DNS").setMtu(1500).addAddress("10.111.0.1", 32)
                .addRoute("10.111.0.2", 32).addDnsServer("10.111.0.2").allowFamily(OsConstants.AF_INET6)
                .setBlocking(true).setConfigureIntent(PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).establish();
            if (tunnel == null) throw new IOException("Не выдано разрешение VPN");
            running = true; store.setText("vpn_error", ""); store.setBool("vpn_wanted", true);
            FileInputStream input = new FileInputStream(tunnel.getFileDescriptor());
            FileOutputStream output = new FileOutputStream(tunnel.getFileDescriptor());
            byte[] buffer = new byte[32767];
            while (!stopping) {
                int count = input.read(buffer); if (count < 0) break;
                DnsPacket.Query query = DnsPacket.parse(buffer, count); if (query == null) continue;
                pool.execute(() -> resolve(query, output));
            }
        } catch (Exception e) {
            if (!stopping) store.setText("vpn_error", "DNS-фильтр остановлен: " + e.getClass().getSimpleName());
        } finally { running = false; stopSelf(); }
    }
    private synchronized boolean blocked(String host) {
        String source = store.text("rules", Store.DEFAULT_RULES);
        if (!source.equals(rulesText)) { rules = new DomainRules(source); rulesText = source; }
        return store.bool("enabled", true) && (rules.blocks(host) || (store.bool("adult_local", true) && AdultBlocklist.blocks(host)));
    }
    private void resolve(DnsPacket.Query query, FileOutputStream output) {
        byte[] answer;
        try {
            if (blocked(query.host())) answer = DnsPacket.error(query.dns(), 3);
            else answer = upstream(query.dns());
        } catch (Exception e) { answer = DnsPacket.error(query.dns(), 2); store.setText("dns_warning", "DNS-сервер недоступен. Проверьте сеть."); }
        byte[] packet = DnsPacket.reply(query, answer);
        synchronized (output) { try { if (!stopping) output.write(packet); } catch (IOException ignored) {} }
    }
    private byte[] upstream(byte[] query) throws IOException {
        // DNS only. Protected UDP sockets never loop back into our own virtual DNS interface.
        String[] servers = store.bool("adult_dns", true) ? new String[]{"1.1.1.3", "1.0.0.3"} : new String[]{"1.1.1.1", "1.0.0.1"};
        for (String server : servers) {
            try (DatagramSocket socket = new DatagramSocket()) {
                if (!protect(socket)) throw new IOException("protect failed");
                socket.connect(InetAddress.getByName(server), 53); socket.setSoTimeout(2500);
                socket.send(new DatagramPacket(query, query.length));
                byte[] response = new byte[4096]; DatagramPacket packet = new DatagramPacket(response, response.length);
                socket.receive(packet); byte[] answer = Arrays.copyOf(response, packet.getLength());
                if (!DnsPacket.matches(query, answer)) throw new IOException("Unexpected DNS response");
                if (answer.length > 1400 || (answer[2] & 2) != 0) throw new IOException("DNS TCP fallback not supported");
                store.setText("dns_warning", ""); return answer;
            } catch (IOException e) { /* Try the second resolver; never silently bypass filtering. */ }
        }
        throw new IOException("DNS unavailable");
    }
    @Override public void onRevoke() {
        running = false;
        if (store != null) { store.setBool("vpn_wanted", false); store.setText("vpn_error", "DNS-фильтр отключён системой или другим VPN"); }
        stopSelf();
    }
    @Override public void onDestroy() {
        stopping = true; running = false;
        if (tunnel != null) { try { tunnel.close(); } catch (IOException ignored) {} tunnel = null; }
        if (pool != null) pool.shutdownNow();
        if (reader != null) reader.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
