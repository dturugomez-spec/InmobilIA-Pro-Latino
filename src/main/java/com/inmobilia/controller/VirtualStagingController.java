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
@RequestMapping("/staging-digital")
public class VirtualStagingController {

    private final SupabaseService supabase;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> proyectos = supabase.selectFilter(
                "staging_proyectos", "user_id=eq." + uid + "&order=created_at.desc");
        model.addAttribute("proyectos", proyectos);
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid + "&order=created_at.desc"));
        return "virtual_staging";
    }

    @PostMapping("/crear")
    public String crear(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("imagen_original_url", form.get("imagen_original_url"));
        data.put("estilo", form.get("estilo"));
        data.put("tipo_habitacion", form.get("tipo_habitacion"));
        if (form.get("propiedad_id") != null && !form.get("propiedad_id").isBlank())
            data.put("propiedad_id", form.get("propiedad_id"));
        data.put("estado", "pendiente");
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("staging_proyectos", data);

        SessionUtils.flash(session, "✅ Proyecto creado. Procesando con IA...");
        return "redirect:/staging-digital";
    }
}
