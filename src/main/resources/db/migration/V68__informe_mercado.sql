-- Sprint diferenciación: Informe de Mercado / CMA (Comparative Market Analysis)
-- El documento profesional que gana la exclusiva: precio recomendado + comparables
-- reales de la cartera + análisis de zona generado por IA, en un informe de marca
-- compartible. Cierra el embudo tras Farming → Valoración → Portal.

CREATE TABLE IF NOT EXISTS informes_mercado (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    token TEXT UNIQUE,

    -- Inmueble objeto del informe (snapshot)
    titulo TEXT,
    tipo TEXT,
    municipio TEXT,
    provincia TEXT,
    metros NUMERIC(8,2),
    habitaciones INT,
    estado TEXT,

    -- Resultado
    precio_recomendado NUMERIC(12,2),
    precio_min NUMERIC(12,2),
    precio_max NUMERIC(12,2),
    eur_m2 NUMERIC(8,2),
    confianza TEXT,

    -- Comparables y análisis
    comparables_json TEXT,           -- lista de comparables usados (JSON)
    n_comparables INT DEFAULT 0,
    eur_m2_zona NUMERIC(8,2),        -- media €/m² de la zona (cartera)
    dias_medios_venta INT,
    narrativa TEXT,                  -- análisis de zona generado por IA

    propietario_nombre TEXT,
    vistas INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_informes_user ON informes_mercado(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_informes_token ON informes_mercado(token) WHERE token IS NOT NULL;
