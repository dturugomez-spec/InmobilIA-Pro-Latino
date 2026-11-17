package com.inmobilia.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Coupon;
import com.stripe.model.Customer;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.param.CouponCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionItemCreateParams;
import com.stripe.param.SubscriptionItemDeleteParams;
import com.stripe.param.SubscriptionItemUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.inmobilia.util.PreciosPlataforma;

/**
 * Wrapper sobre Stripe Java SDK: checkout de planes y del pack de créditos, extras de la
 * suscripción (activos adicionales y módulos) y portal de cliente.
 *
 * Los precios NO se configuran a mano: cada uno tiene una lookup_key ("inmobilia_plan_pro_mensual"…)
 * y la app lo busca en Stripe; si no existe, o el importe de PlanService ha cambiado, lo crea (y
 * le pasa la lookup_key). Los importes salen de PlanService, en un único sitio.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StripeService {

    @Value("${stripe.secret-key:}")
    private String secretKey;

    // Precios antiguos por usuario: solo para reconocer suscripciones que ya existían
    @Value("${stripe.price.pro:}")
    private String precioAntiguoPro;
    @Value("${stripe.price.agency:}")
    private String precioAntiguoAgency;

    @Value("${app.base-url:https://inmobiliapro.es}")
    private String baseUrl;

    private final SupabaseService supabase;
    private final Promociones promociones;

    @PostConstruct
    public void init() {
        if (secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe configurado (key: {}***)", secretKey.substring(0, Math.min(8, secretKey.length())));
        } else {
            log.warn("Stripe NO configurado (falta STRIPE_SECRET_KEY)");
        }
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    // ─── Catálogo de precios ────────────────────────────────────────────

    /** Lookup keys de Latino: no chocan con las de España aunque se use la misma cuenta de Stripe. */
    static final String PREFIJO = "inmobilia_latam_";
    /** Precios de antes de las monedas locales (solo USD): se siguen reconociendo al leer una suscripción. */
    static final String PREFIJO_ANTIGUO = "inmobilia_";
    /** Extras que se suman a la suscripción. */
    public static final String ACTIVO_EXTRA = "activo_extra";
    public static final String SOCIEDAD_EXTRA = "sociedad_extra";

    /**
     * Un precio de Stripe con todas sus monedas: importes en unidades de Stripe por moneda
     * ("usd" la principal; el resto van como currency_options) e intervalo "month" o null (pago único).
     */
    record Precio(String clave, String producto, String nombre, Map<String, Long> importes, String intervalo) {
        String lookupKey() { return PREFIJO + clave; }
        long importeUsd() { return importes.get("usd"); }
    }

    /**
     * Precio para una clave: plan_pro_mensual, plan_business_mensual, activo_extra_mensual,
     * sociedad_extra_mensual, modulo_futureproof_mensual o creditos_100. Solo hay pago mensual.
     * Los importes de cada moneda salen de PreciosPlataforma.
     */
    static Precio catalogo(String clave) {
        if (!clave.endsWith("_mensual") && !clave.equals(PreciosPlataforma.CREDITOS)) return null;
        String base = clave.replaceAll("_mensual$", "");
        String nombre;
        if (base.startsWith("plan_")) {
            String plan = base.substring(5);
            if (!PlanService.PLANES_DE_PAGO.contains(plan)) return null;
            nombre = "InmobilIA Pro · Plan " + PlanService.PLANS.get(plan).label();
        } else if (base.equals(ACTIVO_EXTRA)) {
            nombre = "InmobilIA Pro · Activo adicional";
        } else if (base.equals(SOCIEDAD_EXTRA)) {
            nombre = "InmobilIA Pro · Sociedad adicional";
        } else if (base.startsWith("modulo_")) {
            PlanService.Modulo m = PlanService.modulo(base.substring(7));
            if (m == null || !PreciosPlataforma.existe(base)) return null;
            nombre = "InmobilIA Pro · " + m.nombre();
        } else if (base.equals(PreciosPlataforma.CREDITOS)) {
            return new Precio(clave, "creditos", "InmobilIA Pro · " + PlanService.PACK_CREDITOS + " créditos de IA",
                    PreciosPlataforma.unidadesStripe(base), null);
        } else {
            return null;
        }
        return new Precio(clave, base, nombre, PreciosPlataforma.unidadesStripe(base), "month");
    }

    /** ¿Este precio de Stripe tiene exactamente los importes del catálogo en todas las monedas? */
    static boolean mismosImportes(Price existente, Precio p) {
        if (!"usd".equalsIgnoreCase(existente.getCurrency()) || !Objects.equals(existente.getUnitAmount(), p.importeUsd())) return false;
        Map<String, Price.CurrencyOption> opciones = existente.getCurrencyOptions() == null ? Map.of() : existente.getCurrencyOptions();
        for (Map.Entry<String, Long> e : p.importes().entrySet()) {
            if ("usd".equals(e.getKey())) continue;
            Price.CurrencyOption o = opciones.get(e.getKey());
            if (o == null || !Objects.equals(o.getUnitAmount(), e.getValue())) return false;
        }
        return true;
    }

    private final Map<String, String> precios = new ConcurrentHashMap<>();

    /** Id del precio de Stripe para una clave del catálogo (lo crea si hace falta). */
    String priceId(String clave) throws StripeException {
        String cacheado = precios.get(clave);
        if (cacheado != null) return cacheado;
        Precio p = catalogo(clave);
        if (p == null) throw new IllegalArgumentException("Precio desconocido: " + clave);
        synchronized (this) {
            cacheado = precios.get(clave);
            if (cacheado != null) return cacheado;
            for (Price existente : Price.list(PriceListParams.builder().addLookupKey(p.lookupKey()).setActive(true)
                    .addExpand("data.currency_options").build()).getData()) {
                String intervalo = existente.getRecurring() == null ? null : existente.getRecurring().getInterval();
                if (mismosImportes(existente, p) && Objects.equals(intervalo, p.intervalo())) {
                    precios.put(clave, existente.getId());
                    return existente.getId();
                }
            }
            String producto = PREFIJO + p.producto();
            try {
                Product.retrieve(producto);
            } catch (StripeException noExiste) {
                Product.create(ProductCreateParams.builder().setId(producto).setName(p.nombre()).build());
            }
            PriceCreateParams.Builder b = PriceCreateParams.builder()
                    .setProduct(producto)
                    .setCurrency("usd")
                    .setUnitAmount(p.importeUsd())
                    .setLookupKey(p.lookupKey())
                    .setTransferLookupKey(true);   // si el importe cambió, la clave pasa al precio nuevo
            // El resto de monedas en el mismo precio: Stripe cobra en la que se pida en el checkout
            p.importes().forEach((moneda, unidades) -> {
                if (!"usd".equals(moneda)) b.putCurrencyOption(moneda, PriceCreateParams.CurrencyOption.builder().setUnitAmount(unidades).build());
            });
            if (p.intervalo() != null) {
                b.setRecurring(PriceCreateParams.Recurring.builder()
                        .setInterval("year".equals(p.intervalo()) ? PriceCreateParams.Recurring.Interval.YEAR : PriceCreateParams.Recurring.Interval.MONTH)
                        .build());
            }
            Price creado = Price.create(b.build());
            log.info("Creado en Stripe el precio {} ({} monedas): {}", p.lookupKey(), p.importes().size(), creado.getId());
            precios.put(clave, creado.getId());
            return creado.getId();
        }
    }

    /** Crea/recupera el customer de Stripe asociado al usuario. */
    public String getOrCreateCustomer(Map<String, Object> user) throws StripeException {
        Object existingId = user.get("stripe_customer_id");
        if (existingId != null && !String.valueOf(existingId).isBlank() && !"null".equals(String.valueOf(existingId))) {
            return String.valueOf(existingId);
        }
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(String.valueOf(user.getOrDefault("email", "")))
                .setName(String.valueOf(user.getOrDefault("name", user.getOrDefault("email", ""))))
                .putMetadata("user_id", String.valueOf(user.get("id")))
                .build();
        Customer c = Customer.create(params);

        // Guardar el customer_id en la BBDD
        try {
            supabase.update("users", String.valueOf(user.get("id")),
                    Map.of("stripe_customer_id", c.getId()),
                    String.valueOf(user.get("id")));
        } catch (Exception e) {
            log.warn("No se pudo guardar stripe_customer_id: {}", e.getMessage());
        }
        return c.getId();
    }

    // ─── Impuestos ─────────────────────────────────────────────────────
    // Clientes en Latinoamérica, precios en USD: no se añade ningún impuesto automáticamente.
    // Si hace falta uno, se configura su id de Stripe en STRIPE_TAX_RATE_IVA=txr_...
    @Value("${stripe.tax-rate.iva:}")
    private String ivaTaxRateConfigured;

    /** Id del tipo impositivo configurado en Stripe, o null si no hay ninguno. */
    String ivaTaxRateId() {
        if (ivaTaxRateConfigured != null && !ivaTaxRateConfigured.isBlank()) return ivaTaxRateConfigured;
        return null;
    }

    /** Datos fiscales para la factura: identificador tributario del cliente y dirección. */
    private static void datosFiscales(SessionCreateParams.Builder b) {
        b.setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
         .setTaxIdCollection(SessionCreateParams.TaxIdCollection.builder().setEnabled(true).build())
         // Con un customer ya existente, Stripe exige permitir guardar nombre y dirección.
         .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                 .setName(SessionCreateParams.CustomerUpdate.Name.AUTO)
                 .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                 .build());
    }

    private SessionCreateParams.LineItem lineaConIva(String priceId) {
        SessionCreateParams.LineItem.Builder item = SessionCreateParams.LineItem.builder()
                .setPrice(priceId)
                .setQuantity(1L);
        String iva = ivaTaxRateId();
        if (iva != null) item.addTaxRate(iva);
        return item.build();
    }

    // ─── Suscripción ────────────────────────────────────────────────────

    /**
     * Checkout de una suscripción nueva a Pro o Business (mensual, en la moneda del país). No se cobra nada hasta
     * que acabe lo que quede de la prueba gratis o, si es más tarde, hasta el día de la oferta de lanzamiento
     * (Promociones). Al socio fundador se le aplica su cupón. Los extras se añaden después desde Facturación.
     */
    public String createCheckoutSession(Map<String, Object> user, String plan, boolean anual) throws StripeException {
        if (!PlanService.PLANES_DE_PAGO.contains(plan)) {
            throw new IllegalArgumentException("Plan no válido: " + plan);
        }
        String priceId = priceId("plan_" + plan + "_mensual");   // solo pago mensual
        String moneda = PreciosPlataforma.moneda();               // la del país de la inmobiliaria
        String customerId = getOrCreateCustomer(user);
        Instant ahora = Instant.now();
        Long finSinCobro = Promociones.finSinCobro(user, promociones.primerCobro(), ahora);
        String cupon = Promociones.esFundador(user) && !PlanService.haSuscritoAlgunaVez(user)
                ? cuponFundador(Promociones.mesesCupon(ahora, finSinCobro)) : null;
        return Session.create(parametrosCheckout(user, plan, customerId, lineaConIva(priceId), finSinCobro, cupon, baseUrl, moneda)).getUrl();
    }

    /** Los parámetros del checkout, aparte para poder comprobarlos sin llamar a Stripe. */
    static SessionCreateParams parametrosCheckout(Map<String, Object> user, String plan, String customerId,
                                                  SessionCreateParams.LineItem linea, Long finSinCobro, String cupon,
                                                  String baseUrl, String moneda) {
        SessionCreateParams.Builder b = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .setSuccessUrl(baseUrl + "/billing/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/billing/cancel")
                .setLocale(SessionCreateParams.Locale.ES_419)
                .addLineItem(linea)
                .putMetadata("user_id", String.valueOf(user.get("id")))
                .putMetadata("plan", plan)
                .putMetadata("periodo", "mensual")
                .putMetadata("moneda", moneda)
                .setCurrency(moneda.toLowerCase(Locale.ROOT))
                .setPaymentMethodCollection(SessionCreateParams.PaymentMethodCollection.ALWAYS)
                // Desactivar Managed Payments (merchant of record, +3,5% y exige tax code).
                // Usamos el checkout estándar; los impuestos los gestionamos nosotros.
                .putExtraParam("managed_payments[enabled]", false);
        // Stripe no deja juntar un cupón puesto por nosotros con el campo de códigos promocionales
        if (cupon != null) {
            b.addDiscount(SessionCreateParams.Discount.builder().setCoupon(cupon).build());
            b.putMetadata("socio_fundador", "true");
        } else {
            b.setAllowPromotionCodes(true);
        }
        if (finSinCobro != null) {
            b.setSubscriptionData(SessionCreateParams.SubscriptionData.builder().setTrialEnd(finSinCobro).build());
        }
        datosFiscales(b);
        return b.build();
    }

    /** Fin de la prueba de la aprobación (epoch) si quedan más de 2 días y nunca se suscribió; si no, null. */
    static Long finDePruebaParaStripe(Map<String, Object> user) {
        return Promociones.finSinCobro(user, null, Instant.now());
    }

    /** Cupón de socio fundador con esa duración: lo busca por su id y, si no existe, lo crea (vale en todas las monedas). */
    String cuponFundador(int meses) throws StripeException {
        String id = Promociones.cuponId(meses);
        try {
            return Coupon.retrieve(id).getId();
        } catch (StripeException noExiste) {
            Coupon c = Coupon.create(CouponCreateParams.builder()
                    .setId(id)
                    .setName("Socio fundador · " + Promociones.FUNDADOR_PCT + " % (" + Promociones.FUNDADOR_COBROS + " cobros)")
                    .setPercentOff(BigDecimal.valueOf(Promociones.FUNDADOR_PCT))
                    .setDuration(CouponCreateParams.Duration.REPEATING)
                    .setDurationInMonths((long) meses)
                    .build());
            log.info("Creado en Stripe el cupón {} ({} % durante {} meses)", id, Promociones.FUNDADOR_PCT, meses);
            return c.getId();
        }
    }

    /** Lo que cobra una suscripción, leído de sus precios. plan = null si no es de un plan conocido. */
    public record EstadoSuscripcion(String plan, boolean anual, int activosExtra, int sociedadesExtra, Set<String> modulos) {}

    public EstadoSuscripcion leer(Subscription sub) {
        String plan = null;
        boolean anual = false;
        int activosExtra = 0, sociedadesExtra = 0;
        Set<String> modulos = new LinkedHashSet<>();
        if (sub != null && sub.getItems() != null && sub.getItems().getData() != null) {
            for (SubscriptionItem item : sub.getItems().getData()) {
                Price price = item.getPrice();
                if (price == null) continue;
                String lk = price.getLookupKey() == null ? "" : price.getLookupKey();
                int cantidad = item.getQuantity() == null ? 1 : item.getQuantity().intValue();
                String clave = (lk.startsWith(PREFIJO) ? lk.substring(PREFIJO.length())
                        : lk.startsWith(PREFIJO_ANTIGUO) ? lk.substring(PREFIJO_ANTIGUO.length()) : "").replaceAll("_(mensual|anual)$", "");
                if (clave.startsWith("plan_")) {
                    plan = clave.substring(5);
                    anual = lk.endsWith("_anual");
                } else if (clave.equals(ACTIVO_EXTRA)) {
                    activosExtra = cantidad;
                } else if (clave.equals(SOCIEDAD_EXTRA)) {
                    sociedadesExtra = cantidad;
                } else if (clave.startsWith("modulo_")) {
                    modulos.add(clave.substring(7));
                } else if (price.getId().equals(precioAntiguoPro)) {
                    plan = PlanService.PRO;
                } else if (price.getId().equals(precioAntiguoAgency)) {
                    plan = PlanService.BUSINESS;
                }
            }
        }
        return new EstadoSuscripcion(plan, anual, activosExtra, sociedadesExtra, modulos);
    }

    /**
     * Pone en la suscripción la cantidad de un extra: "activo_extra" o "sociedad_extra" (unidades) o "modulo_xxx"
     * (1 = activado). 0 lo quita. Mismo intervalo que el plan (mensual o anual: Stripe no mezcla)
     * y prorrateado: lo añadido a mitad de periodo se cobra en proporción en la siguiente factura.
     */
    public void ajustarExtra(String subscriptionId, String extra, int cantidad) throws StripeException {
        Subscription sub = Subscription.retrieve(subscriptionId);
        SubscriptionItem actual = null;
        for (SubscriptionItem item : sub.getItems().getData()) {
            String lk = item.getPrice() == null || item.getPrice().getLookupKey() == null ? "" : item.getPrice().getLookupKey();
            if (lk.startsWith(PREFIJO + extra + "_")) actual = item;
        }
        if (cantidad <= 0) {
            if (actual != null) {
                actual.delete(SubscriptionItemDeleteParams.builder()
                        .setProrationBehavior(SubscriptionItemDeleteParams.ProrationBehavior.CREATE_PRORATIONS).build());
            }
            return;
        }
        if (actual != null) {
            if (actual.getQuantity() == null || actual.getQuantity() != cantidad) {
                actual.update(SubscriptionItemUpdateParams.builder()
                        .setQuantity((long) cantidad)
                        .setProrationBehavior(SubscriptionItemUpdateParams.ProrationBehavior.CREATE_PRORATIONS).build());
            }
            return;
        }
        SubscriptionItemCreateParams.Builder b = SubscriptionItemCreateParams.builder()
                .setSubscription(subscriptionId)
                .setPrice(priceId(extra + "_mensual"))   // misma moneda que la suscripción (precio multimoneda)
                .setQuantity((long) cantidad)
                .setProrationBehavior(SubscriptionItemCreateParams.ProrationBehavior.CREATE_PRORATIONS);
        String iva = ivaTaxRateId();
        if (iva != null) b.addTaxRate(iva);
        SubscriptionItem.create(b.build());
        log.info("Suscripción {}: {} × {}", subscriptionId, extra, cantidad);
    }

    /** Checkout de PAGO ÚNICO del pack de créditos de IA. */
    public String createCreditCheckoutSession(Map<String, Object> user) throws StripeException {
        String customerId = getOrCreateCustomer(user);
        SessionCreateParams.Builder b = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customerId)
                .setSuccessUrl(baseUrl + "/billing/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/creditos")
                .setLocale(SessionCreateParams.Locale.ES_419)
                .addLineItem(lineaConIva(priceId(PreciosPlataforma.CREDITOS)))
                .setCurrency(PreciosPlataforma.moneda().toLowerCase(Locale.ROOT))
                .putMetadata("user_id", String.valueOf(user.get("id")))
                .putMetadata("type", "credits")
                .putMetadata("credits", String.valueOf(PlanService.PACK_CREDITOS))
                // Pago único: que Stripe emita factura (las suscripciones ya la generan solas).
                .setInvoiceCreation(SessionCreateParams.InvoiceCreation.builder().setEnabled(true).build())
                .putExtraParam("managed_payments[enabled]", false);
        datosFiscales(b);
        return Session.create(b.build()).getUrl();
    }

    /** Crea sesión del portal de cliente (gestionar/cancelar suscripción). */
    public String createPortalSession(String customerId) throws StripeException {
        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(customerId)
                        .setReturnUrl(baseUrl + "/billing")
                        .build();
        com.stripe.model.billingportal.Session session =
                com.stripe.model.billingportal.Session.create(params);
        return session.getUrl();
    }
}
