-- ═══════════════════════════════════════════════════════════════════════════
--  MIGRACION · INMOBILIA PRO · HERRAMIENTAS DIFERENCIADORAS
--  Fecha: 2026-04-25
--  Esta migracion es OPCIONAL. Sin ella las herramientas funcionan con
--  datos baked-in y/o las columnas existentes (purchase_price, rental_income,
--  monthly_expenses, mortgage_payment).
--
--  Se recomienda aplicarla para que el STRESS TEST DE CARTERA y el AUDITOR IA
--  trabajen con maxima precision (OPEX granular, hipoteca detallada, datos
--  fiscales por CCAA, contratos con IRAV/IPC, seguros con vencimiento).
--
--  Es 100% IDEMPOTENTE: usa "ADD COLUMN IF NOT EXISTS" en todas partes.
--  Puedes ejecutarla varias veces sin riesgo.
-- ═══════════════════════════════════════════════════════════════════════════

-- ─── 1. PROPIEDADES · OPEX granular y datos hipotecarios ───
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS ibi_anual                  NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS comunidad_mes              NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS seguro_anual               NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS mantenimiento_anual        NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS basuras_anual              NUMERIC;

-- Hipoteca detallada (para stress test mas preciso)
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_capital_inicial   NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_capital_pendiente NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_tipo              NUMERIC;          -- % anual
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_tipo_modalidad    TEXT;             -- 'FIJO' | 'VARIABLE' | 'MIXTO'
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_diferencial       NUMERIC;          -- spread sobre Euribor
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_anios_restantes   NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_banco             TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS hipoteca_proxima_revision  DATE;

-- Datos fiscales (para Comparador CCAA personalizado)
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS ccaa                       TEXT;             -- 'Madrid', 'Cataluna', etc.
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS provincia                  TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS municipio                  TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS zona_tensionada            BOOLEAN DEFAULT FALSE;

-- Catastro y referencia oficial (para Auditor IA y Datos Oficiales)
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS referencia_catastral       TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS valor_catastral            NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS cee_calificacion           TEXT;             -- 'A','B','C','D','E','F','G'
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS cee_fecha_emision          DATE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS cee_fecha_caducidad        DATE;

-- Compras y ventas (para Audit ROI)
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS fecha_compra               DATE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS gastos_compra              NUMERIC;          -- ITP + notaria + registro

-- Geolocalizacion (para Subastas BOE Hunter cruzado)
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS lat                        NUMERIC;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS lon                        NUMERIC;

-- ─── 2. CONTRATOS · IRAV/IPC + zona tensionada + indice MITMA ───
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS property_id                  UUID REFERENCES propiedades(id) ON DELETE SET NULL;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS tenant_id                    UUID REFERENCES inquilinos(id) ON DELETE SET NULL;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS irav_aplicado                BOOLEAN DEFAULT FALSE;   -- Indice Referencia Arrendamiento Vivienda
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS indice_actualizacion         TEXT;                   -- 'IRAV' | 'IPC' | 'INDICE_MITMA' | 'NINGUNO'
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS ultima_actualizacion         DATE;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS proxima_actualizacion        DATE;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS porcentaje_actualizacion     NUMERIC;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS renta_inicial                NUMERIC;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS m2                           NUMERIC;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS bajo_zona_tensionada         BOOLEAN DEFAULT FALSE;

-- ─── 3. SEGUROS · datos para Auditor IA (vencimientos, coberturas) ───
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS property_id                    UUID REFERENCES propiedades(id) ON DELETE CASCADE;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS tipo                           TEXT;             -- 'HOGAR','IMPAGO','RC','MULTIRRIESGO'
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS aseguradora                    TEXT;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS poliza                         TEXT;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS prima_anual                    NUMERIC;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS fecha_inicio                   DATE;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS fecha_vencimiento              DATE;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS auto_renueva                   BOOLEAN DEFAULT TRUE;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS cobertura_eur                  NUMERIC;

-- ─── 4. INQUILINOS · scoring y morosidad para Auditor IA ───
ALTER TABLE inquilinos ADD COLUMN IF NOT EXISTS scoring_credit              INTEGER;          -- 0-100
ALTER TABLE inquilinos ADD COLUMN IF NOT EXISTS dias_morosidad              INTEGER;          -- dias acumulados sin pagar
ALTER TABLE inquilinos ADD COLUMN IF NOT EXISTS deuda_acumulada             NUMERIC;
ALTER TABLE inquilinos ADD COLUMN IF NOT EXISTS impagos_count               INTEGER DEFAULT 0;

-- ─── 5. NUEVA TABLA: alertas_subastas (para suscripciones BOE Hunter) ───
CREATE TABLE IF NOT EXISTS alertas_subastas (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    nombre          TEXT NOT NULL,
    tipo_bien       TEXT,                    -- VIVIENDA, LOCAL, NAVE, SUELO...
    provincia       TEXT,
    municipio       TEXT,
    precio_min      NUMERIC,
    precio_max      NUMERIC,
    descuento_min_pct NUMERIC,
    keywords        TEXT,
    activa          BOOLEAN DEFAULT TRUE,
    notificar_email BOOLEAN DEFAULT TRUE,
    creada_at       TIMESTAMPTZ DEFAULT NOW(),
    ultima_ejecucion TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_alertas_subastas_user ON alertas_subastas(user_id);
ALTER TABLE alertas_subastas DISABLE ROW LEVEL SECURITY;

-- ─── 6. NUEVA TABLA: stress_test_snapshots (historico de stress tests) ───
CREATE TABLE IF NOT EXISTS stress_test_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             UUID NOT NULL,
    fecha               TIMESTAMPTZ DEFAULT NOW(),
    euribor_base        NUMERIC,
    rentas_anuales      NUMERIC,
    opex_anual          NUMERIC,
    deuda_anual         NUMERIC,
    dscr_base           NUMERIC,
    dscr_severo         NUMERIC,
    dscr_apocaliptico   NUMERIC,
    props_total         INTEGER,
    props_rojo_severo   INTEGER,
    veredicto           TEXT,
    payload_json        JSONB                 -- snapshot completo
);
CREATE INDEX IF NOT EXISTS idx_stress_snapshots_user ON stress_test_snapshots(user_id);
ALTER TABLE stress_test_snapshots DISABLE ROW LEVEL SECURITY;

-- ─── 7. NUEVA TABLA: benchmark_renta_logs (auditoria Ley 12/2023) ───
CREATE TABLE IF NOT EXISTS benchmark_renta_logs (
    id                BIGSERIAL PRIMARY KEY,
    user_id           UUID NOT NULL,
    property_id       UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    fecha             TIMESTAMPTZ DEFAULT NOW(),
    provincia         TEXT,
    municipio         TEXT,
    m2                NUMERIC,
    renta_mes         NUMERIC,
    indice_m2         NUMERIC,
    diff_pct          NUMERIC,
    posicion          TEXT,                  -- BAJO/EN_LINEA/ALTO/MUY_ALTO
    zona_tensionada   BOOLEAN,
    tope_legal        NUMERIC,
    veredicto         TEXT
);
CREATE INDEX IF NOT EXISTS idx_benchmark_renta_user ON benchmark_renta_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_benchmark_renta_prop ON benchmark_renta_logs(property_id);
ALTER TABLE benchmark_renta_logs DISABLE ROW LEVEL SECURITY;

-- ─── 8. INDICES utiles para queries analiticos ───
CREATE INDEX IF NOT EXISTS idx_propiedades_ccaa         ON propiedades(ccaa);
CREATE INDEX IF NOT EXISTS idx_propiedades_provincia    ON propiedades(provincia);
CREATE INDEX IF NOT EXISTS idx_propiedades_zona_tension ON propiedades(zona_tensionada) WHERE zona_tensionada = TRUE;
CREATE INDEX IF NOT EXISTS idx_seguros_vencimiento      ON seguros(fecha_vencimiento);
CREATE INDEX IF NOT EXISTS idx_contratos_proxima_actu   ON contratos(proxima_actualizacion);
CREATE INDEX IF NOT EXISTS idx_contratos_end_date       ON contratos(end_date);

-- ═══════════════════════════════════════════════════════════════════════════
--  FIN MIGRACION
-- ═══════════════════════════════════════════════════════════════════════════
