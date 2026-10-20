-- Esquema necesario para el cobro con Stripe.
-- El webhook (StripeWebhookController) escribe estas columnas/tabla al pagar/cancelar.
-- Sin esto, aunque Stripe esté configurado, el plan NO se actualizaría tras el pago.

ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_subscription_id TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_ends_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_canceled_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_stripe_customer ON users(stripe_customer_id)
    WHERE stripe_customer_id IS NOT NULL;

-- Log de eventos de Stripe (auditoría / idempotencia)
CREATE TABLE IF NOT EXISTS billing_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stripe_event_id TEXT UNIQUE,
    event_type TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
