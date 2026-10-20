-- Sprint 66: Caja chica y gastos de oficina
CREATE TABLE IF NOT EXISTS cuentas_caja (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    tipo TEXT NOT NULL,                   -- caja_efectivo, banco, tarjeta
    iban TEXT,
    saldo_actual NUMERIC(12,2) DEFAULT 0,
    saldo_inicial NUMERIC(12,2) DEFAULT 0,
    moneda TEXT,
    activa BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cuentas_user ON cuentas_caja(user_id);

CREATE TABLE IF NOT EXISTS movimientos_caja (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    cuenta_id UUID NOT NULL REFERENCES cuentas_caja(id) ON DELETE CASCADE,

    tipo TEXT NOT NULL,                   -- ingreso, gasto, transferencia
    fecha DATE NOT NULL DEFAULT CURRENT_DATE,
    concepto TEXT NOT NULL,
    categoria TEXT,                       -- alquiler_oficina, suministros, marketing, gastos_personal, papeleria, software, otros
    importe NUMERIC(10,2) NOT NULL,

    -- Referencias
    factura_id UUID,
    proveedor TEXT,
    nif_proveedor TEXT,
    numero_factura TEXT,
    iva_pct NUMERIC(5,2),
    iva_importe NUMERIC(10,2),
    deducible_irpf BOOLEAN DEFAULT TRUE,

    -- Justificante
    ticket_url TEXT,
    notas TEXT,

    -- Transferencias
    cuenta_destino_id UUID,

    creado_por UUID,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mov_user ON movimientos_caja(user_id, fecha DESC);
CREATE INDEX IF NOT EXISTS idx_mov_cuenta ON movimientos_caja(cuenta_id);
CREATE INDEX IF NOT EXISTS idx_mov_categoria ON movimientos_caja(categoria);
