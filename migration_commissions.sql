-- ============================================================================
-- InmobilIA Pro · Sprint 40: Comisiones comerciales + clonar propiedades
-- ============================================================================

-- Comisiones generadas por venta o alquiler
CREATE TABLE IF NOT EXISTS commissions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id          UUID NOT NULL,
    agent_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    agent_name      TEXT,
    operation_type  TEXT NOT NULL,                  -- 'venta', 'alquiler', 'gestión'
    property_id     UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    property_name   TEXT,
    client_name     TEXT,
    operation_value NUMERIC(12,2) NOT NULL,          -- ej. precio venta o renta anual
    commission_pct  NUMERIC(5,2) NOT NULL,           -- % de la comisión
    commission_amount NUMERIC(12,2) GENERATED ALWAYS AS (operation_value * commission_pct / 100) STORED,
    operation_date  DATE,
    status          TEXT DEFAULT 'Pendiente',        -- Pendiente, Pagada, Anulada
    paid_date       DATE,
    notes           TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_comm_org    ON commissions(org_id, operation_date DESC);
CREATE INDEX IF NOT EXISTS idx_comm_agent  ON commissions(agent_user_id);

-- Targets/objetivos por comercial
CREATE TABLE IF NOT EXISTS sales_targets (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id        UUID NOT NULL,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    year          INTEGER NOT NULL,
    month         INTEGER,                          -- null = anual
    target_amount NUMERIC(12,2),                    -- € de operaciones
    target_units  INTEGER,                          -- nº de cierres
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_targets_user_period ON sales_targets(user_id, year, month);

-- Multi-IBAN en business_profile (mejora)
ALTER TABLE business_profile ADD COLUMN IF NOT EXISTS iban_alt JSONB DEFAULT '[]'::jsonb;
-- Estructura: [{"name": "Cuenta principal", "iban": "ES00..."}, ...]

SELECT 'Migration commissions + multi-IBAN completed' AS status;
