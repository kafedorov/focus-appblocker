package ru.focusguard.core;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Host-only globs: no regular expressions, no URL substring matching. */
public final class DomainRules {
    private final List<Pattern> rules = new ArrayList<>();
    public DomainRules(String source) {
        for (String line : source.split("\\R")) {
            String rule = line.trim().toLowerCase(Locale.ROOT);
            if (rule.isEmpty() || rule.startsWith("#")) continue;
            if (rule.length() > 253 || rule.contains(":") || rule.contains("/") || rule.contains(" "))
                throw new IllegalArgumentException("Нужен домен или маска, без https:// и пути: " + rule);
            StringBuilder regex = new StringBuilder("^");
            String[] labels = rule.split("\\.", -1);
            for (int i = 0; i < labels.length; i++) {
                if (i > 0) regex.append("\\.");
                String label = labels[i];
                if (label.isEmpty()) throw new IllegalArgumentException("Пустая часть домена: " + rule);
                if (label.contains("*") || label.contains("?")) {
                    if (!label.matches("[a-z0-9*?_-]+")) throw new IllegalArgumentException("Маска должна быть в ASCII: " + rule);
                    for (char c : label.toCharArray()) {
                        if (c == '*') regex.append(".*");
                        else if (c == '?') regex.append("[^.]");
                        else regex.append(Pattern.quote(String.valueOf(c)));
                    }
                } else regex.append(Pattern.quote(IDN.toASCII(label, IDN.USE_STD3_ASCII_RULES)));
            }
            rules.add(Pattern.compile(regex.append('$').toString()));
        }
    }
    public boolean blocks(String hostname) {
        String host;
        try { host = normalize(hostname); } catch (IllegalArgumentException e) { return false; }
        for (Pattern rule : rules) if (rule.matcher(host).matches()) return true;
        return false;
    }
    public static String normalize(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("Пустой домен");
        return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
    }
}
