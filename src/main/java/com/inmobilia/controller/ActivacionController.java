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
 * 🚀 Primeros pasos: checklist de activación que detecta el estado y guía a la
 * agencia nueva para sacar valor de la plataforma desde el día 1.
 */
@Controller
@RequiredArgsConstructor
public class ActivacionController {

    private final SupabaseService supabase;

    @GetMapping("/primeros-pasos")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> user = uno("users", "id=eq." + uid);
        Map<String, Object> perfil = uno("business_profile", "org_id=eq." + uid);
        boolean hayPropiedad = !safe("propiedades", "user_id=eq." + uid + "&limit=1").isEmpty();

        List<Map<String, Object>> pasos = new ArrayList<>();
        pasos.add(paso("🏢", "Completa el perfil de tu agencia",
                "Nombre, teléfono y logo — aparecen en tus páginas públicas de marca.",
                "/business",
                notBlank(perfil.get("legal_name")) && notBlank(perfil.get("contact_phone"))));
        pasos.add(paso("🖼️", "Sube el logo de tu agencia",
                "Para que valoraciones, portales e informes salgan con tu marca.",
                "/business",
                notBlank(perfil.get("logo_url"))));
        pasos.add(paso("🏠", "Añade tu primera propiedad",
                "La base para el CMA, el optimizador de anuncios y los posts de redes.",
                "/crud/propiedades/new", hayPropiedad));
        pasos.add(paso("🏷️", "Comparte tu enlace de valoración",
                "Tu imán nº1 para captar propietarios. Ponlo en tu web y redes.",
                "/valoracion", notBlank(user.get("valoracion_token"))));
        pasos.add(paso("🔁", "Activa el seguimiento automático",
                "Que el Agente siga solo a cada lead nuevo por ti.",
                "/cadencias", Boolean.TRUE.equals(user.get("auto_seguimiento"))));
        pasos.add(paso("🚀", "Prueba el Autopiloto del Agente",
                "Deja que la IA te prepare las acciones del día y apruébalas con un clic.",
                "/autopiloto", Boolean.TRUE.equals(user.get("autopiloto_auto"))
                        || !safe("agente_acciones", "user_id=eq." + uid + "&limit=1").isEmpty()));

        long hechos = pasos.stream().filter(p -> Boolean.TRUE.equals(p.get("hecho"))).count();
        int pct = pasos.isEmpty() ? 0 : (int) Math.round(hechos * 100.0 / pasos.size());

        model.addAttribute("pasos", pasos);
        model.addAttribute("hechos", hechos);
        model.addAttribute("total", pasos.size());
        model.addAttribute("pct", pct);
        return "primeros_pasos";
    }

    private static Map<String, Object> paso(String icono, String titulo, String desc, String link, boolean hecho) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icono", icono); m.put("titulo", titulo); m.put("desc", desc);
        m.put("link", link); m.put("hecho", hecho);
        return m;
    }

    private Map<String, Object> uno(String t, String f) {
        List<Map<String, Object>> r = safe(t, f);
        return r.isEmpty() ? Map.of() : r.get(0);
    }
    private List<Map<String, Object>> safe(String t, String f) {
        try { return supabase.selectFilter(t, f); } catch (Exception e) { return List.of(); }
    }
    private static boolean notBlank(Object o) {
        return o != null && !String.valueOf(o).isBlank() && !"null".equals(String.valueOf(o));
    }
}
