-- Sprint 86: Extractos mensuales propietarios
CREATE TABLE IF NOT EXISTS extractos_propietario (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propietario_id UUID NOT NULL REFERENCES propietarios(id) ON DELETE CASCADE,
    periodo_inicio DATE NOT NULL,
    periodo_fin DATE NOT NULL,
    propiedades_ids UUID[],

    -- Ingresos
    total_rentas NUMERIC(12,2) DEFAULT 0,
    total_otros_ingresos NUMERIC(12,2) DEFAULT 0,
    total_ingresos NUMERIC(12,2) DEFAULT 0,

    -- Gastos
    total_comision_inmobiliaria NUMERIC(12,2) DEFAULT 0,
    total_irpf_retenido NUMERIC(12,2) DEFAULT 0,
    total_iva NUMERIC(12,2) DEFAULT 0,
    total_gastos_repercutibles NUMERIC(12,2) DEFAULT 0,
    total_seguros NUMERIC(12,2) DEFAULT 0,
    total_comunidad NUMERIC(12,2) DEFAULT 0,
    total_mantenimiento NUMERIC(12,2) DEFAULT 0,
    total_otros_gastos NUMERIC(12,2) DEFAULT 0,
    total_gastos NUMERIC(12,2) DEFAULT 0,

    -- Liquidación
    importe_liquidar NUMERIC(12,2) DEFAULT 0,
    estado TEXT DEFAULT 'pendiente',  -- pendiente, enviado, cobrado, pagado
    fecha_pago DATE,
    iban_pago TEXT,
    referencia_transferencia TEXT,

    pdf_url TEXT,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_extr_user ON extractos_propietario(user_id);
CREATE INDEX IF NOT EXISTS idx_extr_propietario ON extractos_propietario(propietario_id);
