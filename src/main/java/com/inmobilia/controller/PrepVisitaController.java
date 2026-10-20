package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 🧠 Preparador de visitas IA.
 * Antes de cada visita, el agente prepara una chuleta: perfil del cliente,
 * por qué el inmueble encaja, objeciones probables + respuestas y argumentos clave.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/preparacion")
public class PrepVisitaController {

    private final SupabaseService supabase;
    private final AIService aiService;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String hoy = LocalDate.now().toString();
        List<Map<String, Object>> visitas = supabase.selectFilter("visitas",
                "user_id=eq." + uid + "&fecha_inicio=gte." + hoy + "&estado=in.(programada,confirmada)"
                        + "&order=fecha_inicio.asc&limit=30");
        enriquecer(uid, visitas);

        model.addAttribute("visitas", visitas);
        return "preparacion";
    }

    @PostMapping("/{id}")
    @ResponseBody
    public Map<String, Object> preparar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> vr = supabase.selectFilter("visitas", "id=eq." + id);
        if (vr.isEmpty() || !uid.equals(String.valueOf(vr.get(0).get("user_id")))) return Map.of("ok", false);
        Map<String, Object> v = vr.get(0);

        Map<String, Object> prop = uno("propiedades", v.get("propiedad_id"));
        Map<String, Object> lead = uno("leads", v.get("lead_id"));

        StringBuilder ctx = new StringBuilder();
        ctx.append("VISITA: ").append(v.getOrDefault("visitante_nombre", "cliente"))
           .append(" el ").append(v.getOrDefault("fecha_inicio", "")).append("\n\n");
        ctx.append("INMUEBLE: ").append(prop.getOrDefault("name", prop.getOrDefault("address", "?")))
           .append(" en ").append(prop.getOrDefault("city", "?")).append("\n");
        Object precio = prop.get("current_value") != null ? prop.get("current_value") : prop.get("purchase_price");
        ctx.append("- Precio: ").append(precio).append("€ · ")
           .append(prop.getOrDefault("bedrooms", "?")).append(" hab · ")
           .append(prop.getOrDefault("bathrooms", "?")).append(" baños · ")
           .append(prop.getOrDefault("area_m2", "?")).append(" m² · tipo ").append(prop.getOrDefault("type", "?")).append("\n");
        if (prop.get("notes") != null) ctx.append("- Descripción: ").append(prop.get("notes")).append("\n");

        if (!lead.isEmpty()) {
            ctx.append("\nCLIENTE:\n");
            ctx.append("- Interés: ").append(lead.getOrDefault("interes", "?")).append("\n");
            if (lead.get("presupuesto") != null) ctx.append("- Presupuesto: ").append(lead.get("presupuesto")).append("€\n");
            if (lead.get("zona") != null) ctx.append("- Busca en: ").append(lead.get("zona")).append("\n");
            ctx.append("- Etapa: ").append(lead.getOrDefault("etapa", "?")).append("\n");
            if (lead.get("notas") != null) ctx.append("- Notas: ").append(lead.get("notas")).append("\n");

            List<Map<String, Object>> ints = supabase.selectFilter("lead_interacciones",
                    "lead_id=eq." + lead.get("id") + "&order=created_at.desc&limit=8");
            if (!ints.isEmpty()) {
                ctx.append("- Historial:\n");
                for (Map<String, Object> i : ints)
                    ctx.append("  · ").append(i.getOrDefault("tipo", "")).append(": ").append(i.getOrDefault("detalle", "")).append("\n");
            }
        }

        String prompt = ctx + "\n\nPrepara una CHULETA breve para el agente antes de esta visita, con estas secciones:\n" +
                "👤 PERFIL (1-2 frases)\n✅ POR QUÉ LE ENCAJA (2-3 puntos)\n" +
                "⚠️ OBJECIONES PROBABLES Y CÓMO REBATIRLAS (2-3)\n💬 3 ARGUMENTOS CLAVE\n➡️ SIGUIENTE PASO SUGERIDO\n" +
                "Concreto y accionable, español de España. Usa esos emojis como títulos.";
        try {
            String prep = aiService.call(null, prompt, "Eres un coach inmobiliario que prepara al agente para cerrar la visita.", 500, uid);
            return Map.of("ok", prep != null, "prep", prep == null ? "No se pudo generar." : prep);
        } catch (Exception e) {
            return Map.of("ok", false, "prep", "Error: " + e.getMessage());
        }
    }

    // ── helpers ──
    private void enriquecer(String uid, List<Map<String, Object>> visitas) {
        Set<String> propIds = new LinkedHashSet<>();
        for (Map<String, Object> v : visitas) if (v.get("propiedad_id") != null) propIds.add(String.valueOf(v.get("propiedad_id")));
        Map<String, Map<String, Object>> props = new HashMap<>();
        if (!propIds.isEmpty()) {
            try {
                for (Map<String, Object> p : supabase.selectFilter("propiedades", "id=in.(" + String.join(",", propIds) + ")"))
                    props.put(String.valueOf(p.get("id")), p);
            } catch (Exception ignored) {}
        }
        for (Map<String, Object> v : visitas) {
            Map<String, Object> p = props.get(String.valueOf(v.get("propiedad_id")));
            v.put("prop_nombre", p == null ? "—" : p.getOrDefault("name", p.getOrDefault("address", "—")));
            v.put("prop_ciudad", p == null ? "" : p.getOrDefault("city", ""));
        }
    }
    private Map<String, Object> uno(String tabla, Object id) {
        if (id == null || "null".equals(String.valueOf(id))) return Map.of();
        List<Map<String, Object>> r = supabase.selectFilter(tabla, "id=eq." + id);
        return r.isEmpty() ? Map.of() : r.get(0);
    }
}
