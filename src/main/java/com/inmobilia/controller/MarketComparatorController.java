package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.*;

/**
 * Compara la renta de cada propiedad del usuario con el IRAV oficial de la zona.
 * Detecta si la propiedad está en zona tensionada (Ley 12/2023).
 */
@Controller
@RequiredArgsConstructor
public class MarketComparatorController {

    private final SupabaseService supabase;

    // Datos IRAV €/m²/mes orientativos por ciudad (datos públicos MITMA Q4 2024)
    private static final Map<String, Double> IRAV = new LinkedHashMap<>();
    static {
        IRAV.put("Madrid", 17.5);
        IRAV.put("Barcelona", 19.2);
        IRAV.put("San Sebastián", 18.1);
        IRAV.put("Bilbao", 13.8);
        IRAV.put("Palma", 15.6);
        IRAV.put("Valencia", 11.9);
        IRAV.put("Málaga", 12.3);
        IRAV.put("Sevilla", 9.8);
        IRAV.put("Zaragoza", 9.1);
        IRAV.put("Alicante", 10.2);
        IRAV.put("Murcia", 7.5);
        IRAV.put("Córdoba", 8.1);
        IRAV.put("Valladolid", 8.7);
        IRAV.put("Vigo", 9.2);
        IRAV.put("Vitoria", 11.4);
        IRAV.put("Granada", 9.5);
        IRAV.put("Las Palmas", 11.0);
    }

    // Zonas tensionadas declaradas (Ley 12/2023)
    private static final Set<String> ZONAS_TENSIONADAS = Set.of(
        "Barcelona", "Sant Cugat del Vallès", "Terrassa", "Sabadell", "Badalona",
        "L'Hospitalet de Llobregat", "Mataró", "Reus", "Tarragona", "Girona",
        "Sant Adrià de Besòs", "Esplugues de Llobregat", "Sant Boi de Llobregat",
        "Cornellà de Llobregat", "Cerdanyola del Vallès"
    );

    @GetMapping("/herramientas/comparador-renta")
    public String compare(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        var props = supabase.query("propiedades", orgId);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (var p : props) {
            String city = String.valueOf(p.getOrDefault("city", ""));
            double m2 = parseD(p.get("area_m2"));
            double rentMonthly = parseD(p.get("rental_income"));
            if (m2 <= 0 || rentMonthly <= 0) continue;

            double yourEurM2 = rentMonthly / m2;
            Double iravEurM2 = IRAV.get(city);
            if (iravEurM2 == null) iravEurM2 = 12.0; // default

            double diff = ((yourEurM2 - iravEurM2) / iravEurM2 * 100);
            boolean tensionada = ZONAS_TENSIONADAS.contains(city);
            String veredicto;
            String tone;
            if (diff > 20 && tensionada) { veredicto = "RIESGO LEGAL"; tone = "err"; }
            else if (diff > 20)          { veredicto = "Por encima de mercado"; tone = "warn"; }
            else if (diff > 5)           { veredicto = "Algo alta"; tone = "warn"; }
            else if (diff < -15)         { veredicto = "Infravalorada"; tone = "info"; }
            else                          { veredicto = "Ajustada"; tone = "ok"; }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", p.get("id"));
            r.put("name", p.get("name"));
            r.put("city", city);
            r.put("m2", m2);
            r.put("rentMonthly", rentMonthly);
            r.put("yourEurM2", yourEurM2);
            r.put("iravEurM2", iravEurM2);
            r.put("diff", diff);
            r.put("veredicto", veredicto);
            r.put("tone", tone);
            r.put("tensionada", tensionada);
            rows.add(r);
        }

        rows.sort((a, b) -> Double.compare(parseD(b.get("diff")), parseD(a.get("diff"))));

        model.addAttribute("page", "Tu renta vs mercado IRAV");
        model.addAttribute("rows", rows);
        return "market_comparator";
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
