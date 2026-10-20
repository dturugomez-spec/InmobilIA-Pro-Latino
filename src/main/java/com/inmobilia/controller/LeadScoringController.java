package com.inmobilia.controller;

import com.inmobilia.service.LeadScoringService;
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
@RequestMapping("/leads-scoring")
public class LeadScoringController {

    private final SupabaseService supabase;
    private final LeadScoringService scoring;

    /** Ranking de leads por score. */
    @GetMapping
    public String ranking(HttpSession session, Model model,
                          @RequestParam(required = false) String temperatura) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid)
                .append("&estado=neq.descartado")
                .append("&order=score.desc");
        if (temperatura != null && !temperatura.isBlank()) {
            q.append("&temperatura=eq.").append(temperatura);
        }
        List<Map<String, Object>> leads = supabase.selectFilter("leads", q.toString());

        // Contadores
        long hot = leads.stream().filter(l -> "hot".equals(l.get("temperatura"))).count();
        long caliente = leads.stream().filter(l -> "caliente".equals(l.get("temperatura"))).count();
        long tibio = leads.stream().filter(l -> "tibio".equals(l.get("temperatura"))).count();
        long frio = leads.stream().filter(l -> "frio".equals(l.get("temperatura"))).count();

        double scorePromedio = leads.stream()
                .mapToInt(l -> n(l.get("score")))
                .average()
                .orElse(0);

        model.addAttribute("leads", leads);
        model.addAttribute("hot", hot);
        model.addAttribute("caliente", caliente);
        model.addAttribute("tibio", tibio);
        model.addAttribute("frio", frio);
        model.addAttribute("scorePromedio", scorePromedio);
        return "lead_scoring_list";
    }

    /** Detalle scoring + histórico + análisis IA. */
    @GetMapping("/{id}")
    public String detail(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("leads", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/leads-scoring";

        Map<String, Object> lead = rows.get(0);
        List<Map<String, Object>> interacciones = supabase.selectFilter(
                "lead_interacciones", "lead_id=eq." + id + "&order=created_at.desc&limit=50");
        List<Map<String, Object>> historico = supabase.selectFilter(
                "lead_score_historico", "lead_id=eq." + id + "&order=created_at.asc&limit=30");

        model.addAttribute("lead", lead);
        model.addAttribute("interacciones", interacciones);
        model.addAttribute("historico", historico);
        return "lead_scoring_detail";
    }

    /** Recalcular score manualmente. */
    @PostMapping("/{id}/recalcular")
    @ResponseBody
    public Map<String, Object> recalc(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        if (supabase.selectOwned("leads", id, uid).isEmpty()) return Map.of("ok", false); // lead de otra inmobiliaria
        Map<String, Object> r = scoring.calcularScore(id);
        r.put("ok", true);
        return r;
    }

    /** Análisis IA bajo demanda. */
    @PostMapping("/{id}/analisis-ia")
    @ResponseBody
    public Map<String, Object> analisisIA(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String analisis = scoring.analisisAI(id, uid);
        return Map.of("ok", true, "analisis", analisis);
    }

    /** Registrar interacción manual. */
    @PostMapping("/{id}/interaccion")
    public String registrar(@PathVariable String id,
                            @RequestParam String tipo,
                            @RequestParam(required = false) String detalle,
                            HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        scoring.registrarInteraccion(id, tipo, detalle, uid);
        return "redirect:/leads-scoring/" + id;
    }

    /** Recalcular TODOS los leads del usuario. */
    @PostMapping("/recalcular-todos")
    @ResponseBody
    public Map<String, Object> recalcAll(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> leads = supabase.selectFilter("leads", "user_id=eq." + uid);
        int count = 0;
        for (Map<String, Object> l : leads) {
            try { scoring.calcularScore(String.valueOf(l.get("id"))); count++; } catch(Exception ignored) {}
        }
        return Map.of("ok", true, "actualizados", count);
    }

    private static int n(Object o) { try { return o == null ? 0 : Integer.parseInt(String.valueOf(o).split("\\.")[0]); } catch(Exception e) { return 0; } }
}
