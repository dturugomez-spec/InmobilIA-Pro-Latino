package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/google-calendar")
@Slf4j
public class GoogleCalendarController {

    private final SupabaseService supabase;
    private final RestTemplate rest = new RestTemplate();

    private static final String CLIENT_ID = System.getenv().getOrDefault("GOOGLE_CLIENT_ID", "TU_CLIENT_ID");
    private static final String CLIENT_SECRET = System.getenv().getOrDefault("GOOGLE_CLIENT_SECRET", "TU_SECRET");
    /** Dirección de vuelta registrada en Google Cloud: GOOGLE_REDIRECT_URI o la web con www (antes, sin www: no funcionaba). */
    static final String REDIRECT = redireccion(System.getenv("GOOGLE_REDIRECT_URI"));
    private static final String SCOPE = "https://www.googleapis.com/auth/calendar.events";
    private static final String CLAVE_STATE = "gcal_oauth_state";

    static String redireccion(String configurada) {
        return configurada != null && !configurada.isBlank() ? configurada.trim()
                : com.inmobilia.util.UrlBase.BASE + "/google-calendar/callback";
    }

    private static boolean configurado() {
        return !CLIENT_ID.isBlank() && !CLIENT_ID.startsWith("TU_") && !CLIENT_SECRET.isBlank() && !CLIENT_SECRET.startsWith("TU_");
    }

    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> cfgs = supabase.selectFilter("integraciones", "user_id=eq." + uid + "&proveedor=eq.google_calendar");
        boolean conectado = !cfgs.isEmpty() && cfgs.get(0).get("access_token") != null;

        // "state" de un solo uso ligado a la sesión: sin él, otra web podía terminar la conexión
        // con SU cuenta de Google y las visitas de la inmobiliaria acababan en su calendario
        String state = java.util.HexFormat.of().formatHex(randomBytes());
        session.setAttribute(CLAVE_STATE, state);
        String authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=" + enc(CLIENT_ID) +
                "&redirect_uri=" + enc(REDIRECT) +
                "&response_type=code" +
                "&scope=" + enc(SCOPE) +
                "&access_type=offline" +
                "&prompt=consent" +
                "&state=" + state;

        model.addAttribute("conectado", conectado);
        model.addAttribute("googleConfigurado", configurado());
        model.addAttribute("authUrl", authUrl);
        model.addAttribute("cfg", cfgs.isEmpty() ? null : cfgs.get(0));
        return "google_calendar_panel";
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    @GetMapping("/callback")
    public String callback(@RequestParam(required = false) String code,
                           @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error,
                           HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Object esperado = session.getAttribute(CLAVE_STATE);
        session.removeAttribute(CLAVE_STATE);
        if (state == null || esperado == null || !java.security.MessageDigest.isEqual(
                state.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                String.valueOf(esperado).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            SessionUtils.flash(session, "⚠️ No se pudo verificar la conexión con Google. Vuelve a pulsar «Conectar con Google».");
            return "redirect:/google-calendar";
        }
        if (error != null || code == null || code.isBlank()) {
            SessionUtils.flash(session, "Conexión con Google cancelada.");
            return "redirect:/google-calendar";
        }

        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("code", code);
            body.add("client_id", CLIENT_ID);
            body.add("client_secret", CLIENT_SECRET);
            body.add("redirect_uri", REDIRECT);
            body.add("grant_type", "authorization_code");

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(body, h);

            Map<?, ?> resp = rest.postForObject("https://oauth2.googleapis.com/token", req, Map.class);
            if (resp == null || resp.get("access_token") == null) {
                SessionUtils.flash(session, "⚠️ Google no ha devuelto el permiso. Inténtalo de nuevo.");
                return "redirect:/google-calendar";
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id", uid);
            data.put("proveedor", "google_calendar");
            data.put("access_token", resp.get("access_token"));
            data.put("refresh_token", resp.get("refresh_token"));
            data.put("activo", true);
            data.put("ultimo_sync_at", OffsetDateTime.now().toString());

            List<Map<String, Object>> exist = supabase.selectFilter("integraciones", "user_id=eq." + uid + "&proveedor=eq.google_calendar");
            if (!exist.isEmpty()) supabase.update("integraciones", String.valueOf(exist.get(0).get("id")), data);
            else {
                data.put("created_at", OffsetDateTime.now().toString());
                supabase.insert("integraciones", data);
            }
        } catch (Exception e) {
            // Antes se decía "conectado" aunque fallara
            log.error("OAuth Google err: {}", e.getMessage());
            SessionUtils.flash(session, "⚠️ No se pudo conectar con Google Calendar. Inténtalo de nuevo.");
            return "redirect:/google-calendar";
        }
        SessionUtils.flash(session, "✅ Google Calendar conectado");
        return "redirect:/google-calendar";
    }

    /** Sincroniza una visita a Google Calendar. */
    @PostMapping("/sync-visita/{id}")
    @ResponseBody
    public Map<String, Object> syncVisita(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        List<Map<String, Object>> cfgs = supabase.selectFilter("integraciones", "user_id=eq." + uid + "&proveedor=eq.google_calendar");
        if (cfgs.isEmpty()) return Map.of("ok", false, "error", "No conectado");
        String token = String.valueOf(cfgs.get(0).get("access_token"));

        List<Map<String, Object>> visitas = supabase.selectOwned("visitas", id, uid);
        if (visitas.isEmpty()) return Map.of("ok", false);
        Map<String, Object> v = visitas.get(0);

        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("summary", "🏠 Visita: " + v.get("visitante_nombre"));
            event.put("description", "Visita programada vía InmobilIA Pro");
            event.put("start", Map.of("dateTime", v.get("fecha_inicio"), "timeZone", com.inmobilia.util.PaisActual.get().zona()));
            event.put("end", Map.of("dateTime", v.get("fecha_fin"), "timeZone", com.inmobilia.util.PaisActual.get().zona()));

            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            h.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(event, h);

            Map<?, ?> resp = rest.postForObject(
                    "https://www.googleapis.com/calendar/v3/calendars/primary/events", req, Map.class);

            return Map.of("ok", true, "eventId", resp.get("id"));
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/desconectar")
    public String desconectar(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("integraciones", "user_id=eq." + uid + "&proveedor=eq.google_calendar");
        if (!rows.isEmpty()) supabase.delete("integraciones", String.valueOf(rows.get(0).get("id")));
        return "redirect:/google-calendar";
    }
}
