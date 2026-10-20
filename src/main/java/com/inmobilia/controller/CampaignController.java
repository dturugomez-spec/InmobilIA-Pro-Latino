package com.inmobilia.controller;

import com.inmobilia.service.CampaignService;
import com.inmobilia.service.FirmaEnlaces;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@Slf4j
public class CampaignController {

    private final SupabaseService supabase;
    private final CampaignService campaignService;
    private final FirmaEnlaces firmaEnlaces;

    private static final byte[] PIXEL_GIF = new byte[] {
            71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, -1, -1, -1,
            0, 0, 0, 33, -7, 4, 1, 0, 0, 0, 0, 44, 0, 0, 0, 0,
            1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59
    };

    // ===== BACKOFFICE =====

    @GetMapping("/campanas")
    public String list(HttpSession session, Model model,
                       @RequestParam(required = false) String estado) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=created_at.desc");
        if (estado != null && !estado.isBlank()) q.append("&estado=eq.").append(estado);
        List<Map<String, Object>> camps = supabase.selectFilter("campanas", q.toString());

        long enviadas = camps.stream().filter(c -> "enviada".equals(c.get("estado"))).count();
        long programadas = camps.stream().filter(c -> "programada".equals(c.get("estado"))).count();
        int totalEnvios = camps.stream().mapToInt(c -> num(c.get("enviados"))).sum();
        int totalAperturas = camps.stream().mapToInt(c -> num(c.get("abiertos"))).sum();
        int totalClicks = camps.stream().mapToInt(c -> num(c.get("clicks"))).sum();
        double tasaApertura = totalEnvios > 0 ? (totalAperturas * 100.0 / totalEnvios) : 0;
        double tasaClick = totalEnvios > 0 ? (totalClicks * 100.0 / totalEnvios) : 0;

        model.addAttribute("campanas", camps);
        model.addAttribute("enviadas", enviadas);
        model.addAttribute("programadas", programadas);
        model.addAttribute("totalEnvios", totalEnvios);
        model.addAttribute("tasaApertura", tasaApertura);
        model.addAttribute("tasaClick", tasaClick);
        return "campaigns_list";
    }

    @GetMapping("/campanas/nueva")
    public String form(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("plantillas", supabase.selectFilter("plantillas_email", "user_id=eq." + uid));
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        return "campaigns_form";
    }

    @PostMapping("/campanas/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("descripcion", form.get("descripcion"));
        data.put("canal", form.getOrDefault("canal", "email"));
        data.put("asunto", form.get("asunto"));
        data.put("contenido", form.get("contenido"));
        data.put("preheader", form.get("preheader"));
        data.put("from_name", form.get("from_name"));
        data.put("from_email", form.get("from_email"));
        data.put("segmento_tipo", form.getOrDefault("segmento_tipo", "todos_leads"));
        data.put("incluir_unsubscribe", "on".equals(form.getOrDefault("incluir_unsubscribe", "on")));
        data.put("tracking_aperturas", "on".equals(form.getOrDefault("tracking_aperturas", "on")));
        data.put("tracking_clicks", "on".equals(form.getOrDefault("tracking_clicks", "on")));
        data.put("ab_test", "on".equals(form.get("ab_test")));
        if (data.get("ab_test") == Boolean.TRUE) {
            data.put("asunto_b", form.get("asunto_b"));
            data.put("contenido_b", form.get("contenido_b"));
            data.put("ab_split_pct", parseInt(form.get("ab_split_pct"), 50));
        }
        if (form.get("propiedad_id") != null && !form.get("propiedad_id").isBlank()) {
            data.put("propiedad_id", form.get("propiedad_id"));
        }

        String enviarAhora = form.get("enviar_ahora");
        if ("on".equals(enviarAhora)) {
            data.put("estado", "programada");
            data.put("enviar_at", OffsetDateTime.now().toString());
        } else if (notBlank(form.get("enviar_at"))) {
            data.put("estado", "programada");
            data.put("enviar_at", form.get("enviar_at"));
        } else {
            data.put("estado", "borrador");
        }
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("campanas", data);
        SessionUtils.flash(session, "✅ Campaña guardada");
        return "redirect:/campanas/" + saved.get("id");
    }

    @GetMapping("/campanas/{id}")
    public String detail(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("campanas", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/campanas";

        Map<String, Object> camp = rows.get(0);
        List<Map<String, Object>> envios = supabase.selectFilter(
                "campana_envios", "campana_id=eq." + id + "&order=created_at.desc&limit=100");

        int total = num(camp.get("enviados"));
        double tasaApertura = total > 0 ? num(camp.get("abiertos")) * 100.0 / total : 0;
        double tasaClick = total > 0 ? num(camp.get("clicks")) * 100.0 / total : 0;
        double tasaRebote = total > 0 ? num(camp.get("rebotes")) * 100.0 / total : 0;

        Map<String, Object> vista = new LinkedHashMap<>(camp);
        if (camp.get("contenido") != null) vista.put("contenido", com.inmobilia.util.HtmlSeguro.limpiar(String.valueOf(camp.get("contenido"))));
        model.addAttribute("campana", vista);
        model.addAttribute("envios", envios);
        model.addAttribute("tasaApertura", tasaApertura);
        model.addAttribute("tasaClick", tasaClick);
        model.addAttribute("tasaRebote", tasaRebote);
        return "campaigns_detail";
    }

    @PostMapping("/campanas/{id}/lanzar")
    public String launch(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.update("campanas", id, Map.of(
                "estado", "programada",
                "enviar_at", OffsetDateTime.now().toString()
        ));
        // Lanzar inmediatamente en background
        campaignService.enviarCampana(id);
        SessionUtils.flash(session, "🚀 Campaña lanzándose en segundo plano");
        return "redirect:/campanas/" + id;
    }

    @PostMapping("/campanas/{id}/pausar")
    public String pause(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.update("campanas", id, Map.of("estado", "pausada"), uid);
        return "redirect:/campanas/" + id;
    }

    // ===== TRACKING ENDPOINTS PÚBLICOS =====

    @GetMapping("/track/open/{token}")
    public void trackOpen(@PathVariable String token, HttpServletResponse resp, HttpServletRequest req) throws IOException {
        try {
            List<Map<String, Object>> rows = supabase.selectFilter("campana_envios", "tracking_token=eq." + token);
            if (!rows.isEmpty()) {
                Map<String, Object> envio = rows.get(0);
                int aperturas = num(envio.get("aperturas_count")) + 1;
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("aperturas_count", aperturas);
                if (envio.get("primer_abierto_at") == null) {
                    upd.put("primer_abierto_at", OffsetDateTime.now().toString());
                    upd.put("estado", "abierto");
                    // Incrementar contador en campaña
                    String campId = String.valueOf(envio.get("campana_id"));
                    List<Map<String, Object>> camps = supabase.selectFilter("campanas", "id=eq." + campId);
                    if (!camps.isEmpty()) {
                        supabase.update("campanas", campId, Map.of(
                                "abiertos", num(camps.get(0).get("abiertos")) + 1
                        ));
                    }
                }
                supabase.update("campana_envios", String.valueOf(envio.get("id")), upd);
            }
        } catch (Exception e) { log.warn("Track open err: {}", e.getMessage()); }

        // Devolver pixel transparente 1x1
        resp.setContentType("image/gif");
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.getOutputStream().write(PIXEL_GIF);
        resp.getOutputStream().flush();
    }

    /**
     * Cuenta el clic y redirige al destino, pero solo si es uno de verdad: firmado al enviar la
     * campaña o, en emails antiguos sin firma, una web que aparezca en esa campaña. Si no, a la
     * portada (antes redirigía a cualquier web: enlaces de phishing con nuestro dominio).
     */
    @GetMapping("/track/click/{token}")
    public String trackClick(@PathVariable String token, @RequestParam String url,
                             @RequestParam(required = false) String s, HttpServletRequest req) {
        if (!destinoValido(url)) return "redirect:/";
        boolean firmado = firmaEnlaces.valida(s, token, url);
        try {
            List<Map<String, Object>> rows = supabase.selectFilter("campana_envios", "tracking_token=eq." + token);
            if (rows.isEmpty()) return "redirect:/";
            if (!firmado && !apareceEnLaCampana(String.valueOf(rows.get(0).get("campana_id")), url)) return "redirect:/";
            {
                Map<String, Object> envio = rows.get(0);
                int clicks = num(envio.get("clicks_count")) + 1;
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("clicks_count", clicks);
                if (envio.get("primer_click_at") == null) {
                    upd.put("primer_click_at", OffsetDateTime.now().toString());
                    upd.put("estado", "click");
                    String campId = String.valueOf(envio.get("campana_id"));
                    List<Map<String, Object>> camps = supabase.selectFilter("campanas", "id=eq." + campId);
                    if (!camps.isEmpty()) {
                        supabase.update("campanas", campId, Map.of(
                                "clicks", num(camps.get(0).get("clicks")) + 1
                        ));
                    }
                }
                supabase.update("campana_envios", String.valueOf(envio.get("id")), upd);
            }
        } catch (Exception e) {
            log.warn("Track click err: {}", e.getMessage());
            if (!firmado) return "redirect:/";
        }
        return "redirect:" + url;
    }

    /** Solo http(s) y sin espacios ni saltos de línea. */
    static boolean destinoValido(String url) {
        return url != null && url.matches("(?i)https?://[^\\s\\p{Cntrl}]+");
    }

    /** Enlaces antiguos (sin firma): la web de destino tiene que salir en el contenido de la campaña. */
    private boolean apareceEnLaCampana(String campanaId, String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null || host.isBlank()) return false;
            List<Map<String, Object>> camps = supabase.selectFilter("campanas", "id=eq." + campanaId);
            if (camps.isEmpty()) return false;
            String contenido = String.valueOf(camps.get(0).get("contenido")) + " " + String.valueOf(camps.get(0).get("contenido_b"));
            return contenido.toLowerCase().contains("//" + host.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/track/unsubscribe/{token}")
    public String unsubscribe(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("campana_envios", "tracking_token=eq." + token);
        if (rows.isEmpty()) {
            model.addAttribute("error", "Enlace no válido");
            return "campaigns_unsubscribe";
        }
        Map<String, Object> envio = rows.get(0);
        String email = String.valueOf(envio.get("destinatario_email"));
        String userId = String.valueOf(envio.get("user_id"));

        Map<String, Object> baja = new LinkedHashMap<>();
        baja.put("user_id", userId);
        baja.put("email", email);
        baja.put("campana_origen_id", envio.get("campana_id"));
        baja.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("bajas_marketing", baja);

        supabase.update("campana_envios", String.valueOf(envio.get("id")), Map.of("estado", "baja"));

        model.addAttribute("email", email);
        return "campaigns_unsubscribe";
    }

    // ===== PLANTILLAS =====

    @GetMapping("/plantillas")
    public String templates(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("plantillas", supabase.selectFilter("plantillas_email", "user_id=eq." + uid + "&order=veces_usada.desc"));
        return "campaigns_templates";
    }

    @PostMapping("/plantillas/guardar")
    public String saveTemplate(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("categoria", form.get("categoria"));
        data.put("asunto", form.get("asunto"));
        data.put("contenido", form.get("contenido"));
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("plantillas_email", data);
        SessionUtils.flash(session, "✅ Plantilla guardada");
        return "redirect:/plantillas";
    }

    private static int num(Object o) { try { return o == null ? 0 : Integer.parseInt(String.valueOf(o).split("\\.")[0]); } catch(Exception e) { return 0; } }
    private static int parseInt(String s, int d) { try { return Integer.parseInt(s); } catch(Exception e) { return d; } }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
