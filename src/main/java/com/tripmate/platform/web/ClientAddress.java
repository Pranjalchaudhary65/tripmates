package com.tripmate.platform.web;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The IP address a request came from.
 *
 * <p>This is {@link HttpServletRequest#getRemoteAddr()} and nothing else. When
 * the app runs behind a reverse proxy, Tomcat's RemoteIpValve has already
 * replaced the socket address with the client address from X-Forwarded-For -
 * but only if the socket peer is a proxy listed in
 * {@code server.tomcat.remoteip.internal-proxies}, and only by reading the
 * header from its right-hand end, where the entries our own proxy appended
 * sit. Whatever a client writes into the header itself ends up on the left
 * and is never used. Application code never reads the header directly.
 */
public record ClientAddress(InetAddress address) {

    /** An IPv6 user is handed a whole /64, so per-address limits count the /64 as one client. */
    private static final int IPV6_ROUTING_PREFIX_BYTES = 8;

    public ClientAddress {
        Objects.requireNonNull(address, "address");
    }

    public static ClientAddress of(HttpServletRequest request) {
        return parse(request.getRemoteAddr());
    }

    /**
     * Accepts IP literals only. {@link InetAddress#getByName} on anything else
     * would make a DNS query, and an out-of-range dotted quad such as
     * 999.1.1.1 is "anything else" to it - so IPv4 is parsed here by hand.
     * A value containing a colon is always parsed as an IPv6 literal, never
     * resolved.
     */
    static ClientAddress parse(String literal) {
        if (literal == null || literal.isEmpty()) {
            throw new IllegalStateException("Request has no remote address");
        }
        try {
            if (literal.indexOf(':') >= 0) {
                return new ClientAddress(InetAddress.getByName(literal));
            }
            return new ClientAddress(InetAddress.getByAddress(ipv4Octets(literal)));
        } catch (UnknownHostException | IllegalArgumentException e) {
            throw new IllegalStateException("Remote address is not an IP literal", e);
        }
    }

    /** The key a per-client rate limit counts under: the address for IPv4, the /64 network for IPv6. */
    public String rateLimitKey() {
        if (address instanceof Inet6Address) {
            return HexFormat.of().formatHex(address.getAddress(), 0, IPV6_ROUTING_PREFIX_BYTES) + "/64";
        }
        return address.getHostAddress();
    }

    public String hostAddress() {
        return address.getHostAddress();
    }

    private static byte[] ipv4Octets(String literal) {
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Not a dotted quad");
        }
        byte[] octets = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("Not a dotted quad");
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                throw new IllegalArgumentException("Octet out of range");
            }
            octets[i] = (byte) value;
        }
        return octets;
    }
}
