package com.cue.simplebrowser;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact /32 and /128 routes only; Android still routes by IP, not by port. */
final class DnsRoutingPolicy {
    static final List<String> DNS_SERVERS = Collections.unmodifiableList(Arrays.asList(
            "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"));

    private DnsRoutingPolicy() { }

    static int prefixLength(String address) {
        return address.indexOf(':') >= 0 ? 128 : 32;
    }

    static boolean isExactDnsRoute(InetAddress address) {
        if (address == null) return false;
        for (String literal : DNS_SERVERS) {
            try {
                if (Arrays.equals(address.getAddress(), parseLiteral(literal).getAddress())) return true;
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException("Invalid built-in DNS route", impossible);
            }
        }
        return false;
    }

    static InetAddress parseLiteral(String literal) throws UnknownHostException {
        // Built-in literals only; never resolve a hostname while constructing the route table.
        if (literal.indexOf(':') >= 0) {
            byte[] bytes = new byte[16];
            if (literal.equalsIgnoreCase("2620:fe::fe")) {
                bytes[0] = 0x26; bytes[1] = 0x20; bytes[2] = 0; bytes[3] = (byte) 0xfe; bytes[15] = (byte) 0xfe;
                return InetAddress.getByAddress(bytes);
            }
            if (literal.equalsIgnoreCase("2620:fe::9")) {
                bytes[0] = 0x26; bytes[1] = 0x20; bytes[2] = 0; bytes[3] = (byte) 0xfe; bytes[15] = 9;
                return InetAddress.getByAddress(bytes);
            }
            throw new UnknownHostException("Unrecognized built-in IPv6 literal");
        }
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) throw new UnknownHostException("Invalid IPv4 literal");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) throw new NumberFormatException();
                bytes[i] = (byte) value;
            } catch (NumberFormatException error) {
                throw new UnknownHostException("Invalid IPv4 literal");
            }
        }
        return InetAddress.getByAddress(bytes);
    }
}
