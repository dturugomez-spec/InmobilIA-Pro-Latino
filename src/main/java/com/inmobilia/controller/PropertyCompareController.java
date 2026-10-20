package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Comparador lado a lado de hasta 4 propiedades, con cálculos de $/m², ratio,
 * rentabilidad estimada y badge "mejor en X" automático.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/comparar")
public class PropertyCompareController {

    private final SupabaseService supabase;

    @GetMapping
    public String form(HttpSession session, Model model,
                       @RequestParam(required = false) List<String> ids) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));

        if (ids != null && !ids.isEmpty()) {
            List<Map<String, Object>> seleccion = new ArrayList<>();
            for (String id : ids) {
                List<Map<String, Object>> rows = supabase.selectFilter(
                        "propiedades", "id=eq." + id + "&user_id=eq." + uid);
                if (!rows.isEmpty()) {
                    Map<String, Object> p = new LinkedHashMap<>(rows.get(0));
                    // Alias en español para la vista + métricas derivadas
                    double precio = nd(p.get("current_value"));
                    if (precio <= 0) precio = nd(p.get("purchase_price"));
                    double m2 = nd(p.get("area_m2"));
                    p.put("precio", precio);
                    p.put("metros_cuadrados", p.get("area_m2"));
                    p.put("habitaciones", p.get("bedrooms"));
                    p.put("banos", p.get("bathrooms"));
                    p.put("direccion", p.getOrDefault("name", p.get("address")));
                    if (m2 > 0) p.put("precio_m2", precio / m2);
                    // Comisión estimada 3%
                    p.put("comision_estimada", precio * 0.03);
                    seleccion.add(p);
                }
            }
            model.addAttribute("seleccion", seleccion);

            // Calcular "mejor en" por atributo
            if (!seleccion.isEmpty()) {
                model.addAttribute("mejorPrecio", indiceMin(seleccion, "precio"));
                model.addAttribute("mejorM2", indiceMax(seleccion, "metros_cuadrados"));
                model.addAttribute("mejorPrecioM2", indiceMin(seleccion, "precio_m2"));
                model.addAttribute("mejorHab", indiceMax(seleccion, "habitaciones"));
                model.addAttribute("mejorBanos", indiceMax(seleccion, "banos"));
            }
        }

        return "property_compare";
    }

    private int indiceMin(List<Map<String, Object>> list, String campo) {
        int idx = -1; double min = Double.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            double v = nd(list.get(i).get(campo));
            if (v > 0 && v < min) { min = v; idx = i; }
        }
        return idx;
    }

    private int indiceMax(List<Map<String, Object>> list, String campo) {
        int idx = -1; double max = -Double.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            double v = nd(list.get(i).get(campo));
            if (v > max) { max = v; idx = i; }
        }
        return idx;
    }

    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
