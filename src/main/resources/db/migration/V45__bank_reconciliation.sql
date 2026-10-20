-- Sprint 76: Conciliación bancaria
CREATE TABLE IF NOT EXISTS bank_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    cuenta_id UUID REFERENCES cuentas_caja(id) ON DELETE SET NULL,
    nombre_archivo TEXT,
    formato TEXT,                          -- csv, mt940, n43
    num_lineas INT,
    num_conciliadas INT DEFAULT 0,
    num_pendientes INT DEFAULT 0,
    fecha_desde DATE, fecha_hasta DATE,
    saldo_inicial NUMERIC(12,2), saldo_final NUMERIC(12,2),
    estado TEXT DEFAULT 'pendiente',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS bank_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id UUID REFERENCES bank_imports(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    fecha DATE NOT NULL,
    valor_fecha DATE,
    importe NUMERIC(12,2) NOT NULL,
    concepto TEXT,
    referencia TEXT,
    contraparte_nombre TEXT,
    contraparte_iban TEXT,
    saldo NUMERIC(12,2),

    estado TEXT DEFAULT 'pendiente',       -- pendiente, conciliada, ignorada, manual
    factura_id UUID,
    movimiento_caja_id UUID,
    mandato_sepa_id UUID,
    score_match NUMERIC(5,2),              -- 0-100 confianza
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bank_tx_user ON bank_transactions(user_id, fecha DESC);
CREATE INDEX IF NOT EXISTS idx_bank_tx_estado ON bank_transactions(estado);
