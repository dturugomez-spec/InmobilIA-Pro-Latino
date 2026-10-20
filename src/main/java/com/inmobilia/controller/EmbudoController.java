package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 📈 Embudo de Captación 360°.
 *
 * Centro de mando que unifica todos los imanes de captación (valoración,
 * simulador, farming, marketplace) con atribución por origen y el pipeline por
 * etapa. Demuestra de un vistazo el ROI de cada herramienta.
 */
@Controller
@RequiredArgsConstructor
public class EmbudoController {

    private final SupabaseService supabase;

    /** Orígenes "nuevos" de captación, con etiqueta legible e icono. */
    private static final Map<String, String[]> ORIGENES = new LinkedHashMap<>();
    static {
        ORIGENES.put("valoracion", new String[]{"🏷️ Valoración", "blue"});
        ORIGENES.put("simulador", new String[]{"🏦 Simulador hipoteca", "emerald"});
        ORIGENES.put("captacion", new String[]{"🎯 Farming", "amber"});
        ORIGENES.put("marketplace", new String[]{"💞 Marketplace", "rose"});
    }
    private static final List<String> ETAPAS = List.of("Nuevo", "Contactado", "Visita", "Oferta", "Firmado");

    @GetMapping("/embudo")
    public String embudo(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&limit=2000");
        String mesActual = OffsetDateTime.now().toString().substring(0, 7);  // yyyy-MM

        // ── Por origen ──
        Map<String, Long> porOrigen = new LinkedHashMap<>();
        long leadsMes = 0;
        for (Map<String, Object> l : leads) {
            String origen = String.valueOf(l.getOrDefault("origen", "otros")).toLowerCase();
            porOrigen.merge(origen, 1L, Long::sum);
            String ca = String.valueOf(l.getOrDefault("created_at", ""));
            if (ca.startsWith(mesActual)) leadsMes++;
        }

        // Fuentes de captación (solo las nuevas), ordenadas y con metadatos
        List<Map<String, Object>> fuentes = new ArrayList<>();
        long totalCaptacion = 0;
        for (var e : ORIGENES.entrySet()) {
            long n = porOrigen.getOrDefault(e.getKey(), 0L);
            totalCaptacion += n;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("clave", e.getKey());
            f.put("label", e.getValue()[0]);
            f.put("color", e.getValue()[1]);
            f.put("n", n);
            fuentes.add(f);
        }
        long maxFuente = fuentes.stream().mapToLong(f -> (long) f.get("n")).max().orElse(1);
        for (Map<String, Object> f : fuentes) {
            long n = (long) f.get("n");
            f.put("pct", maxFuente > 0 ? Math.round(n * 100.0 / maxFuente) : 0);
        }

        // ── Pipeline por etapa ──
        Map<String, Long> porEtapa = new LinkedHashMap<>();
        for (String et : ETAPAS) porEtapa.put(et, 0L);
        for (Map<String, Object> l : leads) {
            String etapa = String.valueOf(l.getOrDefault("etapa", "Nuevo"));
            if (porEtapa.containsKey(etapa)) porEtapa.merge(etapa, 1L, Long::sum);
        }
        long totalPipeline = leads.size();
        List<Map<String, Object>> pipeline = new ArrayList<>();
        for (String et : ETAPAS) {
            long n = porEtapa.get(et);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("etapa", et);
            e.put("n", n);
            e.put("pct", totalPipeline > 0 ? Math.round(n * 100.0 / totalPipeline) : 0);
            pipeline.add(e);
        }
        long firmados = porEtapa.getOrDefault("Firmado", 0L);
        double conversion = totalPipeline > 0 ? Math.round(firmados * 1000.0 / totalPipeline) / 10.0 : 0;

        // ── Conteos de actividad de los imanes ──
        long valoraciones = count("valoraciones", uid);
        long simulaciones = count("simulaciones_hipoteca", uid);
        List<Map<String, Object>> swipes = safe("tinder_swipes", "agency_id=eq." + uid + "&limit=2000");
        long matches = swipes.stream().filter(s -> Boolean.TRUE.equals(s.get("matched"))).count();

        model.addAttribute("totalLeads", totalPipeline);
        model.addAttribute("leadsMes", leadsMes);
        model.addAttribute("totalCaptacion", totalCaptacion);
        model.addAttribute("conversion", conversion);
        model.addAttribute("fuentes", fuentes);
        model.addAttribute("pipeline", pipeline);
        model.addAttribute("valoraciones", valoraciones);
        model.addAttribute("simulaciones", simulaciones);
        model.addAttribute("matches", matches);
        return "embudo";
    }

    private long count(String table, String uid) {
        return safe(table, "user_id=eq." + uid + "&limit=2000").size();
    }
    private List<Map<String, Object>> safe(String table, String filter) {
        try { return supabase.selectFilter(table, filter); } catch (Exception e) { return List.of(); }
    }
}
