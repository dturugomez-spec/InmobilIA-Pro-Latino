package com.inmobilia.controller;

import com.inmobilia.service.AuthService;
import com.inmobilia.service.DefaultAutomationsSeeder;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Sistema de solicitudes de cuenta con aprobación manual (planes Pro/Agency/Enterprise).
 * El plan Free → registro directo desde /register?plan=free
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AccountRequestsController {

    private final SupabaseService supabase;
    private final AuthService authService;
    private final EmailService emailService;
    private final NotificationService notif;
    private final DefaultAutomationsSeeder automationsSeeder;
    private final com.inmobilia.service.PlanService planService;

    /** Validez del enlace de bienvenida para crear la contraseña. */
    private static final java.time.Duration VALIDEZ_BIENVENIDA = java.time.Duration.ofDays(7);

    /**
     * El panel de solicitudes es de los PROPIETARIOS de la plataforma (ADMIN_EMAILS).
     * Antes bastaba con rol "admin", y todo cliente es admin de su inmobiliaria: cualquiera
     * podía ver los datos de todos los solicitantes y aprobarlos.
     */
    private boolean esPropietario(HttpSession session) {
        return SessionUtils.isLoggedIn(session) && planService.esAdminPlataforma(SessionUtils.email(session));
    }

    private static String esc(Object o) {
        return org.springframework.web.util.HtmlUtils.htmlEscape(o == null ? "" : String.valueOf(o));
    }

    /** Enlace de un solo uso para que el cliente cree su contraseña (no se envían contraseñas por email). */
    private String enlaceBienvenida(String userId, String email) {
        String token = authService.crearTokenPassword(userId, email, VALIDEZ_BIENVENIDA);
        return token == null ? BASE_URL + "/forgot" : BASE_URL + "/reset?token=" + token + "&bienvenida=1";
    }

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;
    private static final String[] ADMIN_EMAILS = (
            System.getenv().getOrDefault("ADMIN_EMAILS", "")
    ).split(",");

    // ═══════════════════════════════════════════════════════════════
    // ENDPOINT PÚBLICO: recibir solicitud desde la landing
    // ═══════════════════════════════════════════════════════════════
    @PostMapping("/solicitar-cuenta")
    public String recibirSolicitud(@RequestParam Map<String, String> form,
                                    HttpServletRequest req,
                                    Model model) {
        try {
            // Generar token único para aprobar/rechazar desde email
            String approvalToken = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nombre", form.get("nombre"));
            data.put("empresa", form.get("empresa"));
            data.put("email", form.get("email") != null ? form.get("email").trim().toLowerCase() : "");
            data.put("telefono", form.get("telefono"));
            data.put("plan", form.getOrDefault("plan", "pro"));
            data.put("num_propiedades", form.get("num_propiedades"));
            data.put("mensaje", form.get("mensaje"));
            data.put("origen", form.getOrDefault("origen", "landing"));
            data.put("ip", req.getRemoteAddr());
            data.put("user_agent", req.getHeader("User-Agent"));
            data.put("estado", "pendiente");
            data.put("approval_token", approvalToken);
            data.put("token_expires_at", OffsetDateTime.now().plusDays(7).toString());
            data.put("created_at", OffsetDateTime.now().toString());

            Map<String, Object> saved = supabase.insert("solicitudes_cuenta", data);
            log.info(">>> Nueva solicitud cuenta: {} ({}) plan={}",
                    form.get("nombre"), form.get("email"), form.get("plan"));

            // Notificar a los admins por email CON BOTONES de aprobación directa
            try {
                String aprobarUrl = BASE_URL + "/email-action/aprobar/" + approvalToken;
                String rechazarUrl = BASE_URL + "/email-action/rechazar/" + approvalToken;

                String adminHtml = String.format(
                    "<div style='font-family:sans-serif;max-width:600px;margin:auto'>" +
                    "  <h2 style='color:#0f172a'>📩 Nueva solicitud de cuenta</h2>" +
                    "  <div style='background:#f1f5f9;padding:20px;border-radius:10px;margin:20px 0'>" +
                    "    <p style='margin:4px 0'><strong>Nombre:</strong> %s</p>" +
                    "    <p style='margin:4px 0'><strong>Empresa:</strong> %s</p>" +
                    "    <p style='margin:4px 0'><strong>Email:</strong> <a href='mailto:%s'>%s</a></p>" +
                    "    <p style='margin:4px 0'><strong>Teléfono:</strong> <a href='tel:%s'>%s</a></p>" +
                    "    <p style='margin:4px 0'><strong>Plan solicitado:</strong> %s</p>" +
                    "    <p style='margin:4px 0'><strong>Propiedades:</strong> %s</p>" +
                    "  </div>" +
                    "  <p><strong>Mensaje del cliente:</strong></p>" +
                    "  <blockquote style='border-left:3px solid #94a3b8;padding-left:15px;color:#475569;font-style:italic'>%s</blockquote>" +
                    "  <div style='margin:30px 0;text-align:center'>" +
                    "    <a href='%s' style='display:inline-block;padding:14px 32px;background:#10b981;color:white;text-decoration:none;border-radius:8px;font-weight:bold;margin:0 8px'>✅ APROBAR Y CREAR CUENTA</a>" +
                    "  </div>" +
                    "  <div style='text-align:center;margin-top:10px'>" +
                    "    <a href='%s' style='display:inline-block;padding:8px 16px;color:#ef4444;text-decoration:none;font-size:13px'>❌ Rechazar solicitud</a>" +
                    "  </div>" +
                    "  <hr style='border:none;border-top:1px solid #e2e8f0;margin:30px 0'>" +
                    "  <p style='font-size:11px;color:#94a3b8;text-align:center'>" +
                    "    Al aprobar, se creará la cuenta y el cliente recibirá un email con un enlace para crear su contraseña.<br>" +
                    "    Si prefieres revisarlo con más detalle, accede al <a href='%s/admin/solicitudes'>panel de admin</a>." +
                    "  </p>" +
                    "</div>",
                    esc(form.get("nombre")), esc(form.get("empresa")),
                    esc(form.get("email")), esc(form.get("email")),
                    esc(form.get("telefono")), esc(form.get("telefono")),
                    esc(form.get("plan")), esc(form.get("num_propiedades")),
                    esc(form.getOrDefault("mensaje", "(sin mensaje)")),
                    aprobarUrl, rechazarUrl, BASE_URL);

                for (String adminEmail : ADMIN_EMAILS) {
                    if (!adminEmail.isBlank()) {
                        emailService.send(null, adminEmail.trim(),
                                "🆕 Nueva solicitud: " + form.get("empresa") + " (" + form.get("plan") + ")",
                                adminHtml);
                    }
                }
            } catch (Exception e) {
                log.warn("Notif admin solicitud falló: {}", e.getMessage());
            }

            // Email de confirmación al solicitante
            try {
                String html = String.format(
                        "<h2>👋 ¡Gracias, %s!</h2>" +
                        "<p>Hemos recibido tu solicitud de cuenta InmobilIA Pro para <strong>%s</strong>.</p>" +
                        "<p>Vamos a revisarla y te enviaremos un email en <strong>menos de 24h laborables</strong> con tus credenciales de acceso.</p>" +
                        "<p>Mientras tanto, si quieres puedes <a href='%s/register?plan=free'>crear una cuenta Free gratuita</a> para ir probando la plataforma.</p>" +
                        "<p>— Equipo InmobilIA Pro</p>",
                        form.get("nombre"), form.get("empresa"), BASE_URL);
                emailService.send(null, String.valueOf(data.get("email")),
                        "✅ Hemos recibido tu solicitud InmobilIA Pro", html);
            } catch (Exception ignored) {}

            model.addAttribute("solicitud_ok", true);
            model.addAttribute("nombre", form.get("nombre"));
            return "solicitud_recibida";

        } catch (Exception e) {
            log.error("Err solicitud cuenta: {}", e.getMessage());
            model.addAttribute("error", "No se pudo procesar tu solicitud. Inténtalo más tarde.");
            return "solicitud_recibida";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PANEL ADMIN
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/admin/solicitudes")
    public String panel(HttpSession session, Model model,
                        @RequestParam(required = false) String estado) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!esPropietario(session)) return "redirect:/dashboard";

        StringBuilder q = new StringBuilder("order=created_at.desc&limit=100");
        if (estado != null && estado.matches("[a-z]{1,20}")) q.append("&estado=eq.").append(estado);
        List<Map<String, Object>> solicitudes = supabase.selectFilter("solicitudes_cuenta", q.toString());

        long pendientes = solicitudes.stream().filter(s -> "pendiente".equals(s.get("estado"))).count();
        long aprobadas = solicitudes.stream().filter(s -> "aprobada".equals(s.get("estado"))).count();
        long rechazadas = solicitudes.stream().filter(s -> "rechazada".equals(s.get("estado"))).count();

        model.addAttribute("solicitudes", solicitudes);
        model.addAttribute("pendientes", pendientes);
        model.addAttribute("aprobadas", aprobadas);
        model.addAttribute("rechazadas", rechazadas);
        return "admin_solicitudes";
    }

    @PostMapping("/admin/solicitudes/{id}/aprobar")
    public String aprobar(@PathVariable String id, HttpSession session) {
        String adminUid = SessionUtils.uid(session);
        if (adminUid == null) return "redirect:/login";
        if (!esPropietario(session)) return "redirect:/dashboard";

        List<Map<String, Object>> rows = supabase.selectFilter("solicitudes_cuenta", "id=eq." + id);
        if (rows.isEmpty()) return "redirect:/admin/solicitudes";
        Map<String, Object> s = rows.get(0);

        String email = String.valueOf(s.get("email"));
        String nombre = String.valueOf(s.get("nombre"));
        String empresa = String.valueOf(s.get("empresa"));
        String plan = String.valueOf(s.getOrDefault("plan", "pro"));

        // 1. Crear el usuario con una contraseña aleatoria que nadie conoce
        Map<String, String> reg = authService.register(email, AuthService.passwordAleatoria());
        if (reg == null) {
            SessionUtils.flash(session, "❌ Error: el email ya existe o es inválido");
            return "redirect:/admin/solicitudes";
        }

        // 2. Actualizar usuario con nombre + plan
        supabase.update("users", reg.get("id"), Map.of(
                "name", nombre,
                "plan", plan,
                "plan_status", "trialing",
                "trial_ends_at", OffsetDateTime.now().plusDays(14).toString(),
                "empresa", empresa
        ), reg.get("id"));

        // 3. Marcar solicitud como aprobada (ya NO se guarda ninguna contraseña)
        supabase.update("solicitudes_cuenta", id, Map.of(
                "estado", "aprobada",
                "aprobada_por", adminUid,
                "aprobada_at", OffsetDateTime.now().toString(),
                "user_creado_id", reg.get("id"),
                "email_enviado_at", OffsetDateTime.now().toString()
        ));

        // 3b. Sembrar automatizaciones por defecto (embudo completo)
        try { automationsSeeder.seed(reg.get("id")); } catch (Exception ignored) {}

        // 4. Email de bienvenida con enlace para crear la contraseña
        enviarBienvenida(email, nombre, empresa, plan, 14, enlaceBienvenida(reg.get("id"), email));

        // 6. Notificación interna para los admins
        notif.create(adminUid, "✅ Solicitud aprobada",
                nombre + " (" + empresa + ") - " + plan,
                "/admin/solicitudes", "media");

        SessionUtils.flash(session, "✅ Cuenta aprobada y email enviado a " + email);
        return "redirect:/admin/solicitudes";
    }

    @PostMapping("/admin/solicitudes/{id}/rechazar")
    public String rechazar(@PathVariable String id,
                            @RequestParam(required = false) String motivo,
                            HttpSession session) {
        String adminUid = SessionUtils.uid(session);
        if (adminUid == null) return "redirect:/login";
        if (!esPropietario(session)) return "redirect:/dashboard";

        supabase.update("solicitudes_cuenta", id, Map.of(
                "estado", "rechazada",
                "motivo_rechazo", motivo != null ? motivo : "Sin motivo especificado",
                "aprobada_por", adminUid
        ));

        // Email de rechazo amable
        try {
            List<Map<String, Object>> rows = supabase.selectFilter("solicitudes_cuenta", "id=eq." + id);
            if (!rows.isEmpty()) {
                Map<String, Object> s = rows.get(0);
                String html = String.format(
                    "<h2>Hola %s</h2>" +
                    "<p>Gracias por tu interés en InmobilIA Pro. Por el momento no podemos darte de alta porque:</p>" +
                    "<blockquote style='border-left:3px solid #94a3b8;padding-left:10px;color:#475569'>%s</blockquote>" +
                    "<p>Si crees que es un error, contesta a este email o pruébanos con una <a href='%s/register?plan=free'>cuenta Free gratis</a>.</p>" +
                    "<p>— Equipo InmobilIA Pro</p>",
                    s.get("nombre"), motivo != null ? motivo : "Tu solicitud no encaja en este momento",
                    BASE_URL);
                emailService.send(null, String.valueOf(s.get("email")),
                        "Tu solicitud InmobilIA Pro", html);
            }
        } catch (Exception ignored) {}

        SessionUtils.flash(session, "📩 Solicitud rechazada");
        return "redirect:/admin/solicitudes";
    }

    @PostMapping("/admin/solicitudes/{id}/notas")
    public String guardarNotas(@PathVariable String id,
                                @RequestParam String notas,
                                HttpSession session) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        if (!esPropietario(session)) return "redirect:/dashboard";
        supabase.update("solicitudes_cuenta", id, Map.of("notas_admin", notas));
        return "redirect:/admin/solicitudes";
    }

    /** Email de bienvenida: enlace para crear la contraseña (nunca una contraseña en claro). */
    private void enviarBienvenida(String email, String nombre, String empresa, String plan, int diasTrial, String enlace) {
        try {
            String html = String.format(
                "<div style='font-family:sans-serif;max-width:600px;margin:auto'>" +
                "  <h2>🎉 ¡Bienvenido a InmobilIA Pro, %s!</h2>" +
                "  <p>Tu cuenta para <strong>%s</strong> está lista. Tienes <strong>%d días gratis</strong> del plan %s.</p>" +
                "  <p>Para entrar, crea tu contraseña con este botón (el enlace sirve una sola vez y caduca en 7 días):</p>" +
                "  <p style='text-align:center;margin:30px 0'>" +
                "    <a href='%s' style='display:inline-block;padding:14px 28px;background:#0f172a;color:white;text-decoration:none;border-radius:8px;font-weight:bold'>Crear mi contraseña →</a>" +
                "  </p>" +
                "  <p style='color:#475569;font-size:14px'>Tu usuario es <strong>%s</strong>. Después podrás activar la verificación en dos pasos en Seguridad.</p>" +
                "  <p style='color:#94a3b8;font-size:12px'>Si el botón no funciona, copia este enlace en el navegador:<br>%s</p>" +
                "  <p>Si tienes cualquier duda, contesta a este email.</p>" +
                "  <p>— Equipo InmobilIA Pro</p>" +
                "</div>",
                esc(nombre), esc(empresa), diasTrial, esc(plan).toUpperCase(), enlace, esc(email), enlace);
            emailService.send(null, email, "🎉 Tu cuenta InmobilIA Pro está lista — crea tu contraseña", html);
        } catch (Exception e) {
            log.warn("Email aprobación falló: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ENDPOINTS POR TOKEN (desde email, sin login)
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/email-action/aprobar/{token}")
    public String aprobarPorToken(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter(
                "solicitudes_cuenta", "approval_token=eq." + token);
        if (rows.isEmpty()) {
            model.addAttribute("titulo", "❌ Enlace no válido");
            model.addAttribute("mensaje", "Este enlace no es válido o ya fue utilizado.");
            return "email_action_result";
        }
        Map<String, Object> s = rows.get(0);

        // Verificar token no usado
        if (s.get("token_used_at") != null) {
            model.addAttribute("titulo", "⚠️ Ya procesado");
            model.addAttribute("mensaje", "Esta solicitud ya fue procesada anteriormente.");
            model.addAttribute("solicitud", s);
            return "email_action_result";
        }

        // Verificar no expirado
        try {
            OffsetDateTime exp = OffsetDateTime.parse(String.valueOf(s.get("token_expires_at")));
            if (exp.isBefore(OffsetDateTime.now())) {
                model.addAttribute("titulo", "⏰ Enlace expirado");
                model.addAttribute("mensaje", "Este enlace ha caducado (7 días). Accede al panel admin manualmente.");
                return "email_action_result";
            }
        } catch (Exception ignored) {}

        // Ya estaba aprobada/rechazada antes desde el panel
        if (!"pendiente".equals(s.get("estado"))) {
            model.addAttribute("titulo", "⚠️ Ya procesada");
            model.addAttribute("mensaje", "Esta solicitud ya estaba en estado: " + s.get("estado"));
            model.addAttribute("solicitud", s);
            return "email_action_result";
        }

        // ─── APROBAR ───
        String email = String.valueOf(s.get("email"));
        String nombre = String.valueOf(s.get("nombre"));
        String empresa = String.valueOf(s.get("empresa"));
        String plan = String.valueOf(s.getOrDefault("plan", "pro"));

        // 1. Crear el usuario con una contraseña aleatoria que nadie conoce
        Map<String, String> reg = authService.register(email, AuthService.passwordAleatoria());
        if (reg == null) {
            model.addAttribute("titulo", "⚠️ No se pudo crear");
            model.addAttribute("mensaje",
                "El email ya existe en la base de datos o hay un problema técnico. " +
                "Accede al panel admin para resolverlo manualmente.");
            return "email_action_result";
        }

        // 3. Actualizar usuario con nombre + plan + duración trial
        int diasTrial = "starter".equals(plan) ? 10 : 14;
        supabase.update("users", reg.get("id"), Map.of(
                "name", nombre,
                "plan", plan,
                "plan_status", "trialing",
                "trial_ends_at", OffsetDateTime.now().plusDays(diasTrial).toString(),
                "empresa", empresa
        ), reg.get("id"));

        // 4. Marcar solicitud como aprobada + invalidar token (ya NO se guarda ninguna contraseña)
        supabase.update("solicitudes_cuenta", String.valueOf(s.get("id")), Map.of(
                "estado", "aprobada",
                "aprobada_at", OffsetDateTime.now().toString(),
                "user_creado_id", reg.get("id"),
                "email_enviado_at", OffsetDateTime.now().toString(),
                "token_used_at", OffsetDateTime.now().toString()
        ));

        // 4b. Sembrar automatizaciones por defecto (embudo completo)
        try { automationsSeeder.seed(reg.get("id")); } catch (Exception ignored) {}

        // 5. Email de bienvenida con enlace para crear la contraseña
        enviarBienvenida(email, nombre, empresa, plan, diasTrial, enlaceBienvenida(reg.get("id"), email));

        log.info(">>> Solicitud {} aprobada por email-action. User creado: {}", s.get("id"), reg.get("id"));

        model.addAttribute("titulo", "✅ Cuenta creada correctamente");
        model.addAttribute("mensaje", String.format(
            "Se ha creado la cuenta de <strong>%s</strong> con plan <strong>%s</strong> y <strong>%d días</strong> de prueba. " +
            "El cliente recibirá en unos segundos un email con un enlace para crear su contraseña.",
            esc(nombre), esc(plan).toUpperCase(), diasTrial));
        model.addAttribute("solicitud", s);
        model.addAttribute("plan", plan);
        model.addAttribute("dias", diasTrial);
        return "email_action_result";
    }

    @GetMapping("/email-action/rechazar/{token}")
    public String rechazarPorToken(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter(
                "solicitudes_cuenta", "approval_token=eq." + token);
        if (rows.isEmpty()) {
            model.addAttribute("titulo", "❌ Enlace no válido");
            model.addAttribute("mensaje", "Este enlace no es válido o ya fue utilizado.");
            return "email_action_result";
        }
        Map<String, Object> s = rows.get(0);

        if (s.get("token_used_at") != null) {
            model.addAttribute("titulo", "⚠️ Ya procesado");
            model.addAttribute("mensaje", "Esta solicitud ya fue procesada.");
            return "email_action_result";
        }

        // Mostrar form para que admin escriba motivo
        if (!"pendiente".equals(s.get("estado"))) {
            model.addAttribute("titulo", "⚠️ Ya procesada");
            model.addAttribute("mensaje", "Estado actual: " + s.get("estado"));
            return "email_action_result";
        }

        model.addAttribute("solicitud", s);
        model.addAttribute("token", token);
        return "email_action_rechazar";
    }

    @PostMapping("/email-action/rechazar/{token}")
    public String rechazarPorTokenPost(@PathVariable String token,
                                        @RequestParam String motivo,
                                        Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter(
                "solicitudes_cuenta", "approval_token=eq." + token);
        if (rows.isEmpty() || rows.get(0).get("token_used_at") != null) {
            model.addAttribute("titulo", "❌ Enlace no válido o ya usado");
            return "email_action_result";
        }
        Map<String, Object> s = rows.get(0);

        supabase.update("solicitudes_cuenta", String.valueOf(s.get("id")), Map.of(
                "estado", "rechazada",
                "motivo_rechazo", motivo,
                "token_used_at", OffsetDateTime.now().toString()
        ));

        // Email al cliente
        try {
            String html = String.format(
                "<h2>Hola %s</h2>" +
                "<p>Gracias por tu interés en InmobilIA Pro. Por el momento no podemos darte de alta porque:</p>" +
                "<blockquote style='border-left:3px solid #94a3b8;padding-left:10px;color:#475569'>%s</blockquote>" +
                "<p>Si crees que es un error, contesta a este email.</p>" +
                "<p>— Equipo InmobilIA Pro</p>",
                s.get("nombre"), motivo);
            emailService.send(null, String.valueOf(s.get("email")),
                    "Tu solicitud InmobilIA Pro", html);
        } catch (Exception ignored) {}

        model.addAttribute("titulo", "📩 Solicitud rechazada");
        model.addAttribute("mensaje", "Se ha enviado un email educado al solicitante con el motivo.");
        return "email_action_result";
    }
}
