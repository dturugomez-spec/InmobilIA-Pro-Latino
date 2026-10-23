package com.inmobilia.controller;

import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.AuthService;
import com.inmobilia.service.PermissionService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.PlazasService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Gestión del equipo (RRHH + admin): listar, invitar, cambiar rol, activar/desactivar.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class OrgController {

    private final AuthService authService;
    private final PermissionService permissions;
    private final PlanService planService;
    private final AuditTrail audit;
    private final PlazasService plazas;

    @GetMapping("/equipo")
    public String list(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "equipo")) return "redirect:/dashboard";

        String orgId = SessionUtils.uid(session);
        List<Map<String, Object>> miembros = authService.teamMembers(orgId);

        long totalActivos = miembros.stream()
                .filter(m -> m.get("active") == null || Boolean.TRUE.equals(m.get("active")) || "true".equalsIgnoreCase(String.valueOf(m.get("active"))))
                .count();

        model.addAttribute("page", "Equipo y empleados");
        model.addAttribute("miembros", miembros);
        model.addAttribute("totalActivos", totalActivos);
        model.addAttribute("roles", PermissionService.ALL_ROLES);
        model.addAttribute("roleLabels", PermissionService.ROLE_LABELS);
        model.addAttribute("roleIcons", PermissionService.ROLE_ICONS);
        model.addAttribute("canWrite", permissions.canWrite(role, "equipo"));
        return "equipo";
    }

    @GetMapping("/equipo/invitar")
    public String inviteForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "equipo")) return "redirect:/equipo";

        // Gate: si ha alcanzado el límite de miembros, ir a upgrade
        String orgId = SessionUtils.uid(session);
        if (!planService.canAddTeamMember(orgId)) {
            SessionUtils.flash(session, "Has alcanzado el límite de empleados de tu plan. Mejora para añadir más.");
            return "redirect:/upgrade";
        }

        model.addAttribute("page", "Invitar a un empleado");
        coste(orgId, model);
        model.addAttribute("roles", PermissionService.ALL_ROLES);
        model.addAttribute("roleLabels", PermissionService.ROLE_LABELS);
        model.addAttribute("roleIcons", PermissionService.ROLE_ICONS);
        return "equipo_invitar";
    }

    @PostMapping("/equipo/invitar")
    public String invite(@RequestParam String email,
                         @RequestParam String password,
                         @RequestParam(required = false) String name,
                         @RequestParam(required = false) String role,
                         HttpServletRequest req,
                         HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String myRole = SessionUtils.role(session);
        if (!permissions.canWrite(myRole, "equipo")) return "redirect:/equipo";

        String rol = role != null ? role : PermissionService.COMERCIAL;
        String problema = problemaRol(rol, myRole);
        if (problema == null) problema = AuthService.problemaPassword(password);
        if (problema != null) {
            SessionUtils.flash(session, "❌ " + problema);
            return "redirect:/equipo/invitar";
        }

        String orgId = SessionUtils.uid(session);
        // El límite se comprobaba solo al abrir el formulario: enviándolo directamente se saltaba
        if (!planService.canAddTeamMember(orgId)) {
            SessionUtils.flash(session, "Tu plan no permite añadir más empleados.");
            return "redirect:/upgrade";
        }
        String invitedBy = SessionUtils.realUid(session);

        boolean ok = authService.invite(email, password,
                name != null ? name : email,
                rol, orgId, invitedBy);
        if (ok) {
            audit.registrar(req, session, "equipo_alta", "users", null,
                    "Dio de alta a " + email + " con rol " + permissions.label(rol), "security", AuditTrail.EQUIPO);
            plazas.sincronizarEnSegundoPlano(orgId);   // se cobra por usuario
        }

        SessionUtils.flash(session, ok ? "Empleado añadido al equipo correctamente." : "ERROR al añadir empleado. ¿Usuario duplicado o datos inválidos?");
        return "redirect:/equipo";
    }

    @PostMapping("/equipo/{id}/role")
    public String changeRole(@PathVariable String id,
                             @RequestParam String role,
                             HttpServletRequest req,
                             HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String myRole = SessionUtils.role(session);
        if (!permissions.canWrite(myRole, "equipo")) return "redirect:/equipo";
        if (id.equals(SessionUtils.realUid(session))) {
            SessionUtils.flash(session, "No puedes cambiar tu propio rol.");
            return "redirect:/equipo";
        }
        String problema = problemaRol(role, myRole);
        if (problema != null) {
            SessionUtils.flash(session, "❌ " + problema);
            return "redirect:/equipo";
        }
        String orgId = SessionUtils.uid(session);
        if (!PermissionService.ADMIN.equals(myRole) && esAdmin(id, orgId)) {
            SessionUtils.flash(session, "Solo un administrador puede cambiar el rol de otro administrador.");
            return "redirect:/equipo";
        }

        boolean ok = authService.changeRole(id, role, orgId);
        if (ok) audit.registrar(req, session, "equipo_rol", "users", id,
                "Cambió el rol de " + emailDe(id) + " a " + permissions.label(role), "security", AuditTrail.EQUIPO);
        SessionUtils.flash(session, ok ? "Rol actualizado." : "ERROR al cambiar rol.");
        return "redirect:/equipo";
    }

    @PostMapping("/equipo/{id}/toggle")
    public String toggleActive(@PathVariable String id,
                               @RequestParam(defaultValue = "false") boolean active,
                               HttpServletRequest req,
                               HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String myRole = SessionUtils.role(session);
        if (!permissions.canWrite(myRole, "equipo")) return "redirect:/equipo";
        if (id.equals(SessionUtils.realUid(session))) {
            SessionUtils.flash(session, "No puedes desactivarte a ti mismo.");
            return "redirect:/equipo";
        }

        String orgId = SessionUtils.uid(session);
        if (!PermissionService.ADMIN.equals(myRole) && esAdmin(id, orgId)) {
            SessionUtils.flash(session, "Solo un administrador puede desactivar a otro administrador.");
            return "redirect:/equipo";
        }
        boolean ok = authService.setActive(id, active, orgId);
        if (ok) plazas.sincronizarEnSegundoPlano(orgId);   // desactivado = deja de cobrarse
        if (ok) audit.registrar(req, session, active ? "equipo_reactivado" : "equipo_desactivado", "users", id,
                (active ? "Reactivó a " : "Desactivó a ") + emailDe(id) + (active ? "" : " (pierde el acceso en menos de 1 minuto)"),
                active ? "security" : "critical", AuditTrail.EQUIPO);
        SessionUtils.flash(session, ok ? (active ? "Usuario reactivado." : "Usuario desactivado: pierde el acceso en menos de un minuto.") : "ERROR.");
        return "redirect:/equipo";
    }

    /** Lo que cuesta un usuario más, para avisarlo antes de invitar (se cobra por usuario). */
    private void coste(String orgId, Model model) {
        Map<String, Object> titular = authService.findById(orgId).orElse(null);
        String plan = titular == null ? "" : String.valueOf(titular.getOrDefault("plan", "")).toLowerCase();
        int usuarios = plazas.contar(orgId);
        model.addAttribute("suscrito", planService.tieneSuscripcionActiva(titular));
        model.addAttribute("usuariosAhora", usuarios);
        model.addAttribute("precioUsuario", PlanService.precioMensual(plan, 1));
        model.addAttribute("precioConNuevo", PlanService.precioMensual(plan, usuarios + 1));
    }

    /** Rol válido y que quien lo asigna puede dar (solo un admin crea admins). */
    private static String problemaRol(String rol, String miRol) {
        if (rol == null || !PermissionService.ALL_ROLES.contains(rol)) return "Rol no válido.";
        if (PermissionService.ADMIN.equals(rol) && !PermissionService.ADMIN.equals(miRol))
            return "Solo un administrador puede dar el rol de administrador.";
        return null;
    }

    private boolean esAdmin(String userId, String orgId) {
        return authService.findById(userId)
                .filter(u -> orgId.equals(String.valueOf(u.get("organization_id"))))
                .map(u -> PermissionService.ADMIN.equals(String.valueOf(u.get("role"))))
                .orElse(false);
    }

    private String emailDe(String userId) {
        return authService.findById(userId).map(u -> String.valueOf(u.getOrDefault("email", userId))).orElse(userId);
    }
}
