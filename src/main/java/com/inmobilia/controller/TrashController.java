package com.inmobilia.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
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

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Papelera: items eliminados se guardan 30 días antes de desaparecer.
 * El usuario puede restaurarlos o vaciar la papelera manualmente.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class TrashController {

    private final SupabaseService supabase;
    private final ObjectMapper mapper;
    private final PermissionService permissions;
    private final AuditTrail audit;

    @GetMapping("/trash")
    public String view(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        // Cada persona ve solo lo borrado de las secciones que puede ver
        String role = SessionUtils.role(session);
        var rows = supabase.selectFilter("trash", "org_id=eq." + orgId + "&order=deleted_at.desc&limit=2000").stream()
                .filter(t -> {
                    String tabla = String.valueOf(t.get("table_name"));
                    return PermissionService.SECTIONS.contains(tabla)
                            ? permissions.canRead(role, tabla)
                            : PermissionService.ADMIN.equals(role);
                })
                .sorted((a, b) -> String.valueOf(b.get("deleted_at")).compareTo(String.valueOf(a.get("deleted_at"))))
                .toList();

        model.addAttribute("page", "Papelera");
        model.addAttribute("rows", rows);
        return "trash";
    }

    @PostMapping("/trash/{id}/restore")
    public String restore(@PathVariable String id, HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        var rows = supabase.findBy("trash", Map.of("id", id, "org_id", orgId));
        if (rows.isEmpty()) return "redirect:/trash";

        Map<String, Object> entry = rows.get(0);
        String tableName = String.valueOf(entry.get("table_name"));
        // Restaurar = volver a crear el registro: exige permiso de escritura en esa sección
        String rol = SessionUtils.role(session);
        if (PermissionService.SECTIONS.contains(tableName) ? !permissions.canWrite(rol, tableName)
                                                           : !PermissionService.ADMIN.equals(rol)) {
            SessionUtils.flash(session, "Tu rol no tiene permiso para restaurar registros de esta sección.");
            return "redirect:/trash";
        }
        audit.registrar(req, session, "restaurar", tableName, String.valueOf(entry.get("record_id")),
                "Restauró un registro desde la papelera", "info", AuditTrail.DATOS);
        Object snapshotObj = entry.get("snapshot");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = snapshotObj instanceof Map ? (Map<String, Object>) snapshotObj
                    : mapper.readValue(String.valueOf(snapshotObj), Map.class);
            // Quitar id para que se asigne uno nuevo (o restaurar el mismo si la BBDD permite)
            Map<String, Object> data = new LinkedHashMap<>(snap);
            // Insertar de nuevo
            supabase.insert(tableName, data);
            // Eliminar de la papelera
            supabase.delete("trash", id, orgId);
            SessionUtils.flash(session, "Registro restaurado en " + tableName);
        } catch (Exception e) {
            log.error("Error restaurando: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR restaurando: " + e.getMessage());
        }
        return "redirect:/trash";
    }

    @PostMapping("/trash/{id}/delete")
    public String deleteForever(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        var rows = supabase.findBy("trash", Map.of("id", id, "org_id", orgId));
        if (rows.isEmpty()) return "redirect:/trash";
        if (!puedeEliminar(session, rows.get(0))) {
            SessionUtils.flash(session, "Tu rol no tiene permiso para eliminar registros de esta sección.");
            return "redirect:/trash";
        }
        supabase.delete("trash", id, orgId);
        SessionUtils.flash(session, "Eliminado permanentemente");
        return "redirect:/trash";
    }

    /** Vacía solo lo que esta persona podría eliminar una a una (antes cualquiera borraba todo). */
    @PostMapping("/trash/empty")
    public String emptyTrash(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        try {
            int n = 0;
            for (var t : supabase.findBy("trash", Map.of("org_id", orgId))) {
                if (!puedeEliminar(session, t)) continue;
                supabase.delete("trash", String.valueOf(t.get("id")), orgId);
                n++;
            }
            SessionUtils.flash(session, "Papelera vaciada (" + n + " elementos)");
        } catch (Exception e) {
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
        }
        return "redirect:/trash";
    }

    private boolean puedeEliminar(HttpSession session, Map<String, Object> entrada) {
        String tabla = String.valueOf(entrada.get("table_name"));
        String rol = SessionUtils.role(session);
        return PermissionService.SECTIONS.contains(tabla) ? permissions.canWrite(rol, tabla)
                                                          : PermissionService.ADMIN.equals(rol);
    }
}
