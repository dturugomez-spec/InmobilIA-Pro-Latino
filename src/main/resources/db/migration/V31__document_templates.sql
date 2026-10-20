-- Sprint 60: Plantillas de documentos con variables
CREATE TABLE IF NOT EXISTS plantillas_documento (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    descripcion TEXT,
    categoria TEXT NOT NULL,             -- contrato_alquiler, contrato_venta, mandato, arras, reserva, anexo, comunicacion, otros
    icono TEXT DEFAULT '📄',

    contenido_html TEXT NOT NULL,        -- HTML con {variables}
    variables_detectadas TEXT[],         -- ["lead.nombre", "propiedad.direccion", ...]

    es_publica BOOLEAN DEFAULT FALSE,    -- compartida con todo el workspace
    es_sistema BOOLEAN DEFAULT FALSE,    -- plantillas predefinidas inalterables

    veces_usada INT DEFAULT 0,
    favorita BOOLEAN DEFAULT FALSE,

    -- Para diseño visual
    margen_top NUMERIC(4,2) DEFAULT 2.0,
    margen_bottom NUMERIC(4,2) DEFAULT 2.0,
    margen_left NUMERIC(4,2) DEFAULT 2.0,
    margen_right NUMERIC(4,2) DEFAULT 2.0,
    fuente TEXT DEFAULT 'Times New Roman',
    tamano_fuente INT DEFAULT 11,
    incluir_logo BOOLEAN DEFAULT TRUE,
    incluir_pie_pagina BOOLEAN DEFAULT TRUE,

    tags TEXT[],
    notas_uso TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_plantillas_doc_user ON plantillas_documento(user_id);
CREATE INDEX IF NOT EXISTS idx_plantillas_doc_categoria ON plantillas_documento(categoria);

-- Documentos generados a partir de plantillas
CREATE TABLE IF NOT EXISTS documentos_generados (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    plantilla_id UUID REFERENCES plantillas_documento(id) ON DELETE SET NULL,

    titulo TEXT NOT NULL,
    contenido_html TEXT NOT NULL,
    variables_usadas JSONB,

    -- Referencias usadas para rellenar
    lead_id UUID,
    propiedad_id UUID,
    contrato_id UUID,
    oferta_id UUID,
    inquilino_id UUID,
    propietario_id UUID,

    -- Si ya se envió a firma
    firma_documento_id UUID REFERENCES firmas_documento(id) ON DELETE SET NULL,
    enviado_firma BOOLEAN DEFAULT FALSE,
    pdf_url TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_docs_gen_user ON documentos_generados(user_id);
