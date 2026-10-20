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
@RequestMapping("/tours-360")
public class VirtualTourController {

    private final SupabaseService supabase;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> tours = supabase.selectFilter("tours_virtuales", "user_id=eq." + uid + "&order=created_at.desc");
        for (Map<String, Object> t : tours) {
            List<Map<String, Object>> p = supabase.selectFilter("propiedades", "id=eq." + t.get("propiedad_id"));
            if (!p.isEmpty()) t.put("propiedad", p.get(0));
        }
        model.addAttribute("tours", tours);
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        model.addAttribute("baseUrl", BASE_URL);
        return "virtual_tours";
    }

    @PostMapping("/crear")
    public String crear(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("propiedad_id", form.get("propiedad_id"));
        data.put("titulo", form.get("titulo"));
        data.put("descripcion", form.get("descripcion"));
        data.put("proveedor", form.getOrDefault("proveedor", "matterport"));
        data.put("embed_url", form.get("embed_url"));
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("tours_virtuales", data);
        return "redirect:/tours-360";
    }

    @GetMapping("/publico/{token}")
    public String viewer(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("tours_virtuales", "public_token=eq." + token);
        if (rows.isEmpty()) { model.addAttribute("error", "Tour no encontrado"); return "tour_viewer"; }
        Map<String, Object> t = rows.get(0);
        int v = ((Number) t.getOrDefault("visitas_count", 0)).intValue() + 1;
        supabase.update("tours_virtuales", String.valueOf(t.get("id")), Map.of("visitas_count", v));
        model.addAttribute("tour", t);
        return "tour_viewer";
    }
}
