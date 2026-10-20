package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;

/**
 * Calendario integrado: agrega eventos de varias tablas (reservas, eventos,
 * vencimientos de contratos, mantenimientos programados) en una vista unificada
 * compatible con FullCalendar.
 */
@Controller
@RequestMapping("/calendar")
@RequiredArgsConstructor
@Slf4j
public class CalendarController {

    private final SupabaseService supabase;

    @GetMapping
    public String view(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Agenda y calendario");
        return "calendar";
    }

    /** Export iCalendar para suscripción desde Google/Outlook/Apple Calendar. */
    @GetMapping(value = "/calendar/export.ics", produces = "text/calendar; charset=UTF-8")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<String> exportIcs(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return org.springframework.http.ResponseEntity.status(401).build();
        String uid = SessionUtils.uid(session);

        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n");
        ics.append("VERSION:2.0\r\n");
        ics.append("PRODID:-//InmobilIA Pro//ES\r\n");
        ics.append("CALSCALE:GREGORIAN\r\n");
        ics.append("X-WR-CALNAME:InmobilIA Pro Agenda\r\n");
        ics.append("X-WR-TIMEZONE:Europe/Madrid\r\n");

        // Eventos
        try {
            for (var e : supabase.query("eventos", uid)) {
                String d = strOrNull(e.get("date"));
                if (d == null || d.isBlank()) continue;
                ics.append("BEGIN:VEVENT\r\n");
                ics.append("UID:event-").append(e.get("id")).append("@inmobilia-pro\r\n");
                ics.append("DTSTART:").append(formatIcsDate(d, strOrNull(e.get("time")))).append("\r\n");
                ics.append("SUMMARY:").append(escape(strOrNull(e.get("title")))).append("\r\n");
                if (e.get("description") != null) ics.append("DESCRIPTION:").append(escape(strOrNull(e.get("description")))).append("\r\n");
                if (e.get("location") != null) ics.append("LOCATION:").append(escape(strOrNull(e.get("location")))).append("\r\n");
                ics.append("END:VEVENT\r\n");
            }
            // Visitas
            for (var r : supabase.query("reservas", uid)) {
                String d = strOrNull(r.get("date"));
                if (d == null || d.isBlank()) continue;
                ics.append("BEGIN:VEVENT\r\n");
                ics.append("UID:visita-").append(r.get("id")).append("@inmobilia-pro\r\n");
                ics.append("DTSTART:").append(formatIcsDate(d, strOrNull(r.get("time")))).append("\r\n");
                ics.append("SUMMARY:").append(escape("👁 Visita · " + strOrNull(r.get("client_name")))).append("\r\n");
                if (r.get("property_name") != null) ics.append("LOCATION:").append(escape(strOrNull(r.get("property_name")))).append("\r\n");
                ics.append("END:VEVENT\r\n");
            }
        } catch (Exception ignored) {}

        ics.append("END:VCALENDAR\r\n");

        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=inmobilia-agenda.ics")
                .body(ics.toString());
    }

    private static String formatIcsDate(String date, String time) {
        String d = date.length() >= 10 ? date.substring(0, 10).replace("-", "") : date;
        if (time != null && time.length() >= 5) {
            String t = time.substring(0, 5).replace(":", "") + "00";
            return d + "T" + t;
        }
        return d;
    }
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n");
    }

    /**
     * Devuelve los eventos en formato FullCalendar JSON.
     * Combina: visitas (reservas), eventos generales, fin de contratos, mantenimiento programado.
     */
    @GetMapping("/events")
    @ResponseBody
    public List<Map<String, Object>> events(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        String uid = SessionUtils.uid(session);
        List<Map<String, Object>> out = new ArrayList<>();

        // ─── Visitas (tabla reservas) ───
        try {
            for (var r : supabase.query("reservas", uid)) {
                String date = strOrNull(r.get("date"));
                if (date == null || date.isBlank()) continue;
                String time = strOrNull(r.get("time"));
                String start = date.substring(0, Math.min(10, date.length())) + (time != null && !time.isBlank() ? "T" + time : "");
                Map<String, Object> ev = new HashMap<>();
                ev.put("id", "reserva-" + r.get("id"));
                ev.put("title", "👁  Visita · " + (r.get("client_name") != null ? r.get("client_name") : "—"));
                ev.put("start", start);
                ev.put("backgroundColor", "#3b82f6");
                ev.put("borderColor", "#1d4ed8");
                ev.put("textColor", "#ffffff");
                Map<String, Object> ext = new HashMap<>();
                ext.put("type", "visita");
                ext.put("property", r.get("property_name"));
                ext.put("status", r.get("status"));
                ext.put("notes", r.get("feedback"));
                ev.put("extendedProps", ext);
                out.add(ev);
            }
        } catch (Exception e) { log.warn("calendar reservas: {}", e.getMessage()); }

        // ─── Eventos generales (tabla eventos) ───
        try {
            for (var e : supabase.query("eventos", uid)) {
                String date = strOrNull(e.get("date"));
                if (date == null || date.isBlank()) continue;
                String time = strOrNull(e.get("time"));
                String start = date.substring(0, Math.min(10, date.length())) + (time != null && !time.isBlank() ? "T" + time : "");
                Map<String, Object> ev = new HashMap<>();
                ev.put("id", "evento-" + e.get("id"));
                ev.put("title", "📅 " + (e.get("title") != null ? e.get("title") : "Evento"));
                ev.put("start", start);
                ev.put("backgroundColor", "#8b5cf6");
                ev.put("borderColor", "#6d28d9");
                ev.put("textColor", "#ffffff");
                Map<String, Object> ext = new HashMap<>();
                ext.put("type", "evento");
                ext.put("property", e.get("property_name"));
                ext.put("location", e.get("location"));
                ext.put("description", e.get("description"));
                ev.put("extendedProps", ext);
                out.add(ev);
            }
        } catch (Exception ex) { log.warn("calendar eventos: {}", ex.getMessage()); }

        // ─── Fin de contratos (vencimientos) ───
        try {
            for (var c : supabase.query("contratos", uid)) {
                String end = strOrNull(c.get("end_date"));
                if (end == null || end.isBlank()) continue;
                Map<String, Object> ev = new HashMap<>();
                ev.put("id", "contrato-end-" + c.get("id"));
                ev.put("title", "⚠ Fin contrato · " + (c.get("tenant_name") != null ? c.get("tenant_name") : "—"));
                ev.put("start", end.substring(0, Math.min(10, end.length())));
                ev.put("allDay", true);
                ev.put("backgroundColor", "#f59e0b");
                ev.put("borderColor", "#d97706");
                ev.put("textColor", "#ffffff");
                Map<String, Object> ext = new HashMap<>();
                ext.put("type", "vencimiento_contrato");
                ext.put("property", c.get("property_name"));
                ext.put("rent", c.get("rent"));
                ev.put("extendedProps", ext);
                out.add(ev);
            }
        } catch (Exception e) { log.warn("calendar contratos: {}", e.getMessage()); }

        // ─── Mantenimiento programado ───
        try {
            for (var m : supabase.query("mantenimiento", uid)) {
                String sch = strOrNull(m.get("scheduled_date"));
                if (sch == null || sch.isBlank()) continue;
                boolean done = "Completado".equalsIgnoreCase(strOrNull(m.get("status")))
                            || "Cerrado".equalsIgnoreCase(strOrNull(m.get("status")));
                Map<String, Object> ev = new HashMap<>();
                ev.put("id", "mant-" + m.get("id"));
                ev.put("title", "🔧 " + (m.get("title") != null ? m.get("title") : "Mantenimiento"));
                ev.put("start", sch.substring(0, Math.min(10, sch.length())));
                ev.put("allDay", true);
                ev.put("backgroundColor", done ? "#10b981" : "#ef4444");
                ev.put("borderColor", done ? "#047857" : "#b91c1c");
                ev.put("textColor", "#ffffff");
                Map<String, Object> ext = new HashMap<>();
                ext.put("type", "mantenimiento");
                ext.put("property", m.get("property_name"));
                ext.put("status", m.get("status"));
                ext.put("cost", m.get("cost"));
                ev.put("extendedProps", ext);
                out.add(ev);
            }
        } catch (Exception e) { log.warn("calendar mantenimiento: {}", e.getMessage()); }

        // ─── Tareas con due_date ───
        try {
            for (var t : supabase.query("tareas", uid)) {
                String due = strOrNull(t.get("due_date"));
                if (due == null || due.isBlank()) continue;
                boolean done = "Completada".equalsIgnoreCase(strOrNull(t.get("status")));
                String prio = strOrNull(t.get("priority"));
                Map<String, Object> ev = new HashMap<>();
                ev.put("id", "tarea-" + t.get("id"));
                ev.put("title", "✓ " + (t.get("title") != null ? t.get("title") : "Tarea"));
                ev.put("start", due.substring(0, Math.min(10, due.length())));
                ev.put("allDay", true);
                String bg = done ? "#10b981" : ("Urgente".equalsIgnoreCase(prio) ? "#dc2626" : "#64748b");
                ev.put("backgroundColor", bg);
                ev.put("borderColor", bg);
                ev.put("textColor", "#ffffff");
                Map<String, Object> ext = new HashMap<>();
                ext.put("type", "tarea");
                ext.put("priority", prio);
                ext.put("status", t.get("status"));
                ext.put("assigned", t.get("assigned_to"));
                ev.put("extendedProps", ext);
                out.add(ev);
            }
        } catch (Exception e) { log.warn("calendar tareas: {}", e.getMessage()); }

        // ─── Próximas citas de leads (next_followup) ───
        try {
            for (var l : supabase.query("leads", uid)) {
                String next = strOrNull(l.get("next_followup"));
                if (next == null || next.isBlank()) continue;
                Map<String, Object> ev = new HashMap<>();
                ev.put("id", "lead-" + l.get("id"));
                ev.put("title", "💼 Seguir lead · " + (l.get("nombre") != null ? l.get("nombre") : "—"));
                ev.put("start", next.substring(0, Math.min(10, next.length())));
                ev.put("allDay", true);
                ev.put("backgroundColor", "#06b6d4");
                ev.put("borderColor", "#0891b2");
                ev.put("textColor", "#ffffff");
                Map<String, Object> ext = new HashMap<>();
                ext.put("type", "lead_followup");
                ext.put("etapa", l.get("etapa"));
                ext.put("interes", l.get("interes"));
                ev.put("extendedProps", ext);
                out.add(ev);
            }
        } catch (Exception e) { log.warn("calendar leads: {}", e.getMessage()); }

        return out;
    }

    private static String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }
}
