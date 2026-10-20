package com.inmobilia.config;

import com.inmobilia.service.PlanService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 💳 Candado de suscripción: un cliente aprobado debe suscribirse (tarjeta por
 * adelantado, 14 días gratis en Stripe) antes de usar la app. Si entra en una
 * sección de la app sin suscripción, va a /upgrade.
 *
 * Solo se registra en las rutas de la app (ver I18nConfig), así que /upgrade,
 * /billing, /login, /logout, páginas públicas y estáticos nunca se bloquean.
 * Exentos (PlanService.requierePago): cuentas demo y propietarios (ADMIN_EMAILS).
 */
@Component
@RequiredArgsConstructor
public class SubscriptionGateInterceptor implements HandlerInterceptor {

    private final PlanService planService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        HttpSession session = req.getSession(false);
        if (session == null || !SessionUtils.isLoggedIn(session)) return true; // el controlador redirige a login

        if (planService.requierePago(SessionUtils.uid(session))) {
            resp.sendRedirect(req.getContextPath() + "/upgrade");
            return false;
        }
        return true;
    }
}
