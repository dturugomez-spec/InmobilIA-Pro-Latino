package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 🏅 Impacto del Agente IA: agrega el trabajo del agente y estima el tiempo ahorrado.
 * Reutilizado por el panel (30 días) y por el email semanal de retención (7 días).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentImpactService {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final NotificationService notif;
    private final com.inmobilia.service.PaisService paises;

    // Minutos de trabajo manual que ahorra cada acción del agente
    private static final int MIN_MENSAJE = 8, MIN_ACCION = 5, MIN_CAPTACION = 6, MIN_SEGUIMIENTO = 10;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    /** Métricas del impacto del agente en los últimos {dias} días. */
    public Map<String, Object> resumen(String uid, int dias) {
        String desde = LocalDate.now().minusDays(dias).toString();

        List<Map<String, Object>> acciones = safe("agente_acciones",
                "user_id=eq." + uid + "&estado=eq.ejecutada&resuelta_at=gte." + desde + "&limit=1000");
        long mensajesIA = acciones.stream().filter(a -> "mensaje_lead".equals(a.get("tipo"))).count();
        long otrasAcciones = acciones.size() - mensajesIA;

        List<Map<String, Object>> cadencias = safe("cadencia_inscripciones", "user_id=eq." + uid + "&limit=1000");
        long enSeguimiento = cadencias.stream().filter(c -> "activa".equals(c.get("estado"))).count();
        long pasosSeguimiento = cadencias.stream().mapToLong(c -> (long) nd(c.get("pasos_dados"))).sum();

        long valoraciones = countDesde("valoraciones", uid, desde);
        long simulaciones = countDesde("simulaciones_hipoteca", uid, desde);
        long captacionAuto = valoraciones + simulaciones;

        long tareasIA = safe("tareas", "user_id=eq." + uid + "&created_at=gte." + desde + "&limit=1000").stream()
                .filter(t -> {
                    String d = String.valueOf(t.getOrDefault("descripcion", ""));
                    return d.contains("Agente IA") || d.contains("Autopiloto") || d.contains("cadencia");
                }).count();

        long minutos = mensajesIA * MIN_MENSAJE + otrasAcciones * MIN_ACCION
                + captacionAuto * MIN_CAPTACION + pasosSeguimiento * MIN_SEGUIMIENTO;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mensajesIA", mensajesIA);
        r.put("otrasAcciones", otrasAcciones);
        r.put("enSeguimiento", enSeguimiento);
        r.put("pasosSeguimiento", pasosSeguimiento);
        r.put("valoraciones", valoraciones);
        r.put("simulaciones", simulaciones);
        r.put("captacionAuto", captacionAuto);
        r.put("tareasIA", tareasIA);
        r.put("horas", minutos / 60);
        r.put("minutos", minutos % 60);
        r.put("totalAcciones", acciones.size() + pasosSeguimiento + captacionAuto);
        return r;
    }

    /** Cron viernes 17:00: envía el resumen semanal de impacto al dueño (retención). */
    @Scheduled(cron = "0 0 17 * * FRI", zone = "${app.zona-horaria:America/Mexico_City}")
    public void cronSemanal() {
        try {
            List<Map<String, Object>> admins = safe("users", "active=eq.true&role=eq.admin&limit=500");
            for (Map<String, Object> admin : admins) {
                String uid = String.valueOf(admin.get("id"));
                paises.usar(uid);
                try {
                    Map<String, Object> r = resumen(uid, 7);
                    if ((long) r.get("totalAcciones") <= 0) continue;  // semana sin actividad → no molestar
                    String email = String.valueOf(admin.getOrDefault("email", ""));
                    if (!email.isBlank() && !"null".equals(email)) {
                        emailService.send(email, "🏅 Lo que tu Agente IA ha hecho esta semana", emailHtml(r));
                    }
                    notif.create(uid, "🏅 Resumen semanal del Agente",
                            "Tu agente te ahorró ~" + r.get("horas") + "h esta semana. Míralo →", "/impacto", "media");
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("[IMPACTO] cron semanal: {}", e.getMessage());
        }
    }

    private String emailHtml(Map<String, Object> r) {
        return "<div style='font-family:sans-serif;max-width:600px;margin:auto'>" +
            "<h2>🏅 Tu Agente IA, esta semana</h2>" +
            "<div style='background:linear-gradient(135deg,#059669,#0f766e);color:#fff;border-radius:12px;padding:22px;text-align:center;margin:16px 0'>" +
            "<p style='margin:0;opacity:.8;font-size:13px;text-transform:uppercase'>Tiempo de trabajo ahorrado (estimado)</p>" +
            "<p style='margin:6px 0 0;font-size:38px;font-weight:800'>" + r.get("horas") + " h " +
            ((long) r.get("minutos") > 0 ? r.get("minutos") + " min" : "") + "</p></div>" +
            "<ul style='font-size:15px;line-height:1.8'>" +
            "<li>✉️ <strong>" + r.get("mensajesIA") + "</strong> mensajes preparados/enviados</li>" +
            "<li>⚡ <strong>" + r.get("otrasAcciones") + "</strong> acciones ejecutadas (precios, tareas, etapas)</li>" +
            "<li>🔁 <strong>" + r.get("enSeguimiento") + "</strong> leads en seguimiento automático</li>" +
            "<li>🧲 <strong>" + r.get("captacionAuto") + "</strong> leads captados solos (valoración + simulador)</li>" +
            "</ul>" +
            "<p style='text-align:center;margin-top:20px'><a href='" + BASE_URL + "/impacto' " +
            "style='display:inline-block;padding:12px 24px;background:#4f46e5;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold'>Ver el detalle →</a></p>" +
            "<p style='font-size:11px;color:#94a3b8'>Un empleado que trabaja por ti, día y noche. — InmobilIA Pro</p></div>";
    }

    private long countDesde(String tabla, String uid, String desde) {
        return safe(tabla, "user_id=eq." + uid + "&created_at=gte." + desde + "&limit=2000").size();
    }
    private List<Map<String, Object>> safe(String tabla, String filtro) {
        try { return supabase.selectFilter(tabla, filtro); } catch (Exception e) { return List.of(); }
    }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }
}
