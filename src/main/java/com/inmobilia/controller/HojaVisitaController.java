package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 📝 Hoja de visita firmable (protege la comisión del agente).
 * El visitante firma que la agencia le enseñó el inmueble. Documento imprimible.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/hoja-visita")
public class HojaVisitaController {

    private final SupabaseService supabase;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("hojas", supabase.selectFilter(
                "hojas_visita", "user_id=eq." + uid + "&order=created_at.desc&limit=100"));
        model.addAttribute("propiedades", supabase.selectFilter(
                "propiedades", "user_id=eq." + uid + "&order=created_at.desc&limit=200"));
        return "hoja_visita";
    }

    @PostMapping("/crear")
    public String crear(@RequestParam String propiedad_id,
                        @RequestParam(required = false) String visitante_nombre,
                        @RequestParam(required = false) String visitante_dni,
                        @RequestParam(required = false) String visitante_telefono,
                        @RequestParam(required = false) String visitante_email,
                        @RequestParam(required = false) String fecha_visita,
                        @RequestParam(required = false) String observaciones,
                        HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> pr = supabase.selectFilter("propiedades", "id=eq." + propiedad_id);
        if (pr.isEmpty() || !uid.equals(String.valueOf(pr.get(0).get("user_id")))) return "redirect:/hoja-visita";
        Map<String, Object> p = pr.get(0);

        String inmueble = String.valueOf(p.getOrDefault("name", p.getOrDefault("address", "Inmueble"))) +
                ", " + p.getOrDefault("city", "") +
                (p.get("reference") != null ? " · Ref. " + p.get("reference") : "");

        Map<String, Object> h = new LinkedHashMap<>();
        h.put("user_id", uid);
        h.put("propiedad_id", propiedad_id);
        h.put("visitante_nombre", visitante_nombre);
        h.put("visitante_dni", visitante_dni);
        h.put("visitante_telefono", visitante_telefono);
        h.put("visitante_email", visitante_email != null ? visitante_email.trim().toLowerCase() : null);
        h.put("inmueble_texto", inmueble);
        h.put("fecha_visita", (fecha_visita == null || fecha_visita.isBlank()) ? LocalDate.now().toString() : fecha_visita);
        h.put("observaciones", observaciones);
        h.put("estado", "pendiente");
        h.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("hojas_visita", h);
        Object id = saved != null ? saved.get("id") : null;
        return id != null ? "redirect:/hoja-visita/" + id : "redirect:/hoja-visita";
    }

    @GetMapping("/{id}")
    public String documento(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("hojas_visita", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/hoja-visita";
        List<Map<String, Object>> bp = supabase.selectFilter("business_profile", "org_id=eq." + uid);
        model.addAttribute("h", rows.get(0));
        model.addAttribute("agencia", bp.isEmpty() ? Map.of() : bp.get(0));
        return "hoja_visita_doc";
    }

    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("hojas_visita", "id=eq." + id);
        if (!rows.isEmpty() && uid.equals(String.valueOf(rows.get(0).get("user_id")))) {
            supabase.delete("hojas_visita", id, uid);
        }
        return "redirect:/hoja-visita";
    }
}
