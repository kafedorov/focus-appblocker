package ru.focusguard.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Strict IPv4/UDP DNS codec. Other traffic is never routed into the DNS-only tunnel. */
public final class DnsPacket {
    public record Query(byte[] ip, byte[] dns, String host) {}
    private static int u16(byte[] b, int p) { return (b[p] & 255) << 8 | b[p + 1] & 255; }
    private static void put16(byte[] b, int p, int n) { b[p] = (byte)(n >>> 8); b[p + 1] = (byte)n; }
    public static Query parse(byte[] packet, int length) {
        if (length < 40 || length > packet.length || (packet[0] & 0xf0) != 0x40) return null;
        int header = (packet[0] & 15) * 4;
        int total = u16(packet, 2);
        if (header < 20 || total > length || total < header + 20 || packet[9] != 17 || (u16(packet, 6) & 0x3fff) != 0) return null;
        if (u16(packet, header + 2) != 53 || u16(packet, header + 4) != total - header) return null;
        byte[] dns = Arrays.copyOfRange(packet, header + 8, total);
        if (dns.length < 12 || (dns[2] & 0x80) != 0 || u16(dns, 4) != 1 || (dns[2] & 0x78) != 0) return null;
        StringBuilder host = new StringBuilder();
        int p = 12;
        while (p < dns.length && dns[p] != 0) {
            int count = dns[p++] & 255;
            if (count > 63 || p + count >= dns.length) return null;
            if (host.length() > 0) host.append('.');
            for (int i = p; i < p + count; i++) if ((dns[i] & 255) < 33 || (dns[i] & 255) > 126) return null;
            host.append(new String(dns, p, count, StandardCharsets.US_ASCII)); p += count;
            if (host.length() > 253) return null;
        }
        if (host.length() == 0 || p + 5 > dns.length) return null;
        return new Query(Arrays.copyOf(packet, total), dns, host.toString());
    }
    public static byte[] error(byte[] query, int rcode) {
        int p = 12;
        while (p < query.length && query[p] != 0) p += 1 + (query[p] & 255);
        byte[] answer = Arrays.copyOf(query, p + 5);
        answer[2] = (byte)(0x80 | (query[2] & 1)); answer[3] = (byte)(0x80 | rcode);
        Arrays.fill(answer, 6, 12, (byte)0);
        return answer;
    }
    public static boolean matches(byte[] question, byte[] response) {
        if (response.length < 12 || response[0] != question[0] || response[1] != question[1] || (response[2] & 0x80) == 0 || u16(response, 4) != 1) return false;
        int p = 12;
        while (p < question.length && question[p] != 0) p += 1 + (question[p] & 255);
        int end = p + 5;
        return response.length >= end && Arrays.equals(Arrays.copyOfRange(question, 12, end), Arrays.copyOfRange(response, 12, end));
    }
    public static byte[] reply(Query query, byte[] dns) {
        byte[] source = query.ip(); int header = (source[0] & 15) * 4;
        byte[] result = new byte[28 + dns.length];
        result[0] = 0x45; put16(result, 2, result.length); result[8] = 64; result[9] = 17;
        System.arraycopy(source, 16, result, 12, 4); System.arraycopy(source, 12, result, 16, 4);
        put16(result, 20, 53); put16(result, 22, u16(source, header)); put16(result, 24, 8 + dns.length);
        System.arraycopy(dns, 0, result, 28, dns.length);
        put16(result, 10, checksum(result, 0, 20)); // Zero UDP checksum is valid for IPv4.
        return result;
    }
    public static int checksum(byte[] b, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length; i += 2) sum += ((b[offset + i] & 255) << 8) | (i + 1 < length ? b[offset + i + 1] & 255 : 0);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return ~sum & 65535;
    }
}
