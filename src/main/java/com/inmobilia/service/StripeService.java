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
        SessionCreateParams.LineItem.Builder item = SessionCreateParams.LineItem.builder()
                .setPrice(priceId)
                .setQuantity(1L);
        String iva = ivaTaxRateId();
        if (iva != null) item.addTaxRate(iva);
        return item.build();
    }

    /** Crea sesión de Stripe Checkout para suscripción nueva. */
    public String createCheckoutSession(Map<String, Object> user, String plan) throws StripeException {
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
                .addLineItem(lineaConIva(priceId))
                .putMetadata("user_id", String.valueOf(user.get("id")))
                .putMetadata("plan", plan)
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
