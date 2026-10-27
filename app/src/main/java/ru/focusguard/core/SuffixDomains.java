package ru.focusguard.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;

/** Domain blocklist lookup is O(number of labels), not O(size of the blocklist). */
public final class SuffixDomains {
    private final Set<String> domains;
    public SuffixDomains(Reader input) throws IOException {
        Set<String> values = new HashSet<>(131072);
        BufferedReader reader = new BufferedReader(input); String line;
        while ((line = reader.readLine()) != null) {
            String value = line.trim(); if (value.isEmpty() || value.startsWith("#")) continue;
            values.add(DomainRules.normalize(value));
        }
        domains = values;
    }
    public int size() { return domains.size(); }
    public boolean blocks(String hostname) {
        String host;
        try { host = DomainRules.normalize(hostname); } catch (IllegalArgumentException e) { return false; }
        while (true) {
            if (domains.contains(host)) return true;
            int dot = host.indexOf('.'); if (dot < 0) return false; host = host.substring(dot + 1);
        }
    }
}
