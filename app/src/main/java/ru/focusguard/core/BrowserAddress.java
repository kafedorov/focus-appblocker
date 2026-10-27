package ru.focusguard.core;

import java.util.Locale;
import java.util.Set;

/** Browser chrome only; document nodes must be excluded by the Android tree adapter. */
public final class BrowserAddress {
    private static final Set<String> IDS = Set.of("url_bar", "location_bar_edit_text", "mozac_browser_toolbar_url_view",
        "mozac_browser_toolbar_edit_url_view", "url_field", "search_src_text", "address_bar", "url_edittext", "url",
        "custom_tabs_url_bar", "custom_tabs_origin", "custom_tabs_toolbar_url", "location_bar_url",
        "bro_omnibar_address", "bro_omnibar_title", "bro_omnibar_text", "bro_omnibar_address_title",
        "omnibox_text", "url_bar_text", "address_text", "url_text");
    public record Element(String id, String className, String text, String description, boolean visible,
        boolean editing, boolean document, boolean toolbar, boolean tabList, int left, int top, int right, int bottom) {}
    public static boolean yandex(String pkg) {
        return "com.yandex.browser".equals(pkg) || "com.yandex.browser.beta".equals(pkg) || "com.yandex.browser.alpha".equals(pkg);
    }
    public static String shortId(String id) { return id == null ? "" : id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT); }
    public static boolean toolbarId(String resourceId) {
        String id = shortId(resourceId);
        return id.contains("toolbar") || id.contains("omnibar") || id.contains("omnibox") || id.contains("address_bar");
    }
    public static boolean documentRoot(String clazz, String resourceId) {
        String id = shortId(resourceId);
        return clazz != null && (clazz.endsWith("WebView") || clazz.endsWith("ContentView") || clazz.contains("RenderWidgetHostView"))
            || id.equals("web_contents") || id.equals("web_content") || id.equals("render_widget_host_view");
    }
    public static boolean tabListId(String resourceId) {
        String id = shortId(resourceId);
        return id.contains("tab_switcher") || id.contains("tab_grid") || id.contains("tab_card") || id.contains("tabs_list");
    }
    public static BrowserPage read(String pkg, Element element, int screenLeft, int screenTop, int screenRight, int screenBottom) {
        if (!element.visible() || element.editing() || element.document() || element.tabList()) return null;
        String id = shortId(element.id());
        boolean known = IDS.contains(id) || (yandex(pkg) && (id.startsWith("bro_omnibar_") || id.startsWith("bro_omnibox_")));
        int width = screenRight - screenLeft, height = screenBottom - screenTop;
        boolean footer = yandex(pkg) && width > 0 && height > 0 && element.className() != null
            && element.className().endsWith("TextView") && element.right() - element.left() >= width / 4
            && element.bottom() > element.top() && element.bottom() - element.top() <= height / 9
            && element.top() >= screenTop + height * 2 / 3 && element.bottom() <= screenBottom;
        if (!known && !(yandex(pkg) && (element.toolbar() || footer))) return null;
        BrowserPage text = parseLabel(element.text()), description = parseLabel(element.description());
        return prefer(text, description);
    }
    public static BrowserPage prefer(BrowserPage current, BrowserPage candidate) {
        if (candidate == null) return current;
        if (current == null || (current.host().equals(candidate.host()) && !current.hasPagePath() && candidate.hasPagePath())) return candidate;
        return current;
    }
    /** Keep only a known YouTube origin while its native toolbar is hidden by fullscreen. */
    public static BrowserPage fullscreenContext(BrowserPage last, boolean document, boolean editing, boolean toolbar, boolean tabs) {
        return last != null && last.youtube() && document && !editing && !toolbar && !tabs
            ? new BrowserPage(last.host(), "") : null;
    }
    private static BrowserPage parseLabel(String value) {
        if (value == null) return null;
        // Some native bars prefix their accessibility description. Never search arbitrary prose.
        String label = value.trim();
        for (String prefix : new String[]{"Адрес: ", "Адрес сайта: ", "Адрес страницы: ", "Address: ", "URL: "}) {
            if (label.regionMatches(true, 0, prefix, 0, prefix.length())) { label = label.substring(prefix.length()); break; }
        }
        return BrowserPage.parse(label);
    }
}
