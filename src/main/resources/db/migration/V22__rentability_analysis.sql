-- Sprint 51: Análisis de rentabilidad de propiedades
CREATE TABLE IF NOT EXISTS analisis_rentabilidad (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,

    -- Inversión
    precio_compra NUMERIC(12,2) NOT NULL,
    gastos_compra NUMERIC(10,2) DEFAULT 0,          -- ITP/IVA, notaría, registro, gestoría
    reformas NUMERIC(10,2) DEFAULT 0,
    mobiliario NUMERIC(10,2) DEFAULT 0,

    -- Financiación
    hipoteca BOOLEAN DEFAULT FALSE,
    capital_hipoteca NUMERIC(12,2),
    tipo_interes NUMERIC(5,3),
    plazo_anos INT,
    cuota_mensual NUMERIC(10,2),

    -- Ingresos
    renta_mensual NUMERIC(10,2) NOT NULL,
    incremento_renta_anual NUMERIC(5,2) DEFAULT 2.0,  -- % IPC anual

    -- Gastos anuales
    ibi NUMERIC(10,2) DEFAULT 0,
    comunidad NUMERIC(10,2) DEFAULT 0,                -- gasto mensual
    seguro_hogar NUMERIC(10,2) DEFAULT 0,
    seguro_impago NUMERIC(10,2) DEFAULT 0,
    mantenimiento NUMERIC(10,2) DEFAULT 0,             -- % del valor o fijo
    gestion NUMERIC(10,2) DEFAULT 0,                   -- gestoría/admin (% renta)
    otros_gastos NUMERIC(10,2) DEFAULT 0,
    tasa_vacancia NUMERIC(5,2) DEFAULT 5.0,            -- % meses sin alquilar

    -- Proyección
    revalorizacion_anual NUMERIC(5,2) DEFAULT 3.0,    -- % apreciación
    horizonte_anos INT DEFAULT 10,
    inflacion NUMERIC(5,2) DEFAULT 2.0,

    -- Resultados cacheados (para listado rápido)
    rentabilidad_bruta NUMERIC(5,2),
    rentabilidad_neta NUMERIC(5,2),
    cash_on_cash NUMERIC(5,2),
    tir_estimada NUMERIC(5,2),

    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_analisis_rent_user ON analisis_rentabilidad(user_id);
CREATE INDEX IF NOT EXISTS idx_analisis_rent_prop ON analisis_rentabilidad(propiedad_id);
