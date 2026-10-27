package ru.focusguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import ru.focusguard.core.DomainRules;
import ru.focusguard.core.SessionGate;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(24, 48, 40), GREEN = Color.rgb(25, 117, 101), MUTED = Color.rgb(95, 112, 103);
    private Store store;
    private LinearLayout page, outer;
    private TextView health, telegramState, vkState, langameState, youtubeState;
    private int tab;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { @Override public void run() { refreshStatus(); handler.postDelayed(this, 1000); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new Store(this); LegacyAccessCleanup.start(this);
        AdultBlocklist.warmUp(this);
        if (!store.bool("initialized", false)) AppCatalog.baseline(this, store);
        if (saved != null) tab = saved.getInt("tab", 0);
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putInt("tab", tab); super.onSaveInstanceState(out); }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private void render() {
        health = null; vkState = null; telegramState = null; langameState = null; youtubeState = null;
        outer = new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL); outer.setBackgroundColor(Color.rgb(244,246,242));
        outer.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            else view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView brand = label("ФОКУС  /  ТВОЁ ВРЕМЯ", 12, GREEN); brand.setTypeface(null, Typeface.BOLD); brand.setPadding(dp(22), dp(20), dp(22), dp(6)); outer.addView(brand);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20), dp(12), dp(20), dp(24)); scroll.addView(page);
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (tab) { case 1 -> rules(); case 2 -> apps(); case 3 -> protection(); default -> overview(); }
        LinearLayout navigation = new LinearLayout(this); navigation.setPadding(dp(8), dp(4), dp(8), dp(6));
        String[] names = {"Обзор", "Правила", "Приложения", "Защита"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; TextView item = label(names[i], 12, tab == i ? GREEN : MUTED);
            item.setGravity(Gravity.CENTER); item.setPadding(0, dp(17), 0, dp(17));
            if (tab == i) { item.setTypeface(null, Typeface.BOLD); item.setBackground(background(0xffe0eee7, 16)); }
            item.setOnClickListener(v -> { tab = index; render(); }); navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        outer.addView(navigation); setContentView(outer); outer.requestApplyInsets(); refreshStatus();
    }
    private TextView label(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private GradientDrawable background(int color, int radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private void title(String value, String subtitle) { TextView t = label(value, 32, INK); t.setTypeface(null, Typeface.BOLD); page.addView(t); paragraph(page, subtitle); }
    private void paragraph(LinearLayout parent, String value) { TextView t = label(value, 15, MUTED); t.setLineSpacing(dp(4), 1); t.setPadding(0, dp(8), 0, dp(12)); parent.addView(t); }
    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18), dp(18), dp(18), dp(14)); card.setBackground(background(Color.WHITE, 22));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(14); page.addView(card, params);
        TextView t = label(title, 19, INK); t.setTypeface(null, Typeface.BOLD); card.addView(t); return card;
    }
    private void button(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setTextColor(GREEN); b.setAllCaps(false); b.setTextSize(14);
        b.setOnClickListener(v -> action.run()); parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    }
    private boolean editable() {
        if (!store.locked()) return true;
        new AlertDialog.Builder(this).setTitle("Настройки защищены").setMessage("Изменения доступны после 24 часов ожидания или по коду восстановления. Открой раздел «Защита».").setPositiveButton("Понятно", null).show(); return false;
    }
    private void toggle(LinearLayout parent, String title, String key, boolean fallback) {
        Switch control = new Switch(this); control.setText(title); control.setTextColor(INK); control.setTextSize(16); control.setPadding(0, dp(14), 0, dp(14)); control.setChecked(store.bool(key, fallback));
        control.setEnabled(!store.locked());
        control.setOnCheckedChangeListener((b, checked) -> {
            if (!editable()) return;
            store.setBool(key, checked);
            if (key.equals("enabled") && !checked) { store.setBool("vpn_wanted", false); stopService(new Intent(this, DnsVpnService.class)); }
            refreshStatus();
        }); parent.addView(control);
    }
    private void overview() {
        title("Меньше шума.\nБольше своего.", "Личные границы для телефона. Правила действуют после включения нужных разрешений.");
        LinearLayout status = card("Состояние защиты"); health = label("", 15, INK); health.setPadding(0, dp(12), 0, dp(12)); status.addView(health);
        toggle(status, "Защита включена", "enabled", true);
        button(status, "Включить проверку открытых приложений", this::accessibilityConsent);
        LinearLayout sessions = card("Твои сеансы");
        paragraph(sessions, clock(store.number("start", 420)) + "–" + clock(store.number("end", 1140)) + " · " + store.number("minutes", 10) + " минут отдельно для каждого мессенджера в час. Вне расписания доступ свободный.");
        vkState = label("", 16, INK); telegramState = label("", 16, INK); sessions.addView(vkState); sessions.addView(telegramState);
        paragraph(sessions, "Перед новым сеансом появится вопрос с длительностью и временем следующего доступа. Таймер начнётся только после «Начать сеанс». Выход не останавливает его. Новый календарный час даёт новый сеанс; у границы часа или конца расписания он будет короче.");
        LinearLayout daily = card("YouTube · 30 минут в день");
        youtubeState = label("", 16, INK); youtubeState.setPadding(0, dp(12), 0, dp(8)); daily.addView(youtubeState);
        paragraph(daily, "Один непрерывный сеанс в день после подтверждения. Общий для приложения YouTube и его сайта в поддерживаемом браузере. Выход не останавливает таймер. В полночь начинается новый день; Shorts остаются запрещены.");
        LinearLayout weekly = card("LANGAME · раз в неделю");
        langameState = label("", 16, INK); langameState.setPadding(0, dp(12), 0, dp(8)); weekly.addView(langameState);
        paragraph(weekly, "Только в субботу по времени телефона: один непрерывный сеанс на 10 минут. Он начинается после подтверждения и заканчивается через 10 минут или в полночь, если она наступит раньше. Выход из приложения не ставит таймер на паузу.");
        LinearLayout vpn = card("Сайты и твой VPN");
        paragraph(vpn, "Маски доменов проверяются в адресной строке поддерживаемых браузеров даже при другом VPN. DNS-фильтр дополняет эту проверку, но занимает единственное VPN-подключение.");
        button(vpn, "Включить DNS-фильтр", this::prepareVpn);
        button(vpn, "Остановить DNS-фильтр", () -> { if (editable()) { store.setBool("vpn_wanted", false); stopService(new Intent(this, DnsVpnService.class)); } });
        button(vpn, "Открыть настройки VPN", () -> startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)));
        LinearLayout setup = card("На Samsung Galaxy A54");
        paragraph(setup, "После установки APK Android может потребовать разрешить ограниченные настройки в карточке приложения. Для работы в фоне выбери для Фокуса расход батареи «Без ограничений» и добавь его в «Никогда не спящие приложения». Названия пунктов зависят от версии One UI.");
        button(setup, "Карточка приложения", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }
    private void refreshStatus() {
        if (health != null) {
            String state = "Проверка экрана: " + (GuardAccessibilityService.connected ? "включена" : "нужно включить")
                + "\nDNS-фильтр: " + (DnsVpnService.running ? "работает" : "не работает")
                + "\nДругой VPN: " + (anotherVpn() ? "подключён" : "не обнаружен")
                + "\nБаза сайтов: " + AdultBlocklist.status()
                + "\nНастройки: " + (store.locked() ? "защищены" : "можно менять");
            if (!store.bool("enabled", true)) state = "Защита приостановлена\n" + state;
            String error = store.text("vpn_error", ""), warning = store.text("dns_warning", "");
            if (!error.isEmpty() && !DnsVpnService.running) state += "\n" + error;
            if (!warning.isEmpty() && DnsVpnService.running) state += "\n" + warning;
            String cleanupError = store.text("legacy_cleanup_error", "");
            if (!cleanupError.isEmpty()) state += "\n" + cleanupError;
            health.setText(state);
        }
        if (vkState != null) vkState.setText("ВК · " + sessionText("vk"));
        if (telegramState != null) telegramState.setText("Telegram · " + sessionText("telegram"));
        if (langameState != null) langameState.setText(langameText());
        if (youtubeState != null) youtubeState.setText(youtubeText());
    }
    private String sessionText(String group) {
        if (!store.bool("enabled", true) || !store.bool("schedule", true)) return "без ограничений";
        SessionGate.Access result = store.access(group);
        return switch(result.status()) {
            case FREE -> "свободный доступ"; case AVAILABLE -> "сеанс ждёт подтверждения";
            case BLOCKED -> "следующее окно доступа — в новом часу или вне расписания";
            case ACTIVE -> String.format(Locale.ROOT, "осталось %d:%02d", result.remainingMillis() / 60_000, result.remainingMillis() / 1000 % 60);
        };
    }
    private String clock(int minutes) { return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60); }
    private String youtubeText() {
        if (!store.bool("enabled", true)) return "Защита приостановлена";
        if (store.bool("youtube_all", false) || store.set("blocked").contains("com.google.android.youtube")) return "Для приложения действует полный запрет";
        if (!store.bool("youtube_daily", true)) return "Дневное ограничение выключено";
        SessionGate.Access access = store.access("youtube");
        String text = switch (access.status()) {
            case AVAILABLE -> "Сегодняшний сеанс ждёт подтверждения";
            case ACTIVE -> String.format(Locale.ROOT, "Осталось %d:%02d", access.remainingMillis() / 60_000, access.remainingMillis() / 1000 % 60);
            case BLOCKED -> "Следующий сеанс — завтра с 00:00";
            case FREE -> "Без ограничений";
        };
        if (!GuardAccessibilityService.connected) text += "\nДля ограничения нужно включить проверку открытых приложений";
        return text;
    }
    private String langameText() {
        if (!store.bool("enabled", true) || !store.bool("langame_weekly", true)) return "Недельное ограничение выключено";
        if (store.set("blocked").contains(AppCatalog.LANGAME_PACKAGE)) return "Действует ручной запрет. Для сеанса выбери для LANGAME правило «Автоматически».";
        SessionGate.Access result = store.access("langame");
        String status = switch (result.status()) {
            case AVAILABLE -> "Субботний сеанс ещё не начат";
            case BLOCKED -> "Закрыт. Следующий доступ: " + result.nextWindow().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
            case FREE -> "Без ограничений";
            case ACTIVE -> String.format(Locale.ROOT, "Осталось %d:%02d", result.remainingMillis() / 60_000, result.remainingMillis() / 1000 % 60);
        };
        if (!GuardAccessibilityService.connected) status += "\nДля ограничения нужно включить проверку открытых приложений";
        return status;
    }
    private EditText field(LinearLayout parent, String value, String hint) {
        EditText edit = new EditText(this); edit.setText(value); edit.setHint(hint); edit.setTextColor(INK); edit.setTextSize(16); parent.addView(edit, new LinearLayout.LayoutParams(-1, -2)); return edit;
    }
    private void rules() {
        title("Правила", "Настрой под свой день. Для блокировок по экрану нужна служба специальных возможностей.");
        LinearLayout video = card("YouTube и Shorts"); toggle(video, "Блокировать YouTube Shorts", "shorts", true); toggle(video, "Блокировать YouTube целиком", "youtube_all", false);
        toggle(video, "YouTube: один сеанс на 30 минут в день", "youtube_daily", true);
        paragraph(video, "Лимит общий для официального приложения и сайта YouTube. Сеанс запускает только кнопка подтверждения; выход не ставит таймер на паузу. Если до полуночи меньше 30 минут, сеанс короче. После перезагрузки начатый сеанс текущего дня считается использованным. Полный запрет YouTube и Shorts сильнее дневного разрешения.");
        paragraph(video, "Shorts определяются по экрану проигрывателя и адресу /shorts в браузере. После обновлений YouTube распознавание может потребовать доработки.");
        toggle(video, "Закрывать YouTube при скрытом пути", "shorts_hidden_url", true);
        paragraph(video, "Строгий режим включён по умолчанию и действует при включённой блокировке Shorts. Если браузер или вкладка внутри приложения показывает только youtube.com или m.youtube.com без пути страницы, Фокус закрывает её целиком. Это может затронуть обычные видео и главную YouTube. При доступном полном пути обычные видео остаются разрешены. Если адрес вообще недоступен, этот режим не гарантирует блокировку.");
        LinearLayout schedule = card("ВК и Telegram"); toggle(schedule, "Ограничивать по расписанию", "schedule", true);
        paragraph(schedule, "Перед каждым новым сеансом нужно подтверждение; возврат в уже начатый сеанс его не требует. Формат времени ЧЧ:ММ. Одинаковые начало и конец — круглосуточно. Поддерживается расписание через полночь.");
        EditText start = field(schedule, clock(store.number("start", 420)), "Начало"), end = field(schedule, clock(store.number("end", 1140)), "Конец"), duration = field(schedule, "" + store.number("minutes", 10), "Минут за сеанс");
        duration.setInputType(InputType.TYPE_CLASS_NUMBER);
        button(schedule, "Сохранить расписание", () -> {
            if (!editable()) return;
            try {
                int from = parseTime(start.getText().toString()), to = parseTime(end.getText().toString()), minutes = Integer.parseInt(duration.getText().toString());
                if (minutes < 1 || minutes > 60) throw new IllegalArgumentException("Длительность — от 1 до 60 минут");
                store.prefs.edit().putInt("start", from).putInt("end", to).putInt("minutes", minutes).apply(); toast("Расписание сохранено");
            } catch (IllegalArgumentException e) { toast(e.getMessage() == null ? "Проверь значения" : e.getMessage()); }
        });
        LinearLayout weekly = card("LANGAME · суббота");
        toggle(weekly, "Один сеанс на 10 минут в субботу", "langame_weekly", true);
        paragraph(weekly, "В остальные дни LANGAME закрыт. Неиспользованные сеансы не переносятся. Выход не останавливает таймер; в полночь доступ заканчивается. После перезагрузки начатый сеанс считается использованным. Дни считаются по времени телефона. Настройка независима от расписания ВК и Telegram.");
        paragraph(weekly, "Приложение: «LANGAME. Компьютерные клубы». Недельное правило действует при включённой проверке экрана. Автоматический карантин и категория игры не перекрывают субботний сеанс; ручное «Всегда блокировать» запрещает доступ полностью, а «Всегда разрешать» не отменяет недельный лимит.");
        LinearLayout domains = card("Запрещённые домены");
        paragraph(domains, "Одна маска на строку. example.com — точный домен; *.example.com — поддомены. Для любого окончания используй site.* и *.site.*: заменяй site своим именем. Первая строка блокирует site.ru, site.com, site.co.uk и другие зоны; вторая — www.site.ru и другие поддомены. *word* ищет фрагмент в любом месте домена; ? — один символ. # — комментарий.");
        EditText patterns = field(domains, store.text("rules", Store.DEFAULT_RULES), "Домен или маска");
        patterns.setGravity(Gravity.TOP); patterns.setMinLines(6); patterns.setMaxLines(12); patterns.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        button(domains, "Сохранить маски", () -> { if (!editable()) return; try { new DomainRules(patterns.getText().toString()); store.setText("rules", patterns.getText().toString()); toast("Маски сохранены"); } catch (IllegalArgumentException e) { toast(e.getMessage()); } });
        EditText testHost = field(domains, "", "Проверить домен, например example.com");
        button(domains, "Проверить маски и локальную базу", () -> {
            try {
                String host = testHost.getText().toString();
                boolean blocked = new DomainRules(patterns.getText().toString()).blocks(host) || (store.bool("adult_local", true) && AdultBlocklist.blocks(host));
                toast(blocked ? "Совпадает: домен будет заблокирован" : "В масках и загруженной локальной базе совпадений нет. Категории DNS здесь не проверяются.");
            }
            catch (IllegalArgumentException e) { toast(e.getMessage()); }
        });
        toggle(domains, "Локальная база взрослых сайтов", "adult_local", true);
        paragraph(domains, "HaGeZi NSFW · снимок от 26.10.2026 · около 90 тысяч доменов. Работает в поддерживаемом браузере при любом VPN и дополняет локальный DNS-фильтр. База не обновляется автоматически; возможны пропуски и ошибочные категории.");
        toggle(domains, "Семейный DNS: взрослые сайты", "adult_dns", true);
        paragraph(domains, "Cloudflare 1.1.1.1 for Families дополняет локальную базу только при работающем DNS-фильтре. При другом VPN остаются локальная база и маски в поддерживаемом браузере.");
        LinearLayout coverage = card("Границы фильтрации");
        paragraph(coverage, "Обработчики адреса есть для Chrome, Samsung Internet, Firefox, Edge, Brave, Яндекс Браузера и Opera. Для Яндекса добавлена нижняя панель адреса; совместимость с конкретной версией нужно проверить на телефоне. Скрытая или недоступная службе строка, полноэкранный просмотр в браузере, встроенные браузеры и альтернативные клиенты YouTube могут обойти часть правил и дневной лимит. Проверка не читает ссылки из содержимого страницы. DNS не фильтрует путь и не закрывает уже загруженные соединения.");
    }
    private int parseTime(String value) {
        if (!value.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException("Время должно быть в формате 07:00");
        int hour = Integer.parseInt(value.substring(0,2)), minute = Integer.parseInt(value.substring(3));
        if (hour > 23 || minute > 59) throw new IllegalArgumentException("Некорректное время"); return hour * 60 + minute;
    }
    private void apps() {
        title("Приложения", "Игры определяются по категории Android. Для приложений без категории есть карантин и ручные правила.");
        LinearLayout auto = card("После установки"); toggle(auto, "Блокировать игры", "games", true); toggle(auto, "Новые приложения — в карантин", "quarantine", false);
        paragraph(auto, "Карантин блокирует новые несистемные приложения до ручного разрешения. Он использует список приложений на момент первого запуска Фокуса. Телефон, клавиатура, главный экран и настройки исключены. Блокируется открытие установленного приложения.");
        button(auto, "Обновить список установленных", this::render);
        for (AppCatalog.App app : AppCatalog.launchable(this)) {
            LinearLayout item = card(app.label());
            String reason = AppCatalog.reason(this, store, app.pkg());
            String access = reason == null ? (AppCatalog.isLangame(app.pkg()) && store.bool("langame_weekly", true) ? "По расписанию: суббота, 10 минут за неделю" : "Разрешено") : reason;
            paragraph(item, (app.game() ? "Игра · " : "") + (app.system() ? "Системное · " : "") + access + "\n" + app.pkg());
            if (AppCatalog.critical(this, app.pkg())) { paragraph(item, "Необходимая функция телефона — исключена из блокировок"); continue; }
            button(item, "Изменить правило", () -> {
                if (!editable()) return;
                new AlertDialog.Builder(this).setTitle(app.label()).setItems(new String[]{"Автоматически", "Всегда разрешать", "Всегда блокировать"}, (dialog, selected) -> {
                    if (!editable()) return;
                    Set<String> allowed = store.set("allowed"), blocked = store.set("blocked"); allowed.remove(app.pkg()); blocked.remove(app.pkg());
                    if (selected == 1) allowed.add(app.pkg()); if (selected == 2) blocked.add(app.pkg());
                    store.saveSet("allowed", allowed); store.saveSet("blocked", blocked); render();
                }).show();
            });
        }
    }
    private void protection() {
        title("Держать слово", "Защита от импульсивного изменения правил. Сначала проверь блокировки на своём телефоне.");
        LinearLayout lock = card(store.locked() ? "Настройки защищены" : "Зафиксировать правила");
        paragraph(lock, "После фиксации менять правила и отключать защиту в Фокусе нельзя. Обычная разблокировка — через 24 часа после запроса. Время считается при включённом телефоне; после перезагрузки ожидание начинается заново. Код восстановления снимает ожидание сразу.");
        if (store.locked()) {
            if (store.unlockRequested()) paragraph(lock, "Ожидание: примерно " + ((store.unlockRemaining() + 3_599_999L) / 3_600_000L) + " ч.");
            button(lock, "Запросить разблокировку через 24 часа", () -> { store.requestUnlock(); render(); });
            button(lock, "Восстановить по коду", this::recover);
        } else {
            button(lock, Recovery.exists(store) ? "Задать новый код восстановления" : "Создать код восстановления", this::createRecovery);
            button(lock, "Зафиксировать текущие правила", () -> {
                if (!Recovery.exists(store)) { toast("Сначала создай и сохрани код восстановления"); return; }
                new AlertDialog.Builder(this).setTitle("Зафиксировать правила?").setMessage("Сохрани код у доверенного человека или в недоступном месте. Без него изменение настроек потребует 24 часа ожидания после запроса.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Зафиксировать", (d,w) -> { store.lock(); render(); }).show();
            });
        }
        LinearLayout guard = card("Защита от удаления");
        toggle(guard, "Перекрывать попытки удаления при фиксации", "uninstall_guard", false);
        paragraph(guard, "Работает через включённую проверку экрана, когда правила зафиксированы. Перекрывает подтверждение удаления Фокуса и его страницу «О приложении» с кнопкой удаления: так защита срабатывает раньше. Распознанное подтверждение сразу закрывается. Другие приложения и их настройки доступны.");
        paragraph(guard, "Чтобы менять разрешения или удалить Фокус, сначала открой Фокус и сними фиксацию по коду либо запроси разблокировку через 24 часа. Защита зависит от доставки событий Android: полностью исключить быстрое нажатие и системные способы обхода нельзя.");
        LinearLayout privacy = card("Данные и безопасность");
        paragraph(privacy, "Фокус хранит правила и начало сеансов на устройстве. Сообщения, история посещений и снимки экрана не сохраняются и не отправляются. DNS-запросы при включённом DNS-фильтре уходят выбранному Cloudflare-резолверу по обычному UDP DNS. Проверка адресной строки и Shorts выполняется локально.");
        paragraph(privacy, "Это первая версия для личного использования. Режим специальных возможностей можно отключить через Android; запрет удаления сам по себе не запрещает это. При включённой защите удаления и зафиксированных правилах перекрываются распознанные подтверждения удаления и собственная страница «О приложении» с кнопкой удаления. Для её открытия сначала сними фиксацию в Фокусе. Остальные настройки, экраны предоставления разрешений и экстренные вызовы доступны. Сброс устройства, безопасный режим и доступ к инструментам разработчика остаются путями обхода.");
    }
    private void createRecovery() {
        if (!editable()) return;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20),0,dp(20),0);
        EditText code = field(form, "", "Не менее 8 символов"), again = field(form, "", "Повтори код");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); again.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Код восстановления").setMessage("Передай код доверенному человеку. Он поможет снять защиту и запрет удаления.").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!editable()) return;
            String value = code.getText().toString(); if (value.length() < 8 || !value.equals(again.getText().toString())) { code.setError("Минимум 8 символов, оба поля должны совпадать"); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            new Thread(() -> {
                try { Recovery.create(store, value); runOnUiThread(() -> { dialog.dismiss(); toast("Код сохранён"); render(); }); }
                catch (Exception e) { runOnUiThread(() -> { toast("Не удалось сохранить код"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }); }
            }, "recovery-create").start();
        })); dialog.show();
    }
    private void recover() {
        if (!Recovery.exists(store)) { toast("Код восстановления ещё не задан"); return; }
        EditText input = new EditText(this); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setHint("Код восстановления");
        String title = "Снять фиксацию правил";
        String message = "Разрешит менять настройки Фокуса и отключит перекрытие удаления до следующей фиксации правил.";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(input)
            .setMessage(message)
            .setNegativeButton("Отмена", null).setPositiveButton("Восстановить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false); String value = input.getText().toString();
            new Thread(() -> {
                boolean valid = Recovery.verify(store, value);
                runOnUiThread(() -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (!valid) { input.setError("Код не подходит"); return; }
                    try {
                        store.unlock(); dialog.dismiss(); render();
                    }
                    catch (RuntimeException e) { toast(e.getMessage()); }
                });
            }, "recovery-check").start();
        })); dialog.show();
    }
    private void accessibilityConsent() {
        new AlertDialog.Builder(this).setTitle("Разрешить проверку экрана?")
            .setMessage(getString(R.string.accessibility_description) + "\n\nВ настройках Android выбери установленную службу «Фокус — блокировка отвлечений» и включи её.")
            .setNegativeButton("Отмена", null).setPositiveButton("Открыть настройки", (d,w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
    }
    private boolean anotherVpn() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (android.net.Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !DnsVpnService.running) return true;
        }
        return false;
    }
    private void prepareVpn() {
        if (!editable()) return;
        new AlertDialog.Builder(this).setTitle(anotherVpn() ? "Другой VPN будет отключён" : "Включить локальный DNS-фильтр?")
            .setMessage("Android допускает один VPN. DNS-фильтр займёт это подключение. Для работы под твоим обычным VPN оставь только проверку браузера и приложений.\n\nФильтр отправляет DNS-запросы Cloudflare. Для надёжной проверки DNS отключи «Безопасный DNS» браузера и частный DNS Android; приложения со своим DNS могут обходить фильтр. Не включай «Блокировать подключения без VPN»: этот режим несовместим с фильтром только DNS.")
            .setNegativeButton("Отмена", null).setPositiveButton("Включить DNS-фильтр", (d,w) -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
                Intent consent = VpnService.prepare(this); if (consent != null) startActivityForResult(consent, 100); else startDns();
            }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 100 && result == RESULT_OK) startDns();
    }
    private void startDns() {
        if (!editable()) return;
        if (!store.bool("enabled", true)) { toast("Сначала включи защиту"); return; }
        store.setBool("vpn_wanted", true); startForegroundService(new Intent(this, DnsVpnService.class));
    }
    private void toast(String value) { Toast.makeText(this, value == null ? "Не удалось выполнить действие" : value, Toast.LENGTH_LONG).show(); }
}
