package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Cron diario que desactiva las cuentas Starter cuyo trial de 10 días ha expirado.
 *
 * Reglas:
 *  - Plan "starter" o "free": dura 10 días desde created_at
 *  - Plan "pro" o "agency": tienen trial_ends_at fijado por la aprobación
 *  - Si trial_ends_at < hoy → set active=false + email avisando
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrialExpiryService {

    private final SupabaseService supabase;
    private final EmailService emailService;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    /** Cron diario a las 03:00. */
    @Scheduled(cron = "0 0 3 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void desactivarTrialsExpirados() {
        log.info("[CRON] Comprobando trials expirados...");
        try {
            List<Map<String, Object>> users = supabase.selectFilter(
                    "users", "active=eq.true&plan=in.(starter,free,trial)");

            LocalDate hoy = LocalDate.now();
            int desactivados = 0, avisados = 0;

            for (Map<String, Object> u : users) {
                String userId = String.valueOf(u.get("id"));
                String email = String.valueOf(u.get("email"));
                String plan = String.valueOf(u.getOrDefault("plan", ""));

                LocalDate inicio = parseDate(u.get("created_at"));
                LocalDate fin = parseDate(u.get("trial_ends_at"));
                if (fin == null && inicio != null) {
                    // Si no hay trial_ends_at, calculo desde created_at + 10 días
                    fin = inicio.plusDays(10);
                }
                if (fin == null) continue;

                long diasRestantes = ChronoUnit.DAYS.between(hoy, fin);

                if (diasRestantes < 0) {
                    // Trial expirado → desactivar
                    supabase.update("users", userId, Map.of(
                            "active", false,
                            "plan_status", "expired"
                    ), userId);
                    try {
                        emailService.send(null, email,
                                "⏰ Tu prueba InmobilIA Pro ha finalizado",
                                String.format(
                                    "<h2>Hola</h2>" +
                                    "<p>Tu período de prueba de 10 días con plan %s ha finalizado y tu cuenta se ha desactivado.</p>" +
                                    "<p>Si quieres seguir utilizando InmobilIA Pro, solicita el plan Pro o Agency:</p>" +
                                    "<p><a href='%s/#contacto' style='display:inline-block;padding:12px 24px;background:#0f172a;color:white;text-decoration:none;border-radius:8px;font-weight:bold'>Solicitar plan Pro o Agency →</a></p>" +
                                    "<p>Tus datos quedan guardados durante 30 días. Si activas un nuevo plan, los recuperas todos.</p>" +
                                    "<p>— Equipo InmobilIA Pro</p>",
                                    plan, BASE_URL));
                    } catch (Exception ignored) {}
                    desactivados++;
                } else if (diasRestantes == 3 || diasRestantes == 1) {
                    // Aviso a 3 días y a 1 día de finalizar
                    try {
                        emailService.send(null, email,
                                String.format("⏰ Tu prueba acaba en %d %s", diasRestantes, diasRestantes == 1 ? "día" : "días"),
                                String.format(
                                    "<h2>Hola</h2>" +
                                    "<p>Tu período de prueba de 10 días con InmobilIA Pro acaba en <strong>%d %s</strong>.</p>" +
                                    "<p>Si quieres seguir utilizando la plataforma sin interrupción, solicita ahora el plan Pro o Agency:</p>" +
                                    "<p><a href='%s/#contacto' style='display:inline-block;padding:12px 24px;background:#0f172a;color:white;text-decoration:none;border-radius:8px;font-weight:bold'>Solicitar plan →</a></p>" +
                                    "<p>— Equipo InmobilIA Pro</p>",
                                    diasRestantes, diasRestantes == 1 ? "día" : "días", BASE_URL));
                    } catch (Exception ignored) {}
                    avisados++;
                }
            }

            log.info("[CRON] Trials: {} desactivados, {} avisados", desactivados, avisados);
        } catch (Exception e) {
            log.error("[CRON] Error desactivando trials: {}", e.getMessage());
        }
    }

    private LocalDate parseDate(Object o) {
        if (o == null) return null;
        try {
            String s = String.valueOf(o);
            if (s.length() >= 10) return LocalDate.parse(s.substring(0, 10));
        } catch (Exception ignored) {}
        return null;
    }
}
