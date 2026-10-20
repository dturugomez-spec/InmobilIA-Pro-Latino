package com.inmobilia.config;

import com.inmobilia.service.RateLimiter;
import com.inmobilia.util.ClientIp;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Límite de envíos por IP en los formularios públicos (los que se usan sin iniciar sesión):
 * contacto de inmuebles y micrositios, tasación, valoración, newsletter, alta, reservas,
 * reseñas, NPS, firmas, calculadoras...
 *
 * Antes no tenían ninguno: un script podía meter miles de leads falsos en el CRM de una
 * inmobiliaria, mandar miles de emails de respuesta automática desde nuestro dominio
 * (y quemar su reputación) o gastar los créditos de IA de la valoración.
 *
 * No toca: peticiones con sesión iniciada, webhooks (Stripe, WhatsApp), la API con clave
 * y lo que ya tiene su propio límite (login, recuperar contraseña, chatbot).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LimitePublicoFilter extends OncePerRequestFilter {

    private static final Set<String> METODOS_QUE_CAMBIAN = Set.of("POST", "PUT", "PATCH", "DELETE");

    record Limite(String grupo, int maximo, Duration ventana) {}

    private static final Pattern SIN_LIMITE = Pattern.compile(
            "^/(stripe/webhook|whatsapp/webhook|reservas-online/webhook|api/v1/|api/v2/|login|forgot|reset"
            + "|chatbot/api/(iniciar|mensaje)$)");
    private static final Pattern ALTA = Pattern.compile("^/(register|solicitar-cuenta|portal/activar)");
    private static final Pattern CON_IA = Pattern.compile("^/v/[^/]+/calcular$");
    private static final Pattern OPEN_HOUSE = Pattern.compile("^/open-house/publico/");
    /** Clics rápidos (me gusta / no me gusta), un envío por inmueble: no son formularios. */
    private static final Pattern INTERACCION = Pattern.compile("^/descubre/swipe$|^/c/[^/]+/reaccion$");
    private static final Pattern CALCULO = Pattern.compile(
            "/calcular$|^/calculadora|^/comparador-hipotecas|^/divisas/convertir|^/catastro/consultar-"
            + "|^/descubre/pool|^/ocr-dni/extraer|^/finanzas/dcf|^/herramientas/future-proof");

    private final RateLimiter rateLimiter;

    /** Límite que toca a una ruta pública, o null si no se limita aquí. */
    static Limite limiteDe(String uri) {
        if (uri == null || SIN_LIMITE.matcher(uri).find()) return null;
        if (ALTA.matcher(uri).find()) return new Limite("alta", 5, Duration.ofHours(1));
        if (CON_IA.matcher(uri).find()) return new Limite("valoracion", 15, Duration.ofHours(1));
        // En una jornada de puertas abiertas se apunta mucha gente desde la misma tablet o wifi
        if (OPEN_HOUSE.matcher(uri).find()) return new Limite("open-house", 150, Duration.ofHours(1));
        if (INTERACCION.matcher(uri).find()) return new Limite("interaccion", 200, Duration.ofHours(1));
        if (CALCULO.matcher(uri).find()) return new Limite("calculo", 120, Duration.ofHours(1));
        return new Limite("formulario", 20, Duration.ofHours(1));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (METODOS_QUE_CAMBIAN.contains(req.getMethod().toUpperCase()) && !conSesion(req)) {
            Limite l = limiteDe(req.getRequestURI());
            if (l != null) {
                String clave = "pub:" + l.grupo() + ":" + ClientIp.of(req);
                if (!rateLimiter.tryAcquire(clave, l.maximo(), l.ventana())) {
                    long min = Math.max(1, (rateLimiter.resetIn(clave, l.ventana()) + 59) / 60);
                    log.warn("Límite de formularios públicos ({}) superado por {} en {}", l.grupo(), ClientIp.of(req), req.getRequestURI());
                    rechazar(req, resp, "Demasiados envíos desde tu conexión. Inténtalo de nuevo en " + min + " min.");
                    return;
                }
            }
        }
        chain.doFilter(req, resp);
    }

    private static boolean conSesion(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && SessionUtils.isLoggedIn(s);
    }

    private static void rechazar(HttpServletRequest req, HttpServletResponse resp, String mensaje) throws IOException {
        String accept = String.valueOf(req.getHeader("Accept"));
        String tipo = String.valueOf(req.getContentType());
        resp.setStatus(429);
        if (accept.contains("json") || tipo.contains("json") || "XMLHttpRequest".equals(req.getHeader("X-Requested-With"))) {
            resp.setContentType("application/json;charset=UTF-8");
            resp.getOutputStream().write(("{\"ok\":false,\"error\":\"" + mensaje + "\"}").getBytes(StandardCharsets.UTF_8));
        } else {
            resp.setContentType("text/html;charset=UTF-8");
            resp.getOutputStream().write(("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                    + "<body style='font-family:system-ui,sans-serif;max-width:520px;margin:15vh auto;padding:0 16px;color:#0f172a'>"
                    + "<h1 style='font-size:20px'>Espera un momento</h1><p>" + mensaje + "</p>"
                    + "<p><a href='javascript:history.back()'>Volver</a></p></body>").getBytes(StandardCharsets.UTF_8));
        }
    }
}
