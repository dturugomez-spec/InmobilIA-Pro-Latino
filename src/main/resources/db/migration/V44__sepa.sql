-- Sprint 75: SEPA Direct Debit (Adeudos directos)
CREATE TABLE IF NOT EXISTS sepa_creditor (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    identificador_acreedor TEXT NOT NULL,  -- ES{checksum}{NIF} - 13 caracteres
    nif TEXT NOT NULL,
    iban TEXT NOT NULL,
    bic TEXT,
    direccion TEXT,
    cp TEXT,
    municipio TEXT,
    provincia TEXT,
    pais TEXT DEFAULT 'ES',
    siguiente_numero_remesa INT DEFAULT 1,
    sufijo_acreedor TEXT DEFAULT '000',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Mandatos de cobro firmados por cada deudor
CREATE TABLE IF NOT EXISTS sepa_mandatos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    referencia_mandato TEXT UNIQUE NOT NULL,  -- 35 chars
    contrato_id UUID REFERENCES contratos(id) ON DELETE SET NULL,
    inquilino_id UUID REFERENCES inquilinos(id) ON DELETE SET NULL,

    deudor_nombre TEXT NOT NULL,
    deudor_nif TEXT,
    deudor_iban TEXT NOT NULL,
    deudor_bic TEXT,
    deudor_direccion TEXT,
    deudor_email TEXT,

    tipo TEXT DEFAULT 'recurrente',     -- recurrente, unico
    secuencia TEXT DEFAULT 'FRST',      -- FRST (first), RCUR (recurring), FNAL (final), OOFF (one-off)

    estado TEXT DEFAULT 'activo',       -- activo, suspendido, cancelado
    fecha_firma DATE NOT NULL DEFAULT CURRENT_DATE,
    firma_documento_id UUID,            -- vínculo a firma electrónica

    importe NUMERIC(10,2),               -- por defecto
    frecuencia TEXT,                     -- mensual, trimestral, anual
    dia_cobro INT DEFAULT 5,             -- día del mes
    proxima_emision DATE,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sepa_mand_user ON sepa_mandatos(user_id);
CREATE INDEX IF NOT EXISTS idx_sepa_mand_ref ON sepa_mandatos(referencia_mandato);

-- Remesas (lotes de cobros)
CREATE TABLE IF NOT EXISTS sepa_remesas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    numero TEXT NOT NULL,                -- REM-2026-0001
    fecha_cobro DATE NOT NULL,
    secuencia TEXT,

    total NUMERIC(12,2),
    num_adeudos INT,

    estado TEXT DEFAULT 'pendiente',     -- pendiente, generada, enviada_banco, cobrada, parcial, rechazada
    xml_url TEXT,                         -- archivo pain.008
    msg_id TEXT,
    enviada_at TIMESTAMPTZ,
    cobrada_at TIMESTAMPTZ,

    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sepa_rem_user ON sepa_remesas(user_id);

CREATE TABLE IF NOT EXISTS sepa_adeudos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    remesa_id UUID NOT NULL REFERENCES sepa_remesas(id) ON DELETE CASCADE,
    mandato_id UUID REFERENCES sepa_mandatos(id) ON DELETE SET NULL,
    factura_id UUID,

    end_to_end_id TEXT NOT NULL,
    importe NUMERIC(10,2) NOT NULL,
    concepto TEXT,

    deudor_nombre TEXT,
    deudor_iban TEXT,

    estado TEXT DEFAULT 'pendiente',     -- pendiente, cobrado, rechazado, devuelto
    fecha_cobro DATE,
    motivo_rechazo TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sepa_ade_remesa ON sepa_adeudos(remesa_id);
