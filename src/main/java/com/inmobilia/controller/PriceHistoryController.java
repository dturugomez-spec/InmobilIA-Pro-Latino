package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/precios-historico")
public class PriceHistoryController {

    private final SupabaseService supabase;

    @GetMapping("/{propId}")
    public String historico(@PathVariable String propId, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> propRows = supabase.selectFilter(
                "propiedades", "id=eq." + propId + "&user_id=eq." + uid);
        if (propRows.isEmpty()) return "redirect:/propiedades";
        Map<String, Object> prop = propRows.get(0);

        List<Map<String, Object>> historico = supabase.selectFilter(
                "precio_historico", "propiedad_id=eq." + propId + "&order=created_at.asc");

        // Cálculos
        double precioOriginal = historico.isEmpty()
                ? nd(prop.get("precio"))
                : nd(historico.get(0).get("precio_anterior"));
        double precioActual = nd(prop.get("precio"));
        double cambioTotal = precioActual - precioOriginal;
        double cambioPct = precioOriginal > 0 ? cambioTotal / precioOriginal * 100 : 0;

        model.addAttribute("propiedad", prop);
        model.addAttribute("historico", historico);
        model.addAttribute("precioOriginal", precioOriginal);
        model.addAttribute("precioActual", precioActual);
        model.addAttribute("cambioTotal", cambioTotal);
        model.addAttribute("cambioPct", cambioPct);
        return "price_history";
    }

    @GetMapping
    public String global(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Cambios recientes globales
        List<Map<String, Object>> cambios = supabase.selectFilter(
                "precio_historico", "user_id=eq." + uid + "&order=created_at.desc&limit=50");

        for (Map<String, Object> c : cambios) {
            List<Map<String, Object>> p = supabase.selectFilter("propiedades", "id=eq." + c.get("propiedad_id"));
            if (!p.isEmpty()) c.put("propiedad", p.get(0));
        }

        long bajadas = cambios.stream().filter(c -> nd(c.get("diferencia")) < 0).count();
        long subidas = cambios.stream().filter(c -> nd(c.get("diferencia")) > 0).count();

        model.addAttribute("cambios", cambios);
        model.addAttribute("bajadas", bajadas);
        model.addAttribute("subidas", subidas);
        return "price_history_global";
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
