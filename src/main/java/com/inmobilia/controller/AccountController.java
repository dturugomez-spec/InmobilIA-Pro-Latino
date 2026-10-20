package com.inmobilia.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.AuthService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestión de la cuenta del usuario:
 * - Eliminar cuenta (RGPD: derecho al olvido)
 * - Exportar todos sus datos en JSON (RGPD: derecho a la portabilidad)
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final SupabaseService supabase;
    private final AuthService authService;
    private final ObjectMapper mapper;
    private final AuditTrail audit;

    private static final List<String> EXPORTABLE_TABLES = List.of(
        "users", "propiedades", "inquilinos", "contratos", "transacciones",
        "tareas", "mantenimiento", "personal", "eventos", "documentos",
        "reservas", "seguros", "oportunidades", "leads", "email_templates",
        "analisis_financieros", "smtp_config", "ai_usage", "invoices"
    );

    /** Página de gestión de cuenta. */
    @GetMapping("/account")
    public String accountPage(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.realUid(session);
        var users = supabase.findBy("users", Map.of("id", uid));
        if (!users.isEmpty()) model.addAttribute("user", users.get(0));
        model.addAttribute("page", "Mi cuenta");
        return "account";
    }

    /** Campos que nunca salen en una exportación (contraseñas, secretos, tokens, claves). */
    private static boolean campoSensible(String k) {
        String s = k.toLowerCase();
        return s.contains("password") || s.equals("pass") || s.endsWith("_pass") || s.contains("secret")
                || s.contains("token") || s.contains("api_key") || s.contains("apikey")
                || s.contains("recovery_codes") || s.equals("key");
    }

    private static List<Map<String, Object>> sinSensibles(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> { if (!campoSensible(k)) m.put(k, v); });
            return m;
        }).toList();
    }

    /**
     * Exportar todos los datos de la inmobiliaria en JSON (RGPD: portabilidad).
     * Solo el administrador: son los datos de TODA la inmobiliaria, no los de una persona.
     */
    @GetMapping("/account/export")
    public void export(HttpServletRequest req, HttpSession session, HttpServletResponse response) throws Exception {
        if (!SessionUtils.isLoggedIn(session)) { response.sendError(401); return; }
        if (!"admin".equals(SessionUtils.role(session))) {
            audit.registrar(req, session, "exportacion_denegada", null, null,
                    "Intentó exportar todos los datos sin ser administrador", "warning", AuditTrail.EXPORTACION);
            SessionUtils.flash(session, "Solo el administrador puede exportar todos los datos de la inmobiliaria.");
            response.sendRedirect("/account");
            return;
        }
        String uid = SessionUtils.uid(session);

        Map<String, Object> all = new LinkedHashMap<>();
        all.put("exported_at", java.time.OffsetDateTime.now().toString());
        all.put("organization_id", uid);

        for (String table : EXPORTABLE_TABLES) {
            try {
                List<Map<String, Object>> rows = supabase.query(table, uid);
                all.put(table, sinSensibles(rows));
            } catch (Exception e) {
                log.warn("Error exportando tabla {}: {}", table, e.getMessage());
                all.put(table, List.of());
            }
        }
        audit.registrar(req, session, "exportacion_total", null, null,
                "Descargó la exportación completa de datos (JSON)", "critical", AuditTrail.EXPORTACION);

        response.setContentType("application/json; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=inmobilia-export-" + java.time.LocalDate.now() + ".json");
        try (PrintWriter w = response.getWriter()) {
            w.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all));
        }
    }

    /** Eliminar cuenta (soft delete + invalidar sesión). */
    @PostMapping("/account/delete")
    public String deleteAccount(@RequestParam(required = false) String confirm,
                                HttpServletRequest req,
                                HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!"ELIMINAR".equals(confirm)) {
            SessionUtils.flash(session, "Tienes que escribir ELIMINAR para confirmar.");
            return "redirect:/account";
        }
        String uid = SessionUtils.realUid(session);
        boolean ok = authService.deleteAccount(uid);
        log.warn(">>> CUENTA ELIMINADA uid={} ok={}", uid, ok);
        if (ok) audit.seguridad(req, session, "cuenta_eliminada", "Eliminó su cuenta", "critical");

        SessionUtils.logout(session);
        return "redirect:/login?deleted=ok";
    }
}
