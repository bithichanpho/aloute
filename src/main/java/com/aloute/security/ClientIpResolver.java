package com.aloute.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Resolves client IPs without trusting forwarding headers from arbitrary callers. */
@Component
public class ClientIpResolver {

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(
            @Value("${app.security.trusted-proxy-cidrs:127.0.0.1/32,::1/128}") String configuredCidrs) {
        this.trustedProxies = parseCidrs(configuredCidrs);
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalizeAddress(request.getRemoteAddr());
        if (remoteAddress == null) return "unknown";
        if (!isTrustedProxy(remoteAddress)) return remoteAddress;

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remoteAddress;

        String[] chain = forwarded.split(",");
        String current = remoteAddress;
        // Walk the proxy chain from the nearest hop back toward the original client.
        // The first untrusted hop is the client identity used for rate limiting.
        for (int i = chain.length - 1; i >= 0; i--) {
            if (!isTrustedProxy(current)) return current;
            String candidate = normalizeAddress(chain[i]);
            if (candidate == null) return remoteAddress;
            current = candidate;
        }
        return current;
    }

    private boolean isTrustedProxy(String ip) {
        for (Cidr cidr : trustedProxies) {
            if (cidr.contains(ip)) return true;
        }
        return false;
    }

    private static List<Cidr> parseCidrs(String value) {
        List<Cidr> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        for (String raw : value.split(",")) {
            try {
                result.add(Cidr.parse(raw.trim()));
            } catch (RuntimeException ignored) {
                // Invalid trusted-proxy entries are ignored; they must never broaden trust.
            }
        }
        return List.copyOf(result);
    }

    private static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) return null;
        String ip = value.trim();
        if (ip.startsWith("[") && ip.contains("]")) ip = ip.substring(1, ip.indexOf(']'));
        if (ip.startsWith("::ffff:")) ip = ip.substring("::ffff:".length());
        try {
            // Reject host names; proxy headers are required to contain literal IP addresses.
            if (!(ip.contains(":") || ip.matches("[0-9.]+"))) return null;
            return InetAddress.getByName(ip).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefixLength) {
        static Cidr parse(String raw) {
            String[] parts = raw.split("/", 2);
            String ip = normalizeAddress(parts[0]);
            if (ip == null) throw new IllegalArgumentException("Invalid IP");
            try {
                byte[] bytes = InetAddress.getByName(ip).getAddress();
                int max = bytes.length * 8;
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : max;
                if (prefix < 0 || prefix > max) throw new IllegalArgumentException("Invalid CIDR");
                byte[] network = bytes.clone();
                for (int i = 0; i < network.length; i++) {
                    int remaining = prefix - (i * 8);
                    int mask = remaining >= 8 ? 0xff : remaining <= 0 ? 0 : (0xff << (8 - remaining)) & 0xff;
                    network[i] = (byte) (network[i] & mask);
                }
                return new Cidr(network, prefix);
            } catch (UnknownHostException ex) {
                throw new IllegalArgumentException("Invalid CIDR", ex);
            }
        }

        boolean contains(String candidate) {
            try {
                byte[] address = InetAddress.getByName(candidate).getAddress();
                if (address.length != network.length) return false;
                for (int i = 0; i < address.length; i++) {
                    int remaining = prefixLength - i * 8;
                    int mask = remaining >= 8 ? 0xff : remaining <= 0 ? 0 : (0xff << (8 - remaining)) & 0xff;
                    if (((address[i] & 0xff) & mask) != ((network[i] & 0xff) & mask)) return false;
                }
                return true;
            } catch (UnknownHostException ex) {
                return false;
            }
        }
    }
}
