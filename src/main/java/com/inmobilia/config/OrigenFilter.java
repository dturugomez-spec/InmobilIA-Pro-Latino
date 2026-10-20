package com.inmobilia.config;

import com.inmobilia.util.SessionUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * Anti-CSRF por origen: una petición que CAMBIA datos (POST/PUT/PATCH/DELETE) hecha
 * con la sesión iniciada solo se acepta si viene de nuestra propia web.
 *
 * Así otra web no puede aprovechar que el usuario tiene la sesión abierta para hacer
 * cosas en su nombre. Se suma a la cookie SameSite=Lax (que ya frena esto en los
 * navegadores modernos). Las peticiones sin sesión (formularios públicos, webhooks)
 * no se tocan.
 */
@Component
@Slf4j
public class OrigenFilter extends OncePerRequestFilter {

    private static final Set<String> METODOS_QUE_CAMBIAN = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (METODOS_QUE_CAMBIAN.contains(req.getMethod().toUpperCase())) {
            HttpSession session = req.getSession(false);
            if (session != null && SessionUtils.isLoggedIn(session)) {
                String origen = req.getHeader("Origin");
                if (origen == null || origen.isBlank() || "null".equals(origen)) origen = req.getHeader("Referer");
                if (origen != null && !origen.isBlank() && !"null".equals(origen) && !mismoSitio(origen, req)) {
                    log.warn("Petición {} {} bloqueada: origen ajeno {}", req.getMethod(), req.getRequestURI(), origen);
                    resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Origen no permitido");
                    return;
                }
            }
        }
        chain.doFilter(req, resp);
    }

    private static boolean mismoSitio(String origen, HttpServletRequest req) {
        String host;
        try {
            host = URI.create(origen.trim()).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase();
        String propio = req.getServerName() == null ? "" : req.getServerName().toLowerCase();
        return host.equals(propio)
                || host.equals("inmobiliapro.es") || host.endsWith(".inmobiliapro.es")
                || host.equals("localhost") || host.equals("127.0.0.1")
                || host.endsWith(".up.railway.app");
    }
}
