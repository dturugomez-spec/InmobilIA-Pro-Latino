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
        // Leads que se pueden añadir a mano (los automáticos solo entran los de los últimos 3 días)
        Set<String> inscritos = new HashSet<>();
        inscripciones.forEach(i -> inscritos.add(String.valueOf(i.get("lead_id"))));
        model.addAttribute("candidatos", supabase.selectFilter("leads",
                        "select=id,nombre,etapa&user_id=eq." + uid + "&etapa=in.(Nuevo,Contactado)&order=created_at.desc&limit=300")
                .stream().filter(l -> !inscritos.contains(String.valueOf(l.get("id")))).toList());
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
        // Antes, sin leads recientes, el botón parecía no hacer nada
        SessionUtils.flash(session, nuevos == 0 && pasos == 0
                ? "No había nada pendiente: solo entran solos los leads en «Nuevo» de los últimos 3 días, y hoy no toca ningún paso. "
                  + "Para seguir a un lead más antiguo, elígelo abajo en «Añadir un lead al seguimiento»."
                : "🔁 " + nuevos + " leads inscritos · " + pasos + " pasos ejecutados"
                  + (envia ? "" : " (los mensajes están en el Autopiloto para que los revises)"));
        return "redirect:/cadencias";
    }

    /** Añade a la cadencia un lead concreto (también los que no son de los últimos 3 días). */
    @PostMapping("/inscribir")
    public String inscribir(@RequestParam String leadId, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        int n = seguimiento.inscribirManual(uid, leadId);
        if (n > 0) {
            List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
            seguimiento.avanzar(uid, !u.isEmpty() && Boolean.TRUE.equals(u.get(0).get("auto_seguimiento_envia")));
        }
        SessionUtils.flash(session, n > 0 ? "🔁 Lead añadido al seguimiento: el primer paso ya está hecho."
                : "Ese lead ya estaba en seguimiento o no es de tu cuenta.");
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
