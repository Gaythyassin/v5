package logflow;

import java.util.regex.Pattern;

/** IP adresi biçim denetimi; {@code InetAddress} ad çözümlemesi (DNS) yapabildiği için kullanılmaz. */
final class IpAddresses {
    private static final Pattern IPV4 =
            Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_GROUP = Pattern.compile("[0-9a-fA-F]{1,4}");

    private IpAddresses() {
    }

    static boolean isIpv4(String s) {
        return IPV4.matcher(s).matches();
    }

    static boolean isIpv6(String s) {
        if (!s.contains(":") || s.indexOf("::") != s.lastIndexOf("::")) {
            return false;
        }
        if ((s.startsWith(":") && !s.startsWith("::")) || (s.endsWith(":") && !s.endsWith("::"))) {
            return false;
        }
        String[] parts = s.split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue; // "::" kısaltmasının parçası
            }
            if (i == parts.length - 1 && p.contains(".")) {
                if (!isIpv4(p)) {
                    return false;
                }
                groups += 2;
            } else if (IPV6_GROUP.matcher(p).matches()) {
                groups++;
            } else {
                return false;
            }
        }
        return s.contains("::") ? groups < 8 : groups == 8;
    }
}
