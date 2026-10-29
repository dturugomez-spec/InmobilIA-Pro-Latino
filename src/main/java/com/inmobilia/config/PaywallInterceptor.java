package com.inmobilia.config;

import com.inmobilia.service.PlanService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 💳 Paywall por planes. Si el plan de la cuenta no incluye la herramienta de la ruta
 * (PlanService.HERRAMIENTAS: Pro, Business o Enterprise, o un módulo comprado en Pro),
 * lleva a /upgrade diciendo qué plan la incluye.
 *
 *   - Durante la prueba sin tarjeta la cuenta tiene Business (no se bloquea casi nada).
 *   - Fail-open: si no se puede leer el plan, se deja pasar (no bloquear a clientes
 *     de pago por un fallo transitorio de BD).
 */
@Component
@RequiredArgsConstructor
public class PaywallInterceptor implements HandlerInterceptor {

    private final PlanService planService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        PlanService.Herramienta h = PlanService.herramientaDe(req.getRequestURI());
        if (h == null) return true;

        HttpSession session = req.getSession(false);
        if (session == null || !SessionUtils.isLoggedIn(session)) return true; // el controlador redirige a login

        String uid = SessionUtils.uid(session);
        if (uid == null || planService.puedeUsarRuta(uid, req.getRequestURI())) return true;

        // Plan insuficiente → página de planes, diciendo qué herramienta quería usar
        resp.sendRedirect(req.getContextPath() + "/upgrade?herramienta=" + URLEncoder.encode(h.nombre(), StandardCharsets.UTF_8));
        return false;
    }
}
