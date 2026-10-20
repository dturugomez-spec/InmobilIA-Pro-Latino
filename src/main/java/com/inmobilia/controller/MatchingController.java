package com.inmobilia.controller;

import com.inmobilia.service.MatchingEngine;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/matches")
@Slf4j
public class MatchingController {

    private final SupabaseService supabase;
    private final MatchingEngine engine;

    /** Listado de matches del usuario. */
    @GetMapping
    public String list(HttpSession session, Model model,
                       @RequestParam(required = false) String estado,
                       @RequestParam(required = false) Integer minScore) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=score.desc");
        if (estado != null && !estado.isBlank()) q.append("&estado=eq.").append(estado);
        if (minScore != null) q.append("&score=gte.").append(minScore);

        List<Map<String, Object>> matches = supabase.selectFilter("matches", q.toString());

        // Enriquecer con datos de propiedad y lead
        for (Map<String, Object> m : matches) {
            String propId = String.valueOf(m.get("propiedad_id"));
            List<Map<String, Object>> p = supabase.selectFilter("propiedades", "id=eq." + propId);
            if (!p.isEmpty()) m.put("propiedad", p.get(0));

            if (m.get("lead_id") != null) {
                List<Map<String, Object>> l = supabase.selectFilter("leads", "id=eq." + m.get("lead_id"));
                if (!l.isEmpty()) m.put("lead", l.get(0));
            }
        }

        long nuevos = matches.stream().filter(m -> "nuevo".equals(m.get("estado"))).count();
        long interesados = matches.stream().filter(m -> "interesado".equals(m.get("estado"))).count();

        model.addAttribute("matches", matches);
        model.addAttribute("nuevos", nuevos);
        model.addAttribute("interesados", interesados);
        model.addAttribute("total", matches.size());
        return "matching_list";
    }

    /** Lanza matching manual. */
    @PostMapping("/ejecutar")
    @ResponseBody
    public Map<String, Object> run(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        int nuevos = engine.runMatchingForUser(uid);
        return Map.of("ok", true, "nuevos", nuevos);
    }

    /** Cambiar estado de un match. */
    @PostMapping("/{id}/estado")
    public String setEstado(@PathVariable String id, @RequestParam String estado,
                             @RequestParam(required = false) String feedback,
                             HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("estado", estado);
        if (feedback != null) data.put("feedback", feedback);
        if ("visto".equals(estado)) data.put("visto_at", OffsetDateTime.now().toString());
        supabase.update("matches", id, data, uid);
        return "redirect:/matches";
    }

    // ===== Preferencias de búsqueda =====
    @GetMapping("/preferencias")
    public String prefsList(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> prefs = supabase.selectFilter(
                "preferencias_busqueda", "user_id=eq." + uid + "&order=created_at.desc");
        for (Map<String, Object> p : prefs) {
            if (p.get("lead_id") != null) {
                List<Map<String, Object>> l = supabase.selectFilter("leads", "id=eq." + p.get("lead_id"));
                if (!l.isEmpty()) p.put("lead", l.get(0));
            }
        }
        model.addAttribute("preferencias", prefs);
        return "matching_prefs";
    }

    @GetMapping("/preferencias/nueva")
    public String prefForm(HttpSession session, Model model, @RequestParam(required = false) String leadId) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("leads", supabase.selectFilter("leads", "user_id=eq." + uid));
        model.addAttribute("inquilinos", supabase.selectFilter("inquilinos", "user_id=eq." + uid));
        Map<String, Object> pref = new LinkedHashMap<>();
        if (leadId != null) pref.put("lead_id", leadId);
        model.addAttribute("pref", pref);
        return "matching_pref_form";
    }

    @PostMapping("/preferencias/guardar")
    public String savePref(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        if (notBlank(form.get("lead_id"))) data.put("lead_id", form.get("lead_id"));
        if (notBlank(form.get("inquilino_id"))) data.put("inquilino_id", form.get("inquilino_id"));
        data.put("operacion", form.get("operacion"));

        // Arrays vienen como strings separados por coma
        if (notBlank(form.get("tipo_propiedad"))) data.put("tipo_propiedad", Arrays.asList(form.get("tipo_propiedad").split(",")));
        if (notBlank(form.get("zonas"))) data.put("zonas", Arrays.asList(form.get("zonas").split(",")));
        if (notBlank(form.get("municipios"))) data.put("municipios", Arrays.asList(form.get("municipios").split(",")));

        data.put("presupuesto_min", pd(form.get("presupuesto_min")));
        data.put("presupuesto_max", pd(form.get("presupuesto_max")));
        data.put("habitaciones_min", pi(form.get("habitaciones_min")));
        data.put("habitaciones_max", pi(form.get("habitaciones_max")));
        data.put("banos_min", pi(form.get("banos_min")));
        data.put("superficie_min", pd(form.get("superficie_min")));
        data.put("superficie_max", pd(form.get("superficie_max")));
        data.put("requiere_garaje", "on".equals(form.get("requiere_garaje")));
        data.put("requiere_ascensor", "on".equals(form.get("requiere_ascensor")));
        data.put("requiere_terraza", "on".equals(form.get("requiere_terraza")));
        data.put("requiere_piscina", "on".equals(form.get("requiere_piscina")));
        data.put("requiere_amueblado", "on".equals(form.get("requiere_amueblado")));
        data.put("permite_mascotas", "on".equals(form.get("permite_mascotas")));
        data.put("obra_nueva", "on".equals(form.get("obra_nueva")));
        data.put("eficiencia_min", form.get("eficiencia_min"));
        data.put("auto_match", "on".equals(form.getOrDefault("auto_match", "on")));
        data.put("notificar_email", "on".equals(form.getOrDefault("notificar_email", "on")));
        data.put("notas", form.get("notas"));
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());

        String id = form.get("id");
        if (notBlank(id)) {
            supabase.update("preferencias_busqueda", id, data, uid); // solo las propias
        } else {
            supabase.insert("preferencias_busqueda", data);
        }
        SessionUtils.flash(session, "✅ Preferencias guardadas. Ejecutando matching...");
        engine.runMatchingForUser(uid);
        return "redirect:/matches";
    }

    /** Cron 2 veces al día → 9:00 y 18:00 */
    @Scheduled(cron = "0 0 9,18 * * *", zone = "Europe/Madrid")
    public void autoMatchCron() {
        log.info("[CRON] autoMatchCron iniciado");
        // Obtener todos los usuarios activos
        List<Map<String, Object>> users = supabase.selectFilter("users", "active=eq.true");
        for (Map<String, Object> u : users) {
            try {
                engine.runMatchingForUser(String.valueOf(u.get("id")));
            } catch (Exception e) {
                log.warn("Error matching user {}: {}", u.get("id"), e.getMessage());
            }
        }
    }

    private static double pd(String s) { try { return s == null || s.isBlank() ? 0 : Double.parseDouble(s); } catch(Exception e) { return 0; } }
    private static int pi(String s) { try { return s == null || s.isBlank() ? 0 : Integer.parseInt(s); } catch(Exception e) { return 0; } }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
