package com.inmobilia.controller;

import com.inmobilia.service.AuthService;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Portal del inquilino: el inquilino accede con su propio login para ver sus
 * recibos, contratos, pagos y crear solicitudes de mantenimiento.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class TenantPortalController {

    private final SupabaseService supabase;
    private final AuthService authService;
    private final EmailService emailService;

    @Value("${app.base-url:https://inmobiliapro.es}")
    private String appBaseUrl;

    // ─── Admin: invitar inquilino al portal ───
    @PostMapping("/inquilinos/{id}/invite-portal")
    public String invite(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        var rows = supabase.findBy("inquilinos", Map.of("id", id, "user_id", orgId));
        if (rows.isEmpty()) return "redirect:/crud/inquilinos";
        Map<String, Object> inq = rows.get(0);

        String email = String.valueOf(inq.getOrDefault("email", ""));
        if (email.isBlank() || !email.contains("@")) {
            SessionUtils.flash(session, "El inquilino no tiene email. Edítalo y añade uno.");
            return "redirect:/entity/inquilino/" + id;
        }

        String token = randomToken();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inquilino_id", id);
        data.put("org_id", orgId);
        data.put("email", email);
        data.put("token", token);
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("tenant_invites", data);

        // Email
        String link = appBaseUrl + "/portal/activar?token=" + token;
        String body = "Hola " + inq.getOrDefault("name", "") + ",\n\n" +
                "Tu administrador ha activado el Portal del Inquilino para que puedas:\n" +
                "• Ver tus recibos de alquiler\n" +
                "• Consultar tu contrato\n" +
                "• Solicitar reparaciones\n" +
                "• Subir documentos\n\n" +
                "Activa tu cuenta aquí (válido 14 días):\n" + link + "\n\n— InmobilIA Pro";
        try { emailService.send(null, email, "Acceso al Portal del Inquilino", body); } catch (Exception ignored) {}

        SessionUtils.flash(session, "Invitación enviada a " + email);
        return "redirect:/entity/inquilino/" + id;
    }

    // ─── Inquilino: activar cuenta con token ───
    @GetMapping("/portal/activar")
    public String activate(@RequestParam String token, Model model) {
        var rows = supabase.findBy("tenant_invites", Map.of("token", token));
        if (rows.isEmpty()) {
            model.addAttribute("error", "Enlace inválido o expirado.");
            return "tenant_activate";
        }
        Map<String, Object> invite = rows.get(0);
        if (invite.get("accepted_at") != null && !"null".equals(String.valueOf(invite.get("accepted_at")))) {
            model.addAttribute("error", "Este enlace ya fue usado. Si olvidaste tu contraseña usa /portal/login y 'recuperar'.");
            return "tenant_activate";
        }
        model.addAttribute("token", token);
        model.addAttribute("email", invite.get("email"));
        return "tenant_activate";
    }

    @PostMapping("/portal/activar")
    public String doActivate(@RequestParam String token,
                             @RequestParam String password,
                             jakarta.servlet.http.HttpServletRequest req,
                             HttpSession session,
                             Model model) {
        var rows = supabase.findBy("tenant_invites", Map.of("token", token));
        if (rows.isEmpty()) {
            model.addAttribute("error", "Enlace inválido");
            return "tenant_activate";
        }
        Map<String, Object> invite = rows.get(0);
        // El enlace sirve una sola vez (antes solo se comprobaba al ABRIRLO, no al enviarlo)
        if (invite.get("accepted_at") != null && !"null".equals(String.valueOf(invite.get("accepted_at")))) {
            model.addAttribute("error", "Este enlace ya fue usado. Si olvidaste tu contraseña usa /portal/login y 'recuperar'.");
            return "tenant_activate";
        }
        String problema = AuthService.problemaPassword(password);
        if (problema != null) {
            model.addAttribute("error", problema);
            model.addAttribute("token", token);
            model.addAttribute("email", invite.get("email"));
            return "tenant_activate";
        }
        String email = String.valueOf(invite.get("email"));
        String inquilinoId = String.valueOf(invite.get("inquilino_id"));
        String orgId = String.valueOf(invite.get("org_id"));

        // Crear user del inquilino con rol tenant_portal
        Map<String, Object> userData = new LinkedHashMap<>();
        userData.put("email", email);
        userData.put("password", authService.hashPw(password));
        userData.put("role", "tenant_portal");
        userData.put("organization_id", orgId);
        userData.put("tenant_id", inquilinoId);
        userData.put("active", true);
        userData.put("email_verified", true);
        userData.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("users", userData);
        if (saved == null || saved.isEmpty()) {
            model.addAttribute("error", "Error creando cuenta. ¿Email duplicado?");
            model.addAttribute("token", token);
            return "tenant_activate";
        }

        // Marcar invite como aceptada
        supabase.update("tenant_invites", String.valueOf(invite.get("id")),
                Map.of("accepted_at", OffsetDateTime.now().toString()), orgId);

        // Auto-login (sesión nueva)
        req.changeSessionId();
        SessionUtils.loginFull(session, String.valueOf(saved.get("id")), orgId, email, email, "tenant_portal");
        return "redirect:/portal";
    }

    // ─── Inquilino: dashboard ───
    @GetMapping("/portal")
    public String dashboard(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!"tenant_portal".equals(SessionUtils.role(session))) return "redirect:/dashboard";

        String myUid = SessionUtils.realUid(session);
        var users = supabase.findBy("users", Map.of("id", myUid));
        if (users.isEmpty()) return "redirect:/login";
        Map<String, Object> me = users.get(0);

        String tenantId = String.valueOf(me.getOrDefault("tenant_id", ""));
        String orgId = String.valueOf(me.getOrDefault("organization_id", ""));

        Map<String, Object> inq = Map.of();
        if (!tenantId.isBlank() && !"null".equals(tenantId)) {
            var inqRows = supabase.findBy("inquilinos", Map.of("id", tenantId));
            if (!inqRows.isEmpty()) inq = inqRows.get(0);
        }

        String inqName = String.valueOf(inq.getOrDefault("name", ""));
        String propName = String.valueOf(inq.getOrDefault("property_name", ""));

        // Recibos del inquilino
        var receipts = supabase.findBy("receipts", Map.of(
                "user_id", orgId,
                "inquilino_nombre", inqName)).stream()
                .sorted((a, b) -> String.valueOf(b.get("fecha_emision")).compareTo(String.valueOf(a.get("fecha_emision"))))
                .toList();

        // Solicitudes anteriores
        var requests = supabase.findBy("tenant_requests", Map.of("tenant_user_id", myUid)).stream()
                .sorted((a, b) -> String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at"))))
                .limit(20)
                .toList();

        // Contrato
        var contracts = supabase.query("contratos", orgId).stream()
                .filter(c -> inqName.equalsIgnoreCase(String.valueOf(c.getOrDefault("tenant_name", ""))))
                .toList();

        model.addAttribute("page", "Mi portal");
        model.addAttribute("inq", inq);
        model.addAttribute("receipts", receipts);
        model.addAttribute("requests", requests);
        model.addAttribute("contracts", contracts);
        return "tenant_dashboard";
    }

    // ─── Inquilino: crear solicitud de reparación ───
    @PostMapping("/portal/request")
    public String createRequest(@RequestParam String title,
                                @RequestParam String category,
                                @RequestParam(required = false) String description,
                                @RequestParam(defaultValue = "Media") String priority,
                                HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!"tenant_portal".equals(SessionUtils.role(session))) return "redirect:/dashboard";

        String myUid = SessionUtils.realUid(session);
        var users = supabase.findBy("users", Map.of("id", myUid));
        if (users.isEmpty()) return "redirect:/portal";
        Map<String, Object> me = users.get(0);

        String orgId = String.valueOf(me.get("organization_id"));
        String tenantId = String.valueOf(me.get("tenant_id"));

        var inqRows = supabase.findBy("inquilinos", Map.of("id", tenantId));
        Map<String, Object> inq = inqRows.isEmpty() ? Map.of() : inqRows.get(0);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("tenant_user_id", myUid);
        req.put("inquilino_id", tenantId);
        req.put("org_id", orgId);
        req.put("property_name", inq.getOrDefault("property_name", ""));
        req.put("category", category);
        req.put("title", title);
        req.put("description", description);
        req.put("priority", priority);
        req.put("status", "Nueva");
        req.put("created_at", OffsetDateTime.now().toString());

        supabase.insert("tenant_requests", req);
        SessionUtils.flash(session, "Solicitud enviada. Te contestaremos lo antes posible.");
        return "redirect:/portal";
    }

    // ─── Admin: ver bandeja de solicitudes de inquilinos ───
    @GetMapping("/admin/tenant-requests")
    public String adminInbox(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if ("tenant_portal".equals(SessionUtils.role(session))) return "redirect:/portal";

        String orgId = SessionUtils.uid(session);
        var rows = supabase.findBy("tenant_requests", Map.of("org_id", orgId)).stream()
                .sorted((a, b) -> String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at"))))
                .toList();
        model.addAttribute("page", "Solicitudes de inquilinos");
        model.addAttribute("rows", rows);
        return "tenant_requests_admin";
    }

    @PostMapping("/admin/tenant-requests/{id}/respond")
    public String respond(@PathVariable String id,
                          @RequestParam String response,
                          @RequestParam(defaultValue = "En curso") String status,
                          HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("response", response);
        upd.put("status", status);
        upd.put("responded_at", OffsetDateTime.now().toString());
        supabase.update("tenant_requests", id, upd, orgId);

        SessionUtils.flash(session, "Respondido al inquilino");
        return "redirect:/admin/tenant-requests";
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
