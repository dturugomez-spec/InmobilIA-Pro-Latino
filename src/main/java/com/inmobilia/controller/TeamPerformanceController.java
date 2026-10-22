package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.*;

/**
 * 👥 Rendimiento del equipo: métricas por agente (leads asignados, firmados, visitas)
 * para el director de la agencia. Multi-agente (Agency/Enterprise).
 */
@Controller
@RequiredArgsConstructor
public class TeamPerformanceController {

    private final SupabaseService supabase;

    @GetMapping("/rendimiento")
    public String equipo(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Miembros del equipo: el titular + empleados (organization_id). Antes filtraba por
        // users.org_id, que no existe: la consulta fallaba y la pantalla salía vacía.
        List<Map<String, Object>> miembros = safe("users", "or=(organization_id.eq." + uid + ",id.eq." + uid + ")&role=neq.tenant_portal&limit=100");

        List<Map<String, Object>> leads = safe("leads", "user_id=eq." + uid + "&limit=2000");
        List<Map<String, Object>> visitas = safe("visitas", "user_id=eq." + uid + "&limit=2000");

        // Conteos por agente
        Map<String, long[]> porAgente = new HashMap<>();  // agenteId -> [leads, firmados, visitas]
        long sinAsignar = 0;
        for (Map<String, Object> l : leads) {
            String ag = str(l.get("asignado_a"));
            if (ag.isBlank() || "null".equals(ag)) { sinAsignar++; continue; }
            long[] s = porAgente.computeIfAbsent(ag, k -> new long[3]);
            s[0]++;
            String etapa = str(l.get("etapa")).toLowerCase();
            if (etapa.startsWith("cerrad") || etapa.startsWith("firmad") || etapa.startsWith("ganad")) s[1]++;
        }
        for (Map<String, Object> v : visitas) {
            String ag = str(v.get("agente_id"));
            if (!ag.isBlank() && !"null".equals(ag)) porAgente.computeIfAbsent(ag, k -> new long[3])[2]++;
        }

        List<Map<String, Object>> filas = new ArrayList<>();
        for (Map<String, Object> m : miembros) {
            String id = str(m.get("id"));
            long[] s = porAgente.getOrDefault(id, new long[3]);
            double conv = s[0] > 0 ? Math.round(s[1] * 1000.0 / s[0]) / 10.0 : 0;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("nombre", m.getOrDefault("name", m.getOrDefault("email", "Agente")));
            f.put("esAdmin", uid.equals(id));
            f.put("leads", s[0]);
            f.put("firmados", s[1]);
            f.put("visitas", s[2]);
            f.put("conversion", conv);
            f.put("actividad", s[0] + s[2] * 2 + s[1] * 5);  // ranking simple
            filas.add(f);
        }
        filas.sort((a, b) -> Long.compare((long) b.get("actividad"), (long) a.get("actividad")));

        model.addAttribute("filas", filas);
        model.addAttribute("sinAsignar", sinAsignar);
        model.addAttribute("totalLeads", leads.size());
        model.addAttribute("nMiembros", miembros.size());
        return "rendimiento";
    }

    private List<Map<String, Object>> safe(String t, String f) {
        try { return supabase.selectFilter(t, f); } catch (Exception e) { return List.of(); }
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
