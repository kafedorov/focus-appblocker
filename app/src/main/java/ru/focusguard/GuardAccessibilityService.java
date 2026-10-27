package ru.focusguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import ru.focusguard.core.DomainRules;
import ru.focusguard.core.BrowserAddress;
import ru.focusguard.core.BrowserPage;
import ru.focusguard.core.SessionGate;
import ru.focusguard.core.UninstallGuard;

public final class GuardAccessibilityService extends AccessibilityService {
    public static volatile boolean connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private WindowManager windows;
    private View overlay;
    private View overlayLayout;
    private TextView overlayDetail, overlayTitle;
    private Button sessionStart;
    private SessionGate.Access pendingOffer;
    private record Decision(String reason, SessionGate.Access offer) {}
    private String overlayPackage = "", overlayReason = "";
    private String rulesText = "";
    private DomainRules rules = new DomainRules("");
    private final java.util.Map<String, BrowserPage> lastBrowserPages = new java.util.HashMap<>();
    private boolean checkPending;
    private int urgentRetriesPending, dismissedRemovalWindow = -1;
    private final Runnable check = () -> { checkPending = false; inspect(); };
    private final Runnable urgentRetry = () -> {
        urgentRetriesPending--;
        if (store != null && removalProtectionEnabled()) inspectRemoval(applicationRoot());
    };
    private final Runnable ticker = new Runnable() { @Override public void run() { inspect(); handler.postDelayed(this, 1000); } };
    private static final Set<String> BROWSERS = new java.util.HashSet<>(java.util.Arrays.asList("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta", "org.mozilla.firefox", "org.mozilla.fenix", "org.mozilla.focus", "com.microsoft.emmx",
        "com.brave.browser", "com.yandex.browser", "com.yandex.browser.beta", "com.yandex.browser.alpha", "com.opera.browser", "com.opera.mini.native"));

    @Override protected void onServiceConnected() {
        store = new Store(this); windows = getSystemService(WindowManager.class); connected = true;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.notificationTimeout = 0;
            info.eventTypes |= AccessibilityEvent.TYPE_VIEW_CLICKED;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        prepareOverlay();
        LegacyAccessCleanup.start(this);
        AdultBlocklist.warmUp(this);
        handler.removeCallbacks(ticker); handler.post(ticker);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null) return;
        CharSequence packageName = event.getPackageName();
        boolean systemEvent = packageName != null && UninstallGuard.systemPackage(packageName.toString());
        if ((systemEvent || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) && removalProtectionEnabled()) {
            // No debounce: check the system window before app/catalog work.
            if (inspectRemoval(applicationRoot())) return;
            // The tree may arrive just after a window event. Later events never postpone retries.
            if (urgentRetriesPending == 0) {
                urgentRetriesPending = 2;
                handler.postDelayed(urgentRetry, 24);
                handler.postDelayed(urgentRetry, 80);
            }
        }
        if (!checkPending) { checkPending = true; handler.post(check); }
    }
    @Override public void onInterrupt() { hide(); }
    @Override public void onDestroy() {
        connected = false; handler.removeCallbacksAndMessages(null); hide(); super.onDestroy();
    }
    private AccessibilityNodeInfo applicationRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && (overlay == null || active.getPackageName() != null && !active.getPackageName().toString().equals(getPackageName()))) return active;
        List<AccessibilityWindowInfo> list = getWindows();
        AccessibilityNodeInfo underlying = null; int layer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo window : list) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                AccessibilityNodeInfo root = window.getRoot(); if (root == null) continue;
                if (window.isActive() || window.isFocused()) return root;
                // Our focusable overlay can take both focus flags from the underlying app.
                if (window.getLayer() > layer) { layer = window.getLayer(); underlying = root; }
            }
        }
        return underlying != null ? underlying : active;
    }
    private void inspect() {
        if (store == null) return;
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (!store.bool("enabled", true) || !power.isInteractive() || keyguard.isKeyguardLocked()) { hide(); return; }
        AccessibilityNodeInfo root = applicationRoot();
        if (root == null || root.getPackageName() == null) return;
        String pkg = root.getPackageName().toString();
        if (pkg.equals(getPackageName())) { hide(); return; }
        if (removalProtectionEnabled() && inspectRemoval(root)) return;
        renderDecision(pkg, assess(root));
    }
    private Decision assess(AccessibilityNodeInfo root) {
        String pkg = root.getPackageName().toString();
        String reason = AppCatalog.reason(this, store, pkg);
        String group = AppCatalog.group(pkg);
        String sessionKey = AppCatalog.isLangame(pkg) && store.bool("langame_weekly", true) ? "langame" : null;
        boolean youtube = pkg.equals("com.google.android.youtube");
        if (reason == null && pkg.equals("com.google.android.youtube")) {
            if (store.bool("youtube_all", false)) reason = "YouTube целиком заблокирован";
            else if (store.bool("shorts", true) && hasShorts(root)) reason = "Shorts отключены. Можно вернуться к обычным видео.";
        }
        if (reason == null && BROWSERS.contains(pkg)) {
            BrowserPage page = address(pkg, root);
            if (page != null) {
                String host = page.host();
                String source = store.text("rules", Store.DEFAULT_RULES);
                if (!source.equals(rulesText)) { try { rules = new DomainRules(source); rulesText = source; } catch (IllegalArgumentException ignored) { return new Decision("Исправь некорректную маску домена в Фокусе.", null); } }
                if (rules.blocks(host)) reason = "Этот сайт запрещён правилами доменов";
                if (store.bool("adult_local", true) && AdultBlocklist.blocks(host)) reason = "Этот сайт включён в локальную базу взрослых сайтов";
                if (reason == null && store.bool("youtube_all", false) && page.youtube()) reason = "YouTube целиком заблокирован";
                if (reason == null && page.youtube()) {
                    BrowserPage.ShortsDecision decision = page.shortsDecision(store.bool("shorts", true), hasShorts(root), store.bool("shorts_hidden_url", true));
                    if (decision == BrowserPage.ShortsDecision.BLOCK_SHORTS) reason = "YouTube Shorts отключены";
                    else if (decision == BrowserPage.ShortsDecision.BLOCK_HIDDEN_ADDRESS)
                        reason = "Браузер показывает только домен YouTube. Строгий режим закрывает такую страницу: отличить Shorts от обычного видео по этому адресу нельзя. Открой полную версию браузера; если путь всё ещё скрыт, режим также может её закрыть.";
                }
                if (host.equals("web.telegram.org")) group = "telegram";
                if (host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru") || host.endsWith(".vk.ru")) group = "vk";
                youtube = page.youtube();
            }
        }
        if (reason != null) return new Decision(reason, null);
        if (youtube && store.bool("youtube_daily", true)) sessionKey = "youtube";
        if (group != null && store.bool("schedule", true)) sessionKey = group;
        if (sessionKey != null) {
            SessionGate.Access access = store.access(sessionKey);
            if (access.status() == SessionGate.Status.BLOCKED) {
                String blocked = store.sessionSaveFailed(access) ? "Не удалось сохранить сеанс. Проверь свободное место и перезапусти Фокус."
                    : sessionKey.equals("langame") ? "LANGAME доступен только в субботу, один сеанс на 10 минут. Следующий доступ: " + nextWindow(access) + "."
                    : "Сеанс «" + sessionName(sessionKey) + "» завершён. Следующее окно доступа: " + nextWindow(access) + ".";
                return new Decision(blocked, null);
            }
            if (access.status() == SessionGate.Status.AVAILABLE) return new Decision(null, access);
        }
        return new Decision(null, null);
    }
    private void renderDecision(String pkg, Decision decision) {
        if (decision.reason() != null) show(pkg, decision.reason());
        else if (decision.offer() != null) showOffer(pkg, decision.offer());
        else hide();
    }
    private String sessionName(String key) {
        return switch (key) { case "vk" -> "ВК"; case "telegram" -> "Telegram"; case "youtube" -> "YouTube"; default -> "LANGAME"; };
    }
    private String nextWindow(SessionGate.Access access) {
        String date = access.nextWindow().toLocalDate().equals(LocalDate.now()) ? "сегодня"
            : access.nextWindow().toLocalDate().equals(LocalDate.now().plusDays(1)) ? "завтра"
            : access.nextWindow().format(DateTimeFormatter.ofPattern("dd.MM"));
        return date + " в " + access.nextWindow().format(DateTimeFormatter.ofPattern("HH:mm"));
    }
    private String duration(long millis) {
        long seconds = Math.max(1, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private void showOffer(String pkg, SessionGate.Access offer) {
        String reason = "После нажатия начнётся непрерывный сеанс: " + duration(offer.remainingMillis())
            + " (мин:сек). Выход из приложения не останавливает таймер.\n\nСледующее окно доступа: " + nextWindow(offer) + "."
            + (offer.key().equals("vk") || offer.key().equals("telegram") ? " Вне заданного расписания доступ свободный." : "")
            + (offer.key().equals("youtube") ? " Лимит общий для приложения YouTube и его сайта. Shorts остаются запрещены." : "");
        show(pkg, reason);
        pendingOffer = offer;
        overlayTitle.setText("Открыть " + sessionName(offer.key()) + "?");
        sessionStart.setText("Начать сеанс · " + duration(offer.remainingMillis()));
        sessionStart.setVisibility(View.VISIBLE);
    }
    private void confirmPendingSession() {
        SessionGate.Access offered = pendingOffer;
        if (offered == null) return;
        if (!store.bool("enabled", true) || !getSystemService(PowerManager.class).isInteractive()
            || getSystemService(KeyguardManager.class).isKeyguardLocked()) { hide(); return; }
        AccessibilityNodeInfo root = applicationRoot();
        if (root == null || root.getPackageName() == null || !root.getPackageName().toString().equals(overlayPackage)) { hide(); inspect(); return; }
        Decision current = assess(root);
        if (!SessionGate.canConfirm(offered, current.offer())) { renderDecision(overlayPackage, current); return; }
        sessionStart.setEnabled(false);
        boolean started = store.confirmSession(offered);
        sessionStart.setEnabled(true);
        if (started) hide();
        inspect();
    }
    private boolean hasShorts(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo node : nodes(root)) {
            if (!node.isVisibleToUser()) continue;
            if (BrowserPage.shortsPlayerId(node.getViewIdResourceName())) return true;
            if (BrowserPage.selectedShortsLabel(node.isSelected(), node.getContentDescription())
                || BrowserPage.selectedShortsLabel(node.isSelected(), node.getText())) return true;
        }
        return false;
    }
    private record BrowserNode(AccessibilityNodeInfo node, boolean toolbar, boolean tabList) {}
    private BrowserPage address(String pkg, AccessibilityNodeInfo root) {
        BrowserPage best = null;
        boolean documentVisible = false, editing = false, toolbarVisible = false, tabsVisible = false;
        Rect screen = new Rect(); root.getBoundsInScreen(screen);
        ArrayDeque<BrowserNode> queue = new ArrayDeque<>(); queue.add(new BrowserNode(root, false, false));
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 800) {
            BrowserNode entry = queue.remove(); AccessibilityNodeInfo node = entry.node();
            String clazz = node.getClassName() == null ? "" : node.getClassName().toString();
            String id = node.getViewIdResourceName();
            // Skipping the entire document avoids confusing page links/text with the address bar
            // and avoids exhausting the node budget before reaching a bottom toolbar.
            if (BrowserAddress.documentRoot(clazz, id)) { documentVisible |= node.isVisibleToUser(); continue; }
            boolean toolbar = entry.toolbar() || BrowserAddress.toolbarId(id);
            boolean tabList = entry.tabList() || BrowserAddress.tabListId(id);
            if (node.isVisibleToUser()) {
                editing |= node.isEditable() && node.isFocused();
                toolbarVisible |= BrowserAddress.toolbarId(id);
                tabsVisible |= tabList;
            }
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            BrowserAddress.Element element = new BrowserAddress.Element(id, clazz,
                node.getText() == null ? null : node.getText().toString(),
                node.getContentDescription() == null ? null : node.getContentDescription().toString(),
                node.isVisibleToUser(), node.isPassword() || (node.isEditable() && node.isFocused()), false, toolbar, tabList,
                bounds.left, bounds.top, bounds.right, bounds.bottom);
            best = BrowserAddress.prefer(best, BrowserAddress.read(pkg, element, screen.left, screen.top, screen.right, screen.bottom));
            for (int i = 0; i < node.getChildCount() && queue.size() < 800; i++) {
                AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(new BrowserNode(child, toolbar, tabList));
            }
        }
        if (editing) return null;
        if (best != null) { lastBrowserPages.put(pkg, best); return best; }
        return BrowserAddress.fullscreenContext(lastBrowserPages.get(pkg), documentVisible, false, toolbarVisible, tabsVisible);
    }
    private String shortId(String id) { return id == null ? "" : id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT); }
    private boolean removalProtectionEnabled() {
        return store.bool("enabled", true) && store.bool("uninstall_guard", false) && store.locked()
            && getSystemService(PowerManager.class).isInteractive() && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private boolean inspectRemoval(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        if (!UninstallGuard.systemPackage(pkg)) return false;
        UninstallGuard.Scan scan = new UninstallGuard.Scan(pkg);
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 800) {
            AccessibilityNodeInfo node = queue.remove();
            if (node.isVisibleToUser() && !node.isPassword() && !node.isEditable()) {
                String id = shortId(node.getViewIdResourceName());
                CharSequence clazz = node.getClassName();
                boolean action = node.isEnabled() && (node.isClickable() || id.equals("button1") || id.equals("button2")
                    || id.equals("button3") || (clazz != null && clazz.toString().endsWith("Button")));
                if (node.getText() != null) scan.add(new UninstallGuard.Label(node.getText().toString(), action));
                if (node.getContentDescription() != null) scan.add(new UninstallGuard.Label(node.getContentDescription().toString(), action));
                UninstallGuard.Decision decision = scan.decision();
                if (decision != UninstallGuard.Decision.ALLOW) {
                    // Close the confirmation immediately as well as covering it.
                    if (decision == UninstallGuard.Decision.UNINSTALL && root.getWindowId() != dismissedRemovalWindow) {
                        dismissedRemovalWindow = root.getWindowId();
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                    show(pkg, "Удаление Фокуса перекрыто, пока правила зафиксированы. Чтобы открыть его страницу в настройках Android, сначала сними фиксацию в Фокусе по коду или запроси разблокировку через 24 часа.");
                    return true;
                }
            }
            for (int i = 0; i < node.getChildCount() && queue.size() < 800; i++) {
                AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child);
            }
        }
        return false;
    }
    private List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root) {
        java.util.ArrayList<AccessibilityNodeInfo> result = new java.util.ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty() && result.size() < 800) {
            AccessibilityNodeInfo node = queue.remove(); result.add(node);
            for (int i = 0; i < node.getChildCount() && queue.size() < 800; i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child); }
        }
        return result;
    }
    private void prepareOverlay() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER); layout.setPadding(36, 48, 36, 48); layout.setBackgroundColor(Color.rgb(244, 246, 242));
        TextView title = new TextView(this); title.setText("Пора вернуться к своему дню"); title.setTextSize(27); title.setTextColor(Color.rgb(23, 55, 47)); title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER); layout.addView(title);
        TextView detail = new TextView(this); detail.setTextSize(18); detail.setGravity(Gravity.CENTER); detail.setPadding(0, 28, 0, 40); layout.addView(detail);
        sessionStart = new Button(this); sessionStart.setVisibility(View.GONE); sessionStart.setOnClickListener(v -> confirmPendingSession()); layout.addView(sessionStart);
        Button home = new Button(this); home.setText("На главный экран"); home.setOnClickListener(v -> { performGlobalAction(GLOBAL_ACTION_HOME); hide(); }); layout.addView(home);
        Button settings = new Button(this); settings.setText("Открыть Фокус"); settings.setOnClickListener(v -> {
            performGlobalAction(GLOBAL_ACTION_HOME); hide();
            startActivity(new android.content.Intent(this, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }); layout.addView(settings);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(layout);
        overlayLayout = scroll; overlayDetail = detail; overlayTitle = title;
    }
    private void show(String pkg, String reason) {
        if (overlay != null && pkg.equals(overlayPackage) && reason.equals(overlayReason)) return;
        overlayPackage = pkg; overlayReason = reason;
        pendingOffer = null; sessionStart.setVisibility(View.GONE); overlayTitle.setText("Пора вернуться к своему дню");
        overlayDetail.setText(reason);
        if (overlay != null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE);
        params.gravity = Gravity.CENTER;
        try { windows.addView(overlayLayout, params); overlay = overlayLayout; }
        catch (RuntimeException e) { performGlobalAction(GLOBAL_ACTION_HOME); }
    }
    private void hide() {
        if (overlay != null && windows != null) { try { windows.removeViewImmediate(overlay); } catch (RuntimeException ignored) {} }
        overlay = null; overlayPackage = ""; overlayReason = ""; dismissedRemovalWindow = -1; pendingOffer = null;
    }
}
