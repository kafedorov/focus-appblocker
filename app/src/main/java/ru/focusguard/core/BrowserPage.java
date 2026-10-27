package ru.focusguard.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** A page address read from browser chrome, never from links inside the document. */
public record BrowserPage(String host, String path) {
    public enum ShortsDecision { ALLOW, BLOCK_SHORTS, BLOCK_HIDDEN_ADDRESS }

    public static BrowserPage parse(String address) {
        if (address == null) return null;
        String value = address.trim();
        if (value.isEmpty()) return null;
        try {
            URI uri = new URI(value.contains("://") ? value : "https://" + value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawUserInfo() != null || uri.getRawAuthority() == null) return null;
            String host = uri.getHost();
            if (host == null) {
                // URI.getHost() does not accept Unicode hostnames. IDN normalization does.
                host = uri.getRawAuthority();
                if (host.contains(":") || host.contains("%")) return null;
            }
            host = DomainRules.normalize(host);
            if (!host.contains(".")) return null;
            return new BrowserPage(host, uri.getPath() == null ? "" : uri.getPath());
        } catch (URISyntaxException | IllegalArgumentException e) { return null; }
    }

    public boolean youtube() { return host.equals("youtube.com") || host.endsWith(".youtube.com"); }
    public boolean hasPagePath() { return !path.isEmpty() && !path.equals("/"); }
    public ShortsDecision shortsDecision(boolean enabled, boolean playerVisible, boolean strictHiddenAddress) {
        if (!enabled || !youtube()) return ShortsDecision.ALLOW;
        if (path.equals("/shorts") || path.startsWith("/shorts/") || playerVisible) return ShortsDecision.BLOCK_SHORTS;
        if (strictHiddenAddress && !hasPagePath()) return ShortsDecision.BLOCK_HIDDEN_ADDRESS;
        return ShortsDecision.ALLOW;
    }

    /** Only positive player evidence; a mere Shorts link or a vertical video is insufficient. */
    public static boolean shortsPlayerId(String resourceId) {
        if (resourceId == null) return false;
        String id = resourceId.substring(resourceId.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return switch (id) {
            case "reel_recycler", "reel_watch_player", "reel_player_page_container", "reel_watch_fragment_root",
                 "shorts_player", "reel_watch_fragment", "shorts-player", "shorts-container", "reel-player-overlay",
                 "reel-player-overlay-actions", "shorts-video-player" -> true;
            default -> false;
        };
    }
    public static boolean selectedShortsLabel(boolean selected, CharSequence label) {
        if (!selected || label == null) return false;
        String value = label.toString().trim().toLowerCase(Locale.ROOT);
        return value.equals("shorts") || value.equals("шортс") || value.startsWith("shorts,")
            || value.startsWith("shorts, ") || value.startsWith("шортс,");
    }
}
