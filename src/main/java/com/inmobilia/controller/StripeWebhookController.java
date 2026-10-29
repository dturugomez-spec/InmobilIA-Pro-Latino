package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.StripeService;
import com.inmobilia.service.SupabaseService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Recibe eventos de Stripe (webhook) y actualiza el estado de la suscripción del usuario.
 * Configurar el webhook en Stripe Dashboard → Developers → Webhooks → Add endpoint:
 *   URL: https://inmobiliapro.es/stripe/webhook
 *   Eventos: checkout.session.completed, customer.subscription.created,
 *            customer.subscription.updated, customer.subscription.deleted,
 *            invoice.paid, invoice.payment_failed
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    private final SupabaseService supabase;
    private final AIService aiService;
    private final StripeService stripeService;
    private final PlanService planService;

    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> handle(@RequestBody String payload,
                                         @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Stripe webhook recibido pero STRIPE_WEBHOOK_SECRET no configurado");
            return ResponseEntity.status(503).body("not configured");
        }
        if (sigHeader == null) {
            return ResponseEntity.status(400).body("missing signature");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook firma inválida: {}", e.getMessage());
            return ResponseEntity.status(400).body("invalid signature");
        }

        log.info(">>> Stripe webhook: {} (id={})", event.getType(), event.getId());

        // Guardar evento (audit)
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stripe_event_id", event.getId());
            entry.put("event_type", event.getType());
            entry.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("billing_events", entry);
        } catch (Exception ignored) {}

        try {
            switch (event.getType()) {
                case "checkout.session.completed" -> handleCheckoutCompleted(event);
                case "customer.subscription.created",
                     "customer.subscription.updated" -> handleSubscriptionUpdate(event);
                case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
                case "invoice.payment_failed" -> handlePaymentFailed(event);
                default -> log.debug("Evento no gestionado: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("Error procesando webhook {}: {}", event.getType(), e.getMessage());
        }

        return ResponseEntity.ok("ok");
    }

    private void handleCheckoutCompleted(Event event) {
        Optional<StripeObject> obj = deserialize(event);
        if (obj.isEmpty() || !(obj.get() instanceof Session session)) return;
        String userId = session.getMetadata() != null ? session.getMetadata().get("user_id") : null;
        String plan   = session.getMetadata() != null ? session.getMetadata().get("plan") : null;
        String type   = session.getMetadata() != null ? session.getMetadata().get("type") : null;
        if (userId == null) return;

        // Compra de un pack de créditos de IA (pago único) → sumar créditos, no cambiar plan
        if ("credits".equals(type)) {
            int credits = parseIntSafe(session.getMetadata().get("credits"));
            if (credits > 0) aiService.addCredits(userId, credits);
            log.info(">>> Usuario {} compró {} créditos", userId, credits);
            return;
        }

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("plan", PlanService.normalizar(plan != null ? plan : PlanService.PRO));
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("periodo_facturacion", "anual".equals(session.getMetadata().get("periodo")) ? "anual" : "mensual");
        upd.put("stripe_customer_id", session.getCustomer());
        upd.put("stripe_subscription_id", session.getSubscription());
        // Estado real de la suscripción: "trialing" si aún le quedaban días de prueba, "active" si ya cobra.
        String estado = "active";
        if (session.getSubscription() != null) {
            try {
                Subscription sub = Subscription.retrieve(session.getSubscription());
                if (sub.getStatus() != null) estado = sub.getStatus();
                volcar(stripeService.leer(sub), upd, extras);
                if (sub.getCurrentPeriodEnd() != null) {
                    upd.put("subscription_ends_at", OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(sub.getCurrentPeriodEnd()), ZoneOffset.UTC).toString());
                }
            } catch (Exception e) {
                log.warn("No se pudo leer la suscripción {}: {}", session.getSubscription(), e.getMessage());
            }
        }
        upd.put("plan_status", estado);
        supabase.update("users", userId, upd, userId);
        supabase.update("users", userId, extras, userId);
        planService.olvidarExceso(userId);
        log.info(">>> Usuario {} → plan {}", userId, upd.get("plan"));

        // Créditos de IA del plan desde el primer día; después se reponen cada mes
        aiService.reponerCreditos(userId, PlanService.creditosMensuales(String.valueOf(upd.get("plan"))), true);
    }

    /**
     * Plan, periodo, activos adicionales y módulos tal como los cobra Stripe. Las columnas de los
     * extras (migración V92) van aparte: si aún no existen en la base, el plan se guarda igual.
     */
    private static void volcar(StripeService.EstadoSuscripcion e, Map<String, Object> upd, Map<String, Object> extras) {
        if (e.plan() != null) {
            upd.put("plan", PlanService.normalizar(e.plan()));
            extras.put("periodo_facturacion", e.anual() ? "anual" : "mensual");
        }
        extras.put("activos_extra", e.activosExtra());
        extras.put("sociedades_extra", e.sociedadesExtra());
        extras.put("modulos", new ArrayList<>(e.modulos()));
    }

    private void handleSubscriptionUpdate(Event event) {
        Optional<StripeObject> obj = deserialize(event);
        if (obj.isEmpty() || !(obj.get() instanceof Subscription sub)) return;
        String customerId = sub.getCustomer();

        // Buscar usuario por stripe_customer_id
        var users = supabase.findBy("users", Map.of("stripe_customer_id", customerId));
        if (users.isEmpty()) return;
        String userId = String.valueOf(users.get(0).get("id"));

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("plan_status", sub.getStatus());          // active, past_due, canceled, trialing
        upd.put("stripe_subscription_id", sub.getId());
        // Cambios desde el portal de cliente o desde Facturación (plan, anual, extras)
        StripeService.EstadoSuscripcion estado = stripeService.leer(sub);
        Map<String, Object> extras = new LinkedHashMap<>();
        volcar(estado, upd, extras);
        // Al subir de plan, el saldo sube ya a la asignación del nuevo plan.
        // Al bajar no se quita nada: el ajuste llega con la siguiente reposición mensual.
        String planAnterior = String.valueOf(users.get(0).get("plan"));
        if (estado.plan() != null
                && PlanService.creditosMensuales(estado.plan()) > PlanService.creditosMensuales(planAnterior)) {
            aiService.reponerCreditos(userId, PlanService.creditosMensuales(estado.plan()), false);
        }
        if (sub.getCurrentPeriodEnd() != null) {
            OffsetDateTime end = OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(sub.getCurrentPeriodEnd()), ZoneOffset.UTC);
            upd.put("subscription_ends_at", end.toString());
        }
        supabase.update("users", userId, upd, userId);
        supabase.update("users", userId, extras, userId);
        planService.olvidarExceso(userId);
        log.info(">>> Sub actualizada para user {} → status {}", userId, sub.getStatus());
    }

    /** Suscripción cancelada: la cuenta pasa a Free y conserva sus datos. */
    private void handleSubscriptionDeleted(Event event) {
        Optional<StripeObject> obj = deserialize(event);
        if (obj.isEmpty() || !(obj.get() instanceof Subscription sub)) return;
        String customerId = sub.getCustomer();
        var users = supabase.findBy("users", Map.of("stripe_customer_id", customerId));
        if (users.isEmpty()) return;
        String userId = String.valueOf(users.get(0).get("id"));

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("plan", PlanService.FREE);
        upd.put("plan_status", "canceled");
        upd.put("plan_canceled_at", OffsetDateTime.now().toString());
        supabase.update("users", userId, upd, userId);
        supabase.update("users", userId, Map.of("activos_extra", 0, "sociedades_extra", 0, "modulos", new ArrayList<>()), userId);
        planService.olvidarExceso(userId);
        log.info(">>> Sub cancelada para user {}: pasa a Free", userId);
    }

    private void handlePaymentFailed(Event event) {
        Optional<StripeObject> obj = deserialize(event);
        // Marcamos como past_due — el usuario tiene unos días para arreglar el pago
        // antes de que Stripe cancele automáticamente
        log.warn(">>> Pago fallido: {}", event.getId());
    }

    private static int parseIntSafe(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static Optional<StripeObject> deserialize(Event event) {
        EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
        return deser.getObject();
    }
}
