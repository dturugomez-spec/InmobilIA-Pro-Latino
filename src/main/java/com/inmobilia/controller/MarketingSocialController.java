package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.AIService;
import com.inmobilia.service.BrandingService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 📱 Generador de posts para redes sociales.
 * Por cada inmueble: caption con IA (gancho + hashtags + CTA) y una tarjeta
 * visual de marca lista para descargar como imagen.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/redes")
public class MarketingSocialController {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final BrandingService branding;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> props = supabase.selectFilter(
                "propiedades", "user_id=eq." + uid + "&order=created_at.desc&limit=100");
        model.addAttribute("propiedades", props);
        model.addAttribute("branding", branding.branding(uid));
        return "redes";
    }

    @PostMapping("/{id}/caption")
    @ResponseBody
    public Map<String, Object> caption(@PathVariable String id,
                                       @RequestBody(required = false) Map<String, String> body,
                                       HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return Map.of("ok", false);
        Map<String, Object> p = rows.get(0);
        String red = body != null ? body.getOrDefault("red", "instagram") : "instagram";

        Object precio = p.get("current_value") != null ? p.get("current_value") : p.get("purchase_price");
        String datos = "Inmueble: " + p.getOrDefault("name", p.getOrDefault("address", "?")) +
                " en " + p.getOrDefault("city", "?") + ". Tipo " + p.getOrDefault("type", "?") +
                ". Precio " + precio + (" " + PaisActual.moneda() + ". ") + p.getOrDefault("bedrooms", "?") + " hab, " +
                p.getOrDefault("bathrooms", "?") + " baños, " + p.getOrDefault("area_m2", "?") + " m²." +
                (p.get("notes") != null ? " Notas: " + p.get("notes") : "");

        String estilo = "facebook".equals(red)
                ? "Post para Facebook (80-120 palabras), tono cercano, con llamada a la acción y 3-5 hashtags."
                : "Post para Instagram (60-90 palabras), con gancho en la 1ª línea, emojis, llamada a la acción y 6-10 hashtags inmobiliarios en español al final.";
        String prompt = datos + "\n\nEscribe un " + estilo + " Español de Latinoamérica (" + com.inmobilia.util.PaisActual.get().nombre() + "), con ustedes y sin vosotros. No inventes datos que no te doy.";
        try {
            String cap = aiService.call(null, prompt, "Eres un experto en marketing inmobiliario para redes sociales.", 350, uid);
            return Map.of("ok", cap != null, "caption", cap == null ? "No se pudo generar." : cap);
        } catch (Exception e) {
            return Map.of("ok", false, "caption", "Error: " + e.getMessage());
        }
    }
}
