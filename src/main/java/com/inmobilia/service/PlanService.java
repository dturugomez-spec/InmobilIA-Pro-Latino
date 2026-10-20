package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Define los límites de cada plan y comprueba si el usuario puede usar una feature.
 * Tras comprar un plan superior, se actualiza users.plan vía webhook de Stripe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanService {

    public static final String FREE   = "free";
    public static final String PRO    = "pro";
    public static final String AGENCY = "agency";

    /** Límites por plan. Usar -1 para "ilimitado". */
    public record PlanLimits(
            String name,
            String label,
            int monthlyPriceEur,
            int maxProperties,
            int maxTeamMembers,
            int aiCreditsPerMonth,
            boolean kanban,
            boolean reports,
            boolean integrations,
            boolean invoicing,
            boolean ai,
            boolean prioritySupport
    ) {}

    public static final Map<String, PlanLimits> PLANS = new LinkedHashMap<>();
    static {
        PLANS.put(FREE,   new PlanLimits(FREE,   "Free",    0,  1,   1,    0,    false, false, false, false, false, false));
        PLANS.put(PRO,    new PlanLimits(PRO,    "Pro",    78, 15,   2,  200,    true,  true,  false, true,  true,  false));
        PLANS.put(AGENCY, new PlanLimits(AGENCY, "Agency", 198, -1,  -1, 1000,    true,  true,  true,  true,  true,  true));
    }

    private final SupabaseService supabase;

    @org.springframework.beans.factory.annotation.Value("${ADMIN_EMAILS:}")
    private String adminEmailsRaw;

    /**
     * ¿Tiene la cuenta una suscripción de pago activa (o en trial de Stripe con tarjeta)?
     * - plan_status "active" → sí (pagando).
     * - "trialing"/"past_due" CON stripe_subscription_id → sí (se suscribió, tarjeta en Stripe).
     * El trial de la aprobación (sin stripe_subscription_id) NO cuenta: debe meter tarjeta.
     */
    public boolean tieneSuscripcionActiva(Map<String, Object> user) {
        if (user == null) return false;
        String st = String.valueOf(user.getOrDefault("plan_status", "")).toLowerCase();
        if ("active".equals(st)) return true;
        return tieneSuscripcionStripe(user) && ("trialing".equals(st) || "past_due".equals(st));
    }

    private static boolean presente(Object o) {
        return o != null && !String.valueOf(o).isBlank() && !"null".equals(String.valueOf(o));
    }

    /** Tiene (o tuvo) una suscripción creada en Stripe. */
    public static boolean tieneSuscripcionStripe(Map<String, Object> user) {
        return user != null && presente(user.get("stripe_subscription_id"));
    }

    /** ¿Se ha suscrito alguna vez? Si sí, ya no le corresponde otra prueba gratis. */
    public static boolean haSuscritoAlgunaVez(Map<String, Object> user) {
        return user != null && (presente(user.get("stripe_subscription_id")) || presente(user.get("plan_canceled_at")));
    }

    /** Cuenta del modo demo público (/demo): email demo_xxxx (antiguas) o demo_xxxx@demo.inmobiliapro.es. */
    public static boolean esCuentaDemo(String email) {
        if (email == null) return false;
        String e = email.toLowerCase().trim();
        return e.startsWith("demo_") && (!e.contains("@") || e.endsWith("@demo.inmobiliapro.es"));
    }

    /**
     * ¿La cuenta (org) debe suscribirse antes de poder usar la app?
     * Exenta: cuentas demo (email demo_*) y propietarios de la plataforma (ADMIN_EMAILS).
     * Fail-open: si no se puede leer la cuenta, NO bloquea.
     */
    public boolean requierePago(String orgId) {
        if (orgId == null || orgId.isBlank()) return false;
        Map<String, Object> u = getUser(orgId);
        if (u == null) return false; // fail-open
        String email = String.valueOf(u.getOrDefault("email", "")).toLowerCase().trim();
        if (esCuentaDemo(email)) return false;                        // cuentas demo
        if (esAdminPlataforma(email)) return false;                   // propietario
        return !tieneSuscripcionActiva(u);
    }

    /** Créditos de IA que incluye cada mes el plan (pro 200, agency 1.000; 0 si no es de pago). */
    public static int creditosMensuales(String plan) {
        if (plan == null) return 0;
        PlanLimits l = PLANS.get(plan.trim().toLowerCase());
        return l == null ? 0 : Math.max(0, l.aiCreditsPerMonth());
    }

    /** ¿Es uno de los propietarios de la plataforma (ADMIN_EMAILS)? Ojo: NO es el "admin" de una inmobiliaria. */
    public boolean esAdminPlataforma(String email) {
        if (email == null || email.isBlank() || adminEmailsRaw == null) return false;
        for (String a : adminEmailsRaw.split(",")) {
            if (!a.isBlank() && a.trim().equalsIgnoreCase(email.trim())) return true;
        }
        return false;
    }

    /** Devuelve los límites del plan del usuario (o trial). */
    public PlanLimits limitsFor(Map<String, Object> user) {
        if (user == null) return PLANS.get(FREE);

        String plan = String.valueOf(user.getOrDefault("plan", FREE)).toLowerCase();
        String status = String.valueOf(user.getOrDefault("plan_status", "active"));

        // Trial sin tarjeta (demo) → Agency (acceso total). Si ya se suscribió en Stripe,
        // durante su prueba tiene exactamente el plan que eligió.
        if ("trialing".equals(status) && !isTrialExpired(user) && !tieneSuscripcionStripe(user)) {
            return PLANS.get(AGENCY);
        }

        return PLANS.getOrDefault(plan, PLANS.get(FREE));
    }

    public boolean isTrialing(Map<String, Object> user) {
        if (user == null) return false;
        return "trialing".equals(String.valueOf(user.getOrDefault("plan_status", ""))) && !isTrialExpired(user);
    }

    public long trialDaysLeft(Map<String, Object> user) {
        return user == null ? 0 : diasHasta(user.get("trial_ends_at"));
    }

    private static long diasHasta(Object fecha) {
        try {
            String s = String.valueOf(fecha);
            if (s == null || s.isBlank() || "null".equals(s)) return 0;
            OffsetDateTime end = OffsetDateTime.parse(s.length() > 25 ? s.substring(0, 25) : s);
            long days = java.time.Duration.between(OffsetDateTime.now(), end).toDays();
            return Math.max(0, days);
        } catch (Exception e) { return 0; }
    }

    public boolean isTrialExpired(Map<String, Object> user) {
        try {
            String s = String.valueOf(user.get("trial_ends_at"));
            if (s == null || s.isBlank() || "null".equals(s)) return false;
            OffsetDateTime end = OffsetDateTime.parse(s.length() > 25 ? s.substring(0, 25) : s);
            return end.isBefore(OffsetDateTime.now());
        } catch (Exception e) { return false; }
    }

    /** Comprueba si el usuario puede crear más propiedades según su plan. */
    public boolean canAddProperty(String orgId) {
        Map<String, Object> user = getUser(orgId);
        PlanLimits l = limitsFor(user);
        if (l.maxProperties() < 0) return true;  // ilimitado
        try {
            int current = supabase.query("propiedades", orgId).size();
            return current < l.maxProperties();
        } catch (Exception e) { return true; }
    }

    /** Comprueba si el usuario puede invitar más miembros según su plan. */
    public boolean canAddTeamMember(String orgId) {
        Map<String, Object> user = getUser(orgId);
        PlanLimits l = limitsFor(user);
        if (l.maxTeamMembers() < 0) return true;
        try {
            int current = supabase.findBy("users", Map.of("organization_id", orgId)).size();
            return current < l.maxTeamMembers();
        } catch (Exception e) { return true; }
    }

    /**
     * Acceso a feature por uid (para el paywall/interceptor).
     * Fail-open: si no se puede leer el plan del usuario, NO bloquea (mejor dejar
     * pasar puntualmente que bloquear a un cliente de pago por un fallo transitorio).
     */
    public boolean canUseByUid(String uid, String feature) {
        if (uid == null || uid.isBlank()) return true;
        Map<String, Object> user = getUser(uid);
        if (user == null) return true;   // fail-open
        return canUse(user, feature);
    }

    /** Acceso a feature concreta. */
    public boolean canUse(Map<String, Object> user, String feature) {
        PlanLimits l = limitsFor(user);
        return switch (feature) {
            case "kanban" -> l.kanban();
            case "reports" -> l.reports();
            case "integrations" -> l.integrations();
            case "invoicing" -> l.invoicing();
            case "ai" -> l.ai();
            default -> true;
        };
    }

    /** Estado para mostrar en UI (banner/upgrade prompts). */
    public Map<String, Object> statusFor(Map<String, Object> user) {
        Map<String, Object> out = new LinkedHashMap<>();
        PlanLimits l = limitsFor(user);
        out.put("plan", user != null ? user.get("plan") : FREE);
        out.put("planLabel", l.label());
        out.put("status", user != null ? user.get("plan_status") : "active");
        boolean suscrito = tieneSuscripcionActiva(user);
        // "isTrialing" = prueba SIN tarjeta (demo/aprobación). Quien ya se suscribió no la ve.
        out.put("isTrialing", isTrialing(user) && !suscrito);
        out.put("trialDaysLeft", trialDaysLeft(user));
        out.put("subscribed", suscrito);
        // Prueba de Stripe (con tarjeta): días hasta el primer cobro automático.
        boolean stripeTrial = suscrito && user != null
                && "trialing".equalsIgnoreCase(String.valueOf(user.get("plan_status")));
        out.put("stripeTrial", stripeTrial);
        out.put("stripeTrialDaysLeft", stripeTrial ? diasHasta(user.get("subscription_ends_at")) : 0L);
        out.put("limits", l);
        return out;
    }

    private Map<String, Object> getUser(String userId) {
        try {
            List<Map<String, Object>> r = supabase.findBy("users", Map.of("id", userId));
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) { return null; }
    }
}
