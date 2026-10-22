package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.WorkflowEngine;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pipelines Kanban configurables. A diferencia del KanbanController básico,
 * estos pipelines permiten:
 *  - Columnas personalizadas con colores
 *  - Múltiples tipos (leads, captación, ofertas)
 *  - Drag&drop con historial de movimientos
 *  - Métricas: tiempo medio por columna, forecast por probabilidad
 *  - Workflow automático al cambiar columna
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/pipelines")
@Slf4j
public class PipelineController {

    private final SupabaseService supabase;
    private final WorkflowEngine workflowEngine;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> pipelines = supabase.selectFilter(
                "pipelines", "user_id=eq." + uid + "&order=created_at.asc");

        if (pipelines.isEmpty()) {
            crearPipelinesDefault(uid);
            pipelines = supabase.selectFilter("pipelines", "user_id=eq." + uid);
        }

        // Contar cards por pipeline
        for (Map<String, Object> p : pipelines) {
            List<Map<String, Object>> cards = supabase.selectFilter(
                    "pipeline_cards",
                    "pipeline_id=eq." + p.get("id") + "&archivada=eq.false");
            p.put("total_cards", cards.size());
            p.put("valor_total", cards.stream().mapToDouble(c -> nd(c.get("valor"))).sum());
        }

        model.addAttribute("pipelines", pipelines);
        return "pipelines_list";
    }

    @GetMapping("/{id}")
    public String board(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter(
                "pipelines", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/pipelines";

        Map<String, Object> pipeline = rows.get(0);
        List<Map<String, Object>> cards = supabase.selectFilter(
                "pipeline_cards",
                "pipeline_id=eq." + id + "&archivada=eq.false&order=orden.asc");

        // Stats por columna
        Map<String, Integer> contColumna = new LinkedHashMap<>();
        Map<String, Double> valorColumna = new LinkedHashMap<>();
        Map<String, Long> tiempoMedio = new LinkedHashMap<>();
        Map<String, Long> tiempoAcum = new LinkedHashMap<>();
        for (Map<String, Object> c : cards) {
            String col = String.valueOf(c.get("columna_key"));
            contColumna.merge(col, 1, Integer::sum);
            valorColumna.merge(col, nd(c.get("valor")), Double::sum);
            try {
                OffsetDateTime t = OffsetDateTime.parse(String.valueOf(c.get("fecha_entrada_columna")));
                long horas = ChronoUnit.HOURS.between(t, OffsetDateTime.now());
                tiempoAcum.merge(col, horas, Long::sum);
            } catch (Exception ignored) {}
        }
        for (Map.Entry<String, Long> e : tiempoAcum.entrySet()) {
            tiempoMedio.put(e.getKey(), e.getValue() / Math.max(1, contColumna.getOrDefault(e.getKey(), 1)));
        }

        // Forecast: suma(valor * probabilidad / 100)
        double forecast = cards.stream()
                .mapToDouble(c -> nd(c.get("valor")) * nd(c.get("probabilidad")) / 100.0)
                .sum();
        double valorTotal = cards.stream().mapToDouble(c -> nd(c.get("valor"))).sum();

        model.addAttribute("pipeline", pipeline);
        model.addAttribute("cards", cards);
        model.addAttribute("contColumna", contColumna);
        model.addAttribute("valorColumna", valorColumna);
        model.addAttribute("tiempoMedio", tiempoMedio);
        model.addAttribute("forecast", forecast);
        model.addAttribute("valorTotal", valorTotal);
        return "pipelines_board";
    }

    /** Drag&drop endpoint. */
    @SuppressWarnings("unchecked")
    @PostMapping("/cards/{id}/mover")
    @ResponseBody
    public Map<String, Object> moverCard(@PathVariable String id,
                                          @RequestBody Map<String, Object> body,
                                          HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        String columnaNueva = String.valueOf(body.get("columna"));
        int orden = body.get("orden") != null ? ((Number) body.get("orden")).intValue() : 0;

        List<Map<String, Object>> rows = supabase.selectOwned("pipeline_cards", id, uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        Map<String, Object> card = rows.get(0);
        String columnaAnterior = String.valueOf(card.get("columna_key"));

        List<Object> movimientos = card.get("movimientos") instanceof List
                ? new ArrayList<>((List<Object>) card.get("movimientos"))
                : new ArrayList<>();
        boolean cambio = !columnaAnterior.equals(columnaNueva);
        if (cambio) {
            Map<String, Object> mov = new LinkedHashMap<>();
            mov.put("from", columnaAnterior);
            mov.put("to", columnaNueva);
            mov.put("at", OffsetDateTime.now().toString());
            mov.put("usuario", uid);
            movimientos.add(mov);
        }

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("columna_key", columnaNueva);
        upd.put("orden", orden);
        if (cambio) {
            upd.put("fecha_entrada_columna", OffsetDateTime.now().toString());
            upd.put("movimientos", movimientos);
        }
        upd.put("updated_at", OffsetDateTime.now().toString());
        supabase.update("pipeline_cards", id, upd, uid);

        // Trigger workflow
        if (cambio) {
            Map<String, Object> payload = new LinkedHashMap<>(card);
            payload.put("columna_anterior", columnaAnterior);
            payload.put("columna_nueva", columnaNueva);
            try { workflowEngine.triggerEvent(uid, "kanban_card_movida", payload); } catch (Exception ignored) {}
            if ("calificado".equalsIgnoreCase(columnaNueva) && card.get("lead_id") != null) {
                try { workflowEngine.triggerEvent(uid, "lead_calificado", payload); } catch (Exception ignored) {}
            }
        }

        return Map.of("ok", true);
    }

    @PostMapping("/cards/crear")
    public String createCard(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("pipeline_id", form.get("pipeline_id"));
        data.put("columna_key", form.get("columna_key"));
        data.put("titulo", form.get("titulo"));
        data.put("subtitulo", form.get("subtitulo"));
        data.put("valor", com.inmobilia.util.Numeros.decimal(form.get("valor")));             // "250.000 $" ya no da 500
        data.put("probabilidad", com.inmobilia.util.Numeros.decimal(form.get("probabilidad")));
        if (notBlank(form.get("fecha_limite"))) data.put("fecha_limite", form.get("fecha_limite"));
        if (notBlank(form.get("lead_id"))) data.put("lead_id", form.get("lead_id"));
        if (notBlank(form.get("propiedad_id"))) data.put("propiedad_id", form.get("propiedad_id"));
        data.put("notas", form.get("notas"));
        data.put("color_etiqueta", form.get("color_etiqueta"));
        data.put("fecha_entrada_columna", OffsetDateTime.now().toString());
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());
        supabase.insert("pipeline_cards", data);

        return "redirect:/pipelines/" + form.get("pipeline_id");
    }

    @PostMapping("/cards/{id}/archivar")
    @ResponseBody
    public Map<String, Object> archivar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        boolean ok = supabase.update("pipeline_cards", id,
                Map.of("archivada", true, "updated_at", OffsetDateTime.now().toString()), uid);
        return Map.of("ok", ok);
    }

    /** Campos de una tarjeta que se pueden editar (nunca user_id, pipeline ni historial). */
    static final Set<String> CAMPOS_EDITABLES = Set.of(
            "titulo", "subtitulo", "valor", "probabilidad", "notas", "color_etiqueta", "fecha_limite");

    @PostMapping("/cards/{id}/editar")
    @ResponseBody
    public Map<String, Object> editar(@PathVariable String id, @RequestBody Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        Map<String, Object> upd = new LinkedHashMap<>();
        body.forEach((k, v) -> { if (CAMPOS_EDITABLES.contains(k)) upd.put(k, v); });
        if (upd.isEmpty()) return Map.of("ok", false, "error", "Nada que guardar");
        upd.put("updated_at", OffsetDateTime.now().toString());
        return Map.of("ok", supabase.update("pipeline_cards", id, upd, uid));
    }

    private void crearPipelinesDefault(String uid) {
        crearPipeline(uid, "Embudo de leads", "leads",
                "Captura → Calificación → Visita → Oferta → Cierre",
                List.of(
                        col("nuevo", "🆕 Nuevo", "#3b82f6", 1),
                        col("contactado", "📞 Contactado", "#06b6d4", 2),
                        col("calificado", "✅ Calificado", "#8b5cf6", 3),
                        col("visita", "🏠 Con visita", "#f59e0b", 4),
                        col("oferta", "💰 Con oferta", "#ec4899", 5),
                        col("ganado", "🎉 Ganado", "#10b981", 6),
                        col("perdido", "❌ Perdido", "#94a3b8", 7)
                ));

        crearPipeline(uid, "Captación de propiedades", "captacion",
                "Propietarios potenciales → exclusiva firmada",
                List.of(
                        col("prospecto", "👤 Prospecto", "#6366f1", 1),
                        col("primera_visita", "🏠 Visita técnica", "#0ea5e9", 2),
                        col("valoracion", "📊 Valoración entregada", "#8b5cf6", 3),
                        col("negociacion", "🤝 Negociación", "#f59e0b", 4),
                        col("captado", "✅ Captado", "#10b981", 5),
                        col("perdido", "❌ Perdido", "#ef4444", 6)
                ));

        crearPipeline(uid, "Ofertas en curso", "ofertas",
                "Gestión de ofertas y contraofertas",
                List.of(
                        col("recibida", "📥 Recibida", "#3b82f6", 1),
                        col("presentada", "📤 Presentada", "#8b5cf6", 2),
                        col("negociando", "💬 Negociando", "#f59e0b", 3),
                        col("aceptada", "✅ Aceptada", "#10b981", 4),
                        col("arras", "📝 Con arras", "#06b6d4", 5),
                        col("escriturada", "🏆 Escriturada", "#0d9488", 6),
                        col("caida", "💔 Caída", "#ef4444", 7)
                ));
    }

    private void crearPipeline(String uid, String nombre, String tipo, String desc, List<Map<String, Object>> columnas) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("user_id", uid);
        p.put("nombre", nombre);
        p.put("tipo", tipo);
        p.put("descripcion", desc);
        p.put("columnas", columnas);
        p.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("pipelines", p);
    }

    private Map<String, Object> col(String key, String label, String color, int orden) {
        return Map.of("key", key, "label", label, "color", color, "orden", orden);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
