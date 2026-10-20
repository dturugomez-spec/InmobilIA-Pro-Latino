package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.AIService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Asistente conversacional IA: responde preguntas sobre los datos del usuario.
 * Ej: "¿Cuántos morosos tengo?", "Top 3 propiedades por rentabilidad", "Renta total este mes"
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AIAssistantController {

    private final SupabaseService supabase;
    private final AIService aiService;

    @GetMapping("/ai/asistente")
    public String page(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Asistente IA");
        return "ai_assistant";
    }

    @PostMapping("/api/ai/ask")
    @ResponseBody
    public Map<String, Object> ask(@RequestBody Map<String, String> body, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false, "error", "No autorizado");

        String question = body.getOrDefault("question", "").trim();
        if (question.isEmpty()) return Map.of("ok", false, "error", "Pregunta vacía");

        String uid = SessionUtils.uid(session);

        // Construir contexto compacto con datos del usuario
        String context = buildContext(uid);

        String prompt = "Eres un asistente experto en gestión inmobiliaria que responde en español a preguntas " +
                "del dueño de la app sobre SUS PROPIOS DATOS de la cartera. Sé conciso, directo, usa cifras.\n\n" +
                "DATOS DEL USUARIO:\n" + context + "\n\n" +
                "PREGUNTA: " + question + "\n\n" +
                "RESPUESTA (máximo 4 frases, con cifras concretas si las hay):";

        try {
            String answer = aiService.call(null, prompt,
                    "Eres un asistente experto en gestión inmobiliaria. Responde SIEMPRE en español, conciso, con cifras.",
                    600, uid);
            return Map.of("ok", true, "answer", answer);
        } catch (Exception e) {
            log.error("AI error: {}", e.getMessage());
            return Map.of("ok", false, "error", "Error consultando IA: " + e.getMessage());
        }
    }

    private String buildContext(String orgId) {
        StringBuilder sb = new StringBuilder();
        try {
            var props = supabase.query("propiedades", orgId);
            sb.append("PROPIEDADES (").append(props.size()).append(" total):\n");
            for (var p : props.subList(0, Math.min(20, props.size()))) {
                sb.append("- ").append(p.get("name"))
                  .append(" | ").append(p.get("city"))
                  .append(" | ").append(p.get("status"))
                  .append(" | valor ").append(p.get("current_value"))
                  .append((" " + PaisActual.moneda() + " | renta ")).append(p.get("rental_income")).append((" " + PaisActual.moneda() + "/mes\n"));
            }

            var inq = supabase.query("inquilinos", orgId);
            sb.append("\nINQUILINOS (").append(inq.size()).append("):\n");
            for (var i : inq.subList(0, Math.min(15, inq.size()))) {
                sb.append("- ").append(i.get("name"))
                  .append(" | ").append(i.get("property_name"))
                  .append(" | renta ").append(i.get("rent_amount"))
                  .append((" " + PaisActual.moneda() + " | estado ")).append(i.get("status")).append("\n");
            }

            int year = LocalDate.now().getYear();
            int month = LocalDate.now().getMonthValue();
            var tx = supabase.query("transacciones", orgId);
            double ingresosMes = 0, gastosMes = 0;
            for (var t : tx) {
                String d = String.valueOf(t.getOrDefault("date", ""));
                if (d.length() < 10) continue;
                try {
                    if (Integer.parseInt(d.substring(0, 4)) != year) continue;
                    if (Integer.parseInt(d.substring(5, 7)) != month) continue;
                    double amt = Double.parseDouble(String.valueOf(t.getOrDefault("amount", "0")));
                    if ("Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))) ingresosMes += amt;
                    else gastosMes += amt;
                } catch (Exception ignored) {}
            }
            sb.append(String.format(("\nMES ACTUAL (%d/%d): ingresos %.0f " + PaisActual.moneda() + ", gastos %.0f " + PaisActual.moneda() + ", neto %.0f " + PaisActual.moneda() + "\n"),
                    month, year, ingresosMes, gastosMes, ingresosMes - gastosMes));

            var leads = supabase.query("leads", orgId);
            sb.append("\nLEADS ACTIVOS: ").append(leads.size()).append("\n");
        } catch (Exception e) {
            log.warn("Error context: {}", e.getMessage());
        }
        return sb.toString();
    }
}
