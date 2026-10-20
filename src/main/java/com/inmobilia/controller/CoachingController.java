package com.inmobilia.controller;

import com.inmobilia.service.CoachingService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🎭 Entrenador IA de objeciones.
 *  - /coach: panel (escenarios + historial + progreso)
 *  - /coach/mensaje y /coach/evaluar: AJAX del role-play
 */
@Controller
@RequiredArgsConstructor
public class CoachingController {

    private final SupabaseService supabase;
    private final CoachingService coaching;

    @GetMapping("/coach")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> sesiones = supabase.selectFilter(
                "coaching_sesiones", "user_id=eq." + uid + "&order=created_at.desc&limit=50");
        OptionalDouble media = sesiones.stream()
                .filter(s -> s.get("puntuacion") != null)
                .mapToDouble(s -> nd(s.get("puntuacion"))).average();

        model.addAttribute("escenarios", coaching.escenarios());
        model.addAttribute("sesiones", sesiones);
        model.addAttribute("totalSesiones", sesiones.size());
        model.addAttribute("mediaPuntuacion", media.isPresent() ? (int) Math.round(media.getAsDouble()) : null);
        return "coach";
    }

    @PostMapping("/coach/mensaje")
    @ResponseBody
    public Map<String, Object> mensaje(@RequestBody Map<String, Object> in, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String clave = String.valueOf(in.get("escenario"));
        String dificultad = String.valueOf(in.getOrDefault("dificultad", "media"));
        List<Map<String, Object>> historial = historial(in.get("historial"));

        String reply = coaching.replicaCliente(clave, dificultad, historial, uid);
        return Map.of("ok", true, "reply", reply);
    }

    @PostMapping("/coach/evaluar")
    @ResponseBody
    public Map<String, Object> evaluar(@RequestBody Map<String, Object> in, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String clave = String.valueOf(in.get("escenario"));
        String dificultad = String.valueOf(in.getOrDefault("dificultad", "media"));
        List<Map<String, Object>> historial = historial(in.get("historial"));

        Map<String, Object> eval = coaching.evaluar(clave, historial, uid);

        // Persistir la sesión
        CoachingService.Escenario e = coaching.escenario(clave);
        long turnosAgente = historial.stream().filter(m -> "agente".equals(String.valueOf(m.get("rol")))).count();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("user_id", uid);
        rec.put("agente_id", uid);
        rec.put("escenario", clave);
        rec.put("escenario_titulo", e != null ? e.titulo() : clave);
        rec.put("rol_cliente", e != null ? e.rol() : null);
        rec.put("dificultad", dificultad);
        rec.put("transcripcion_json", coaching.comparablesJson(historial));
        rec.put("n_turnos", (int) turnosAgente);
        rec.put("puntuacion", asInt(eval.get("puntuacion")));
        rec.put("fortalezas", joinList(eval.get("fortalezas")));
        rec.put("mejoras", joinList(eval.get("mejoras")));
        rec.put("respuesta_modelo", String.valueOf(eval.getOrDefault("respuesta_modelo", "")));
        rec.put("created_at", OffsetDateTime.now().toString());
        try { supabase.insert("coaching_sesiones", rec); } catch (Exception ignored) {}

        Map<String, Object> out = new LinkedHashMap<>(eval);
        out.put("ok", true);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> historial(Object o) {
        if (o instanceof List<?> l) {
            List<Map<String, Object>> r = new ArrayList<>();
            for (Object item : l) if (item instanceof Map) r.add((Map<String, Object>) item);
            return r;
        }
        return List.of();
    }

    private static String joinList(Object o) {
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (Object x : l) { if (sb.length() > 0) sb.append("\n"); sb.append("• ").append(x); }
            return sb.toString();
        }
        return o == null ? "" : String.valueOf(o);
    }
    private static int asInt(Object o) { try { return o == null ? 0 : (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }
}
