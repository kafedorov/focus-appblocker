package ru.focusguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Browser;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import ru.focusguard.core.DomainRules;
import ru.focusguard.core.BudgetPolicy;
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
    private Button sessionStart, browserLeave, browserNewTab, youtubeHome;
    private SeekBar sessionLength;
    private long shortsExitUntil;
    private boolean shortsBackPending;
    private final Runnable deadlineCheck = this::inspect;
    private final Runnable settledWindow = this::inspect;
    private final Runnable retryRoot = () -> inspect(false);
    private boolean listening;
    private static final String YOUTUBE = "com.google.android.youtube";
    private static final String SHORTS_BLOCK = "YouTube Shorts отключены.";
    private static final String REMOVAL_BLOCK = "Системные настройки Фокуса защищены. Для доступа открой Фокус и сними фиксацию по коду восстановления.";
    private static final long REMOVAL_CHECK_INTERVAL_MS = 1000;
    private int dismissedSettingsDialogWindow = -1;
    private long settingsOverlayUntil;
    private final Runnable checkSettingsTransition = this::inspect;
    private final SharedPreferences.OnSharedPreferenceChangeListener removalSettingsChanged = (prefs, key) -> {
        if ("locked".equals(key) || "uninstall_guard".equals(key) || "enabled".equals(key)) {
            removalSettingsUpdated();
        }
    };
    private final Runnable removalCheck = new Runnable() {
        @Override public void run() {
            if (!connected || !removalProtectionEnabled()) return;
            AccessibilityNodeInfo root = applicationRoot();
            if (!inspectRemoval(root) && root != null && REMOVAL_BLOCK.equals(overlayReason)) inspect();
            handler.postDelayed(this, REMOVAL_CHECK_INTERVAL_MS);
        }
    };
    private final BroadcastReceiver deviceEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handler.removeCallbacks(deadlineCheck);
                handler.removeCallbacks(removalCheck);
                clearSettingsTransition();
                hide();
                return;
            }
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) store.recordMorningUnlock();
            refreshRemovalChecks();
            inspect();
        }
    };
    private boolean browserPageOverlay;
    private BrowserExit browserExit;
    private final Runnable checkBrowserExit = this::inspect;
    private enum ExitStage { FOCUS, WRITE, SUBMIT, VERIFY, CLOSE, NEW_TAB, FAILED }
    private record BrowserExit(String pkg, int windowId, BrowserPage page, long deadline, ExitStage stage, boolean submitted) {
        BrowserExit at(ExitStage next) { return new BrowserExit(pkg, windowId, page, deadline, next, submitted || next == ExitStage.VERIFY); }
    }
    private SessionGate.Access pendingOffer;
    private record Decision(String reason, SessionGate.Access offer, boolean browserPage, long checkAfter) {
        Decision(String reason, SessionGate.Access offer, boolean browserPage) { this(reason, offer, browserPage, 0); }
    }
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
    private static final Set<String> BROWSERS = new java.util.HashSet<>(java.util.Arrays.asList("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta", "org.mozilla.firefox", "org.mozilla.fenix", "org.mozilla.focus", "com.microsoft.emmx",
        "com.brave.browser", "com.yandex.browser", "com.yandex.browser.beta", "com.yandex.browser.alpha", "com.opera.browser", "com.opera.mini.native"));

    @Override protected void onServiceConnected() {
        if (store != null) store.prefs.unregisterOnSharedPreferenceChangeListener(removalSettingsChanged);
        store = new Store(this); windows = getSystemService(WindowManager.class); connected = true;
        store.prefs.registerOnSharedPreferenceChangeListener(removalSettingsChanged);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.notificationTimeout = 0;
            info.eventTypes |= AccessibilityEvent.TYPE_VIEW_CLICKED;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        prepareOverlay();
        LegacyAccessCleanup.start(this);
        AdultBlocklist.warmUp(this, () -> handler.post(check));
        if (!listening) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(deviceEvents, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(deviceEvents, filter);
            listening = true;
        }
        refreshRemovalChecks();
        handler.post(check);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        int type = event.getEventType();
        boolean windowChanged = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        boolean systemEvent = UninstallGuard.systemPackage(pkg);
        if ((systemEvent || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || windowChanged && pkg.isEmpty())
            && removalProtectionEnabled()) {
            boolean settingsDialog = UninstallGuard.settingsPackage(pkg) && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && protectSettingsDialog(event);
            boolean settingsInteraction = settingsDialog || UninstallGuard.settingsPackage(pkg)
                && type == AccessibilityEvent.TYPE_VIEW_CLICKED && protectSettingsClick(event);
            if ((windowChanged || settingsInteraction) && urgentRetriesPending == 0) {
                urgentRetriesPending = 2;
                handler.postDelayed(urgentRetry, 24);
                handler.postDelayed(urgentRetry, 80);
            }
            AccessibilityNodeInfo active = applicationRoot();
            if (windowChanged && (active == null || active.getWindowId() != event.getWindowId())
                && inspectRemovalEventWindow(event.getWindowId())) return;
            boolean settingsDialogEvent = windowChanged && UninstallGuard.settingsPackage(pkg)
                && isDialogClass(event.getClassName());
            if (inspectRemoval(active, settingsDialog || settingsDialogEvent)) return;
        }
        if (windowChanged) {
            handler.removeCallbacks(check);
            checkPending = true;
            handler.post(check);
            handler.removeCallbacks(settledWindow);
            handler.postDelayed(settledWindow, 180);
        } else if (!checkPending && (systemEvent || pkg.isEmpty() || YOUTUBE.equals(pkg) || BROWSERS.contains(pkg))) {
            // Frequent content changes share one check; later events do not postpone it.
            checkPending = true;
            handler.postDelayed(check, 120);
        }
    }
    @Override public void onInterrupt() { handler.removeCallbacks(deadlineCheck); clearSettingsTransition(); hide(); }
    @Override public void onDestroy() {
        connected = false;
        if (listening) unregisterReceiver(deviceEvents);
        if (store != null) store.prefs.unregisterOnSharedPreferenceChangeListener(removalSettingsChanged);
        handler.removeCallbacksAndMessages(null);
        hide();
        super.onDestroy();
    }
    private AccessibilityNodeInfo applicationRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (browserExit == null && active != null && (overlay == null
            || active.getPackageName() != null && !active.getPackageName().toString().equals(getPackageName()))) return active;
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
    private void inspect() { inspect(true); }
    private void inspect(boolean retryMissingRoot) {
        if (!connected || store == null) return;
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (!store.bool("enabled", true) || !power.isInteractive() || keyguard.isKeyguardLocked()) {
            handler.removeCallbacks(deadlineCheck);
            clearSettingsTransition();
            hide();
            return;
        }
        AccessibilityNodeInfo root = applicationRoot();
        if (root == null || root.getPackageName() == null) {
            if (retryMissingRoot) {
                handler.removeCallbacks(retryRoot);
                handler.postDelayed(retryRoot, 120);
            }
            return;
        }
        handler.removeCallbacks(retryRoot);
        String pkg = root.getPackageName().toString();
        handler.removeCallbacks(deadlineCheck);
        if (!UninstallGuard.settingsPackage(pkg)) clearSettingsTransition();
        if (pkg.equals(getPackageName())) { hide(); return; }
        if (UninstallGuard.systemPackage(pkg) && removalProtectionEnabled() && inspectRemoval(root)) return;
        long settling = shortsExitUntil - SystemClock.elapsedRealtime();
        if (YOUTUBE.equals(pkg) && settling > 0) {
            handler.postDelayed(deadlineCheck, settling);
            return;
        }
        if (shortsExitUntil != 0) {
            if (YOUTUBE.equals(pkg) && shortsBackPending && hasShorts(root)) {
                shortsBackPending = false;
                setOverlayFocusable(false);
                performGlobalAction(GLOBAL_ACTION_BACK);
                checkAfterShortsExit();
                return;
            }
            shortsExitUntil = 0;
            shortsBackPending = false;
            setOverlayFocusable(true);
        }
        BrowserUi browser = BROWSERS.contains(pkg) ? browserUi(pkg, root) : null;
        if (browserExit != null && continueBrowserExit(pkg, root, browser)) return;
        Decision decision = assess(root, browser == null ? null : browser.page());
        renderDecision(pkg, decision);
        if (decision.checkAfter() > 0) handler.postDelayed(deadlineCheck, decision.checkAfter());
    }
    private Decision assess(AccessibilityNodeInfo root, BrowserPage page) {
        String pkg = root.getPackageName().toString();
        String reason = AppCatalog.reason(this, store, pkg);
        boolean browserPage = reason == null && page != null;
        String group = AppCatalog.group(pkg);
        String sessionKey = AppCatalog.isLangame(pkg) && store.bool("langame_weekly", true) ? "langame" : null;
        boolean youtube = YOUTUBE.equals(pkg);
        if (reason == null && youtube) {
            if (store.bool("youtube_all", false)) reason = "YouTube целиком заблокирован";
            else if (store.bool("shorts", true) && hasShorts(root)) reason = SHORTS_BLOCK;
        }
        if (browserPage) {
            String host = page.host();
            String source = store.text("rules", Store.DEFAULT_RULES);
            if (!source.equals(rulesText)) { try { rules = new DomainRules(source); rulesText = source; } catch (IllegalArgumentException ignored) { return new Decision("Исправь некорректную маску домена в Фокусе.", null, true); } }
            if (rules.blocks(host)) reason = "Этот сайт запрещён правилами доменов";
            if (store.bool("adult_local", true) && AdultBlocklist.blocks(host)) reason = "Этот сайт включён в локальную базу взрослых сайтов";
            if (reason == null && page.youtube() && (store.bool("youtube_all", false) || store.bool("youtube_web", true)))
                reason = "YouTube в браузере полностью заблокирован";
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
        if (reason != null) return new Decision(reason, null, browserPage);
        String target = youtube ? "youtube" : group;
        LocalDateTime morning = target == null ? null : store.morningEnd();
        if (youtube && !store.bool("youtube_daily", true)) {
            LocalDateTime opens = LocalDate.now().atTime(8, 0);
            if (LocalDateTime.now().isBefore(opens)) {
                if (morning != null && morning.isAfter(opens)) opens = morning;
                return new Decision("YouTube закрыт до " + nextWindow(opens) + ".", null, browserPage, until(opens));
            }
        }
        if (youtube && store.bool("youtube_daily", true)) sessionKey = target;
        if (group != null && store.bool("schedule", true)) sessionKey = group;
        if (sessionKey == null) {
            return morning == null ? new Decision(null, null, false, youtube ? until(LocalDate.now().plusDays(1).atStartOfDay()) : 0)
                : new Decision("Утренний режим. Доступ " + nextWindow(morning) + ".", null, browserPage, until(morning));
        }
        SessionGate.Access access = store.access(sessionKey);
        return switch (access.status()) {
            case ACTIVE -> new Decision(null, null, false, access.remainingMillis());
            case AVAILABLE -> new Decision(null, access, false, offerCheckAfter(access));
            case BLOCKED -> {
                String blocked = store.sessionSaveFailed(access) ? "Не удалось сохранить сеанс. Проверь свободное место и перезапусти Фокус."
                    : (morning != null ? "Утренний режим. " : "") + "Следующий доступ к «" + sessionName(sessionKey) + "» " + nextWindow(access.nextWindow()) + ".";
                yield new Decision(blocked, null, browserPage, until(access.nextWindow()));
            }
            case FREE -> new Decision(null, null, false);
        };
    }
    private long until(LocalDateTime time) {
        return Math.max(1, Duration.between(LocalDateTime.now(), time).toMillis() + 1);
    }
    private long offerCheckAfter(SessionGate.Access offer) {
        if (offer.key().equals("langame")) {
            LocalDate friday = offer.nextWindow().toLocalDate().minusDays(3);
            return until(friday.atStartOfDay());
        }
        if (offer.remainingMillis() < BudgetPolicy.MINUTE) return offer.remainingMillis() + 1;
        long toWindowEnd = offer.key().equals("telegram") ? until(offer.nextWindow())
            : until(LocalDate.now().atTime(offer.key().equals("vk") ? 20 : 0, 0).plusDays(offer.key().equals("vk") ? 0 : 1));
        return Math.max(1, toWindowEnd - (offer.remainingMillis() / BudgetPolicy.MINUTE) * BudgetPolicy.MINUTE + 2);
    }
    private void renderDecision(String pkg, Decision decision) {
        if (decision.reason() != null) show(pkg, decision.reason(), decision.browserPage());
        else if (decision.offer() != null) showOffer(pkg, decision.offer());
        else hide();
    }
    private String sessionName(String key) {
        return switch (key) { case "vk" -> "ВК"; case "telegram" -> "Telegram"; case "youtube" -> "YouTube"; default -> "LANGAME"; };
    }
    private String nextWindow(LocalDateTime time) {
        String date = time.toLocalDate().equals(LocalDate.now()) ? "сегодня"
            : time.toLocalDate().equals(LocalDate.now().plusDays(1)) ? "завтра"
            : time.format(DateTimeFormatter.ofPattern("dd.MM"));
        return date + " в " + time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }
    private String duration(long millis) {
        long seconds = Math.max(1, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private void showOffer(String pkg, SessionGate.Access offer) {
        boolean fixed = offer.key().equals("langame");
        boolean same = pkg.equals(overlayPackage) && SessionGate.canConfirm(pendingOffer, offer);
        int selection = same ? sessionLength.getProgress() : 0;
        int minutes = (int) (offer.remainingMillis() / BudgetPolicy.MINUTE);
        String reason = fixed ? "Один непрерывный сеанс. Следующий доступ " + nextWindow(offer.nextWindow()) + "."
            : "Остаток бюджета · " + duration(offer.budgetRemainingMillis()) + "\nВыбери длительность. Выход не останавливает таймер. Если остаток закончится, новый бюджет будет "
                + nextWindow(offer.nextWindow()) + ".";
        show(pkg, reason, false);
        pendingOffer = offer;
        overlayTitle.setText("Открыть " + sessionName(offer.key()) + "?");
        sessionLength.setVisibility(!fixed && minutes > 1 ? View.VISIBLE : View.GONE);
        sessionLength.setMax(Math.max(0, minutes - 1));
        sessionLength.setProgress(selection);
        updateSessionButton();
        sessionStart.setVisibility(View.VISIBLE);
    }
    private long chosenAmount(SessionGate.Access offer) {
        return offer.key().equals("langame") || offer.remainingMillis() < BudgetPolicy.MINUTE
            ? -1 : (sessionLength.getProgress() + 1L) * BudgetPolicy.MINUTE;
    }
    private void updateSessionButton() {
        if (pendingOffer == null) return;
        long amount = chosenAmount(pendingOffer);
        sessionStart.setText(amount > 0 ? "Начать сеанс · " + duration(amount)
            : pendingOffer.key().equals("langame") ? "Начать сеанс · " + duration(pendingOffer.remainingMillis())
            : "Начать сеанс до конца окна");
    }
    private void confirmPendingSession() {
        SessionGate.Access offered = pendingOffer;
        if (offered == null) return;
        if (!store.bool("enabled", true) || !getSystemService(PowerManager.class).isInteractive()
            || getSystemService(KeyguardManager.class).isKeyguardLocked()) { hide(); return; }
        AccessibilityNodeInfo root = applicationRoot();
        if (root == null || root.getPackageName() == null || !root.getPackageName().toString().equals(overlayPackage)) { hide(); inspect(); return; }
        BrowserPage page = BROWSERS.contains(overlayPackage) ? browserUi(overlayPackage, root).page() : null;
        Decision current = assess(root, page);
        if (!SessionGate.canConfirm(offered, current.offer())) { renderDecision(overlayPackage, current); return; }
        sessionStart.setEnabled(false);
        boolean started = store.confirmSession(offered, chosenAmount(offered));
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
    private void leaveYoutubeShorts() {
        if (!YOUTUBE.equals(overlayPackage) || !SHORTS_BLOCK.equals(overlayReason) || shortsExitUntil != 0) return;
        AccessibilityNodeInfo root = applicationRoot();
        if (root == null || root.getPackageName() == null || !YOUTUBE.contentEquals(root.getPackageName()) || !hasShorts(root)) { inspect(); return; }
        shortsBackPending = true;
        for (AccessibilityNodeInfo node : nodes(root)) {
            AccessibilityNodeInfo tab = node.isVisibleToUser() ? youtubeHomeTab(node) : null;
            if (tab != null && tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                checkAfterShortsExit();
                return;
            }
        }
        // The Shorts player sometimes hides the bottom tabs. Send YouTube its own home URL.
        Intent home = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/"))
            .setPackage(YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); }
        catch (ActivityNotFoundException | SecurityException ignored) { /* The settle check can still back out once. */ }
        checkAfterShortsExit();
    }
    private void checkAfterShortsExit() {
        shortsExitUntil = SystemClock.elapsedRealtime() + 350;
        handler.removeCallbacks(deadlineCheck);
        handler.postDelayed(deadlineCheck, 350);
    }
    private AccessibilityNodeInfo youtubeHomeTab(AccessibilityNodeInfo node) {
        String id = shortId(node.getViewIdResourceName());
        boolean namedTab = Set.of("pivot_home", "home_tab", "tab_home", "navigation_home").contains(id);
        if (!namedTab && !homeLabel(node.getText()) && !homeLabel(node.getContentDescription())) return null;
        AccessibilityNodeInfo clickable = null;
        for (int depth = 0; node != null && depth < 5; depth++, node = node.getParent()) {
            String parentId = shortId(node.getViewIdResourceName());
            if (parentId.contains("pivot_bar") || parentId.contains("bottom_navigation")) return clickable;
            if (clickable == null && node.isEnabled() && node.isClickable()) {
                if (namedTab) return node;
                clickable = node;
            }
        }
        return null;
    }
    private boolean homeLabel(CharSequence label) {
        if (label == null) return false;
        String text = label.toString().trim().toLowerCase(Locale.ROOT);
        return text.equals("главная") || text.startsWith("главная,") || text.equals("home") || text.startsWith("home,");
    }
    private record BrowserNode(AccessibilityNodeInfo node, boolean toolbar, boolean tabList) {}
    private record BrowserUi(BrowserPage page, boolean blank, AccessibilityNodeInfo editor,
                             AccessibilityNodeInfo addressButton, AccessibilityNodeInfo customTabClose) {}
    private BrowserUi browserUi(String pkg, AccessibilityNodeInfo root) {
        BrowserPage best = null;
        AccessibilityNodeInfo editor = null, addressButton = null, closeButton = null;
        boolean blank = false, customTab = false;
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
                String localId = BrowserAddress.shortId(id);
                customTab |= localId.startsWith("custom_tabs_");
                if (!tabList && node.isEnabled() && node.isClickable() && localId.equals("close_button")) closeButton = node;
                if (!tabList && node.isEnabled() && node.isEditable() && !node.isPassword() && BrowserAddress.editorId(pkg, id)
                    && (editor == null || node.isFocused())) editor = node;
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
            BrowserPage candidate = BrowserAddress.read(pkg, element, screen.left, screen.top, screen.right, screen.bottom);
            BrowserPage preferred = BrowserAddress.prefer(best, candidate);
            if (preferred != best) {
                best = preferred;
                addressButton = clickableAddress(node);
            } else if (candidate != null && candidate.equals(best) && addressButton == null) {
                addressButton = clickableAddress(node);
            }
            blank |= BrowserAddress.blank(pkg, element, screen.left, screen.top, screen.right, screen.bottom);
            for (int i = 0; i < node.getChildCount() && queue.size() < 800; i++) {
                AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(new BrowserNode(child, toolbar, tabList));
            }
        }
        if (editing) best = null;
        else if (best != null) lastBrowserPages.put(pkg, best);
        else if (blank) lastBrowserPages.remove(pkg);
        else best = BrowserAddress.fullscreenContext(lastBrowserPages.get(pkg), documentVisible, false, toolbarVisible, tabsVisible);
        return new BrowserUi(best, blank && !editing && best == null, editor, addressButton, customTab ? closeButton : null);
    }
    private static AccessibilityNodeInfo clickableAddress(AccessibilityNodeInfo node) {
        if (node.isClickable() && node.isEnabled()) return node;
        AccessibilityNodeInfo parent = node.getParent();
        return parent != null && parent.isClickable() && parent.isEnabled() && BrowserAddress.toolbarId(parent.getViewIdResourceName())
            ? parent : null;
    }
    private void leaveBrowserPage(boolean newTab) {
        if (!browserPageOverlay || overlay == null || browserExit != null && browserExit.stage() != ExitStage.FAILED) return;
        AccessibilityNodeInfo root = applicationRoot();
        if (root == null || root.getPackageName() == null || !overlayPackage.contentEquals(root.getPackageName())) { inspect(); return; }
        BrowserUi browser = browserUi(overlayPackage, root);
        BrowserPage page = browser.page();
        if (browser.blank() && (browserExit == null || browserExit.submitted())) { inspect(); return; }
        if (page == null && browserExit != null && root.getWindowId() == browserExit.windowId()) page = browserExit.page();
        Decision current = assess(root, page);
        if (!current.browserPage() || current.reason() == null) { inspect(); return; }
        browserExit = new BrowserExit(overlayPackage, root.getWindowId(), page, SystemClock.elapsedRealtime() + 2500,
            newTab ? ExitStage.NEW_TAB : ExitStage.FOCUS, false);
        browserLeave.setEnabled(false);
        browserNewTab.setVisibility(View.GONE);
        overlayDetail.setText("Открываю безопасную страницу…");
        if (newTab) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
                .setPackage(overlayPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Browser.EXTRA_CREATE_NEW_TAB, true);
            try { startActivity(intent); }
            catch (ActivityNotFoundException | SecurityException ignored) { failBrowserExit(browser); return; }
        } else {
            // Keep the page covered while the browser receives input focus.
            setOverlayFocusable(false);
        }
        scheduleBrowserExitCheck();
    }
    private boolean continueBrowserExit(String pkg, AccessibilityNodeInfo root, BrowserUi browser) {
        BrowserExit exit = browserExit;
        if (!exit.pkg().equals(pkg) || root.getWindowId() != exit.windowId() && exit.stage() != ExitStage.NEW_TAB
            || browser.blank() && exit.submitted() || browser.page() != null && !browser.page().equals(exit.page())) {
            finishBrowserExit();
            return false;
        }
        if (exit.stage() == ExitStage.FAILED) {
            if (browser.page() == null) return true;
            Decision current = assess(root, browser.page());
            if (current.browserPage() && overlayReason.equals(current.reason())) return true;
            finishBrowserExit();
            return false;
        }
        if (SystemClock.elapsedRealtime() >= exit.deadline()) { failBrowserExit(browser); return true; }
        AccessibilityNodeInfo editor = browser.editor();
        switch (exit.stage()) {
            case FOCUS -> {
                if (browser.customTabClose() != null && browser.customTabClose().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    browserExit = exit.at(ExitStage.CLOSE);
                } else if (editor != null && (editor.isFocused() || editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS))) {
                    browserExit = exit.at(ExitStage.WRITE);
                } else if (browser.addressButton() != null && browser.addressButton().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    browserExit = exit.at(ExitStage.WRITE);
                }
            }
            case WRITE -> {
                if (editor != null && editor.isFocused()) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, BrowserAddress.BLANK_PAGE);
                    if (editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) browserExit = exit.at(ExitStage.SUBMIT);
                }
            }
            case SUBMIT -> {
                if (Build.VERSION.SDK_INT >= 30 && editor != null && editor.isFocused()
                    && BrowserAddress.BLANK_PAGE.contentEquals(editor.getText() == null ? "" : editor.getText())
                    && editor.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                    browserExit = exit.at(ExitStage.VERIFY);
                }
            }
            default -> { }
        }
        scheduleBrowserExitCheck();
        return true;
    }
    private void scheduleBrowserExitCheck() {
        handler.removeCallbacks(checkBrowserExit);
        handler.postDelayed(checkBrowserExit, 100);
    }
    private void failBrowserExit(BrowserUi browser) {
        AccessibilityNodeInfo editor = browser.editor();
        if (editor != null && editor.isFocused()) editor.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
        browserExit = browserExit.at(ExitStage.FAILED);
        setOverlayFocusable(true);
        browserLeave.setEnabled(true);
        browserNewTab.setVisibility(View.VISIBLE);
        overlayDetail.setText(overlayReason + "\n\nНе удалось перейти на пустую страницу. Можно открыть example.org в новой вкладке.");
    }
    private void finishBrowserExit() {
        handler.removeCallbacks(checkBrowserExit);
        browserExit = null;
        setOverlayFocusable(true);
        overlayReason = "";
        browserLeave.setEnabled(true);
        browserNewTab.setVisibility(View.GONE);
    }
    private void setOverlayFocusable(boolean focusable) {
        if (overlay == null) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) overlay.getLayoutParams();
        int flags = focusable ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            : params.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (flags == params.flags) return;
        params.flags = flags;
        try { windows.updateViewLayout(overlay, params); }
        catch (IllegalArgumentException ignored) { }
    }
    private String shortId(String id) { return id == null ? "" : id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT); }
    private void removalSettingsUpdated() {
        refreshRemovalChecks();
        if (!removalProtectionEnabled()) {
            clearSettingsTransition();
            if (REMOVAL_BLOCK.equals(overlayReason)) inspect();
        }
    }
    private void refreshRemovalChecks() {
        handler.removeCallbacks(removalCheck);
        if (connected && removalProtectionEnabled()) handler.post(removalCheck);
    }
    private boolean inspectRemovalEventWindow(int windowId) {
        if (windowId < 0) return false;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getId() == windowId && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                && (window.isActive() || window.isFocused())) {
                return inspectRemoval(window.getRoot());
            }
        }
        return false;
    }
    private boolean removalProtectionEnabled() {
        return store.bool("enabled", true) && store.bool("uninstall_guard", false) && store.locked()
            && getSystemService(PowerManager.class).isInteractive() && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private boolean protectSettingsClick(AccessibilityEvent event) {
        if (!eventLabel(event, UninstallGuard::focusAppName) && !eventLabel(event, UninstallGuard::focusServiceName)
            && !eventLabel(event, UninstallGuard::forceStopControl)) return false;
        protectSettingsTransition(SystemClock.elapsedRealtime());
        return true;
    }
    private boolean eventLabel(AccessibilityEvent event, Predicate<CharSequence> matches) {
        return matches.test(event.getContentDescription()) || event.getText().stream().anyMatch(matches);
    }
    private boolean protectSettingsDialog(AccessibilityEvent event) {
        long now = SystemClock.elapsedRealtime();
        UninstallGuard.Scan scan = new UninstallGuard.Scan(event.getPackageName().toString());
        for (CharSequence label : event.getText()) {
            if (label != null) scan.add(new UninstallGuard.Label(label.toString(), false));
        }
        boolean dialog = isDialogClass(event.getClassName());
        if (dialog) scan.markDialogWindow();
        if (event.getContentDescription() != null) {
            scan.add(new UninstallGuard.Label(event.getContentDescription().toString(), false));
        }
        UninstallGuard.Decision decision = scan.decision();
        if (decision != UninstallGuard.Decision.FORCE_STOP && decision != UninstallGuard.Decision.DISABLE_ACCESSIBILITY) return false;
        int windowId = event.getWindowId();
        if (windowId >= 0 && windowId != dismissedSettingsDialogWindow && dialog && backFromActiveDialog(windowId, true)) {
            dismissedSettingsDialogWindow = windowId;
        }
        protectSettingsTransition(now);
        return true;
    }
    private void protectSettingsTransition(long now) {
        settingsOverlayUntil = now + 400;
        show("com.android.settings", REMOVAL_BLOCK, false);
        handler.removeCallbacks(checkSettingsTransition);
        handler.postDelayed(checkSettingsTransition, 400);
    }
    private static boolean isDialogClass(CharSequence className) {
        return className != null && className.toString().contains("Dialog");
    }
    private void clearSettingsTransition() {
        settingsOverlayUntil = 0;
        dismissedSettingsDialogWindow = -1;
    }
    private record RemovalWindow(UninstallGuard.Scan scan, AccessibilityNodeInfo cancel) {}
    private boolean inspectRemoval(AccessibilityNodeInfo root) { return inspectRemoval(root, false); }
    private boolean inspectRemoval(AccessibilityNodeInfo root, boolean settingsDialogEvent) {
        if (root == null || root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        if (!UninstallGuard.systemPackage(pkg)) return false;
        long now = SystemClock.elapsedRealtime();
        RemovalWindow current = readRemovalWindow(root, pkg, settingsDialogEvent);
        UninstallGuard.Scan scan = current.scan();
        UninstallGuard.Decision decision = scan.decision();
        if (decision == UninstallGuard.Decision.ALLOW) {
            dismissedSettingsDialogWindow = -1;
            return UninstallGuard.settingsPackage(pkg) && REMOVAL_BLOCK.equals(overlayReason) && now < settingsOverlayUntil;
        }
        if (decision == UninstallGuard.Decision.APP_INFO || decision == UninstallGuard.Decision.ACCESSIBILITY) {
            dismissedRemovalWindow = -1;
            dismissedSettingsDialogWindow = -1;
        } else if (decision == UninstallGuard.Decision.UNINSTALL) {
            if (root.getWindowId() != dismissedRemovalWindow) {
                boolean cancelled = current.cancel() != null && current.cancel().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!cancelled) cancelled = backFromActiveDialog(root.getWindowId(), false);
                if (cancelled) dismissedRemovalWindow = root.getWindowId();
            }
        } else if (root.getWindowId() != dismissedSettingsDialogWindow) {
            boolean cancelled = current.cancel() != null && current.cancel().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!cancelled) cancelled = backFromActiveDialog(root.getWindowId(), true);
            if (cancelled) dismissedSettingsDialogWindow = root.getWindowId();
        }
        show(pkg, REMOVAL_BLOCK, false);
        return true;
    }
    private boolean backFromActiveDialog(int windowId, boolean allowCovered) {
        if (windowId < 0 || overlay != null && !allowCovered) return false;
        AccessibilityNodeInfo active = applicationRoot();
        if (active == null || active.getWindowId() != windowId) return false;
        boolean covered = overlay != null;
        if (covered) setOverlayFocusable(false);
        try {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        } finally {
            if (covered) setOverlayFocusable(true);
        }
    }
    private RemovalWindow readRemovalWindow(AccessibilityNodeInfo root, String pkg, boolean waitForDialog) {
        UninstallGuard.Scan scan = new UninstallGuard.Scan(pkg);
        if (isDialogClass(root.getClassName())) scan.markDialogWindow();
        if (Build.VERSION.SDK_INT >= 28 && root.getPaneTitle() != null) {
            scan.add(new UninstallGuard.Label(root.getPaneTitle().toString(), false));
        }
        AccessibilityNodeInfo cancel = null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 800) {
            AccessibilityNodeInfo node = queue.remove();
            if (node.isVisibleToUser() && !node.isPassword() && !node.isEditable()) {
                String id = shortId(node.getViewIdResourceName());
                CharSequence clazz = node.getClassName();
                boolean action = node.isEnabled() && (node.isClickable() || id.equals("button1") || id.equals("button2")
                    || id.equals("button3") || (clazz != null && clazz.toString().endsWith("Button")));
                if (clazz != null && (clazz.toString().endsWith("Switch") || clazz.toString().endsWith("SwitchCompat"))
                    || node.isCheckable() && (id.equals("switch_widget") || id.equals("switch_bar"))) scan.markSwitch();
                if (node.getText() != null) scan.add(new UninstallGuard.Label(node.getText().toString(), action));
                if (node.getContentDescription() != null) scan.add(new UninstallGuard.Label(node.getContentDescription().toString(), action));
                if (action && (UninstallGuard.cancelControl(node.getText()) || UninstallGuard.cancelControl(node.getContentDescription()))) cancel = node;
                UninstallGuard.Decision decision = scan.decision();
                if (decision == UninstallGuard.Decision.APP_INFO && !waitForDialog) return new RemovalWindow(scan, null);
                if (cancel != null && (decision == UninstallGuard.Decision.FORCE_STOP || decision == UninstallGuard.Decision.UNINSTALL
                    || decision == UninstallGuard.Decision.DISABLE_ACCESSIBILITY))
                    return new RemovalWindow(scan, cancel);
            }
            for (int i = 0; i < node.getChildCount() && queue.size() < 800; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        if (scan.decision() == UninstallGuard.Decision.ALLOW && !scan.isForceStopDialog()) {
            AccessibilityWindowInfo window = root.getWindow();
            if (window != null && window.getTitle() != null) scan.add(new UninstallGuard.Label(window.getTitle().toString(), false));
        }
        return new RemovalWindow(scan, cancel);
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
        sessionLength = new SeekBar(this);
        sessionLength.setContentDescription("Длительность сеанса в минутах");
        sessionLength.setVisibility(View.GONE);
        sessionLength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { updateSessionButton(); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        layout.addView(sessionLength, layout.indexOfChild(sessionStart), new LinearLayout.LayoutParams(-1, -2));
        youtubeHome = new Button(this); youtubeHome.setText("На главную YouTube"); youtubeHome.setVisibility(View.GONE);
        youtubeHome.setOnClickListener(v -> leaveYoutubeShorts()); layout.addView(youtubeHome);
        browserLeave = new Button(this); browserLeave.setText("Уйти со страницы"); browserLeave.setVisibility(View.GONE);
        browserLeave.setOnClickListener(v -> leaveBrowserPage(false)); layout.addView(browserLeave);
        browserNewTab = new Button(this); browserNewTab.setText("Открыть новую вкладку"); browserNewTab.setVisibility(View.GONE);
        browserNewTab.setOnClickListener(v -> leaveBrowserPage(true)); layout.addView(browserNewTab);
        Button home = new Button(this); home.setText("На главный экран"); home.setOnClickListener(v -> { clearSettingsTransition(); performGlobalAction(GLOBAL_ACTION_HOME); hide(); }); layout.addView(home);
        Button settings = new Button(this); settings.setText("Открыть Фокус"); settings.setOnClickListener(v -> {
            clearSettingsTransition();
            performGlobalAction(GLOBAL_ACTION_HOME); hide();
            startActivity(new android.content.Intent(this, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }); layout.addView(settings);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(layout);
        overlayLayout = scroll; overlayDetail = detail; overlayTitle = title;
    }
    private void show(String pkg, String reason, boolean browserPage) {
        if (overlay != null && pkg.equals(overlayPackage) && reason.equals(overlayReason) && browserPage == browserPageOverlay) return;
        if (browserExit != null) finishBrowserExit();
        overlayPackage = pkg; overlayReason = reason; browserPageOverlay = browserPage;
        browserLeave.setVisibility(browserPage ? View.VISIBLE : View.GONE);
        youtubeHome.setVisibility(YOUTUBE.equals(pkg) && SHORTS_BLOCK.equals(reason) ? View.VISIBLE : View.GONE);
        pendingOffer = null; sessionStart.setVisibility(View.GONE); sessionLength.setVisibility(View.GONE);
        overlayTitle.setText("Пора вернуться к своему дню");
        overlayDetail.setText(reason);
        if (overlay != null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE);
        params.gravity = Gravity.CENTER;
        try { windows.addView(overlayLayout, params); overlay = overlayLayout; }
        catch (RuntimeException e) { performGlobalAction(GLOBAL_ACTION_HOME); }
    }
    private void hide() {
        shortsExitUntil = 0;
        shortsBackPending = false;
        if (browserExit != null) finishBrowserExit();
        browserPageOverlay = false;
        if (overlay != null && windows != null) { try { windows.removeViewImmediate(overlay); } catch (RuntimeException ignored) {} }
        overlay = null; overlayPackage = ""; overlayReason = ""; dismissedRemovalWindow = -1; pendingOffer = null;
    }
}
