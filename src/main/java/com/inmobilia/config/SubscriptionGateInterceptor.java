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
 * Candado de usuarios. Ya no hace falta pagar para entrar (existe el plan Free), pero cada plan
 * incluye un número de personas: si la cuenta tiene más (p. ej. al acabar la prueba de Business
 * y pasar a Free con empleados), los empleados van a /upgrade y solo entra el titular, que puede
 * mejorar el plan o desactivar a alguien. Los datos no se tocan.
 *
 * Solo se registra en las rutas de la app (ver I18nConfig), así que /upgrade,
 * /billing, /login, /logout, páginas públicas y estáticos nunca se bloquean.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionGateInterceptor implements HandlerInterceptor {

    private final PlanService planService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        HttpSession session = req.getSession(false);
        if (session == null || !SessionUtils.isLoggedIn(session)) return true; // el controlador redirige a login

        String org = SessionUtils.uid(session);
        boolean titular = org != null && org.equals(SessionUtils.realUid(session));
        if (!titular && planService.excedeUsuarios(org)) {
            resp.sendRedirect(req.getContextPath() + "/upgrade?limite=usuarios");
            return false;
        }
        return true;
    }
}
