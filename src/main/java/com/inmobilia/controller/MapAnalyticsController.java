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
@RequestMapping("/mapa-global")
public class MapAnalyticsController {

    private final SupabaseService supabase;

    @GetMapping
    public String mapa(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> props = supabase.selectFilter("propiedades",
                "user_id=eq." + uid + "&lat=not.is.null&lng=not.is.null");

        // Análisis por municipio
        Map<String, List<Double>> preciosPorMunicipio = new LinkedHashMap<>();
        Map<String, List<Double>> ratiosPorMunicipio = new LinkedHashMap<>();
        for (Map<String, Object> p : props) {
            String m = String.valueOf(p.getOrDefault("municipio", "—"));
            double precio = nd(p.get("precio"));
            double metros = nd(p.get("metros_cuadrados"));
            preciosPorMunicipio.computeIfAbsent(m, k -> new ArrayList<>()).add(precio);
            if (metros > 0) ratiosPorMunicipio.computeIfAbsent(m, k -> new ArrayList<>()).add(precio / metros);
        }

        List<Map<String, Object>> estadisticas = new ArrayList<>();
        for (String mun : preciosPorMunicipio.keySet()) {
            List<Double> precios = preciosPorMunicipio.get(mun);
            List<Double> ratios = ratiosPorMunicipio.getOrDefault(mun, new ArrayList<>());
            double avgP = precios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double minP = precios.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double maxP = precios.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double avgRatio = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("municipio", mun);
            e.put("count", precios.size());
            e.put("precio_medio", avgP);
            e.put("precio_min", minP);
            e.put("precio_max", maxP);
            e.put("eur_m2_medio", avgRatio);
            estadisticas.add(e);
        }
        estadisticas.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));

        model.addAttribute("propiedades", props);
        model.addAttribute("estadisticas", estadisticas);
        return "map_global";
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
