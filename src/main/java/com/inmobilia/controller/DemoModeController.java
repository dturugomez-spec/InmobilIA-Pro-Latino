package com.inmobilia.controller;

import com.inmobilia.service.AuthService;
import com.inmobilia.service.DemoDataService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Modo demo público: cualquiera puede entrar sin registrarse a un workspace
 * temporal con datos ficticios. Sirve para que el visitante vea la app real
 * antes de registrarse.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class DemoModeController {

    private final SupabaseService supabase;
    private final AuthService authService;
    private final DemoDataService demoDataService;

    @GetMapping("/demo")
    public String enterDemo(HttpSession session) {
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
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("users", data);
        if (saved == null || saved.isEmpty()) {
            log.warn("No se pudo crear usuario demo");
            return "redirect:/login";
        }

        String uid = String.valueOf(saved.get("id"));

        // Sembrar datos demo
        try {
            demoDataService.seedDemoData(uid);
        } catch (Exception e) {
            log.warn("Demo seed: {}", e.getMessage());
        }

        // Auto-login
        SessionUtils.loginFull(session, uid, uid, demoEmail, "Usuario Demo", "admin");
        SessionUtils.flash(session, "🎮 Modo DEMO activo. Los datos son ficticios. Cuando termines, regístrate para guardar los tuyos.");
        return "redirect:/dashboard";
    }
}
