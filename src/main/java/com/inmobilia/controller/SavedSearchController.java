package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/busquedas-guardadas")
public class SavedSearchController {

    private final SupabaseService supabase;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> busquedas = supabase.selectFilter("busquedas_guardadas",
                "user_id=eq." + uid + "&order=created_at.desc");
        model.addAttribute("busquedas", busquedas);
        return "saved_searches";
    }

    @PostMapping("/crear")
    public String crear(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        for (String k : List.of("cliente_nombre", "cliente_email", "cliente_telefono", "nombre_busqueda",
                "operacion", "tipo", "municipio", "frecuencia")) {
            if (form.get(k) != null) data.put(k, form.get(k));
        }
        for (String k : List.of("precio_min", "precio_max", "metros_min", "habitaciones_min", "banos_min")) {
            if (form.get(k) != null && !form.get(k).isBlank()) data.put(k, Double.parseDouble(form.get(k)));
        }
        data.put("requiere_garaje", "on".equals(form.get("requiere_garaje")));
        data.put("requiere_ascensor", "on".equals(form.get("requiere_ascensor")));
        data.put("requiere_terraza", "on".equals(form.get("requiere_terraza")));
        data.put("notificar_email", "on".equals(form.getOrDefault("notificar_email", "on")));
        data.put("activa", true);
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("busquedas_guardadas", data);
        return "redirect:/busquedas-guardadas";
    }

    @PostMapping("/{id}/toggle")
    @ResponseBody
    public Map<String, Object> toggle(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectOwned("busquedas_guardadas", id, uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        boolean activa = !Boolean.TRUE.equals(rows.get(0).get("activa"));
        supabase.update("busquedas_guardadas", id, Map.of("activa", activa), uid);
        return Map.of("ok", true, "activa", activa);
    }
}
