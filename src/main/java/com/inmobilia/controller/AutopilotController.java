package com.inmobilia.controller;

import com.inmobilia.service.AgentAutopilotService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 🚀 Autopiloto del Agente: bandeja de acciones propuestas por la IA.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/autopiloto")
public class AutopilotController {

    private final SupabaseService supabase;
    private final AgentAutopilotService autopilot;

    @GetMapping
    public String bandeja(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> pendientes = supabase.selectFilter("agente_acciones",
                "user_id=eq." + uid + "&estado=eq.propuesta&order=prioridad.asc,created_at.desc&limit=100");
        List<Map<String, Object>> historial = supabase.selectFilter("agente_acciones",
                "user_id=eq." + uid + "&estado=in.(ejecutada,descartada)&order=resuelta_at.desc&limit=20");

        long ejecutadas = historial.stream().filter(a -> "ejecutada".equals(a.get("estado"))).count();
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        boolean auto = !u.isEmpty() && Boolean.TRUE.equals(u.get(0).get("autopiloto_auto"));

        model.addAttribute("pendientes", pendientes);
        model.addAttribute("historial", historial);
        model.addAttribute("nPendientes", pendientes.size());
        model.addAttribute("ejecutadas", ejecutadas);
        model.addAttribute("auto", auto);
        model.addAttribute("aprendizaje", autopilot.aprendizaje(uid));
        return "autopiloto";
    }

    @PostMapping("/config")
    public String config(@RequestParam(required = false, defaultValue = "false") boolean auto, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.update("users", uid, Map.of("autopiloto_auto", auto), uid);
        SessionUtils.flash(session, auto ? "🚀 El Autopiloto preparará acciones cada mañana automáticamente" : "Auto-generación desactivada");
        return "redirect:/autopiloto";
    }

    @PostMapping("/aprobar-todo")
    @ResponseBody
    public Map<String, Object> aprobarTodo(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        boolean soloSeguras = body != null && Boolean.TRUE.equals(body.get("soloSeguras"));
        return autopilot.aprobarTodo(uid, soloSeguras);
    }

    @PostMapping("/generar")
    public String generar(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        int n = autopilot.generarPropuestas(uid);
        SessionUtils.flash(session, n > 0 ? "🚀 " + n + " acciones propuestas listas para revisar" : "Nada urgente que proponer ahora mismo 👍");
        return "redirect:/autopiloto";
    }

    @PostMapping("/{id}/aprobar")
    @ResponseBody
    public Map<String, Object> aprobar(@PathVariable String id, @RequestBody(required = false) Map<String, String> body,
                                       HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String borrador = body != null ? body.get("borrador") : null;
        Map<String, Object> r = autopilot.ejecutar(uid, id, borrador);

        // Si es un WhatsApp, devolver el enlace para abrirlo
        Object msg = r.get("msg");
        if (msg != null && String.valueOf(msg).startsWith("WHATSAPP:")) {
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.put("whatsapp", String.valueOf(msg).substring("WHATSAPP:".length()));
            out.put("msg", "💬 WhatsApp listo para enviar");
            return out;
        }
        return r;
    }

    @PostMapping("/{id}/descartar")
    @ResponseBody
    public Map<String, Object> descartar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        return autopilot.descartar(uid, id);
    }
}
