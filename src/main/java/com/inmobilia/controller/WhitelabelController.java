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
@RequestMapping("/whitelabel")
public class WhitelabelController {

    private final SupabaseService supabase;

    @GetMapping
    public String config(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter("whitelabel_config", "user_id=eq." + uid);
        Map<String, Object> cfg;
        if (rows.isEmpty()) {
            Map<String, Object> nueva = new LinkedHashMap<>();
            nueva.put("user_id", uid);
            nueva.put("created_at", OffsetDateTime.now().toString());
            cfg = supabase.insert("whitelabel_config", nueva);
        } else cfg = rows.get(0);

        model.addAttribute("cfg", cfg);
        return "whitelabel_panel";
    }

    @PostMapping("/guardar")
    public String guardar(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nombre_marca", form.get("nombre_marca"));
        data.put("subdominio", form.get("subdominio"));
        data.put("logo_url", form.get("logo_url"));
        data.put("logo_dark_url", form.get("logo_dark_url"));
        data.put("favicon_url", form.get("favicon_url"));
        data.put("color_primario", form.get("color_primario"));
        data.put("color_secundario", form.get("color_secundario"));
        data.put("color_acento", form.get("color_acento"));
        data.put("email_remitente", form.get("email_remitente"));
        data.put("soporte_telefono", form.get("soporte_telefono"));
        data.put("soporte_email", form.get("soporte_email"));
        data.put("sitio_web", form.get("sitio_web"));
        data.put("css_personalizado", form.get("css_personalizado"));
        data.put("updated_at", OffsetDateTime.now().toString());

        List<Map<String, Object>> exist = supabase.selectFilter("whitelabel_config", "user_id=eq." + uid);
        if (!exist.isEmpty()) supabase.update("whitelabel_config", String.valueOf(exist.get(0).get("id")), data);

        SessionUtils.flash(session, "✅ Configuración whitelabel guardada");
        return "redirect:/whitelabel";
    }
}
