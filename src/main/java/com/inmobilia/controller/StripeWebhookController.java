package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.PlanService;
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
    private final com.inmobilia.service.StripeService stripeService;

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
        upd.put("plan", plan != null ? plan : "pro");
        upd.put("stripe_customer_id", session.getCustomer());
        upd.put("stripe_subscription_id", session.getSubscription());
        // Estado real de la suscripción: "trialing" durante los 14 días gratis, "active" si ya cobra.
        String estado = "active";
        int usuarios = Math.max(1, parseIntSafe(session.getMetadata().get("usuarios")));
        if (session.getSubscription() != null) {
            try {
                Subscription sub = Subscription.retrieve(session.getSubscription());
                if (sub.getStatus() != null) estado = sub.getStatus();
                usuarios = usuariosDe(sub, usuarios);
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
        log.info(">>> Usuario {} → plan {}", userId, plan);

        // Créditos de IA del plan (por usuario) desde el primer día; después se reponen cada mes
        aiService.reponerCreditos(userId, PlanService.creditosMensuales(plan != null ? plan : "pro", usuarios), true);
    }

    /** Usuarios (unidades) que cobra la suscripción. */
    private static int usuariosDe(Subscription sub, int porDefecto) {
        try {
            Long q = sub.getItems().getData().get(0).getQuantity();
            return q == null ? porDefecto : Math.max(1, q.intValue());
        } catch (Exception e) {
            return porDefecto;
        }
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
        // Si cambió de plan desde el portal de cliente, reflejarlo.
        String planNuevo = null;
        try {
            var items = sub.getItems();
            if (items != null && items.getData() != null && !items.getData().isEmpty()
                    && items.getData().get(0).getPrice() != null) {
                planNuevo = stripeService.planForPrice(items.getData().get(0).getPrice().getId());
                if (planNuevo != null) upd.put("plan", planNuevo);
            }
        } catch (Exception ignored) { }
        // Al subir de plan (Pro → Agency), el saldo sube ya a la asignación del nuevo plan.
        // Al bajar no se quita nada: el ajuste llega con la siguiente reposición mensual.
        // (Los usuarios que se añaden a mitad de mes suman sus créditos en PlazasService.)
        String planAnterior = String.valueOf(users.get(0).get("plan"));
        int usuarios = usuariosDe(sub, 1);
        if (planNuevo != null
                && PlanService.creditosMensuales(planNuevo) > PlanService.creditosMensuales(planAnterior)) {
            aiService.reponerCreditos(userId, PlanService.creditosMensuales(planNuevo, usuarios), false);
        }
        if (sub.getCurrentPeriodEnd() != null) {
            OffsetDateTime end = OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(sub.getCurrentPeriodEnd()), ZoneOffset.UTC);
            upd.put("subscription_ends_at", end.toString());
        }
        supabase.update("users", userId, upd, userId);
        log.info(">>> Sub actualizada para user {} → status {}", userId, sub.getStatus());
    }

    private void handleSubscriptionDeleted(Event event) {
        Optional<StripeObject> obj = deserialize(event);
        if (obj.isEmpty() || !(obj.get() instanceof Subscription sub)) return;
        String customerId = sub.getCustomer();
        var users = supabase.findBy("users", Map.of("stripe_customer_id", customerId));
        if (users.isEmpty()) return;
        String userId = String.valueOf(users.get(0).get("id"));

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("plan", "free");
        upd.put("plan_status", "canceled");
        upd.put("plan_canceled_at", OffsetDateTime.now().toString());
        supabase.update("users", userId, upd, userId);
        log.info(">>> Sub cancelada para user {}", userId);
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
