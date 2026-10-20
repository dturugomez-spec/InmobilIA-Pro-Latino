package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapa interactivo con todas las propiedades de la cartera.
 */
@Controller
@RequiredArgsConstructor
public class PropertyMapController {

    private final SupabaseService supabase;

    @GetMapping("/propiedades/mapa")
    public String map(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        var props = supabase.query("propiedades", orgId);
        // Convertir a JSON-serializable para JS
        List<Map<String, Object>> markers = props.stream()
                .filter(p -> p.get("lat") != null && p.get("lon") != null)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.get("id"));
                    m.put("name", p.get("name"));
                    m.put("address", p.get("address"));
                    m.put("city", p.get("city"));
                    m.put("status", p.get("status"));
                    m.put("type", p.get("type"));
                    m.put("value", p.get("current_value"));
                    m.put("rent", p.get("rental_income"));
                    m.put("photo", p.get("featured_photo"));
                    m.put("lat", p.get("lat"));
                    m.put("lon", p.get("lon"));
                    return m;
                }).toList();

        model.addAttribute("page", "Mapa de la cartera");
        model.addAttribute("markers", markers);
        model.addAttribute("totalProps", props.size());
        model.addAttribute("totalGeolocalizadas", markers.size());
        return "property_map";
    }
}
