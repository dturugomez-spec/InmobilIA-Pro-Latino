package com.inmobilia.controller;

import com.inmobilia.service.SeguimientoService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 🔁 Seguimiento Automático: panel de control de las cadencias del Agente.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/cadencias")
public class SeguimientoController {

    private final SupabaseService supabase;
    private final SeguimientoService seguimiento;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        Map<String, Object> user = u.isEmpty() ? Map.of() : u.get(0);

        List<Map<String, Object>> inscripciones = supabase.selectFilter("cadencia_inscripciones",
                "user_id=eq." + uid + "&order=created_at.desc&limit=100");
        long activas = inscripciones.stream().filter(i -> "activa".equals(i.get("estado"))).count();
        long completadas = inscripciones.stream().filter(i -> "completada".equals(i.get("estado"))).count();

        model.addAttribute("activo", Boolean.TRUE.equals(user.get("auto_seguimiento")));
        model.addAttribute("envia", Boolean.TRUE.equals(user.get("auto_seguimiento_envia")));
        model.addAttribute("pasos", seguimiento.pasos());
        model.addAttribute("inscripciones", inscripciones);
        model.addAttribute("activas", activas);
        model.addAttribute("completadas", completadas);
        return "cadencias";
    }

    @PostMapping("/config")
    public String config(@RequestParam(required = false, defaultValue = "false") boolean activo,
                         @RequestParam(required = false, defaultValue = "false") boolean envia,
                         HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("auto_seguimiento", activo);
        upd.put("auto_seguimiento_envia", envia);
        supabase.update("users", uid, upd, uid);
        SessionUtils.flash(session, activo ? "🔁 Seguimiento automático activado" : "Seguimiento automático desactivado");
        return "redirect:/cadencias";
    }

    /** Ejecuta ahora la cadencia (inscribe nuevos + avanza vencidos). */
    @PostMapping("/ejecutar")
    public String ejecutar(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        boolean envia = !u.isEmpty() && Boolean.TRUE.equals(u.get(0).get("auto_seguimiento_envia"));
        int nuevos = seguimiento.inscribirNuevos(uid);
        int pasos = seguimiento.avanzar(uid, envia);
        SessionUtils.flash(session, "🔁 " + nuevos + " leads inscritos · " + pasos + " pasos ejecutados"
                + (envia ? "" : " (revisa el Autopiloto)"));
        return "redirect:/cadencias";
    }

    @PostMapping("/{id}/pausar")
    public String pausar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        seguimiento.cambiarEstado(uid, id, "pausada");
        return "redirect:/cadencias";
    }

    @PostMapping("/{id}/reanudar")
    public String reanudar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        seguimiento.cambiarEstado(uid, id, "activa");
        return "redirect:/cadencias";
    }
}
