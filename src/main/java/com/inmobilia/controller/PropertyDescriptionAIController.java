package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.AIService;
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
@RequestMapping("/descripcion-ia")
public class PropertyDescriptionAIController {

    private final SupabaseService supabase;
    private final AIService ai;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid + "&limit=100"));
        return "ai_descriptions";
    }

    @PostMapping("/generar/{propId}")
    @ResponseBody
    public Map<String, Object> generar(@PathVariable String propId,
                                        @RequestParam(defaultValue = "venta") String objetivo,
                                        @RequestParam(defaultValue = "profesional") String tono,
                                        @RequestParam(defaultValue = "150") int longitud,
                                        HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + propId + "&user_id=eq." + uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        Map<String, Object> p = rows.get(0);

        StringBuilder datos = new StringBuilder();
        datos.append("Tipo: ").append(p.get("type")).append("\n");
        datos.append("Nombre/Dirección: ").append(p.getOrDefault("name", p.get("address"))).append("\n");
        datos.append("Población: ").append(p.get("city")).append("\n");
        datos.append("Habitaciones: ").append(p.get("bedrooms")).append("\n");
        datos.append("Baños: ").append(p.get("bathrooms")).append("\n");
        datos.append("Superficie: ").append(p.get("area_m2")).append(" m²\n");
        Object precio = p.get("current_value") != null ? p.get("current_value") : p.get("purchase_price");
        datos.append("Precio: ").append(precio).append((" " + PaisActual.moneda() + "\n"));
        if (p.get("year_built") != null) datos.append("Año construcción: ").append(p.get("year_built")).append("\n");
        if (p.get("floor") != null) datos.append("Piso (nivel): ").append(p.get("floor")).append("\n");
        if (p.get("notes") != null) datos.append("Notas del agente: ").append(p.get("notes")).append("\n");

        String prompt = "Datos de la propiedad:\n" + datos + "\n\n" +
                "Genera una descripción SEO en español para anuncio de " + objetivo + ", tono " + tono +
                ", máx " + longitud + " palabras. Incluye llamada a la acción. Sin clichés.";

        String desc = ai.call(null, prompt, "Eres copywriter inmobiliario experto en SEO. Persuasivo y conciso.", longitud * 3, uid);
        return ai.respuesta("descripcion", desc, uid);
    }

    @PostMapping("/aplicar/{propId}")
    @ResponseBody
    public Map<String, Object> aplicar(@PathVariable String propId, @RequestBody Map<String, String> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        String desc = body.get("descripcion");
        if (desc == null || desc.isBlank()) return Map.of("ok", false, "error", "No hay descripción que aplicar");
        // Con el uid: antes se podía escribir en la ficha de un inmueble de otra inmobiliaria
        return Map.of("ok", supabase.update("propiedades", propId, Map.of("notes", desc), uid));
    }
}
