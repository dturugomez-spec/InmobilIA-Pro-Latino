-- Sprint 59: Pipelines Kanban + ofertas formales
CREATE TABLE IF NOT EXISTS pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    tipo TEXT NOT NULL,                  -- leads, propiedades, ofertas, captacion
    descripcion TEXT,
    es_default BOOLEAN DEFAULT FALSE,
    columnas JSONB NOT NULL,             -- [{"key": "nuevo", "label": "Nuevo", "color": "#3b82f6", "orden": 1}, ...]
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pipelines_user ON pipelines(user_id);

-- Tarjetas en el kanban (referencia genérica)
CREATE TABLE IF NOT EXISTS pipeline_cards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE CASCADE,
    columna_key TEXT NOT NULL,
    orden INT DEFAULT 0,

    -- Referencias (uno será NOT NULL)
    lead_id UUID,
    propiedad_id UUID,
    oferta_id UUID,
    contrato_id UUID,

    titulo TEXT,                          -- Cache para mostrar
    subtitulo TEXT,
    valor NUMERIC(12,2),                  -- importe potencial
    probabilidad NUMERIC(5,2),            -- % cierre

    asignado_a UUID,
    color_etiqueta TEXT,
    fecha_limite DATE,

    -- Timeline
    fecha_entrada_columna TIMESTAMPTZ DEFAULT NOW(),
    movimientos JSONB DEFAULT '[]'::jsonb,  -- historial cambios columna

    notas TEXT,
    archivada BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cards_pipeline ON pipeline_cards(pipeline_id, columna_key, orden);
CREATE INDEX IF NOT EXISTS idx_cards_user ON pipeline_cards(user_id);

-- Ofertas formales
CREATE TABLE IF NOT EXISTS ofertas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    lead_id UUID,
    visita_id UUID,

    numero_oferta TEXT,                   -- OF-2026-001
    importe NUMERIC(12,2) NOT NULL,
    importe_inicial NUMERIC(12,2),       -- antes de negociar
    moneda TEXT,
    forma_pago TEXT,                      -- hipoteca, contado, hipoteca+ahorros
    condiciones TEXT,

    nombre_ofertante TEXT,
    email_ofertante TEXT,
    telefono_ofertante TEXT,
    dni_ofertante TEXT,

    estado TEXT DEFAULT 'nueva',          -- nueva, en_negociacion, contraoferta, aceptada, rechazada, retirada, caducada
    fecha_oferta DATE DEFAULT CURRENT_DATE,
    fecha_caducidad DATE,
    fecha_respuesta TIMESTAMPTZ,
    respuesta_propietario TEXT,

    contraoferta_importe NUMERIC(12,2),
    contraoferta_at TIMESTAMPTZ,

    deposito_arras NUMERIC(10,2),
    plazo_firma_dias INT DEFAULT 30,

    documento_pdf_url TEXT,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ofertas_user ON ofertas(user_id);
CREATE INDEX IF NOT EXISTS idx_ofertas_propiedad ON ofertas(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_ofertas_estado ON ofertas(estado);
