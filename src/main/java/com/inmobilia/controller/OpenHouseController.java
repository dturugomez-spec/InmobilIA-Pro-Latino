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
@RequestMapping("/open-house")
public class OpenHouseController {

    private final SupabaseService supabase;
    private final com.inmobilia.service.PaisService paises;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> ohs = supabase.selectFilter(
                "open_houses", "user_id=eq." + uid + "&order=fecha.desc");

        for (Map<String, Object> oh : ohs) {
            List<Map<String, Object>> p = supabase.selectFilter("propiedades", "id=eq." + oh.get("propiedad_id"));
            if (!p.isEmpty()) oh.put("propiedad", p.get(0));
        }

        model.addAttribute("openHouses", ohs);
        model.addAttribute("baseUrl", BASE_URL);
        return "open_house_list";
    }

    @GetMapping("/nuevo")
    public String form(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        return "open_house_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("propiedad_id", form.get("propiedad_id"));
        data.put("titulo", form.get("titulo"));
        data.put("descripcion", form.get("descripcion"));
        data.put("fecha", form.get("fecha"));
        data.put("hora_inicio", form.get("hora_inicio"));
        data.put("hora_fin", form.get("hora_fin"));
        data.put("max_asistentes", parseInt(form.get("max_asistentes")));
        data.put("requiere_registro", "on".equals(form.getOrDefault("requiere_registro", "on")));
        data.put("incluye_aperitivo", "on".equals(form.get("incluye_aperitivo")));
        data.put("incluye_valoracion", "on".equals(form.get("incluye_valoracion")));
        data.put("incluye_financiacion", "on".equals(form.get("incluye_financiacion")));
        data.put("estado", "programado");
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("open_houses", data);

        SessionUtils.flash(session, "✅ Open House programado");
        return "redirect:/open-house";
    }

    /** Página pública de registro. */
    @GetMapping("/publico/{token}")
    public String publicPage(@PathVariable String token, Model model) {
        List<Map<String, Object>> ohs = supabase.selectFilter(
                "open_houses", "pagina_publica_token=eq." + token);
        if (ohs.isEmpty()) {
            model.addAttribute("error", "Evento no encontrado");
            return "open_house_public";
        }
        Map<String, Object> oh = ohs.get(0);
        paises.usar(String.valueOf(oh.get("user_id")));  // moneda y formato del país de la inmobiliaria
        List<Map<String, Object>> p = supabase.selectFilter("propiedades", "id=eq." + oh.get("propiedad_id"));
        if (!p.isEmpty()) oh.put("propiedad", p.get(0));
        model.addAttribute("oh", oh);
        return "open_house_public";
    }

    @PostMapping("/publico/{token}/registrar")
    public String registrarPublico(@PathVariable String token, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> ohs = supabase.selectFilter("open_houses", "pagina_publica_token=eq." + token);
        if (ohs.isEmpty()) return "redirect:/";
        Map<String, Object> oh = ohs.get(0);

        Map<String, Object> a = new LinkedHashMap<>();
        a.put("open_house_id", oh.get("id"));
        a.put("nombre", form.get("nombre"));
        a.put("email", form.get("email"));
        a.put("telefono", form.get("telefono"));
        a.put("num_acompanantes", parseInt(form.get("num_acompanantes")));
        a.put("interes", form.get("interes"));
        a.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("open_house_asistentes", a);

        // Crear lead automáticamente
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("user_id", oh.get("user_id"));
        lead.put("nombre", form.get("nombre"));
        lead.put("email", form.get("email"));
        lead.put("telefono", form.get("telefono"));
        lead.put("origen", "open_house");
        lead.put("etapa", "Nuevo");
        lead.put("notas", "Registrado en Open House: " + oh.get("titulo"));
        lead.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("leads", lead);

        // Incrementar contador
        int count = parseInt(String.valueOf(oh.getOrDefault("asistentes_count", 0)));
        supabase.update("open_houses", String.valueOf(oh.get("id")), Map.of("asistentes_count", count + 1));

        model.addAttribute("registrado", true);
        model.addAttribute("oh", oh);
        return "open_house_public";
    }

    private static Integer parseInt(String s) { try { return s == null || s.isBlank() ? null : Integer.parseInt(s); } catch(Exception e) { return null; } }
}
