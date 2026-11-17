package com.inmobilia.service;

import com.inmobilia.util.Fechas;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * ⚡ Reposición mensual de los créditos de IA del plan, para toda la cuenta
 * (Free 20, Pro 200, Business 1.000, Enterprise 3.000 de base).
 *
 * Cada día repasa las cuentas con suscripción de Stripe activa o en prueba, las de Free y las de
 * planes cobrados a mano (Pro, Business, Enterprise sin Stripe) y, si hace un mes (o más) de la
 * última reposición, deja el saldo en la asignación del plan que vale ahora más lo que quede de
 * packs comprados (ver AIService.saldoTrasReponer). La primera reposición de un plan de pago se
 * hace al activarlo (StripeWebhookController o CobroManualService.activarPlan).
 *
 * Necesita las columnas users.ai_credits_renewed_at y ai_credits_comprados (migración V83).
 * Sin ellas, la consulta falla sin romper nada y simplemente no repone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditosMensualesService {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final PlanService planService;

    @Scheduled(cron = "0 15 4 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void reponerVencidos() {
        String haceUnMes = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(1).toString();
        String vencidos = "&organization_id=is.null&or=(ai_credits_renewed_at.is.null,ai_credits_renewed_at.lt." + haceUnMes + ")&limit=1000";
        // Todas las columnas: si aún falta alguna migración (p. ej. pagado_hasta, V94), la consulta no falla
        String campos = "select=*";
        List<Map<String, Object>> cuentas = new ArrayList<>();
        cuentas.addAll(supabase.selectFilter("users", campos
                + "&plan_status=in.(active,trialing)&stripe_subscription_id=not.is.null" + vencidos));
        cuentas.addAll(supabase.selectFilter("users", campos + "&plan=eq.free&active=eq.true" + vencidos));
        // Planes cobrados a mano (transferencia o efectivo) y Enterprise pactado
        cuentas.addAll(supabase.selectFilter("users", campos
                + "&plan=in.(pro,business,enterprise,agency)&plan_status=eq.active&stripe_subscription_id=is.null" + vencidos));
        int repuestas = 0;
        Set<String> vistas = new HashSet<>();
        for (Map<String, Object> u : cuentas) {
            if (!vistas.add(String.valueOf(u.get("id")))) continue;
            if (!tocaReponer(u.get("ai_credits_renewed_at"), OffsetDateTime.now(ZoneOffset.UTC))) continue;
            // El plan que vale ahora: un plan sin pagar pasado el margen repone como Free
            int asignacion = PlanService.creditosMensuales(planService.planEfectivo(u));
            if (asignacion <= 0) continue;
            if (aiService.reponerCreditos(String.valueOf(u.get("id")), asignacion, true) >= 0) repuestas++;
        }
        if (repuestas > 0) log.info("Créditos mensuales repuestos en {} cuentas", repuestas);
    }

    /** ¿Ha pasado al menos un mes desde la última reposición (o no ha habido ninguna)? */
    static boolean tocaReponer(Object ultimaRenovacion, OffsetDateTime ahora) {
        OffsetDateTime ultima = Fechas.leer(ultimaRenovacion);
        return ultima == null || !ultima.plusMonths(1).isAfter(ahora);
    }
}
