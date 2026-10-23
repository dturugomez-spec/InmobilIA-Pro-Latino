package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Cobro por usuario: cuántas personas de la inmobiliaria se facturan y ajuste de la
 * suscripción de Stripe cuando cambian.
 *
 * Se cobra: el titular de la cuenta + cada empleado ACTIVO. No se cobran los inquilinos con
 * acceso al portal (rol tenant_portal) ni los empleados desactivados.
 *
 * Al invitar, desactivar o reactivar a alguien se ajusta al momento (en segundo plano), y cada
 * noche se repasan todas las suscripciones por si algún ajuste falló (Stripe caído, deploy...).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlazasService {

    private final SupabaseService supabase;
    private final StripeService stripe;
    private final AIService ai;

    /** Usuarios que se cobran en la inmobiliaria (mínimo 1: el titular). */
    public int contar(String orgId) {
        if (orgId == null || orgId.isBlank()) return 1;
        List<Map<String, Object>> miembros = supabase.selectFilter("users",
                "select=id,role,active&organization_id=eq." + orgId);
        return 1 + (int) miembros.stream().filter(m -> seCobra(m, orgId)).count();
    }

    static boolean seCobra(Map<String, Object> miembro, String orgId) {
        if (orgId.equals(String.valueOf(miembro.get("id")))) return false;           // el titular ya cuenta
        if ("tenant_portal".equals(String.valueOf(miembro.get("role")))) return false;  // inquilinos del portal
        Object activo = miembro.get("active");
        return activo == null || Boolean.TRUE.equals(activo) || "true".equalsIgnoreCase(String.valueOf(activo));
    }

    /**
     * Pone en Stripe tantos usuarios como tenga ahora la inmobiliaria. Si suben, suma ya los
     * créditos de IA del plan de los nuevos (los que bajan no se quitan: se ajusta el mes que viene).
     */
    public void sincronizar(String orgId) {
        Map<String, Object> titular = titular(orgId);
        if (titular == null || !PlanService.tieneSuscripcionStripe(titular)) return;
        String estado = String.valueOf(titular.get("plan_status")).toLowerCase();
        if (!estado.equals("active") && !estado.equals("trialing") && !estado.equals("past_due")) return;
        if (!stripe.isConfigured()) return;
        int usuarios = contar(orgId);
        try {
            long antes = stripe.actualizarUsuarios(String.valueOf(titular.get("stripe_subscription_id")), usuarios);
            if (antes > 0 && usuarios > antes) {
                ai.sumarCreditosDelPlan(orgId, PlanService.creditosMensuales(String.valueOf(titular.get("plan"))) * (int) (usuarios - antes));
            }
        } catch (Exception e) {
            log.warn("No se pudo ajustar a {} usuarios la suscripción de {}: {} (se reintenta esta noche)", usuarios, orgId, e.getMessage());
        }
    }

    @Async
    public void sincronizarEnSegundoPlano(String orgId) {
        sincronizar(orgId);
    }

    /** Repaso nocturno de todas las suscripciones. */
    @Scheduled(cron = "0 40 4 * * *", zone = "Europe/Madrid")
    public void conciliar() {
        List<Map<String, Object>> cuentas = supabase.selectFilter("users",
                "select=id&stripe_subscription_id=not.is.null&organization_id=is.null"
                + "&plan_status=in.(active,trialing,past_due)");
        for (Map<String, Object> c : cuentas) sincronizar(String.valueOf(c.get("id")));
    }

    private Map<String, Object> titular(String orgId) {
        List<Map<String, Object>> r = supabase.selectFilter("users", "id=eq." + orgId + "&limit=1");
        return r.isEmpty() ? null : r.get(0);
    }
}
