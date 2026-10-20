-- ============================================================================
-- InmobilIA Pro · Migración suscripciones Stripe (Sprint 11)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

-- ─── Columnas de suscripción en users ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan                  TEXT DEFAULT 'free';
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_status           TEXT DEFAULT 'active';   -- active, trialing, past_due, canceled
ALTER TABLE users ADD COLUMN IF NOT EXISTS trial_ends_at         TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_ends_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id    TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_subscription_id TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_canceled_at      TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_plan       ON users(plan);
CREATE INDEX IF NOT EXISTS idx_users_stripe_cus ON users(stripe_customer_id);
CREATE INDEX IF NOT EXISTS idx_users_stripe_sub ON users(stripe_subscription_id);

-- ─── Trial automático de 14 días al registrarse ───
CREATE OR REPLACE FUNCTION users_default_trial()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.trial_ends_at IS NULL AND NEW.organization_id = NEW.id THEN
        NEW.trial_ends_at := NOW() + INTERVAL '14 days';
        IF NEW.plan IS NULL OR NEW.plan = '' THEN
            NEW.plan := 'free';
        END IF;
        IF NEW.plan_status IS NULL OR NEW.plan_status = '' THEN
            NEW.plan_status := 'trialing';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_default_trial ON users;
CREATE TRIGGER trg_users_default_trial
    BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION users_default_trial();

-- ─── Tabla de eventos de facturación Stripe (audit) ───
CREATE TABLE IF NOT EXISTS billing_events (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    stripe_event_id TEXT UNIQUE,
    event_type      TEXT NOT NULL,
    amount_cents    INTEGER,
    currency        TEXT,
    status          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_billing_events_user ON billing_events(user_id);
CREATE INDEX IF NOT EXISTS idx_billing_events_type ON billing_events(event_type);

-- ─── Aplicar trial a usuarios existentes que no lo tengan ───
UPDATE users
   SET trial_ends_at = NOW() + INTERVAL '14 days',
       plan = COALESCE(plan, 'free'),
       plan_status = COALESCE(plan_status, 'trialing')
 WHERE trial_ends_at IS NULL
   AND organization_id = id;

SELECT 'Migration subscriptions completed' AS status;
