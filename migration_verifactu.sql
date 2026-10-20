-- ============================================================================
-- InmobilIA Pro · Sprint 21: Verifactu + perfil empresa + recibos
-- RD 1007/2023 · Sistema de facturación verificable AEAT
-- ============================================================================

-- ─── Verifactu en facturas ───
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_hash       TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_prev_hash  TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_qr_payload TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_chain_id   INTEGER;

CREATE INDEX IF NOT EXISTS idx_invoices_verifactu ON invoices(verifactu_chain_id);

-- ─── Perfil de empresa (configuración del admin/organización) ───
CREATE TABLE IF NOT EXISTS business_profile (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id          UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    -- Datos fiscales
    legal_name      TEXT,                         -- Razón social
    tax_id          TEXT,                         -- NIF/CIF
    address         TEXT,
    city            TEXT,
    postal_code     TEXT,
    province        TEXT,
    country         TEXT DEFAULT 'España',
    iban            TEXT,
    -- Contacto
    contact_name    TEXT,
    contact_email   TEXT,
    contact_phone   TEXT,
    website         TEXT,
    -- Branding
    logo_url        TEXT,                         -- URL del logo
    brand_color     TEXT DEFAULT '#1e40af',       -- color primario
    -- Horario y otros
    business_hours  TEXT,
    notes_invoice   TEXT,                         -- Notas legales por defecto en factura
    receipt_footer  TEXT,                         -- Pie por defecto en recibos
    -- Verifactu
    verifactu_seq   INTEGER DEFAULT 0,            -- secuencia para chain_id
    -- Auditoría
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Recibos de alquiler (sin IVA, distintos a facturas) ───
CREATE TABLE IF NOT EXISTS receipts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    serie           TEXT DEFAULT 'R',
    numero          INTEGER NOT NULL,
    full_number     TEXT GENERATED ALWAYS AS (serie || '/' || lpad(numero::text, 5, '0')) STORED,
    -- Período
    period_year     INTEGER,
    period_month    INTEGER,
    fecha_emision   DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_pago      DATE,
    -- Emisor (arrendador)
    emisor_nombre   TEXT,
    emisor_nif      TEXT,
    emisor_direccion TEXT,
    -- Receptor (arrendatario)
    inquilino_nombre TEXT NOT NULL,
    inquilino_nif    TEXT,
    inquilino_direccion TEXT,
    -- Inmueble + concepto
    property_name   TEXT,
    property_ref    TEXT,
    catastro_ref    TEXT,
    concepto        TEXT,
    -- Importes
    importe         NUMERIC(12,2) NOT NULL,
    irpf_pct        NUMERIC(5,2) DEFAULT 19,      -- retención típica del arrendador persona física
    irpf_amount     NUMERIC(12,2),
    total_pagado    NUMERIC(12,2),
    metodo_pago     TEXT,
    -- Estado
    status          TEXT DEFAULT 'Emitido',       -- Emitido, Pagado, Anulado
    notas           TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_receipts_user   ON receipts(user_id);
CREATE INDEX IF NOT EXISTS idx_receipts_period ON receipts(period_year, period_month);
CREATE UNIQUE INDEX IF NOT EXISTS uq_receipts_seq ON receipts(user_id, serie, numero);

-- Auto-numeración recibos
CREATE OR REPLACE FUNCTION receipts_auto_number()
RETURNS TRIGGER AS $$
DECLARE
    next_num INTEGER;
BEGIN
    IF NEW.numero IS NULL OR NEW.numero = 0 THEN
        SELECT COALESCE(MAX(numero), 0) + 1 INTO next_num
          FROM receipts
         WHERE user_id = NEW.user_id AND serie = NEW.serie;
        NEW.numero := next_num;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_receipts_auto_number ON receipts;
CREATE TRIGGER trg_receipts_auto_number
    BEFORE INSERT ON receipts
    FOR EACH ROW EXECUTE FUNCTION receipts_auto_number();

-- ─── Modelos AEAT extendidos: 184 (CB) y 347 ───
-- (Solo se añaden columnas necesarias en invoices/transacciones para luego generar)
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS for_modelo184 BOOLEAN DEFAULT false;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS for_modelo347 BOOLEAN DEFAULT false;

SELECT 'Migration verifactu + business + receipts completed' AS status;
