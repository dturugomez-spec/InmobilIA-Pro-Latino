package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🎨 Branding de la agencia para las páginas públicas (imanes de captación).
 *
 * Combina business_profile (org_id = uid) con la ficha de users como respaldo,
 * para que valoración, simulador, portales e informes muestren el nombre, el
 * teléfono, el email y el logo REALES de la agencia en lugar de un genérico.
 */
@Service
@RequiredArgsConstructor
public class BrandingService {

    private final SupabaseService supabase;

    /** Caché en memoria con TTL: las páginas públicas piden el branding en cada visita. */
    private static final long TTL_MS = 5 * 60 * 1000;
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();  // uid → [expiraMs, branding]

    @SuppressWarnings("unchecked")
    public Map<String, Object> branding(String uid) {
        Object[] hit = cache.get(uid);
        if (hit != null && (long) hit[0] > System.currentTimeMillis()) {
            return (Map<String, Object>) hit[1];
        }
        Map<String, Object> b = cargar(uid);
        cache.put(uid, new Object[]{System.currentTimeMillis() + TTL_MS, b});
        return b;
    }

    private Map<String, Object> cargar(String uid) {
        Map<String, Object> perfil = new HashMap<>();
        try {
            List<Map<String, Object>> bp = supabase.selectFilter("business_profile", "org_id=eq." + uid);
            if (!bp.isEmpty()) perfil = bp.get(0);
        } catch (Exception ignored) {}

        Map<String, Object> user = new HashMap<>();
        try {
            List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
            if (!u.isEmpty()) user = u.get(0);
        } catch (Exception ignored) {}

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nombre", first(perfil.get("legal_name"), user.get("name"), "Tu inmobiliaria"));
        b.put("contacto", first(perfil.get("contact_name"), user.get("name"), ""));
        b.put("telefono", str(perfil.get("contact_phone")));
        b.put("email", first(perfil.get("contact_email"), user.get("email"), ""));
        b.put("logo", str(perfil.get("logo_url")));
        b.put("color", first(perfil.get("brand_color"), "#1d4ed8"));
        b.put("web", str(perfil.get("website")));
        return b;
    }

    private static String first(Object... vals) {
        for (Object v : vals) {
            String s = str(v);
            if (!s.isBlank() && !"null".equals(s)) return s;
        }
        return "";
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
