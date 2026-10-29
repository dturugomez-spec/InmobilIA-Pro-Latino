package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Fin de la prueba gratis (14 días de Business sin tarjeta, desde la aprobación).
 *
 *  - 3 días y 1 día antes: aviso por correo.
 *  - Al acabar, si no ha elegido plan: la cuenta pasa a Free y CONSERVA sus datos. Free incluye
 *    3 activos y 1 usuario: puede seguir entrando, pero no añadir más activos ni más personas
 *    hasta que mejore el plan (y los empleados que no caben no entran; ver SubscriptionGateInterceptor).
 *
 * Mientras tanto PlanService.planEfectivo ya trata como Free una prueba acabada, así que nada
 * depende de la hora a la que pase este cron.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrialExpiryService {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final PlanService planService;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    /** Cron diario a las 03:00. */
    @Scheduled(cron = "0 0 3 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void pasarAFreeLasPruebasAcabadas() {
        log.info("[CRON] Comprobando pruebas acabadas...");
        try {
            List<Map<String, Object>> users = supabase.selectFilter("users",
                    "select=id,email,trial_ends_at,stripe_subscription_id&plan_status=eq.trialing"
                    + "&stripe_subscription_id=is.null&organization_id=is.null&limit=1000");
            LocalDate hoy = LocalDate.now();
            int aFree = 0, avisados = 0;
            for (Map<String, Object> u : users) {
                LocalDate fin = parseDate(u.get("trial_ends_at"));
                if (fin == null) continue;
                String userId = String.valueOf(u.get("id"));
                String email = String.valueOf(u.get("email"));
                long diasRestantes = ChronoUnit.DAYS.between(hoy, fin);
                if (diasRestantes < 0) {
                    if (pasarAFree(userId)) {
                        aFree++;
                        enviar(email, "Tu prueba de InmobilIA Pro ha terminado: sigues en el plan Free",
                                "<h2>Hola</h2>"
                                + "<p>Tu prueba gratis del plan Business ha terminado. <strong>Tu cuenta sigue activa en el plan Free y conserva todos tus datos.</strong></p>"
                                + "<p>Free incluye 3 activos, 1 usuario y 20 créditos de IA al mes. Si gestionas más activos o trabajas en equipo, elige el plan que te encaje:</p>"
                                + boton("Ver planes", BASE_URL + "/upgrade")
                                + "<p>Pro desde US$39/mes y Business desde US$119/mes pagando el año (impuestos locales no incluidos). Sin permanencia.</p>"
                                + "<p>— Equipo InmobilIA Pro</p>");
                    }
                } else if (diasRestantes == 3 || diasRestantes == 1) {
                    avisados++;
                    enviar(email, String.format("Tu prueba acaba en %d %s", diasRestantes, diasRestantes == 1 ? "día" : "días"),
                            String.format("<h2>Hola</h2>"
                                + "<p>Tu prueba gratis del plan Business acaba en <strong>%d %s</strong>.</p>"
                                + "<p>Si no eliges un plan, tu cuenta pasará al plan Free (3 activos y 1 usuario) y conservarás tus datos. "
                                + "Para seguir con todo, elige tu plan antes:</p>%s<p>— Equipo InmobilIA Pro</p>",
                                diasRestantes, diasRestantes == 1 ? "día" : "días", boton("Elegir plan", BASE_URL + "/upgrade")));
                }
            }
            log.info("[CRON] Pruebas: {} pasan a Free, {} avisadas", aFree, avisados);
        } catch (Exception e) {
            log.error("[CRON] Error con las pruebas acabadas: {}", e.getMessage());
        }
    }

    /** Pasa la cuenta a Free (sin tocar sus datos). */
    boolean pasarAFree(String userId) {
        boolean ok = supabase.update("users", userId, Map.of(
                "plan", PlanService.FREE,
                "plan_status", "active"
        ), userId);
        planService.olvidarExceso(userId);
        return ok;
    }

    private void enviar(String email, String asunto, String html) {
        try { emailService.send(null, email, asunto, html); } catch (Exception ignored) {}
    }

    private static String boton(String texto, String url) {
        return "<p><a href='" + url + "' style='display:inline-block;padding:12px 24px;background:#0f172a;color:white;"
                + "text-decoration:none;border-radius:8px;font-weight:bold'>" + texto + " →</a></p>";
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
