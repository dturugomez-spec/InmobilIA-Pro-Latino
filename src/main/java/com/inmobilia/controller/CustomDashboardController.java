package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/dashboards")
public class CustomDashboardController {

    private final SupabaseService supabase;

    /** Lista de dashboards. */
    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> dashes = supabase.selectFilter(
                "dashboards_custom", "user_id=eq." + uid + "&order=es_default.desc,nombre.asc");

        if (dashes.isEmpty()) {
            crearDashboardDefault(uid);
            dashes = supabase.selectFilter("dashboards_custom", "user_id=eq." + uid);
        }

        model.addAttribute("dashboards", dashes);
        return "custom_dashboards_list";
    }

    /** Ver dashboard. */
    @GetMapping("/{id}")
    public String view(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter(
                "dashboards_custom", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/dashboards";

        Map<String, Object> dash = rows.get(0);

        // Calcular datos para cada widget
        Map<String, Object> datos = calcularDatosWidgets(uid);

        model.addAttribute("dashboard", dash);
        model.addAttribute("datos", datos);
        return "custom_dashboard_view";
    }

    /** Editar dashboard. */
    @GetMapping("/{id}/editar")
    public String edit(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter(
                "dashboards_custom", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/dashboards";
        model.addAttribute("dashboard", rows.get(0));
        return "custom_dashboard_edit";
    }

    @PostMapping("/guardar")
    @ResponseBody
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", body.get("nombre"));
        data.put("descripcion", body.get("descripcion"));
        data.put("icono", body.getOrDefault("icono", "📊"));
        data.put("widgets", body.get("widgets"));
        data.put("columnas", body.getOrDefault("columnas", 12));
        data.put("updated_at", OffsetDateTime.now().toString());

        String id = String.valueOf(body.get("id"));
        if (id != null && !"null".equals(id) && !id.isBlank()) {
            boolean ok = supabase.update("dashboards_custom", id, data, uid); // solo los propios
            return Map.of("ok", ok, "id", id);
        } else {
            data.put("created_at", OffsetDateTime.now().toString());
            Map<String, Object> saved = supabase.insert("dashboards_custom", data);
            return Map.of("ok", true, "id", saved.get("id"));
        }
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.delete("dashboards_custom", id, uid);
        return "redirect:/dashboards";
    }

    private Map<String, Object> calcularDatosWidgets(String uid) {
        Map<String, Object> datos = new LinkedHashMap<>();

        // Propiedades activas
        long propsActivas = supabase.selectFilter("propiedades", "user_id=eq." + uid + "&estado=neq.vendida").size();
        datos.put("kpi_propiedades", propsActivas);

        // Leads totales / por estado
        List<Map<String, Object>> leads = supabase.selectFilter("leads", "user_id=eq." + uid);
        datos.put("kpi_leads", leads.size());
        datos.put("kpi_leads_hot", leads.stream().filter(l -> "hot".equals(l.get("temperatura"))).count());
        datos.put("kpi_leads_nuevos", leads.stream().filter(l -> "nuevo".equals(l.get("estado"))).count());

        // Visitas mes
        String mesActual = java.time.LocalDate.now().toString().substring(0, 7);
        List<Map<String, Object>> visitas = supabase.selectFilter("visitas", "user_id=eq." + uid);
        long visitasMes = visitas.stream().filter(v -> String.valueOf(v.get("fecha_inicio")).startsWith(mesActual)).count();
        datos.put("kpi_visitas_mes", visitasMes);

        // Facturas pagadas mes
        List<Map<String, Object>> facturas = supabase.selectFilter("invoices", "select=fecha,total,status&user_id=eq." + uid + "&status=neq.Anulada");
        double facturadoMes = facturas.stream()
                .filter(f -> String.valueOf(f.get("fecha")).startsWith(mesActual))
                .mapToDouble(f -> nd(f.get("total"))).sum();
        datos.put("kpi_facturado_mes", facturadoMes);

        // Tareas pendientes
        long tareasPendientes = supabase.selectFilter("tareas", "user_id=eq." + uid + "&estado=eq.pendiente").size();
        datos.put("kpi_tareas_pendientes", tareasPendientes);

        // Contratos activos
        long contratosActivos = supabase.selectFilter("contratos", "user_id=eq." + uid + "&estado=eq.activo").size();
        datos.put("kpi_contratos", contratosActivos);

        // Matches nuevos
        long matchesNuevos = supabase.selectFilter("matches", "user_id=eq." + uid + "&estado=eq.nuevo").size();
        datos.put("kpi_matches_nuevos", matchesNuevos);

        return datos;
    }

    private void crearDashboardDefault(String uid) {
        List<Map<String, Object>> widgets = new ArrayList<>();
        widgets.add(widget("kpi", "kpi_propiedades", "🏠 Propiedades", "#3b82f6", 3, 2));
        widgets.add(widget("kpi", "kpi_leads", "📋 Leads", "#8b5cf6", 3, 2));
        widgets.add(widget("kpi", "kpi_visitas_mes", "📅 Visitas mes", "#ec4899", 3, 2));
        widgets.add(widget("kpi", "kpi_facturado_mes", "💰 Facturado mes", "#10b981", 3, 2));
        widgets.add(widget("kpi", "kpi_leads_hot", "🔥 Leads HOT", "#ef4444", 3, 2));
        widgets.add(widget("kpi", "kpi_tareas_pendientes", "✅ Tareas", "#f59e0b", 3, 2));
        widgets.add(widget("kpi", "kpi_contratos", "📄 Contratos", "#06b6d4", 3, 2));
        widgets.add(widget("kpi", "kpi_matches_nuevos", "🎯 Matches", "#ec4899", 3, 2));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", "Mi dashboard principal");
        data.put("descripcion", "Vista rápida de los KPIs más importantes");
        data.put("es_default", true);
        data.put("widgets", widgets);
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("dashboards_custom", data);
    }

    private Map<String, Object> widget(String tipo, String campo, String titulo, String color, int w, int h) {
        Map<String, Object> w_ = new LinkedHashMap<>();
        w_.put("id", UUID.randomUUID().toString().substring(0, 8));
        w_.put("tipo", tipo);
        w_.put("campo", campo);
        w_.put("titulo", titulo);
        w_.put("color", color);
        w_.put("w", w);
        w_.put("h", h);
        return w_;
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
