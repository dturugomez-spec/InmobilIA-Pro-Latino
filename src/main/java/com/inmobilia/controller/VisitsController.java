package com.inmobilia.controller;

import com.inmobilia.service.EmailService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/visitas")
@Slf4j
public class VisitsController {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final NotificationService notif;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String list(HttpSession session, Model model,
                       @RequestParam(required = false) String estado,
                       @RequestParam(required = false) String vista) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=fecha_inicio.asc");
        if (estado != null && !estado.isBlank()) q.append("&estado=eq.").append(estado);

        List<Map<String, Object>> visitas = supabase.selectFilter("visitas", q.toString());

        // Enriquecer
        for (Map<String, Object> v : visitas) {
            String propId = String.valueOf(v.get("propiedad_id"));
            List<Map<String, Object>> p = supabase.selectFilter("propiedades", "id=eq." + propId);
            if (!p.isEmpty()) v.put("propiedad", p.get(0));
        }

        // KPIs
        LocalDate hoy = LocalDate.now();
        long hoyCount = visitas.stream().filter(v -> {
            try { return LocalDate.parse(String.valueOf(v.get("fecha_inicio")).substring(0, 10)).equals(hoy); }
            catch (Exception e) { return false; }
        }).count();
        long programadas = visitas.stream().filter(v -> "programada".equals(v.get("estado")) || "confirmada".equals(v.get("estado"))).count();
        long realizadas = visitas.stream().filter(v -> "realizada".equals(v.get("estado"))).count();
        long convertidas = visitas.stream().filter(v -> Boolean.TRUE.equals(v.get("convirtio_en_oferta"))).count();

        model.addAttribute("visitas", visitas);
        model.addAttribute("hoyCount", hoyCount);
        model.addAttribute("programadas", programadas);
        model.addAttribute("realizadas", realizadas);
        model.addAttribute("convertidas", convertidas);
        model.addAttribute("tasaConversion", realizadas > 0 ? (convertidas * 100.0 / realizadas) : 0);
        model.addAttribute("vista", vista != null ? vista : "lista");
        return "visits_list";
    }

    @GetMapping("/calendario")
    public String calendar(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        return "visits_calendar";
    }

    @GetMapping("/api/eventos")
    @ResponseBody
    public List<Map<String, Object>> apiEvents(HttpSession session,
                                                @RequestParam String start,
                                                @RequestParam String end) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Collections.emptyList();

        List<Map<String, Object>> visitas = supabase.selectFilter(
                "visitas",
                "user_id=eq." + uid +
                        "&fecha_inicio=gte." + start +
                        "&fecha_inicio=lte." + end);

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> v : visitas) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", v.get("id"));
            e.put("title", "🏠 " + v.get("visitante_nombre"));
            e.put("start", v.get("fecha_inicio"));
            e.put("end", v.get("fecha_fin"));
            String estado = String.valueOf(v.get("estado"));
            e.put("color", switch (estado) {
                case "programada" -> "#f59e0b";
                case "confirmada" -> "#3b82f6";
                case "realizada" -> "#10b981";
                case "cancelada" -> "#94a3b8";
                case "no_presentado" -> "#ef4444";
                default -> "#6366f1";
            });
            e.put("url", "/visitas/" + v.get("id"));
            events.add(e);
        }
        return events;
    }

    @GetMapping("/nueva")
    public String form(HttpSession session, Model model,
                       @RequestParam(required = false) String propiedadId,
                       @RequestParam(required = false) String leadId) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        model.addAttribute("leads", supabase.selectFilter("leads", "user_id=eq." + uid));
        model.addAttribute("agentes", supabase.selectFilter("users", "org_id=eq." + uid + "&active=eq.true"));
        model.addAttribute("preseleccion", Map.of(
                "propiedad_id", propiedadId != null ? propiedadId : "",
                "lead_id", leadId != null ? leadId : ""
        ));
        return "visits_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("propiedad_id", form.get("propiedad_id"));
        if (notBlank(form.get("lead_id"))) data.put("lead_id", form.get("lead_id"));
        if (notBlank(form.get("agente_id"))) data.put("agente_id", form.get("agente_id"));
        data.put("visitante_nombre", form.get("visitante_nombre"));
        data.put("visitante_email", form.get("visitante_email"));
        data.put("visitante_telefono", form.get("visitante_telefono"));
        data.put("visitante_dni", form.get("visitante_dni"));

        // Fecha + hora → timestamp
        String fecha = form.get("fecha");
        String hora = form.get("hora");
        int duracion = parseInt(form.get("duracion_min"), 30);
        if (notBlank(fecha) && notBlank(hora)) {
            LocalDateTime inicio = LocalDateTime.parse(fecha + "T" + hora + ":00");
            data.put("fecha_inicio", inicio.toString());
            data.put("fecha_fin", inicio.plusMinutes(duracion).toString());
        }
        data.put("duracion_min", duracion);
        data.put("tipo", form.getOrDefault("tipo", "presencial"));
        data.put("reunion_url", form.get("reunion_url"));
        data.put("notas", form.get("notas"));
        data.put("estado", "programada");
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("visitas", data);

        // Enviar email confirmación
        if (notBlank(form.get("visitante_email")) && saved != null) {
            sendConfirmationEmail(saved);
        }

        SessionUtils.flash(session, "✅ Visita programada");
        return "redirect:/visitas";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("visitas", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/visitas";

        Map<String, Object> v = rows.get(0);
        List<Map<String, Object>> prop = supabase.selectFilter("propiedades", "id=eq." + v.get("propiedad_id"));
        if (!prop.isEmpty()) v.put("propiedad", prop.get(0));

        model.addAttribute("visita", v);
        model.addAttribute("baseUrl", BASE_URL);
        return "visits_detail";
    }

    @PostMapping("/{id}/estado")
    public String setEstado(@PathVariable String id, @RequestParam String estado,
                             @RequestParam(required = false) String motivo,
                             HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("estado", estado);
        if ("cancelada".equals(estado)) {
            data.put("cancelada_at", OffsetDateTime.now().toString());
            data.put("motivo_cancelacion", motivo);
        }
        data.put("updated_at", OffsetDateTime.now().toString());
        supabase.update("visitas", id, data, uid);
        return "redirect:/visitas/" + id;
    }

    @PostMapping("/{id}/feedback")
    public String feedback(@PathVariable String id, @RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interes_nivel", parseInt(form.get("interes_nivel"), 3));
        data.put("feedback_agente", form.get("feedback_agente"));
        data.put("objeciones", form.get("objeciones"));
        data.put("siguiente_paso", form.get("siguiente_paso"));
        data.put("fecha_siguiente_paso", form.get("fecha_siguiente_paso"));
        data.put("estado", "realizada");
        data.put("updated_at", OffsetDateTime.now().toString());
        if ("oferta".equals(form.get("siguiente_paso"))) {
            data.put("convirtio_en_oferta", true);
        }
        supabase.update("visitas", id, data, uid);
        SessionUtils.flash(session, "📝 Feedback guardado");
        return "redirect:/visitas/" + id;
    }

    // ===== ENDPOINTS PÚBLICOS (sin auth) =====

    @GetMapping("/confirmar/{token}")
    public String confirm(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("visitas", "confirmacion_token=eq." + token);
        if (rows.isEmpty()) {
            model.addAttribute("error", "Enlace no válido o expirado");
            return "visits_public_error";
        }
        Map<String, Object> v = rows.get(0);
        if (!"programada".equals(v.get("estado"))) {
            model.addAttribute("visita", v);
            model.addAttribute("yaConfirmada", true);
            return "visits_public_confirm";
        }
        // Confirmar
        supabase.update("visitas", String.valueOf(v.get("id")), Map.of(
                "estado", "confirmada",
                "confirmada_at", OffsetDateTime.now().toString(),
                "updated_at", OffsetDateTime.now().toString()
        ));
        v.put("estado", "confirmada");
        model.addAttribute("visita", v);
        return "visits_public_confirm";
    }

    @GetMapping("/feedback-publico/{token}")
    public String publicFeedbackForm(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("visitas", "feedback_token=eq." + token);
        if (rows.isEmpty()) {
            model.addAttribute("error", "Enlace no válido");
            return "visits_public_error";
        }
        model.addAttribute("visita", rows.get(0));
        return "visits_public_feedback";
    }

    @PostMapping("/feedback-publico/{token}")
    public String publicFeedbackSave(@PathVariable String token, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("visitas", "feedback_token=eq." + token);
        if (rows.isEmpty()) return "redirect:/";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interes_nivel", parseInt(form.get("interes_nivel"), 3));
        data.put("feedback_visitante", form.get("feedback_visitante"));
        data.put("updated_at", OffsetDateTime.now().toString());
        supabase.update("visitas", String.valueOf(rows.get(0).get("id")), data);
        model.addAttribute("gracias", true);
        return "visits_public_feedback";
    }

    // ===== CRONS =====

    /** Recordatorio 24h antes (cada hora a y media para precisión) */
    @Scheduled(cron = "0 30 * * * *", zone = "Europe/Madrid")
    public void cron24h() {
        log.debug("[CRON] Recordatorios 24h visitas");
        ZonedDateTime ahora = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        ZonedDateTime en24h = ahora.plusHours(24);
        ZonedDateTime en25h = ahora.plusHours(25);

        List<Map<String, Object>> visitas = supabase.selectFilter(
                "visitas",
                "fecha_inicio=gte." + en24h.toOffsetDateTime() +
                        "&fecha_inicio=lt." + en25h.toOffsetDateTime() +
                        "&recordatorio_24h_enviado=eq.false" +
                        "&estado=in.(programada,confirmada)");

        for (Map<String, Object> v : visitas) {
            try {
                sendReminderEmail(v, "24h");
                supabase.update("visitas", String.valueOf(v.get("id")),
                        Map.of("recordatorio_24h_enviado", true));
            } catch (Exception e) {
                log.warn("Err recordatorio 24h: {}", e.getMessage());
            }
        }
    }

    /** Recordatorio 1h antes (cada 15 minutos) */
    @Scheduled(cron = "0 */15 * * * *", zone = "Europe/Madrid")
    public void cron1h() {
        ZonedDateTime ahora = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        ZonedDateTime en1h = ahora.plusMinutes(45);
        ZonedDateTime en1h15 = ahora.plusMinutes(75);

        List<Map<String, Object>> visitas = supabase.selectFilter(
                "visitas",
                "fecha_inicio=gte." + en1h.toOffsetDateTime() +
                        "&fecha_inicio=lt." + en1h15.toOffsetDateTime() +
                        "&recordatorio_1h_enviado=eq.false" +
                        "&estado=in.(programada,confirmada)");

        for (Map<String, Object> v : visitas) {
            try {
                sendReminderEmail(v, "1h");
                // Notificación al agente
                notif.create(String.valueOf(v.get("user_id")),
                        "⏰ Visita en 1h",
                        v.get("visitante_nombre") + " en " + (v.get("fecha_inicio")),
                        "/visitas/" + v.get("id"),
                        "alta");
                supabase.update("visitas", String.valueOf(v.get("id")),
                        Map.of("recordatorio_1h_enviado", true));
            } catch (Exception e) {
                log.warn("Err recordatorio 1h: {}", e.getMessage());
            }
        }
    }

    /** Solicitar feedback 2h después */
    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Madrid")
    public void cronFeedback() {
        ZonedDateTime ahora = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        ZonedDateTime hace2h = ahora.minusHours(2);
        ZonedDateTime hace3h = ahora.minusHours(3);

        List<Map<String, Object>> visitas = supabase.selectFilter(
                "visitas",
                "fecha_fin=gte." + hace3h.toOffsetDateTime() +
                        "&fecha_fin=lt." + hace2h.toOffsetDateTime() +
                        "&estado=in.(programada,confirmada)");

        for (Map<String, Object> v : visitas) {
            try {
                // Auto-marcar como "no presentado" si era hace 2h y nadie cambió estado
                String email = String.valueOf(v.get("visitante_email"));
                if (notBlank(email) && !email.equals("null")) {
                    sendFeedbackEmail(v);
                }
            } catch (Exception e) { /* silent */ }
        }
    }

    // ===== EMAILS =====

    private void sendConfirmationEmail(Map<String, Object> v) {
        try {
            String email = String.valueOf(v.get("visitante_email"));
            if (!notBlank(email)) return;
            String url = BASE_URL + "/visitas/confirmar/" + v.get("confirmacion_token");
            String html = String.format(
                "<h2>📅 Confirmación de visita</h2>" +
                "<p>Hola %s, te confirmamos tu visita:</p>" +
                "<ul><li>📅 Fecha: <strong>%s</strong></li>" +
                "<li>📍 Dirección: ver detalles en el enlace</li></ul>" +
                "<p><a href='%s' style='display:inline-block;padding:10px 20px;background:#10b981;color:white;text-decoration:none;border-radius:6px'>✓ Confirmar asistencia</a></p>" +
                "<p style='color:#64748b;font-size:12px'>Si no puedes asistir, contesta a este email.</p>",
                v.get("visitante_nombre"), v.get("fecha_inicio"), url);
            emailService.send(email, "📅 Confirma tu visita - InmobilIA Pro", html);
        } catch (Exception e) { log.warn("Err send confirm: {}", e.getMessage()); }
    }

    private void sendReminderEmail(Map<String, Object> v, String tipo) {
        try {
            String email = String.valueOf(v.get("visitante_email"));
            if (!notBlank(email) || "null".equals(email)) return;
            String emoji = "24h".equals(tipo) ? "📅" : "⏰";
            String when = "24h".equals(tipo) ? "mañana" : "en 1 hora";
            String html = String.format(
                "<h2>%s Recordatorio de visita</h2>" +
                "<p>Hola %s, recuerda que tu visita es <strong>%s</strong>:</p>" +
                "<p>📅 %s</p>",
                emoji, v.get("visitante_nombre"), when, v.get("fecha_inicio"));
            emailService.send(email, emoji + " Recordatorio de visita", html);
        } catch (Exception e) { log.warn("Err reminder: {}", e.getMessage()); }
    }

    private void sendFeedbackEmail(Map<String, Object> v) {
        try {
            String email = String.valueOf(v.get("visitante_email"));
            if (!notBlank(email) || "null".equals(email)) return;
            String url = BASE_URL + "/visitas/feedback-publico/" + v.get("feedback_token");
            String html = String.format(
                "<h2>¿Qué te ha parecido la visita?</h2>" +
                "<p>Hola %s, nos encantaría saber tu opinión.</p>" +
                "<p><a href='%s' style='display:inline-block;padding:10px 20px;background:#3b82f6;color:white;text-decoration:none;border-radius:6px'>📝 Dejar feedback</a></p>",
                v.get("visitante_nombre"), url);
            emailService.send(email, "¿Cómo te pareció la propiedad?", html);
        } catch (Exception e) { /* silent */ }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch(Exception e) { return def; } }
}
