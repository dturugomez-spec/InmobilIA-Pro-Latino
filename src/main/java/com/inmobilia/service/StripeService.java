package com.inmobilia.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.TaxRate;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.TaxRateCreateParams;
import com.stripe.param.TaxRateListParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Wrapper sobre Stripe Java SDK.
 * Expone: crear sesión de checkout, crear sesión del portal de cliente, get/create customer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StripeService {

    @Value("${stripe.secret-key:}")
    private String secretKey;

    @Value("${stripe.price.pro:}")
    private String pricePro;

    @Value("${stripe.price.agency:}")
    private String priceAgency;

    // Packs de créditos de IA (pago único). eurHint es solo para mostrar; el cargo real
    // es el del precio de Stripe. Configura los precios para que coincidan.
    @Value("${stripe.price.credits-s:}")
    private String priceCreditsS;
    @Value("${stripe.price.credits-m:}")
    private String priceCreditsM;
    @Value("${stripe.price.credits-l:}")
    private String priceCreditsL;

    @Value("${app.base-url:https://inmobiliapro.es}")
    private String baseUrl;

    private final SupabaseService supabase;

    public record CreditPack(String key, int credits, int eurHint, String priceId, boolean configured) {}

    /** Definición de un pack por su clave (s/m/l). Null si la clave no es válida. */
    public CreditPack packFor(String key) {
        return switch (key == null ? "" : key) {
            case "s" -> new CreditPack("s",  100,   9, priceCreditsS, notBlank(priceCreditsS));
            case "m" -> new CreditPack("m",  500,  39, priceCreditsM, notBlank(priceCreditsM));
            case "l" -> new CreditPack("l", 2000, 129, priceCreditsL, notBlank(priceCreditsL));
            default -> null;
        };
    }

    /** Los 3 packs para pintar en la UI. */
    public java.util.List<CreditPack> creditPacks() {
        return java.util.List.of(packFor("s"), packFor("m"), packFor("l"));
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

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

    /** Resuelve el priceId según el plan. */
    public String priceIdFor(String plan) {
        return switch (plan) {
            case "pro" -> pricePro;
            case "agency" -> priceAgency;
            default -> null;
        };
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

    // ─── IVA ────────────────────────────────────────────────────────────
    // Clientes en Latinoamérica, precios en USD: no se añade ningún impuesto automáticamente.
    // Si hace falta uno, se configura su id de Stripe en STRIPE_TAX_RATE_IVA=txr_...
    @Value("${stripe.tax-rate.iva:}")
    private String ivaTaxRateConfigured;
    private volatile String ivaTaxRateCache;

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
        return lineaConIva(priceId, 1);
    }

    private SessionCreateParams.LineItem lineaConIva(String priceId, int cantidad) {
        SessionCreateParams.LineItem.Builder item = SessionCreateParams.LineItem.builder()
                .setPrice(priceId)
                .setQuantity((long) Math.max(1, cantidad));
        String iva = ivaTaxRateId();
        if (iva != null) item.addTaxRate(iva);
        return item.build();
    }

    /**
     * Crea sesión de Stripe Checkout para suscripción nueva, con tantas unidades como usuarios
     * tenga la inmobiliaria: los precios de Stripe (STRIPE_PRICE_PRO / STRIPE_PRICE_AGENCY) son
     * POR USUARIO y mes. Después PlazasService ajusta la cantidad al invitar o desactivar.
     */
    public String createCheckoutSession(Map<String, Object> user, String plan, int usuarios) throws StripeException {
        String priceId = priceIdFor(plan);
        if (priceId == null || priceId.isBlank()) {
            throw new IllegalArgumentException("Plan no válido o priceId no configurado: " + plan);
        }
        String customerId = getOrCreateCustomer(user);
        // Los 14 días gratis solo la primera vez: quien ya se suscribió antes paga desde el día 1.
        boolean conPrueba = !PlanService.haSuscritoAlgunaVez(user);

        SessionCreateParams.Builder b = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .setSuccessUrl(baseUrl + "/billing/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/billing/cancel")
                .setAllowPromotionCodes(true)
                .setLocale(SessionCreateParams.Locale.ES_419)
                .addLineItem(lineaConIva(priceId, usuarios))
                .putMetadata("user_id", String.valueOf(user.get("id")))
                .putMetadata("plan", plan)
                .putMetadata("usuarios", String.valueOf(Math.max(1, usuarios)))
                // Tarjeta por adelantado siempre.
                .setPaymentMethodCollection(SessionCreateParams.PaymentMethodCollection.ALWAYS)
                // Desactivar Managed Payments (merchant of record, +3,5% y exige tax code).
                // Usamos el checkout estándar; gestionamos el IVA nosotros.
                .putExtraParam("managed_payments[enabled]", false);
        if (conPrueba) {
            // 14 días gratis: no se cobra nada hasta que acaba la prueba.
            b.setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                    .setTrialPeriodDays(14L)
                    .build());
        }
        datosFiscales(b);

        Session session = Session.create(b.build());
        return session.getUrl();
    }

    /**
     * Pone en la suscripción tantas unidades como usuarios. Stripe prorratea: un usuario que
     * entra a mitad de mes se cobra la parte proporcional en la siguiente factura, y uno que sale
     * deja saldo a favor. Devuelve la cantidad que había antes, o -1 si no se cambió nada
     * (ya coincidía, o la suscripción es de un precio antiguo que no es por usuario).
     */
    public long actualizarUsuarios(String subscriptionId, int usuarios) throws StripeException {
        com.stripe.model.Subscription sub = com.stripe.model.Subscription.retrieve(subscriptionId);
        if (sub.getItems() == null || sub.getItems().getData() == null || sub.getItems().getData().isEmpty()) return -1;
        com.stripe.model.SubscriptionItem item = sub.getItems().getData().get(0);
        String priceId = item.getPrice() == null ? null : item.getPrice().getId();
        if (planForPrice(priceId) == null) {
            log.warn("Suscripción {} con un precio que no es por usuario ({}): no se ajusta la cantidad", subscriptionId, priceId);
            return -1;
        }
        long antes = item.getQuantity() == null ? 1 : item.getQuantity();
        long ahora = Math.max(1, usuarios);
        if (antes == ahora) return -1;
        item.update(com.stripe.param.SubscriptionItemUpdateParams.builder()
                .setQuantity(ahora)
                .setProrationBehavior(com.stripe.param.SubscriptionItemUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                .build());
        log.info("Suscripción {}: {} → {} usuarios", subscriptionId, antes, ahora);
        return antes;
    }

    /** Usuarios (unidades) que se están cobrando en la suscripción, o -1 si no se puede leer. */
    public long usuariosFacturados(String subscriptionId) {
        try {
            var items = com.stripe.model.Subscription.retrieve(subscriptionId).getItems();
            if (items == null || items.getData().isEmpty() || items.getData().get(0).getQuantity() == null) return -1;
            return items.getData().get(0).getQuantity();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Plan ("pro"/"agency") que corresponde a un price de Stripe, o null si no es de un plan. */
    public String planForPrice(String priceId) {
        if (priceId == null) return null;
        if (priceId.equals(pricePro)) return "pro";
        if (priceId.equals(priceAgency)) return "agency";
        return null;
    }

    /** Crea sesión de Stripe Checkout de PAGO ÚNICO para un pack de créditos de IA. */
    public String createCreditCheckoutSession(Map<String, Object> user, String packKey) throws StripeException {
        CreditPack pack = packFor(packKey);
        if (pack == null || !pack.configured()) {
            throw new IllegalArgumentException("Pack no válido o priceId no configurado: " + packKey);
        }
        String customerId = getOrCreateCustomer(user);

        SessionCreateParams.Builder b = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customerId)
                .setSuccessUrl(baseUrl + "/billing/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/creditos")
                .setLocale(SessionCreateParams.Locale.ES_419)
                .addLineItem(lineaConIva(pack.priceId()))
                .putMetadata("user_id", String.valueOf(user.get("id")))
                .putMetadata("type", "credits")
                .putMetadata("credits", String.valueOf(pack.credits()))
                // Pago único: que Stripe emita factura (las suscripciones ya la generan solas).
                .setInvoiceCreation(SessionCreateParams.InvoiceCreation.builder().setEnabled(true).build())
                // Desactivar Managed Payments también en la compra de créditos.
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
