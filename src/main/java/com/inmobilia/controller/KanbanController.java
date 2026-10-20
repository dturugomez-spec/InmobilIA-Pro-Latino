package com.inmobilia.controller;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pipeline Kanban (drag & drop) para Leads y Oportunidades.
 * Agrupa por etapa/status y permite mover tarjetas entre columnas.
 */
@Controller
@RequestMapping("/kanban")
@RequiredArgsConstructor
@Slf4j
public class KanbanController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    private static final List<String> ETAPAS_LEADS = List.of("Nuevo", "Contactado", "Visita", "Oferta", "Cerrado", "Perdido");
    private static final List<String> ETAPAS_OPORTUNIDADES = List.of("Nuevo", "Análisis", "Negociación", "Cerrado", "Descartado");

    // ─── KANBAN LEADS ───
    @GetMapping("/leads")
    public String leads(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "leads")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        var rows = supabase.query("leads", uid);

        // Agrupar por etapa
        Map<String, List<Map<String, Object>>> byEtapa = new LinkedHashMap<>();
        for (String e : ETAPAS_LEADS) byEtapa.put(e, new ArrayList<>());
        for (var r : rows) {
            String etapa = String.valueOf(r.getOrDefault("etapa", "Nuevo"));
            if (etapa.isBlank() || etapa.equals("null")) etapa = "Nuevo";
            byEtapa.computeIfAbsent(etapa, k -> new ArrayList<>()).add(r);
        }

        // Ordenar dentro de cada columna por score descendente
        byEtapa.values().forEach(list -> list.sort((a, b) -> {
            int sa = parseInt(a.get("score"));
            int sb = parseInt(b.get("score"));
            return Integer.compare(sb, sa);
        }));

        // Total presupuesto por etapa
        Map<String, Double> presupuestoEtapa = new LinkedHashMap<>();
        for (var entry : byEtapa.entrySet()) {
            double total = entry.getValue().stream()
                    .mapToDouble(r -> parseDouble(r.get("presupuesto")))
                    .sum();
            presupuestoEtapa.put(entry.getKey(), total);
        }

        model.addAttribute("page", "Pipeline Leads");
        model.addAttribute("kanbanType", "leads");
        model.addAttribute("etapas", ETAPAS_LEADS);
        model.addAttribute("byEtapa", byEtapa);
        model.addAttribute("presupuestoEtapa", presupuestoEtapa);
        model.addAttribute("canWrite", permissions.canWrite(role, "leads"));
        return "kanban";
    }

    // ─── KANBAN OPORTUNIDADES ───
    @GetMapping("/oportunidades")
    public String oportunidades(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "oportunidades")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        var rows = supabase.query("oportunidades", uid);

        Map<String, List<Map<String, Object>>> byEtapa = new LinkedHashMap<>();
        for (String e : ETAPAS_OPORTUNIDADES) byEtapa.put(e, new ArrayList<>());
        for (var r : rows) {
            String etapa = String.valueOf(r.getOrDefault("status", "Nuevo"));
            if (etapa.isBlank() || etapa.equals("null")) etapa = "Nuevo";
            byEtapa.computeIfAbsent(etapa, k -> new ArrayList<>()).add(r);
        }

        byEtapa.values().forEach(list -> list.sort((a, b) ->
            Double.compare(parseDouble(b.get("price")), parseDouble(a.get("price")))));

        Map<String, Double> presupuestoEtapa = new LinkedHashMap<>();
        for (var entry : byEtapa.entrySet()) {
            double total = entry.getValue().stream()
                    .mapToDouble(r -> parseDouble(r.get("price")))
                    .sum();
            presupuestoEtapa.put(entry.getKey(), total);
        }

        model.addAttribute("page", "Pipeline Oportunidades");
        model.addAttribute("kanbanType", "oportunidades");
        model.addAttribute("etapas", ETAPAS_OPORTUNIDADES);
        model.addAttribute("byEtapa", byEtapa);
        model.addAttribute("presupuestoEtapa", presupuestoEtapa);
        model.addAttribute("canWrite", permissions.canWrite(role, "oportunidades"));
        return "kanban";
    }

    // ─── ENDPOINT AJAX: cambiar etapa ───
    @PostMapping("/{type}/{id}/move")
    @ResponseBody
    public Map<String, Object> move(@PathVariable String type,
                                    @PathVariable String id,
                                    @RequestBody Map<String, String> body,
                                    HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false, "error", "Sesión expirada");
        String role = SessionUtils.role(session);

        String table, field;
        if ("leads".equals(type)) {
            if (!permissions.canWrite(role, "leads")) return Map.of("ok", false, "error", "Sin permisos");
            table = "leads"; field = "etapa";
        } else if ("oportunidades".equals(type)) {
            if (!permissions.canWrite(role, "oportunidades")) return Map.of("ok", false, "error", "Sin permisos");
            table = "oportunidades"; field = "status";
        } else {
            return Map.of("ok", false, "error", "Tipo no válido");
        }

        String newStage = body.get("stage");
        if (newStage == null || newStage.isBlank()) return Map.of("ok", false, "error", "Etapa requerida");

        String uid = SessionUtils.uid(session);
        boolean ok = supabase.update(table, id, Map.of(field, newStage), uid);
        return Map.of("ok", ok, "id", id, "stage", newStage);
    }

    private static int parseInt(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
    private static double parseDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
