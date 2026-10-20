-- Sprint 64: Certificados de eficiencia energética (CEE)

CREATE TABLE IF NOT EXISTS certificados_energeticos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,

    -- Datos del certificado (RD 390/2021)
    numero_registro TEXT,                 -- Nº registro CCAA
    fecha_emision DATE NOT NULL,
    fecha_caducidad DATE NOT NULL,         -- emisión + 10 años (5 si E/F/G)
    fecha_visita_tecnica DATE,

    -- Calificaciones
    calificacion_consumo TEXT NOT NULL,   -- A, B, C, D, E, F, G
    consumo_kwh_m2_anio NUMERIC(7,2),
    calificacion_emisiones TEXT NOT NULL, -- A, B, C, D, E, F, G
    emisiones_kg_co2_m2_anio NUMERIC(7,2),

    -- Datos técnico certificador
    tecnico_nombre TEXT,
    tecnico_titulacion TEXT,              -- arquitecto, ingeniero, etc.
    tecnico_colegiado TEXT,
    tecnico_telefono TEXT,
    tecnico_email TEXT,
    coste NUMERIC(10,2),

    -- Documentos
    pdf_certificado_url TEXT,
    pdf_etiqueta_url TEXT,                -- etiqueta para anuncios
    xml_registro_url TEXT,

    -- Recomendaciones mejora
    medidas_mejora TEXT,                  -- aislamiento, ventanas, calefacción...
    ahorro_estimado_pct NUMERIC(5,2),
    inversion_estimada NUMERIC(10,2),

    estado TEXT DEFAULT 'vigente',        -- vigente, caducado, en_renovacion, anulado

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cee_propiedad ON certificados_energeticos(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_cee_user ON certificados_energeticos(user_id);
CREATE INDEX IF NOT EXISTS idx_cee_caducidad ON certificados_energeticos(fecha_caducidad) WHERE estado = 'vigente';
