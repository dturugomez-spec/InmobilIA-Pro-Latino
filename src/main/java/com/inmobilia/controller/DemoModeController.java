package com.inmobilia.controller;

import com.inmobilia.service.AuthService;
import com.inmobilia.service.EjemplosService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Entrar a la demo": una inmobiliaria de ejemplo de Ciudad de México, con todas las herramientas
 * rellenas, para enseñar la app a clientes. Solo la abre el propietario de la plataforma, desde el
 * botón de su menú (antes cualquiera podía crear cuentas demo entrando en /demo).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class DemoModeController {

    private final SupabaseService supabase;
    private final AuthService authService;
    private final EjemplosService ejemplos;
    private final PlanService planService;

    @org.springframework.beans.factory.annotation.Value("${inmobilia.demo-local:false}")
    private boolean demoLocal;

    /** Enlaces antiguos a /demo: ya no crean nada. */
    @GetMapping("/demo")
    public String demoPorEnlace() {
        // En la demo local ya hay una inmobiliaria de ejemplo completa (DemoLocalController)
        return demoLocal ? "redirect:/entrar" : "redirect:/dashboard";
    }

    @PostMapping("/demo")
    public String enterDemo(HttpSession session) {
        if (demoLocal) return "redirect:/entrar";
        if (!SessionUtils.isLoggedIn(session) || !"admin".equals(SessionUtils.role(session))
                || !planService.esAdminPlataforma(SessionUtils.email(session))) {
            return "redirect:/login";
        }

        // Crear usuario demo temporal
        // Email con formato válido (Stripe rechaza "demo_xxxx" sin @). El dominio demo
        // identifica estas cuentas como exentas del candado de pago (PlanService.esCuentaDemo).
        String demoEmail = "demo_" + UUID.randomUUID().toString().substring(0, 8) + "@demo.inmobiliapro.es";
        String pw = UUID.randomUUID().toString().substring(0, 12);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", demoEmail);
        data.put("password", authService.hashPw(pw));
        data.put("name", "Usuario Demo");
        data.put("role", "admin");
        data.put("active", true);
        data.put("email_verified", true);
        data.put("ai_credits", 10);
        data.put("plan", "agency");          // demo siempre con todas las features
        data.put("plan_status", "trialing");
        data.put("trial_ends_at", OffsetDateTime.now().plusDays(1).toString());
        data.put("pais", "MX");              // los ejemplos son de una inmobiliaria de CDMX
        data.put("moneda", "MXN");
        data.put("ciudad", "MX:Ciudad de México");
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("users", data);
        if (saved == null || saved.isEmpty()) {
            log.warn("No se pudo crear usuario demo");
            SessionUtils.flash(session, "No se pudo crear la demo: " + (supabase.lastError() == null ? "sin respuesta de la base de datos" : supabase.lastError()));
            return "redirect:/dashboard";
        }

        String uid = String.valueOf(saved.get("id"));

        // Los mismos ejemplos que "Ejemplos para demos" (en segundo plano: tardan unos segundos)
        ejemplos.cargar(uid);

        // Auto-login
        SessionUtils.loginFull(session, uid, uid, demoEmail, "Usuario Demo", "admin");
        SessionUtils.flash(session, "Modo DEMO: inmobiliaria de ejemplo en Ciudad de México (los ejemplos tardan unos segundos en aparecer). "
                + "Para volver a tu cuenta, cierra sesión y entra con la tuya.");
        return "redirect:/dashboard";
    }
}
