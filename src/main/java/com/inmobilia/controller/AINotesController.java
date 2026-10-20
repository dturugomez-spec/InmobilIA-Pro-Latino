package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.AIService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/notas-ia")
public class AINotesController {

    private final SupabaseService supabase;
    private final AIService ai;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "ai_notes";
    }

    @PostMapping("/resumir-lead/{id}")
    @ResponseBody
    public Map<String, Object> resumirLead(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        List<Map<String, Object>> leads = supabase.selectFilter("leads", "id=eq." + id + "&user_id=eq." + uid);
        if (leads.isEmpty()) return Map.of("ok", false);
        Map<String, Object> lead = leads.get(0);

        List<Map<String, Object>> ints = supabase.selectFilter("lead_interacciones", "lead_id=eq." + id + "&order=created_at.desc");
        List<Map<String, Object>> visitas = supabase.selectFilter("visitas", "lead_id=eq." + id);

        StringBuilder ctx = new StringBuilder();
        ctx.append("LEAD: ").append(lead.get("nombre")).append("\n");
        ctx.append("Email: ").append(lead.get("email")).append("\n");
        ctx.append("Tipo: ").append(lead.get("tipo")).append("\n");
        ctx.append("Presupuesto: ").append(lead.get("presupuesto_max")).append(("" + PaisActual.moneda() + "\n"));
        ctx.append("Zona: ").append(lead.get("zona")).append("\n");
        ctx.append("Score: ").append(lead.get("score")).append("/100\n\n");

        ctx.append("INTERACCIONES (").append(ints.size()).append("):\n");
        for (Map<String, Object> i : ints) ctx.append("- ").append(i.get("tipo")).append(": ").append(i.get("detalle")).append("\n");

        ctx.append("\nVISITAS (").append(visitas.size()).append("):\n");
        for (Map<String, Object> v : visitas) ctx.append("- ").append(v.get("fecha_inicio")).append(" (").append(v.get("estado")).append(")\n");

        String prompt = ctx + "\n\nResume en español (máx. 150 palabras): perfil, etapa del embudo, próxima acción recomendada.";
        try {
            String resumen = ai.call(null, prompt, "Eres analista CRM senior. Conciso y accionable.", 300, uid);
            return Map.of("ok", true, "resumen", resumen);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/sugerir-respuesta")
    @ResponseBody
    public Map<String, Object> sugerirRespuesta(@RequestBody Map<String, String> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        String mensaje = body.get("mensaje");
        String tono = body.getOrDefault("tono", "profesional");

        String prompt = "Mensaje recibido del cliente:\n\"" + mensaje + "\"\n\n" +
                "Redacta una respuesta breve (máx 80 palabras) en tono " + tono + " para un agente inmobiliario.";
        try {
            String resp = ai.call(null, prompt, "Eres redactor experto en comunicación inmobiliaria.", 200, uid);
            return Map.of("ok", true, "respuesta", resp);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
