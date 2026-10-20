package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * Genera carteles imprimibles con QR para colocar en la puerta/escaparate.
 * El QR lleva a la ficha pública (/p/{reference}).
 */
@Controller
@RequiredArgsConstructor
public class PropertyQrController {

    private final SupabaseService supabase;

    @Value("${app.base-url:https://inmobiliapro.es}")
    private String appBaseUrl;

    @GetMapping("/propiedades/{id}/qr")
    public String qrPoster(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var rows = supabase.findBy("propiedades", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/propiedades/grid";

        Map<String, Object> prop = rows.get(0);
        String ref = String.valueOf(prop.getOrDefault("reference", ""));
        String publicUrl = appBaseUrl + "/p/" + ref;

        // QR via API pública gratis (api.qrserver.com)
        String qrImageUrl = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data="
                + java.net.URLEncoder.encode(publicUrl, java.nio.charset.StandardCharsets.UTF_8);

        var profileRows = supabase.findBy("business_profile", Map.of("org_id", uid));
        Map<String, Object> profile = profileRows.isEmpty() ? Map.of() : profileRows.get(0);

        model.addAttribute("page", "QR · " + prop.get("name"));
        model.addAttribute("prop", prop);
        model.addAttribute("qrImageUrl", qrImageUrl);
        model.addAttribute("publicUrl", publicUrl);
        model.addAttribute("profile", profile);
        return "property_qr_poster";
    }
}
