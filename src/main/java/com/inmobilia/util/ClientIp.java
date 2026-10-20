package com.inmobilia.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP real del cliente detrás del proxy de Railway.
 *
 * El PRIMER valor de X-Forwarded-For lo puede escribir cualquiera (se falsifica con
 * una cabecera), así que nunca se usa: con él se podía saltar el límite de intentos
 * de login cambiando la cabecera en cada petición. El ÚLTIMO valor lo añade el proxy
 * de Railway y es fiable. Con server.forward-headers-strategy=native, Tomcat ya
 * resuelve getRemoteAddr() a la IP del cliente cuando reconoce al proxy como interno.
 */
public final class ClientIp {
    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        if (remote != null && !esPrivada(remote)) return remote;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] partes = xff.split(",");
            String ultima = partes[partes.length - 1].trim();
            if (!ultima.isEmpty()) return ultima;
        }
        return remote;
    }

    /** Rangos de red internos (proxies, loopback, CGNAT de las plataformas cloud). */
    static boolean esPrivada(String ip) {
        String s = ip.trim().toLowerCase();
        if (s.startsWith("10.") || s.startsWith("127.") || s.startsWith("192.168.") || s.startsWith("169.254.")) return true;
        if (s.equals("::1") || s.equals("0:0:0:0:0:0:0:1") || s.startsWith("fc") || s.startsWith("fd") || s.startsWith("fe80")) return true;
        if (s.startsWith("172.") || s.startsWith("100.")) {
            String[] p = s.split("\\.");
            if (p.length < 2) return false;
            try {
                int b = Integer.parseInt(p[1]);
                return s.startsWith("172.") ? (b >= 16 && b <= 31) : (b >= 64 && b <= 127);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
