package com.inmobilia.controller;

import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.AuthService;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.RateLimiter;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Recuperación de contraseña por email (con token de un solo uso, 1 hora vigencia).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PasswordResetController {

    private final SupabaseService supabase;
    private final AuthService authService;
    private final EmailService emailService;
    private final RateLimiter rateLimiter;
    private final AuditTrail audit;

    @Value("${app.base-url:https://inmobiliapro.es}")
    private String appBaseUrl;

    // ─── Pedir reset (formulario) ───
    @GetMapping("/forgot")
    public String forgotForm(Model model) {
        model.addAttribute("page", "Recuperar contraseña");
        return "auth_forgot";
    }

    @PostMapping("/forgot")
    public String forgot(@RequestParam String email, HttpServletRequest req, Model model) {
        email = email == null ? "" : email.trim().toLowerCase();
        // SIEMPRE responder igual (no revelar si el email existe)
        model.addAttribute("page", "Recuperar contraseña");
        model.addAttribute("sentMessage", "Si el usuario existe, te hemos enviado un email con instrucciones.");

        // Anti-spam: 5 solicitudes/hora por IP y 3/hora por cuenta
        if (!rateLimiter.tryAcquire("forgot-ip:" + ClientIp.of(req), 5, Duration.ofHours(1))
                || !rateLimiter.tryAcquire("forgot-cuenta:" + email, 3, Duration.ofHours(1))) {
            log.info("Forgot pwd: limitado por exceso de solicitudes");
            return "auth_forgot";
        }

        var users = supabase.findBy("users", Map.of("email", email));
        if (users.isEmpty()) {
            return "auth_forgot";
        }

        Map<String, Object> u = users.get(0);
        String uid = String.valueOf(u.get("id"));
        String userEmail = String.valueOf(u.getOrDefault("email", email));

        String token = authService.crearTokenPassword(uid, userEmail, Duration.ofHours(1));
        if (token == null) {
            log.error("Error guardando reset token: {}", supabase.lastError());
            return "auth_forgot";
        }
        audit.registrarPara(req, AuthService.orgDe(u), uid, userEmail, String.valueOf(u.get("role")),
                "password_reset_solicitado", "users", uid, "Pidió un enlace para restablecer la contraseña",
                "info", AuditTrail.SEGURIDAD);

        // Enviar email (si el SMTP está configurado en config global, si no, queda registrado y el admin puede recuperar el token)
        String resetLink = appBaseUrl + "/reset?token=" + token;
        String body = "Hola " + userEmail + ",\n\n" +
                "Has solicitado restablecer tu contraseña en InmobilIA Pro.\n\n" +
                "Haz click en este enlace para crear una nueva contraseña (válido 1 hora):\n" +
                resetLink + "\n\n" +
                "Si no fuiste tú, ignora este email. Tu contraseña no cambiará.\n\n" +
                "— Equipo InmobilIA Pro";

        try {
            EmailService.SendResult res = emailService.send(null, userEmail,
                    "Recuperar contraseña · InmobilIA Pro", body);
            if (!res.success()) {
                log.warn("Email reset no enviado (SMTP no configurado?): {}", res.message());
            }
        } catch (Exception e) {
            log.error("Error envío email reset: {}", e.getMessage());
        }

        return "auth_forgot";
    }

    // ─── Resetear contraseña con token ───
    @GetMapping("/reset")
    public String resetForm(@RequestParam(required = false) String token,
                            @RequestParam(required = false) String bienvenida,
                            Model model) {
        if (token == null || token.isBlank()) return "redirect:/login";
        boolean nuevo = "1".equals(bienvenida);
        model.addAttribute("page", nuevo ? "Crea tu contraseña" : "Restablecer contraseña");
        model.addAttribute("bienvenida", nuevo);
        model.addAttribute("minPassword", AuthService.MIN_PASSWORD);

        String problema = problemaToken(buscarToken(token));
        if (problema != null) {
            model.addAttribute("error", problema);
            return "auth_reset";
        }
        model.addAttribute("token", token);
        return "auth_reset";
    }

    @PostMapping("/reset")
    public String resetSubmit(@RequestParam String token,
                              @RequestParam String password,
                              @RequestParam(required = false) String bienvenida,
                              HttpServletRequest req,
                              Model model) {
        boolean nuevo = "1".equals(bienvenida);
        model.addAttribute("page", nuevo ? "Crea tu contraseña" : "Restablecer contraseña");
        model.addAttribute("bienvenida", nuevo);
        model.addAttribute("minPassword", AuthService.MIN_PASSWORD);

        // Antes se aceptaban enlaces ya usados o caducados al ENVIAR el formulario
        Map<String, Object> reset = buscarToken(token);
        String problema = problemaToken(reset);
        if (problema != null) {
            model.addAttribute("error", problema);
            return "auth_reset";
        }

        problema = AuthService.problemaPassword(password);
        if (problema != null) {
            model.addAttribute("error", problema);
            model.addAttribute("token", token);
            return "auth_reset";
        }

        String userId = String.valueOf(reset.get("user_id"));
        String resetId = String.valueOf(reset.get("id"));

        // Actualizar password
        boolean ok = supabase.update("users", userId,
                Map.of("password", authService.hashPw(password)),
                userId);
        if (!ok) {
            model.addAttribute("error", "No se pudo actualizar la contraseña. Inténtalo de nuevo.");
            model.addAttribute("token", token);
            model.addAttribute("page", "Restablecer contraseña");
            return "auth_reset";
        }

        // Marcar token como usado
        supabase.update("password_resets", resetId,
                Map.of("used_at", OffsetDateTime.now().toString()),
                userId);

        // La cuenta recupera sus intentos de login
        Object email = reset.get("email");
        if (email != null) rateLimiter.reset("login-cuenta:" + String.valueOf(email).trim().toLowerCase());
        authService.findById(userId).ifPresent(u -> audit.registrarPara(req, AuthService.orgDe(u), userId,
                String.valueOf(u.getOrDefault("email", "")), String.valueOf(u.get("role")),
                nuevo ? "password_creada" : "password_restablecida", "users", userId,
                nuevo ? "Creó su contraseña desde el enlace de bienvenida" : "Restableció su contraseña por email",
                "security", AuditTrail.SEGURIDAD));

        return "redirect:/login?reset=ok";
    }

    private Map<String, Object> buscarToken(String token) {
        if (token == null || token.isBlank() || !token.matches("[a-fA-F0-9]{16,128}")) return null;
        var rows = supabase.findBy("password_resets", Map.of("token", token));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Motivo por el que el enlace no vale (inexistente, usado o caducado), o null si es válido. */
    private static String problemaToken(Map<String, Object> reset) {
        if (reset == null) return "Enlace inválido o caducado. Solicita uno nuevo.";
        Object usado = reset.get("used_at");
        if (usado != null && !String.valueOf(usado).isBlank() && !"null".equals(String.valueOf(usado)))
            return "Este enlace ya se utilizó. Si lo necesitas, solicita uno nuevo.";
        OffsetDateTime caduca = leerFecha(reset.get("expires_at"));
        if (caduca == null) return "Enlace inválido o caducado. Solicita uno nuevo.";
        if (caduca.isBefore(OffsetDateTime.now())) return "El enlace ha caducado. Solicita uno nuevo.";
        return null;
    }

    /**
     * Lee una fecha tal como la devuelve Supabase/PostgREST ("2026-10-17T10:15:30.123456+00:00",
     * a veces con espacio en vez de 'T' o con zona "+00"). Sin zona, se asume UTC. null si no se entiende.
     * (Antes se recortaba a 25 caracteres y se perdía la zona horaria: la fecha no se podía leer.)
     */
    static OffsetDateTime leerFecha(Object valor) {
        return com.inmobilia.util.Fechas.leer(valor);
    }
}
