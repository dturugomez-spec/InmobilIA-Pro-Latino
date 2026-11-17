package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Planes y límites. El precio depende del número de ACTIVOS que gestiona la cuenta, no de los
 * usuarios (hoja "Planes y precios" de oct-2026):
 *
 *   Free 0 USD (3 activos, 1 usuario, 20 créditos) · Pro 49 USD (15, 2, 200) · Business 149 USD (50, 5, 1.000)
 *   · Enterprise desde 399 USD (ilimitado, a medida). Pagando el año, un 20 % menos. Impuestos locales no incluidos.
 *   Los mismos números que la versión española, en dólares.
 *
 * Extras sobre un plan de pago: activos adicionales (4 USD/mes cada uno) y, en Pro, el módulo
 * Future-Proof Score (29 USD/mes), incluido en Business. (El optimizador fiscal de holding es solo
 * de la versión española: calcula con impuestos de España.)
 *
 * Prueba: un mes (30 días) de Business sin tarjeta (al aprobar la solicitud). Al acabar, si no ha elegido
 * plan, la cuenta pasa a Free y conserva sus datos (TrialExpiryService).
 *
 * Cobro: mientras haya pocos clientes, por transferencia o en efectivo con factura (V94). El
 * propietario de la plataforma activa el plan en /admin/clientes y apunta hasta qué día está pagado
 * (users.pagado_hasta). Pasada esa fecha hay DIAS_GRACIA días de margen; después la cuenta funciona
 * como Free, sin perder datos, hasta el siguiente cobro. Con COBRO_ONLINE=true vuelve el pago con
 * Stripe y entonces el plan, el periodo y los extras se actualizan vía su webhook.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanService {

    public static final String FREE       = "free";
    public static final String PRO        = "pro";
    public static final String BUSINESS   = "business";
    public static final String ENTERPRISE = "enterprise";
    /** Plan antiguo (cobro por usuario). Las cuentas que aún lo tengan tienen Business. */
    public static final String AGENCY     = "agency";

    /**
     * Límites por plan. -1 = ilimitado. Precios en USD sin impuestos: el mensual y, pagando el año,
     * lo que sale al mes (0 = a medida). Los créditos de IA son de la cuenta entera, cada mes.
     */
    public record PlanLimits(
            String name,
            String label,
            int monthlyPriceEur,
            int annualMonthlyPriceEur,
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
        PLANS.put(FREE,       new PlanLimits(FREE,       "Free",         0,   0,  3,  1,   20, false, false, false, false, true, false));
        PLANS.put(PRO,        new PlanLimits(PRO,        "Pro",         49,  39, 15,  2,  200, true,  true,  false, true,  true, false));
        PLANS.put(BUSINESS,   new PlanLimits(BUSINESS,   "Business",   149, 119, 50,  5, 1000, true,  true,  true,  true,  true, false));
        // A medida: los créditos de Enterprise se pactan con cada cliente; esta es la base
        PLANS.put(ENTERPRISE, new PlanLimits(ENTERPRISE, "Enterprise", 399,   0, -1, -1, 3000, true,  true,  true,  true,  true, true));
    }

    /** Planes que se contratan desde la app (Enterprise se pacta por correo). */
    public static final List<String> PLANES_DE_PAGO = List.of(PRO, BUSINESS);

    /** Cada activo por encima de los del plan, al mes (todos los planes de pago). */
    public static final int PRECIO_ACTIVO_EXTRA = 4;
    /** Sociedades de la gestión multi-empresa que incluye Business (Enterprise: ilimitadas). */
    public static final int SOCIEDADES_BUSINESS = 3;
    /** Cada sociedad por encima de las de Business, al mes. */
    public static final int PRECIO_SOCIEDAD_EXTRA = 15;
    /** Pack de créditos de IA de pago único (todos los planes; no caducan). */
    public static final int PACK_CREDITOS = 100;
    public static final int PRECIO_PACK_CREDITOS = 10;
    /** Días de prueba con Business al aprobar una solicitud. */
    public static final int DIAS_PRUEBA = 30;
    /** Días de margen tras users.pagado_hasta antes de pasar a Free (cobro manual). */
    public static final int DIAS_GRACIA = 10;
    /** Precio de un mes en un pago anual: 12 meses al precio anual. */
    public static final int MESES_ANUAL = 12;

    /**
     * Base sin impuestos (USD) de un periodo (un mes o un año) de un plan con sus extras: lo que hay que
     * cobrar a mano. Enterprise es a medida: 0 (lo pone quien cobra).
     */
    public static double importeBase(String plan, boolean anual, int activosExtra, int sociedadesExtra, Collection<String> modulos) {
        String p = normalizar(plan);
        if (FREE.equals(p) || ENTERPRISE.equals(p)) return 0;
        int meses = anual ? MESES_ANUAL : 1;
        double mes = precioMensual(p, anual) + Math.max(0, activosExtra) * PRECIO_ACTIVO_EXTRA;
        if (BUSINESS.equals(p)) mes += Math.max(0, sociedadesExtra) * PRECIO_SOCIEDAD_EXTRA;
        if (PRO.equals(p) && modulos != null)
            for (String m : new LinkedHashSet<>(modulos)) { Modulo mod = modulo(m); if (mod != null) mes += mod.precioMensual(); }
        return mes * meses;
    }

    /** Fecha hasta la que está pagado el plan (cobro manual), o null si no tiene. */
    public static java.time.LocalDate pagadoHasta(Map<String, Object> user) {
        if (user == null || !presente(user.get("pagado_hasta"))) return null;
        try { return java.time.LocalDate.parse(String.valueOf(user.get("pagado_hasta")).substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    /**
     * Plan de pago cobrado a mano cuyo pago venció hace más de DIAS_GRACIA días: funciona como
     * Free hasta que se apunte el siguiente cobro. Las suscripciones de Stripe van por su webhook.
     */
    public static boolean pagoCaducado(Map<String, Object> user, java.time.LocalDate hoy) {
        java.time.LocalDate hasta = pagadoHasta(user);
        return hasta != null && !tieneSuscripcionStripe(user) && hoy.isAfter(hasta.plusDays(DIAS_GRACIA));
    }

    /** Herramienta avanzada que en Pro se puede añadir suelta (en Business viene incluida). */
    public record Modulo(String clave, String nombre, int precioMensual) {}

    public static final Modulo FUTUREPROOF = new Modulo("futureproof", "Future-Proof Score", 29);
    public static final List<Modulo> MODULOS = List.of(FUTUREPROOF);

    public static Modulo modulo(String clave) {
        for (Modulo m : MODULOS) if (m.clave().equals(clave)) return m;
        return null;
    }

    /**
     * Herramienta que no está en todos los planes: plan mínimo y, si lo hay, el módulo que la
     * añade a Pro. En la ruta, "*" es un tramo cualquiera (un id). Lo que no está aquí lo tienen
     * todos los planes (los límites de activos y usuarios siguen valiendo).
     */
    public record Herramienta(String ruta, String nombre, String planMinimo, String modulo) {
        private static final Map<String, Pattern> PATRONES = new java.util.concurrent.ConcurrentHashMap<>();
        private Pattern patron() {
            return PATRONES.computeIfAbsent(ruta, r -> Pattern.compile(Pattern.quote(r).replace("*", "\\E[^/]+\\Q") + "(/.*)?"));
        }
        public boolean cubre(String uri) { return uri != null && patron().matcher(uri).matches(); }
    }

    public static final List<Herramienta> HERRAMIENTAS = List.of(
            // Pro
            new Herramienta("/herramientas/ocr", "Escáner de documentos (OCR)", PRO, null),
            new Herramienta("/ia/contratos", "Escáner de documentos (OCR)", PRO, null),
            new Herramienta("/inquilinos/*/invite-portal", "Portal del inquilino", PRO, null),
            new Herramienta("/herramientas/health-score", "Portfolio Health Score", PRO, null),
            new Herramienta("/herramientas/autopilot-comunicacion", "Communication AutoPilot", PRO, null),
            new Herramienta("/herramientas/comandos-voz", "Comandos de voz", PRO, null),
            new Herramienta("/reports", "Informes en PDF", PRO, null),
            new Herramienta("/informes", "Informes en PDF", PRO, null),
            new Herramienta("/ia/memo", "Informes en PDF", PRO, null),
            new Herramienta("/invoices", "Facturas y recibos", PRO, null),
            new Herramienta("/receipts", "Facturas y recibos", PRO, null),
            new Herramienta("/kanban", "Pipeline kanban", PRO, null),
            new Herramienta("/pipelines", "Pipeline kanban", PRO, null),
            // Business (o módulo en Pro)
            new Herramienta("/herramientas/future-proof", FUTUREPROOF.nombre(), BUSINESS, FUTUREPROOF.clave()),
            new Herramienta("/herramientas/esg", "ESG Score", BUSINESS, null),
            new Herramienta("/herramientas/radar", "Live Market Radar", BUSINESS, null),
            new Herramienta("/ia/dealflow", "Opportunity Hunter", BUSINESS, null),
            new Herramienta("/herramientas/mantenimiento-predictivo", "Mantenimiento predictivo", BUSINESS, null),
            new Herramienta("/integrations", "Integraciones", BUSINESS, null),
            new Herramienta("/herramientas/multi-empresa", "Gestión multi-empresa", BUSINESS, null),
            new Herramienta("/herramientas/negociacion", "Negotiation Co-Pilot", BUSINESS, null),
            // Enterprise
            new Herramienta("/api-keys", "Acceso por API", ENTERPRISE, null),
            new Herramienta("/api-key", "Acceso por API", ENTERPRISE, null)
    );

    /** Herramienta limitada que cubre esta ruta, o null si la tienen todos los planes. */
    public static Herramienta herramientaDe(String uri) {
        for (Herramienta h : HERRAMIENTAS) if (h.cubre(uri)) return h;
        return null;
    }

    private static final List<String> ORDEN = List.of(FREE, PRO, BUSINESS, ENTERPRISE);

    /** ¿El plan a llega al nivel del plan b? */
    public static boolean alcanza(String a, String b) {
        return ORDEN.indexOf(normalizar(a)) >= ORDEN.indexOf(normalizar(b));
    }

    /** Nombre de plan válido: los antiguos (agency, starter, trial) pasan a su equivalente. */
    public static String normalizar(String plan) {
        String p = plan == null ? "" : plan.trim().toLowerCase();
        if (AGENCY.equals(p)) return BUSINESS;
        return PLANS.containsKey(p) ? p : FREE;
    }

    /** Precio al mes (USD, sin impuestos) del plan; pagando el año, lo que sale al mes. */
    public static int precioMensual(String plan, boolean anual) {
        PlanLimits l = PLANS.get(normalizar(plan));
        return anual && l.annualMonthlyPriceEur() > 0 ? l.annualMonthlyPriceEur() : l.monthlyPriceEur();
    }

    /** Créditos de IA que trae el plan cada mes (para toda la cuenta). */
    public static int creditosMensuales(String plan) {
        return plan == null ? 0 : PLANS.get(normalizar(plan)).aiCreditsPerMonth();
    }

    private final SupabaseService supabase;

    @org.springframework.beans.factory.annotation.Value("${ADMIN_EMAILS:}")
    private String adminEmailsRaw;

    /**
     * ¿Paga una suscripción (o está en su prueba de Stripe con tarjeta)? Free nunca.
     * - plan_status "active" en un plan de pago → sí (pagando, o Enterprise pactado).
     * - "trialing"/"past_due" CON stripe_subscription_id → sí (se suscribió, tarjeta en Stripe).
     * La prueba de la aprobación (sin stripe_subscription_id) NO cuenta.
     */
    public boolean tieneSuscripcionActiva(Map<String, Object> user) {
        if (user == null || FREE.equals(normalizar(str(user.get("plan"))))) return false;
        String st = str(user.get("plan_status")).toLowerCase();
        if ("active".equals(st)) return !pagoCaducado(user, java.time.LocalDate.now());
        return tieneSuscripcionStripe(user) && ("trialing".equals(st) || "past_due".equals(st));
    }

    private static boolean presente(Object o) {
        return o != null && !String.valueOf(o).isBlank() && !"null".equals(String.valueOf(o));
    }

    private static String str(Object o) { return presente(o) ? String.valueOf(o) : ""; }

    /** Tiene (o tuvo) una suscripción creada en Stripe. */
    public static boolean tieneSuscripcionStripe(Map<String, Object> user) {
        return user != null && presente(user.get("stripe_subscription_id"));
    }

    /** ¿Se ha suscrito alguna vez? */
    public static boolean haSuscritoAlgunaVez(Map<String, Object> user) {
        return user != null && (presente(user.get("stripe_subscription_id")) || presente(user.get("plan_canceled_at")));
    }

    /** Cuenta del modo demo (/demo): email demo_xxxx (antiguas) o demo_xxxx@demo.inmobiliapro.es. */
    public static boolean esCuentaDemo(String email) {
        if (email == null) return false;
        String e = email.toLowerCase().trim();
        return e.startsWith("demo_") && (!e.contains("@") || e.endsWith("@demo.inmobiliapro.es"));
    }

    /** ¿Es uno de los propietarios de la plataforma (ADMIN_EMAILS)? Ojo: NO es el "admin" de una inmobiliaria. */
    public boolean esAdminPlataforma(String email) {
        if (email == null || email.isBlank() || adminEmailsRaw == null) return false;
        for (String a : adminEmailsRaw.split(",")) {
            if (!a.isBlank() && a.trim().equalsIgnoreCase(email.trim())) return true;
        }
        return false;
    }

    /** Cuentas sin límites: las demo y las de los propietarios de la plataforma. */
    private boolean exenta(Map<String, Object> user) {
        String email = str(user.get("email"));
        return esCuentaDemo(email) || esAdminPlataforma(email);
    }

    /**
     * Plan que vale ahora: durante la prueba sin tarjeta, Business; acabada la prueba sin haber
     * elegido plan, Free (aunque el aviso nocturno aún no haya cambiado users.plan).
     */
    public String planEfectivo(Map<String, Object> user) {
        if (user == null) return FREE;
        if (exenta(user)) return ENTERPRISE;
        String status = str(user.get("plan_status")).toLowerCase();
        if ("trialing".equals(status) && !tieneSuscripcionStripe(user)) {
            return isTrialExpired(user) ? FREE : BUSINESS;
        }
        if ("canceled".equals(status) || "expired".equals(status)) return FREE;
        if (pagoCaducado(user, java.time.LocalDate.now())) return FREE;
        return normalizar(str(user.get("plan")));
    }

    /** Límites del plan que vale ahora. */
    public PlanLimits limitsFor(Map<String, Object> user) {
        return PLANS.get(planEfectivo(user));
    }

    /** Activos adicionales comprados (solo cuentan en un plan de pago). */
    public static int activosExtra(Map<String, Object> user) {
        try { return Math.max(0, Integer.parseInt(str(user.get("activos_extra")))); } catch (Exception e) { return 0; }
    }

    /** Activos que puede tener: los del plan + los adicionales. -1 = ilimitados. */
    public int maxActivos(Map<String, Object> user) {
        PlanLimits l = limitsFor(user);
        if (l.maxProperties() < 0) return -1;
        return l.maxProperties() + (FREE.equals(l.name()) ? 0 : activosExtra(user));
    }

    /** Sociedades adicionales compradas (solo cuentan en Business). */
    public static int sociedadesExtra(Map<String, Object> user) {
        try { return Math.max(0, Integer.parseInt(str(user.get("sociedades_extra")))); } catch (Exception e) { return 0; }
    }

    /** Sociedades que puede gestionar: Business 3 + adicionales, Enterprise ilimitadas (-1), el resto 0. */
    public int maxSociedades(Map<String, Object> user) {
        String plan = planEfectivo(user);
        if (ENTERPRISE.equals(plan)) return -1;
        return BUSINESS.equals(plan) ? SOCIEDADES_BUSINESS + sociedadesExtra(user) : 0;
    }

    /** Módulos comprados (columna users.modulos). */
    public static Set<String> modulosComprados(Map<String, Object> user) {
        Set<String> out = new LinkedHashSet<>();
        Object m = user == null ? null : user.get("modulos");
        if (m instanceof Collection<?> c) c.forEach(x -> out.add(String.valueOf(x)));
        else if (presente(m)) for (String x : String.valueOf(m).replaceAll("[{}\\[\\]\"]", "").split(",")) if (!x.isBlank()) out.add(x.trim());
        out.removeIf(x -> modulo(x) == null);
        return out;
    }

    /** ¿Tiene esta herramienta (por su plan o por un módulo comprado en Pro)? */
    public boolean puedeUsar(Map<String, Object> user, Herramienta h) {
        if (h == null) return true;
        String plan = planEfectivo(user);
        if (alcanza(plan, h.planMinimo())) return true;
        return h.modulo() != null && PRO.equals(plan) && modulosComprados(user).contains(h.modulo());
    }

    public boolean isTrialing(Map<String, Object> user) {
        if (user == null) return false;
        return "trialing".equals(str(user.get("plan_status"))) && !isTrialExpired(user);
    }

    public long trialDaysLeft(Map<String, Object> user) {
        return user == null ? 0 : diasHasta(user.get("trial_ends_at"));
    }

    // Fechas.leer: antes se cortaba la fecha a 25 caracteres y, con fracciones de segundo
    // ("…T10:00:00.123456+00:00"), no se podía leer: la prueba nunca acababa y quedaban 0 días.
    private static long diasHasta(Object fecha) {
        OffsetDateTime end = com.inmobilia.util.Fechas.leer(fecha);
        if (end == null) return 0;
        return Math.max(0, java.time.Duration.between(OffsetDateTime.now(), end).toDays());
    }

    public boolean isTrialExpired(Map<String, Object> user) {
        OffsetDateTime end = user == null ? null : com.inmobilia.util.Fechas.leer(user.get("trial_ends_at"));
        return end != null && end.isBefore(OffsetDateTime.now());
    }

    /** Activos (inmuebles con ficha) que tiene ahora la cuenta. */
    public int activosUsados(String orgId) {
        try {
            return supabase.selectFilter("propiedades", "select=id&user_id=eq." + orgId + "&limit=100000").size();
        } catch (Exception e) { return 0; }
    }

    /** ¿Puede dar de alta otro activo? */
    public boolean canAddProperty(String orgId) {
        Map<String, Object> user = getUser(orgId);
        if (user == null) return true;   // fail-open
        int max = maxActivos(user);
        return max < 0 || activosUsados(orgId) < max;
    }

    /** Huecos libres para activos nuevos (Integer.MAX_VALUE si son ilimitados). */
    public int activosLibres(String orgId) {
        Map<String, Object> user = getUser(orgId);
        int max = user == null ? -1 : maxActivos(user);
        return max < 0 ? Integer.MAX_VALUE : Math.max(0, max - activosUsados(orgId));
    }

    /** Mensaje cuando el plan ya no admite más activos. */
    public String mensajeLimiteActivos(String orgId) {
        Map<String, Object> user = getUser(orgId);
        PlanLimits l = limitsFor(user);
        String extra = FREE.equals(l.name()) ? "Mejora a Pro (hasta 15 activos)."
                : "Mejora el plan o pide activos adicionales (" + com.inmobilia.util.PreciosPlataforma.texto(com.inmobilia.util.PreciosPlataforma.ACTIVO_EXTRA) + "/mes cada uno) en Plan y facturación.";
        return "Tu plan " + l.label() + " incluye " + maxActivos(user) + " activos y ya los tienes todos. " + extra;
    }

    /**
     * Personas con acceso: el titular + los empleados activos. No cuentan los inquilinos del
     * portal, los empleados desactivados ni los empleados de los ejemplos para demos.
     */
    public int usuariosActivos(String orgId) {
        if (orgId == null || orgId.isBlank()) return 1;
        try {
            List<Map<String, Object>> miembros = supabase.selectFilter("users",
                    "select=id,role,active&organization_id=eq." + orgId);
            Set<String> deEjemplo = new HashSet<>();
            for (Map<String, Object> r : supabase.selectFilter("ejemplos_demo",
                    "select=registro_id&user_id=eq." + orgId + "&tabla=eq.users&limit=100"))
                deEjemplo.add(String.valueOf(r.get("registro_id")));
            return 1 + (int) miembros.stream()
                    .filter(m -> cuenta(m, orgId) && !deEjemplo.contains(String.valueOf(m.get("id")))).count();
        } catch (Exception e) { return 1; }
    }

    static boolean cuenta(Map<String, Object> miembro, String orgId) {
        if (orgId.equals(String.valueOf(miembro.get("id")))) return false;           // el titular ya cuenta
        if ("tenant_portal".equals(String.valueOf(miembro.get("role")))) return false;  // inquilinos del portal
        Object activo = miembro.get("active");
        return activo == null || Boolean.TRUE.equals(activo) || "true".equalsIgnoreCase(String.valueOf(activo));
    }

    /** ¿Puede invitar (o reactivar) a otra persona? */
    public boolean canAddTeamMember(String orgId) {
        Map<String, Object> user = getUser(orgId);
        if (user == null) return true;   // fail-open
        int max = limitsFor(user).maxTeamMembers();
        return max < 0 || usuariosActivos(orgId) < max;
    }

    private record Exceso(boolean excede, long en) {}
    private final Map<String, Exceso> excesos = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ¿Tiene la cuenta más personas activas de las que incluye su plan (p. ej. al pasar a Free
     * con empleados)? Entonces solo entra el titular hasta que mejore el plan o desactive a
     * alguien. Se recuerda un minuto: se mira en cada petición.
     */
    public boolean excedeUsuarios(String orgId) {
        if (orgId == null || orgId.isBlank()) return false;
        Exceso e = excesos.get(orgId);
        if (e != null && System.currentTimeMillis() - e.en() < 60_000) return e.excede();
        Map<String, Object> user = getUser(orgId);
        int max = user == null ? -1 : limitsFor(user).maxTeamMembers();
        boolean excede = max >= 0 && usuariosActivos(orgId) > max;
        excesos.put(orgId, new Exceso(excede, System.currentTimeMillis()));
        return excede;
    }

    /** Tras cambiar el plan o el equipo: que se vuelva a mirar. */
    public void olvidarExceso(String orgId) {
        if (orgId != null) excesos.remove(orgId);
    }

    /**
     * Acceso a una herramienta por uid (paywall). Fail-open: si no se puede leer la cuenta, NO
     * bloquea (mejor dejar pasar puntualmente que bloquear a un cliente por un fallo de BD).
     */
    public boolean puedeUsarRuta(String uid, String uri) {
        Herramienta h = herramientaDe(uri);
        if (h == null || uid == null || uid.isBlank()) return true;
        Map<String, Object> user = getUser(uid);
        return user == null || puedeUsar(user, h);
    }

    /** Estado para mostrar en UI (banner/upgrade prompts). */
    public Map<String, Object> statusFor(Map<String, Object> user) {
        Map<String, Object> out = new LinkedHashMap<>();
        PlanLimits l = limitsFor(user);
        out.put("plan", l.name());
        out.put("planLabel", l.label());
        out.put("status", user != null ? user.get("plan_status") : "active");
        boolean suscrito = tieneSuscripcionActiva(user);
        // "isTrialing" = prueba SIN tarjeta (aprobación). Quien ya se suscribió no la ve.
        out.put("isTrialing", isTrialing(user) && !suscrito);
        out.put("trialDaysLeft", trialDaysLeft(user));
        out.put("subscribed", suscrito);
        // Prueba de Stripe (con tarjeta): días hasta el primer cobro automático.
        boolean stripeTrial = suscrito && user != null
                && "trialing".equalsIgnoreCase(str(user.get("plan_status")));
        out.put("stripeTrial", stripeTrial);
        out.put("stripeTrialDaysLeft", stripeTrial ? diasHasta(user.get("subscription_ends_at")) : 0L);
        out.put("anual", user != null && "anual".equals(str(user.get("periodo_facturacion"))));
        out.put("maxActivos", maxActivos(user));
        out.put("activosExtra", user == null ? 0 : activosExtra(user));
        out.put("modulos", modulosComprados(user));
        out.put("maxSociedades", maxSociedades(user));
        out.put("sociedadesExtra", user == null ? 0 : sociedadesExtra(user));
        out.put("limits", l);
        // Cobro manual: hasta cuándo está pagado y si toca renovar
        java.time.LocalDate hoy = java.time.LocalDate.now();
        java.time.LocalDate hasta = tieneSuscripcionStripe(user) ? null : pagadoHasta(user);
        boolean dePago = hasta != null && !FREE.equals(normalizar(str(user.get("plan"))));
        long dias = hasta == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(hoy, hasta);
        out.put("pagadoHasta", dePago ? hasta : null);
        out.put("diasPagados", dias);
        // Vencido pero aún dentro del margen: sigue con su plan y se le avisa
        out.put("pagoPendiente", dePago && hoy.isAfter(hasta) && !pagoCaducado(user, hoy));
        out.put("pagoCaducado", dePago && pagoCaducado(user, hoy));
        out.put("finMargen", dePago ? hasta.plusDays(DIAS_GRACIA) : null);
        out.put("renovarPronto", dePago && dias >= 0 && dias <= 7);
        return out;
    }

    private Map<String, Object> getUser(String userId) {
        try {
            List<Map<String, Object>> r = supabase.findBy("users", Map.of("id", userId));
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) { return null; }
    }
}
