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
public class MicrositeController {

    private final SupabaseService supabase;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping("/microsite/admin")
    public String admin(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter("microsites", "user_id=eq." + uid);
        Map<String, Object> ms;
        if (rows.isEmpty()) {
            Map<String, Object> nueva = new LinkedHashMap<>();
            nueva.put("user_id", uid);
            nueva.put("slug", "agencia-" + uid.substring(0, 6));
            nueva.put("nombre_publico", "Mi Inmobiliaria");
            nueva.put("publicado", false);
            nueva.put("created_at", OffsetDateTime.now().toString());
            ms = supabase.insert("microsites", nueva);
        } else ms = rows.get(0);

        model.addAttribute("ms", ms);
        model.addAttribute("baseUrl", BASE_URL);
        return "microsite_admin";
    }

    @PostMapping("/microsite/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : List.of("slug", "nombre_publico", "tagline", "descripcion",
                "hero_imagen", "hero_titulo", "hero_subtitulo", "hero_cta_texto",
                "sobre_nosotros_titulo", "sobre_nosotros_texto", "sobre_nosotros_foto",
                "direccion", "telefono", "email", "horario",
                "redes_facebook", "redes_instagram", "redes_linkedin",
                "seo_title", "seo_description", "seo_keywords", "seo_og_image",
                "google_analytics_id", "color_primario")) {
            if (form.get(k) != null) data.put(k, form.get(k));
        }
        data.put("publicado", "on".equals(form.get("publicado")));
        data.put("updated_at", OffsetDateTime.now().toString());

        List<Map<String, Object>> rows = supabase.selectFilter("microsites", "user_id=eq." + uid);
        if (!rows.isEmpty()) supabase.update("microsites", String.valueOf(rows.get(0).get("id")), data);
        SessionUtils.flash(session, "✅ Microsite guardado");
        return "redirect:/microsite/admin";
    }

    /** Página pública del microsite. */
    @GetMapping("/m/{slug}")
    public String publico(@PathVariable String slug, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("microsites", "slug=eq." + slug + "&publicado=eq.true");
        if (rows.isEmpty()) {
            model.addAttribute("error", "Microsite no encontrado");
            return "microsite_public";
        }
        Map<String, Object> ms = rows.get(0);

        // Visitas++
        int v = ((Number) ms.getOrDefault("visitas_count", 0)).intValue() + 1;
        supabase.update("microsites", String.valueOf(ms.get("id")), Map.of("visitas_count", v));

        // Propiedades destacadas del usuario
        List<Map<String, Object>> props = supabase.selectFilter("propiedades",
                "user_id=eq." + ms.get("user_id") + "&publicar_portales=eq.true&limit=6");

        model.addAttribute("ms", ms);
        model.addAttribute("propiedades", props);
        return "microsite_public";
    }

    /** Endpoint público para captura de leads desde el microsite. */
    @PostMapping("/m/{slug}/contacto")
    public String capturarLead(@PathVariable String slug, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("microsites", "slug=eq." + slug);
        if (rows.isEmpty()) return "redirect:/";
        Map<String, Object> ms = rows.get(0);

        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("user_id", ms.get("user_id"));
        lead.put("nombre", form.get("nombre"));
        lead.put("email", form.get("email"));
        lead.put("telefono", form.get("telefono"));
        lead.put("notas", form.get("mensaje"));
        lead.put("origen", "microsite");
        lead.put("etapa", "Nuevo");
        lead.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("leads", lead);

        int l = ((Number) ms.getOrDefault("leads_capturados", 0)).intValue() + 1;
        supabase.update("microsites", String.valueOf(ms.get("id")), Map.of("leads_capturados", l));

        model.addAttribute("ms", ms);
        model.addAttribute("gracias", true);
        return "microsite_public";
    }
}
