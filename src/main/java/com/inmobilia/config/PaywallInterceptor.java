package com.inmobilia.config;

import com.inmobilia.service.PlanService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 💳 Paywall por planes. Redirige a /upgrade cuando el plan del usuario no incluye
 * la feature premium solicitada. Enchufa el gate PlanService.canUse() que ya existía
 * pero no se llamaba en ningún sitio.
 *
 * Seguridad de negocio:
 *   - Los usuarios en trial se tratan como Agency (acceso total) → no se bloquean.
 *   - Fail-open: si no se puede leer el plan, se deja pasar (no bloquear a clientes
 *     de pago por un fallo transitorio de BD).
 *   - Solo afecta a cuentas realmente en plan Free/expirado.
 */
@Component
@RequiredArgsConstructor
public class PaywallInterceptor implements HandlerInterceptor {

    private final PlanService planService;

    /** Prefijo de URL → feature requerida (según PlanService.PlanLimits). */
    private static final Map<String, String> GATES = new LinkedHashMap<>();
    static {
        GATES.put("/integrations", "integrations"); // solo Agency
        GATES.put("/invoices", "invoicing");         // Pro+
        GATES.put("/receipts", "invoicing");         // Pro+
        GATES.put("/reports", "reports");            // Pro+
        GATES.put("/kanban", "kanban");              // Pro+
        GATES.put("/pipelines", "kanban");           // Pro+
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String feature = featureFor(req.getRequestURI());
        if (feature == null) return true;

        HttpSession session = req.getSession(false);
        if (session == null || !SessionUtils.isLoggedIn(session)) return true; // el controlador redirige a login

        String uid = SessionUtils.uid(session);
        if (uid == null || planService.canUseByUid(uid, feature)) return true;

        // Plan insuficiente → página de mejora de plan
        resp.sendRedirect(req.getContextPath() + "/upgrade?feature=" + feature);
        return false;
    }

    private String featureFor(String uri) {
        if (uri == null) return null;
        for (Map.Entry<String, String> g : GATES.entrySet()) {
            String p = g.getKey();
            if (uri.equals(p) || uri.startsWith(p + "/")) return g.getValue();
        }
        return null;
    }
}
