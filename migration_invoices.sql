-- ============================================================================
-- InmobilIA Pro · Migración facturación ES (Sprint 6)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

CREATE TABLE IF NOT EXISTS invoices (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    serie             TEXT DEFAULT 'A',
    numero            INTEGER NOT NULL,
    full_number       TEXT GENERATED ALWAYS AS (serie || '/' || lpad(numero::text, 5, '0')) STORED,
    fecha             DATE NOT NULL DEFAULT CURRENT_DATE,
    vencimiento       DATE,
    -- Emisor (de tu propia config)
    emisor_nombre     TEXT,
    emisor_nif        TEXT,
    emisor_direccion  TEXT,
    emisor_email      TEXT,
    emisor_iban       TEXT,
    -- Cliente
    cliente_nombre    TEXT NOT NULL,
    cliente_nif       TEXT,
    cliente_direccion TEXT,
    cliente_email     TEXT,
    -- Importes
    concepto          TEXT,
    base_imponible    NUMERIC(12,2) NOT NULL DEFAULT 0,
    iva_pct           NUMERIC(5,2) DEFAULT 21,
    iva_amount        NUMERIC(12,2) DEFAULT 0,
    irpf_pct          NUMERIC(5,2) DEFAULT 0,        -- Retención IRPF (típico 19% para alquiler)
    irpf_amount       NUMERIC(12,2) DEFAULT 0,
    total             NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Líneas (JSONB array: [{descripcion, cantidad, precio, base, iva_pct}])
    lineas            JSONB DEFAULT '[]'::jsonb,
    -- Estado
    status            TEXT DEFAULT 'Borrador',     -- Borrador, Emitida, Pagada, Vencida, Anulada
    fecha_pago        DATE,
    metodo_pago       TEXT,
    notas             TEXT,
    -- Vinculaciones
    property_name     TEXT,
    tenant_name       TEXT,
    contract_id       UUID REFERENCES contratos(id) ON DELETE SET NULL,
    -- Auditoría
    created_at        TIMESTAMPTZ DEFAULT NOW(),
    updated_at        TIMESTAMPTZ DEFAULT NOW(),
    created_by        UUID,
    updated_by        UUID
);

CREATE INDEX IF NOT EXISTS idx_invoices_user    ON invoices(user_id);
CREATE INDEX IF NOT EXISTS idx_invoices_status  ON invoices(status);
CREATE INDEX IF NOT EXISTS idx_invoices_fecha   ON invoices(fecha);
CREATE INDEX IF NOT EXISTS idx_invoices_serie   ON invoices(user_id, serie, numero);
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_seq ON invoices(user_id, serie, numero);

-- Auto-numerar (siguiente número de la serie por usuario)
CREATE OR REPLACE FUNCTION invoices_auto_number()
RETURNS TRIGGER AS $$
DECLARE
    next_num INTEGER;
BEGIN
    IF NEW.numero IS NULL OR NEW.numero = 0 THEN
        SELECT COALESCE(MAX(numero), 0) + 1 INTO next_num
          FROM invoices
         WHERE user_id = NEW.user_id AND serie = NEW.serie;
        NEW.numero := next_num;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_invoices_auto_number ON invoices;
CREATE TRIGGER trg_invoices_auto_number
    BEFORE INSERT ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_auto_number();

-- Verificación
SELECT 'Tabla invoices creada' AS info, count(*) AS facturas FROM invoices;
