package com.inmobilia.controller;

import com.inmobilia.service.AuthService;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.SupabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Endpoint público de diagnóstico (sin auth).
 * Útil para verificar que la app conecta a Supabase desde Render.
 * Llamar a /debug/supabase desde el navegador.
 */
@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class DebugController {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final com.inmobilia.service.EjemplosService ejemplos;
    private final com.inmobilia.service.PlanService planService;

    @Value("${supabase.url:}")
    private String supabaseUrl;

    @Value("${supabase.key:}")
    private String supabaseKey;

    @Value("${spring.mail.host:}")
    private String smtpHost;

    @Value("${spring.mail.username:}")
    private String smtpUser;

    @Value("${SMTP_FROM_EMAIL:}")
    private String smtpFromEmail;

    @Value("${ADMIN_EMAILS:}")
    private String adminEmailsRaw;

    @Value("${resend.api-key:${RESEND_API_KEY:}}")
    private String resendKey;
    @Value("${resend.from:${MAIL_FROM:}}")
    private String resendFrom;

    /**
     * Token de acceso a estos endpoints de diagnóstico. Seguridad por defecto:
     * si DEBUG_TOKEN NO está configurado, TODOS los endpoints /debug/* quedan
     * DESACTIVADOS (devuelven 404). Para usarlos, poner DEBUG_TOKEN en el entorno
     * y llamar con ?token=ESE_VALOR. Así nadie puede crear admins, ver hashes ni
     * enviar correos desde producción.
     */
    @Value("${debug.token:${DEBUG_TOKEN:}}")
    private String debugToken;

    /**
     * Corta la petición con 404 salvo que: DEBUG_TOKEN esté configurado, el token coincida
     * (comparación en tiempo constante) Y haya una sesión iniciada de un propietario de la
     * plataforma (ADMIN_EMAILS). Un token filtrado (p. ej. en el historial) ya no basta.
     */
    private void guard(String token, jakarta.servlet.http.HttpSession session) {
        boolean tokenOk = debugToken != null && !debugToken.isBlank() && token != null
                && java.security.MessageDigest.isEqual(
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        debugToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean propietario = session != null && com.inmobilia.util.SessionUtils.isLoggedIn(session)
                && planService.esAdminPlataforma(com.inmobilia.util.SessionUtils.email(session));
        if (!tokenOk || !propietario) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Not found");
        }
    }

    @GetMapping("/supabase")
    public Map<String, Object> diagnostico(@RequestParam(required = false) String token,
                                           jakarta.servlet.http.HttpSession session) {
        guard(token, session);
        Map<String, Object> r = new LinkedHashMap<>();

        // 1. Configuración (sin exponer la key entera)
        r.put("supabase_url", supabaseUrl == null || supabaseUrl.isBlank() ? "❌ VACÍO" : "✅ " + supabaseUrl);
        r.put("supabase_key_set", supabaseKey != null && !supabaseKey.isBlank());
        r.put("supabase_key_length", supabaseKey == null ? 0 : supabaseKey.length());
        r.put("supabase_key_starts_with", supabaseKey != null && supabaseKey.length() > 10 ? supabaseKey.substring(0, 10) + "..." : "-");

        // 2. Probar query a la tabla users
        try {
            List<Map<String, Object>> users = supabase.selectFilter("users", "limit=1");
            r.put("query_users_ok", true);
            r.put("query_users_count_sample", users.size());
            r.put("last_error", supabase.lastError());
        } catch (Exception e) {
            r.put("query_users_ok", false);
            r.put("query_users_error", e.getMessage());
        }

        // 3. Total de users en BD
        try {
            List<Map<String, Object>> allUsers = supabase.selectFilter("users", "select=id&limit=500");
            r.put("total_users_in_db", allUsers.size());
        } catch (Exception ignored) {}

        return r;
    }

    /** Test directo del envío de email. Llama a /debug/test-email?to=tuemail@gmail.com */
    @GetMapping("/test-email")
    public Map<String, Object> testEmail(@RequestParam(required = false) String to,
                                         @RequestParam(required = false) String token,
                                         jakarta.servlet.http.HttpSession session) {
        guard(token, session);
        Map<String, Object> r = new LinkedHashMap<>();
        if (to == null || to.isBlank()) to = adminEmailsRaw == null ? "test@test.com" : adminEmailsRaw.split(",")[0].trim();

        r.put("destinatario", to);
        r.put("smtp_host_configurado", smtpHost == null ? "❌ VACÍO" : smtpHost);
        r.put("smtp_user_configurado", smtpUser == null ? "❌ VACÍO" : smtpUser);
        r.put("smtp_from_email", smtpFromEmail == null ? "❌ VACÍO" : smtpFromEmail);
        r.put("admin_emails_var", adminEmailsRaw == null ? "❌ VACÍO" : adminEmailsRaw);
        r.put("resend_key_set", resendKey != null && !resendKey.isBlank());
        r.put("resend_from", (resendFrom == null || resendFrom.isBlank()) ? "(por defecto onboarding@resend.dev)" : resendFrom);

        try {
            var resultado = emailService.send(null, to,
                    "✅ Test InmobilIA Pro · " + java.time.LocalDateTime.now(),
                    "<h2>Test exitoso</h2><p>Si lees esto, el envío SMTP funciona correctamente.</p>" +
                    "<p>Destinatario: " + to + "</p>" +
                    "<p>From: " + smtpFromEmail + "</p>");
            r.put("envio_ok", resultado.success());
            r.put("envio_mensaje", resultado.message());
        } catch (Exception e) {
            r.put("envio_ok", false);
            r.put("envio_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return r;
    }

    /**
     * Siembra datos de ejemplo enriquecidos en la cuenta indicada (para demos a clientes).
     * /debug/seed-demo?email=inmobilia.support@gmail.com&token=XXX&wipe=true
     * wipe=true borra antes lo ya sembrado (para re-sembrar limpio).
     */
    @GetMapping("/seed-demo")
    public Map<String, Object> seedDemo(@RequestParam String email,
                                        @RequestParam(required = false) String token,
                                        @RequestParam(required = false, defaultValue = "false") boolean wipe,
                                        jakarta.servlet.http.HttpSession session) {
        guard(token, session);
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> users = supabase.findBy("users", Map.of("email", email));
        if (users == null || users.isEmpty()) {
            r.put("error", "usuario no encontrado: " + email);
            return r;
        }
        String uid = String.valueOf(users.get(0).get("id"));
        r.put("email", email);
        r.put("uid", uid);
        // wipe=true quita solo los ejemplos cargados antes (antes borraba TODO lo de la cuenta)
        if (wipe) r.put("wiped", ejemplos.quitar(uid));
        r.put("seeded", ejemplos.cargarAhora(uid));
        return r;
    }

    /** Lista todos los users en BD (sin passwords). */
    @GetMapping("/users-list")
    public Map<String, Object> usersList(@RequestParam(required = false) String token,
                                         jakarta.servlet.http.HttpSession session) {
        guard(token, session);
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> users = supabase.selectFilter("users", "select=id,email,role,active,created_at&order=created_at.desc&limit=20");
            // Nunca devolver password/hashes ni tokens: proyectar solo campos seguros.
            List<Map<String, Object>> safe = new ArrayList<>();
            for (Map<String, Object> u : users) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", u.get("id"));
                s.put("email", u.get("email"));
                s.put("role", u.get("role"));
                s.put("active", u.get("active"));
                s.put("created_at", u.get("created_at"));
                safe.add(s);
            }
            r.put("count", safe.size());
            r.put("users", safe);
            r.put("last_error", supabase.lastError());
        } catch (Exception e) {
            r.put("error", e.getMessage());
        }
        return r;
    }
}
