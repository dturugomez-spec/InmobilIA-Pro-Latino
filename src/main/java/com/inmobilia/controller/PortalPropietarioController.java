package com.inmobilia.controller;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.OwnerReportService;
import com.inmobilia.service.PortalPropietarioService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🪟 Portal de Transparencia del Propietario.
 *  - Admin: /propiedad/{id}/portal → enlace compartible + config
 *  - Propietario (público, sin login): /p/{token} → ve la actividad real de su inmueble
 */
@Controller
@RequiredArgsConstructor
public class PortalPropietarioController {

    private final SupabaseService supabase;
    private final com.inmobilia.service.PaisService paises;
    private final PortalPropietarioService portal;
    private final NotificationService notif;
    private final BrandingService branding;
    private final OwnerReportService ownerReport;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    // ─── ADMIN: configurar y compartir ───
    @GetMapping("/propiedad/{id}/portal")
    public String admin(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) {
            return "redirect:/cartera";
        }
        Map<String, Object> p = rows.get(0);

        String token = obtenerOcrearToken(id, p, uid);
        Map<String, Object> resumen = portal.resumen(p);

        List<Map<String, Object>> mensajes = supabase.selectFilter(
                "portal_mensajes", "propiedad_id=eq." + id + "&order=created_at.desc&limit=30");

        model.addAttribute("p", p);
        model.addAttribute("token", token);
        model.addAttribute("urlPublica", BASE_URL + "/mi-inmueble/" + token);
        model.addAttribute("resumen", resumen);
        model.addAttribute("mensajes", mensajes);
        return "portal_propietario_admin";
    }

    @PostMapping("/propiedad/{id}/portal/config")
    public String config(@PathVariable String id,
                         @RequestParam(required = false) String propietario_nombre,
                         @RequestParam(required = false) String propietario_email,
                         @RequestParam(required = false) String portal_mensaje_agente,
                         @RequestParam(required = false, defaultValue = "false") boolean portal_activo,
                         HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/cartera";

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("propietario_nombre", propietario_nombre);
        upd.put("propietario_email", propietario_email != null ? propietario_email.trim().toLowerCase() : null);
        upd.put("portal_mensaje_agente", portal_mensaje_agente);
        upd.put("portal_activo", portal_activo);
        supabase.update("propiedades", id, upd, uid);
        return "redirect:/propiedad/" + id + "/portal";
    }

    /** Envío MANUAL del informe mensual al propietario (además del envío automático el día 1). */
    @PostMapping("/propiedad/{id}/portal/informe")
    public String enviarInforme(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/cartera";
        Map<String, Object> p = rows.get(0);
        boolean ok = false;
        String err = null;
        try { ok = ownerReport.enviarInforme(p, true); }
        catch (Throwable e) { ok = false; err = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        SessionUtils.flash(session, ok
                ? "Informe enviado al propietario (" + p.get("propietario_email") + ")."
                : ("No se pudo enviar el informe. " + (err != null ? err : "Revisa el email del propietario.")));
        return "redirect:/propiedad/" + id + "/portal";
    }

    private String obtenerOcrearToken(String id, Map<String, Object> p, String uid) {
        Object t = p.get("portal_token");
        if (t != null && !String.valueOf(t).isBlank() && !"null".equals(String.valueOf(t))) {
            return String.valueOf(t);
        }
        String token = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        supabase.update("propiedades", id, Map.of("portal_token", token), uid);
        p.put("portal_token", token);
        return token;
    }

    // ─── PÚBLICO: el propietario ve su inmueble ───
    @GetMapping("/mi-inmueble/{token}")
    public String publico(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "portal_token=eq." + token);
        if (rows.isEmpty()) { model.addAttribute("error", "Enlace no válido"); return "portal_propietario_publico"; }
        Map<String, Object> p = rows.get(0);
        if (Boolean.FALSE.equals(p.get("portal_activo"))) {
            model.addAttribute("error", "Este informe ya no está disponible. Contacta con tu agente.");
            return "portal_propietario_publico";
        }
        String uid = String.valueOf(p.get("user_id"));
        paises.usar(uid);  // moneda y formato del país de la inmobiliaria

        // Contador de visitas al portal (no bloqueante)
        try {
            int vistas = p.get("portal_vistas") == null ? 0 : (int) Double.parseDouble(String.valueOf(p.get("portal_vistas")));
            supabase.update("propiedades", String.valueOf(p.get("id")),
                    Map.of("portal_vistas", vistas + 1, "portal_ultima_vista", OffsetDateTime.now().toString()), uid);
        } catch (Exception ignored) {}

        Map<String, Object> resumen = portal.resumen(p);
        String narrativa = portal.narrativaIA(p, resumen, uid);

        // Datos del agente para branding/contacto
        List<Map<String, Object>> users = supabase.selectFilter("users", "id=eq." + uid);
        Map<String, Object> agente = users.isEmpty() ? null : users.get(0);

        model.addAttribute("p", p);
        model.addAttribute("token", token);
        model.addAttribute("resumen", resumen);
        model.addAttribute("narrativa", narrativa);
        model.addAttribute("agente", agente);
        model.addAttribute("branding", branding.branding(uid));
        return "portal_propietario_publico";
    }

    // ─── PÚBLICO: el propietario escribe a su agente ───
    @PostMapping("/mi-inmueble/{token}/mensaje")
    @ResponseBody
    public Map<String, Object> mensaje(@PathVariable String token, @RequestBody Map<String, String> in) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "portal_token=eq." + token);
        if (rows.isEmpty()) return Map.of("ok", false);
        Map<String, Object> p = rows.get(0);
        String uid = String.valueOf(p.get("user_id"));
        String texto = in.getOrDefault("mensaje", "").trim();
        if (texto.isBlank()) return Map.of("ok", false);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", uid);
        m.put("propiedad_id", p.get("id"));
        m.put("autor", "propietario");
        m.put("mensaje", texto);
        m.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("portal_mensajes", m);

        notif.create(uid, "🪟 Mensaje del propietario",
                String.valueOf(p.getOrDefault("address", p.getOrDefault("name", "Tu inmueble"))) + ": " +
                        (texto.length() > 80 ? texto.substring(0, 80) + "…" : texto),
                "/propiedad/" + p.get("id") + "/portal", "alta");
        return Map.of("ok", true);
    }
}
