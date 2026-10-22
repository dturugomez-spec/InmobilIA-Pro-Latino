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
    private final com.inmobilia.service.WorkflowEngine workflows;

    private static final List<String> ETAPAS_LEADS = List.of("Nuevo", "Contactado", "Cualificado", "Visita", "Oferta", "Cerrado", "Perdido");
    private static final List<String> ETAPAS_OPORTUNIDADES = List.of("Nuevo", "Análisis", "Negociación", "Cerrado", "Descartado");

    /**
     * Columna del tablero para un estado. Antes, una tarjeta cuyo estado no coincidía letra a
     * letra con una columna ("Analizando", "Negociando", "Cualificado"…, escritos en la ficha o
     * por una automatización) no salía en ninguna columna: desaparecía del tablero.
     */
    static String columna(Object valor, List<String> etapas) {
        String v = plano(valor);
        if (v.isEmpty() || v.equals("null")) return etapas.get(0);
        for (String e : etapas) if (plano(e).equals(v)) return e;
        String raiz = v.substring(0, Math.min(5, v.length()));
        for (String e : etapas) if (plano(e).startsWith(raiz)) return e;
        String[][] sinonimos = {{"ganad", "Cerrado"}, {"vendid", "Cerrado"}, {"escritur", "Cerrado"}, {"firmad", "Cerrado"},
                {"comprad", "Cerrado"}, {"alquilad", "Cerrado"}, {"perdid", "Perdido"}, {"caid", "Perdido"}, {"rechaz", "Perdido"},
                {"descart", "Perdido"}, {"perdid", "Descartado"}, {"caid", "Descartado"}, {"rechaz", "Descartado"},
                {"calif", "Cualificado"}, {"llamad", "Contactado"}, {"estudi", "Análisis"}, {"revis", "Análisis"}};
        for (String[] s : sinonimos) if (v.startsWith(s[0]) && etapas.contains(s[1])) return s[1];
        return etapas.get(0);
    }

    private static String plano(Object o) {
        return java.text.Normalizer.normalize(String.valueOf(o == null ? "" : o), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().trim();
    }

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
        for (var r : rows) byEtapa.get(columna(r.get("etapa"), ETAPAS_LEADS)).add(r);

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
        for (var r : rows) byEtapa.get(columna(r.get("status"), ETAPAS_OPORTUNIDADES)).add(r);

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
        List<Map<String, Object>> previo = supabase.selectOwned(table, id, uid);
        boolean ok = supabase.update(table, id, Map.of(field, newStage), uid);
        // Automatizaciones: tarjeta movida (y "lead cualificado" si entra en esa etapa)
        if (ok && !previo.isEmpty() && !newStage.equals(String.valueOf(previo.get(0).get(field)))) {
            Map<String, Object> evento = new LinkedHashMap<>(previo.get(0));
            evento.put(field, newStage);
            evento.put("columna_anterior", previo.get(0).get(field));
            evento.put("columna_nueva", newStage);
            if ("leads".equals(table)) evento.put("lead_id", id);
            workflows.triggerEvent(uid, "kanban_card_movida", evento);
            if ("leads".equals(table) && newStage.toLowerCase().matches("c?alificad.*|cualificad.*"))
                workflows.triggerEvent(uid, "lead_calificado", evento);
        }
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
