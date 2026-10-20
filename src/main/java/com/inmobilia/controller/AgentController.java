package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.AgentActionService;
import com.inmobilia.service.AgentBriefingService;
import com.inmobilia.service.AIService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Panel del Agente IA: briefing en vivo + chat para que el trabajador
 * le pida cosas en lenguaje natural (consultas y acciones sobre la cartera).
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/agente")
public class AgentController {

    private final AgentBriefingService briefingService;
    private final AgentActionService actionService;
    private final AIService aiService;
    private final SupabaseService supabase;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("b", briefingService.generarAhora(uid));
        return "agente_panel";
    }

    /** Chat del agente: el trabajador pregunta y la IA responde con contexto de la cartera. */
    @PostMapping("/preguntar")
    @ResponseBody
    public Map<String, Object> preguntar(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false, "respuesta", "Sesión expirada.");

        String pregunta = String.valueOf(body.getOrDefault("pregunta", "")).trim();
        if (pregunta.isBlank()) return Map.of("ok", false, "respuesta", "Escribe una pregunta.");

        // Historial de la conversación (viaja desde el cliente; sin estado en servidor)
        List<Map<String, Object>> historial = new ArrayList<>();
        if (body.get("historial") instanceof List<?> h) {
            for (Object o : h) if (o instanceof Map<?, ?> m) {
                Map<String, Object> turno = new LinkedHashMap<>();
                turno.put("rol", m.get("rol"));
                turno.put("texto", m.get("texto"));
                historial.add(turno);
            }
        }

        // Construir contexto con datos reales de la cartera (resumen)
        StringBuilder ctx = new StringBuilder();
        try {
            var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&order=score.desc&limit=25");
            ctx.append("LEADS TOP (").append(leads.size()).append("):\n");
            for (var l : leads) {
                ctx.append("- ").append(l.getOrDefault("nombre", "?"))
                   .append(" | etapa:").append(l.getOrDefault("etapa", "?"))
                   .append(" | score:").append(l.getOrDefault("score", "?"));
                if (l.get("presupuesto") != null) ctx.append(" | presup:").append(l.get("presupuesto")).append(("" + PaisActual.moneda()));
                if (l.get("zona") != null) ctx.append(" | zona:").append(l.get("zona"));
                ctx.append(" | tel:").append(l.getOrDefault("telefono", "-")).append("\n");
            }
            var props = supabase.selectFilter("propiedades", "user_id=eq." + uid + "&limit=20");
            ctx.append("\nPROPIEDADES (").append(props.size()).append("):\n");
            for (var p : props) {
                Object precio = p.get("current_value") != null ? p.get("current_value") : p.getOrDefault("purchase_price", "?");
                ctx.append("- ").append(p.getOrDefault("name", p.getOrDefault("address", "?")))
                   .append(" | ").append(p.getOrDefault("city", "?"))
                   .append(" | ").append(precio).append(("" + PaisActual.moneda()))
                   .append(" | ").append(p.getOrDefault("status", "?")).append("\n");
            }
            // Agenda inmediata: visitas próximas y tareas pendientes
            String hoy = java.time.LocalDate.now().toString();
            var visitas = supabase.selectFilter("visitas",
                    "user_id=eq." + uid + "&fecha_inicio=gte." + hoy + "&order=fecha_inicio.asc&limit=5");
            if (!visitas.isEmpty()) {
                ctx.append("\nVISITAS PRÓXIMAS:\n");
                for (var v : visitas) {
                    ctx.append("- ").append(v.getOrDefault("visitante_nombre", "?"))
                       .append(" | ").append(v.getOrDefault("fecha_inicio", "?"))
                       .append(" | ").append(v.getOrDefault("estado", "?")).append("\n");
                }
            }
            var tareas = supabase.selectFilter("tareas",
                    "user_id=eq." + uid + "&estado=eq.pendiente&order=fecha_limite.asc&limit=5");
            if (!tareas.isEmpty()) {
                ctx.append("\nTAREAS PENDIENTES:\n");
                for (var t : tareas) {
                    ctx.append("- ").append(t.getOrDefault("titulo", "?"))
                       .append(" (límite ").append(t.getOrDefault("fecha_limite", "?")).append(")\n");
                }
            }
        } catch (Throwable ignored) {}

        // El agente no solo responde: EJECUTA acciones (tarea, etapa, visita,
        // buscar compradores, nota, mensaje...) según la petición en lenguaje natural.
        try {
            return actionService.procesar(uid, pregunta, ctx.toString(), historial);
        } catch (Throwable t) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("respuesta", "Ha ocurrido un error procesando tu petición. Inténtalo de nuevo en un momento.");
            return err;
        }
    }

    /** Regenerar briefing + ejecutar acciones bajo demanda. */
    @PostMapping("/regenerar")
    public String regenerar(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        // Solo re-analiza (las acciones automáticas las hace el cron diario)
        briefingService.generarAhora(uid);
        SessionUtils.flash(session, "🤖 Briefing actualizado");
        return "redirect:/agente";
    }

    // ─── Acciones rápidas del briefing (1 clic) ───

    /** Marca un lead como Contactado (desde "leads calientes"). */
    @PostMapping("/accion/contactado")
    @ResponseBody
    public Map<String, Object> marcarContactado(@RequestBody Map<String, String> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String leadId = body.get("leadId");
        var rows = supabase.selectFilter("leads", "id=eq." + leadId);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return Map.of("ok", false);
        supabase.update("leads", leadId, Map.of("etapa", "Contactado"));
        return Map.of("ok", true, "msg", "✅ Marcado como contactado");
    }

    /** Aplica el precio sugerido a una propiedad estancada. */
    @PostMapping("/accion/aplicar-precio")
    @ResponseBody
    public Map<String, Object> aplicarPrecio(@RequestBody Map<String, String> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String propId = body.get("propId");
        double precio = nd(body.get("precio"));
        if (precio <= 0) return Map.of("ok", false);
        var rows = supabase.selectFilter("propiedades", "id=eq." + propId);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return Map.of("ok", false);
        supabase.update("propiedades", propId, Map.of("current_value", precio));
        return Map.of("ok", true, "msg", "💶 Precio actualizado a " + Math.round(precio) + ("" + PaisActual.moneda()));
    }

    /** Crea una tarea de seguimiento/reactivación (desde "leads en riesgo" o matches). */
    @PostMapping("/accion/tarea")
    @ResponseBody
    public Map<String, Object> crearTareaRapida(@RequestBody Map<String, String> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String titulo = body.getOrDefault("titulo", "").trim();
        if (titulo.isBlank()) return Map.of("ok", false);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("user_id", uid);
        t.put("titulo", titulo);
        t.put("prioridad", body.getOrDefault("prioridad", "alta"));
        t.put("estado", "pendiente");
        if (body.get("leadId") != null && !body.get("leadId").isBlank()) t.put("lead_id", body.get("leadId"));
        t.put("fecha_limite", java.time.LocalDate.now().plusDays(1).toString());
        t.put("descripcion", "Creada desde el briefing del Agente IA");
        t.put("created_at", java.time.OffsetDateTime.now().toString());
        supabase.insert("tareas", t);
        return Map.of("ok", true, "msg", "📋 Tarea creada: " + titulo);
    }

    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
}
