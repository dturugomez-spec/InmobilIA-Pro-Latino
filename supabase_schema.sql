-- ============================================================================
-- InmobilIA Pro — Esquema completo para Supabase (PostgreSQL)
-- ============================================================================
-- Uso:
--   1. Abre tu proyecto en https://supabase.com
--   2. Menu izquierdo -> SQL Editor -> New query
--   3. Pega TODO este fichero y pulsa "Run"
--   4. Menu izquierdo -> Database -> Tables para verificar que se crearon
--   5. (Importante) En Settings -> API copia "Project URL" y "anon public key"
--      y pasalos al asistente para configurar el .env / application.properties
-- ============================================================================

-- Extensiones
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------------
-- USERS (autenticacion propia, NO usa supabase auth)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email       TEXT UNIQUE NOT NULL,
    password    TEXT NOT NULL,           -- bcrypt hash
    ai_credits  INTEGER NOT NULL DEFAULT 50,
    api_key     TEXT,                    -- clave OpenAI personal (opcional)
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- PROPIEDADES (cartera de activos)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS propiedades (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name              TEXT,
    address           TEXT,
    city              TEXT,
    postal_code       TEXT,
    type              TEXT,
    status            TEXT,
    area_m2           NUMERIC,
    bedrooms          INTEGER,
    bathrooms         INTEGER,
    year_built        INTEGER,
    purchase_price    NUMERIC,
    current_value     NUMERIC,
    rental_income     NUMERIC,
    monthly_expenses  NUMERIC,
    mortgage_payment  NUMERIC,
    notes             TEXT,
    created_at        TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_propiedades_user ON propiedades(user_id);

-- ---------------------------------------------------------------------------
-- INQUILINOS (CRM)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inquilinos (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT,
    email           TEXT,
    phone           TEXT,
    id_number       TEXT,
    nationality     TEXT,
    property_name   TEXT,
    rent_amount     NUMERIC,
    deposit         NUMERIC,
    lease_start     DATE,
    lease_end       DATE,
    status          TEXT,
    guarantor_name  TEXT,
    guarantor_phone TEXT,
    has_insurance   BOOLEAN DEFAULT FALSE,
    score           INTEGER,
    notes           TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_inquilinos_user ON inquilinos(user_id);

-- ---------------------------------------------------------------------------
-- CONTRATOS
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contratos (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    property_name  TEXT,
    tenant_name    TEXT,
    start_date     DATE,
    end_date       DATE,
    rent           NUMERIC,
    deposit        NUMERIC,
    ipc_revision   BOOLEAN DEFAULT FALSE,
    status         TEXT,
    terms          TEXT,
    signed_date    DATE,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_contratos_user ON contratos(user_id);

-- ---------------------------------------------------------------------------
-- TRANSACCIONES (ingresos + gastos)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transacciones (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            TEXT,                     -- ingreso | gasto
    category        TEXT,
    amount          NUMERIC,
    date            DATE,
    property_name   TEXT,
    description     TEXT,
    invoice_number  TEXT,
    vat_pct         NUMERIC,
    deductible      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_transacciones_user ON transacciones(user_id);
CREATE INDEX IF NOT EXISTS idx_transacciones_date ON transacciones(date);

-- ---------------------------------------------------------------------------
-- TAREAS
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tareas (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title          TEXT,
    description    TEXT,
    priority       INTEGER,
    assigned_to    TEXT,
    due_date       DATE,
    status         TEXT,
    property_name  TEXT,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tareas_user ON tareas(user_id);

-- ---------------------------------------------------------------------------
-- MANTENIMIENTO
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mantenimiento (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           TEXT,
    description     TEXT,
    property_name   TEXT,
    priority        INTEGER,
    status          TEXT,
    assigned_to     TEXT,
    cost            NUMERIC,
    scheduled_date  DATE,
    completed_date  DATE,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_mantenimiento_user ON mantenimiento(user_id);

-- ---------------------------------------------------------------------------
-- PERSONAL (RRHH)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS personal (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT,
    role        TEXT,
    email       TEXT,
    phone       TEXT,
    salary      NUMERIC,
    start_date  DATE,
    status      TEXT,
    notes       TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_personal_user ON personal(user_id);

-- ---------------------------------------------------------------------------
-- EVENTOS (agenda)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eventos (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title          TEXT,
    date           DATE,
    time           TIME,
    type           TEXT,
    description    TEXT,
    property_name  TEXT,
    attendees      TEXT,
    location       TEXT,
    reminder_days  INTEGER,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_eventos_user ON eventos(user_id);

-- ---------------------------------------------------------------------------
-- DOCUMENTOS
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS documentos (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name           TEXT,
    doc_type       TEXT,
    property_name  TEXT,
    url            TEXT,
    file_size_kb   INTEGER,
    expiry_date    DATE,
    notes          TEXT,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_documentos_user ON documentos(user_id);

-- ---------------------------------------------------------------------------
-- RESERVAS (visitas)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reservas (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_name    TEXT,
    client_phone   TEXT,
    client_email   TEXT,
    property_name  TEXT,
    date           DATE,
    time           TIME,
    type           TEXT,
    status         TEXT,
    agent_name     TEXT,
    feedback       TEXT,
    notes          TEXT,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_reservas_user ON reservas(user_id);

-- ---------------------------------------------------------------------------
-- SEGUROS
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seguros (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type           TEXT,
    company        TEXT,
    policy_number  TEXT,
    property_name  TEXT,
    annual_cost    NUMERIC,
    coverage       NUMERIC,
    start_date     DATE,
    expiry_date    DATE,
    auto_renew     BOOLEAN DEFAULT FALSE,
    contact_phone  TEXT,
    notes          TEXT,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_seguros_user ON seguros(user_id);

-- ---------------------------------------------------------------------------
-- OPORTUNIDADES (deal flow)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oportunidades (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    property_name  TEXT,
    address        TEXT,
    city           TEXT,
    price          NUMERIC,
    cap_rate       NUMERIC,
    gross_yield    NUMERIC,
    area_m2        NUMERIC,
    type           TEXT,
    status         TEXT,
    source         TEXT,
    contact        TEXT,
    expected_irr   NUMERIC,
    priority       INTEGER,
    notes          TEXT,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_oportunidades_user ON oportunidades(user_id);

-- ---------------------------------------------------------------------------
-- LEADS
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS leads (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nombre         TEXT,
    email          TEXT,
    telefono       TEXT,
    origen         TEXT,
    interes        TEXT,
    presupuesto    NUMERIC,
    zona           TEXT,
    tipo_activo    TEXT,
    etapa          TEXT,
    score          INTEGER,
    notas          TEXT,
    next_followup  DATE,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_leads_user ON leads(user_id);

-- ---------------------------------------------------------------------------
-- EMAIL TEMPLATES
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS email_templates (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nombre      TEXT,
    asunto      TEXT,
    cuerpo      TEXT,
    categoria   TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_email_templates_user ON email_templates(user_id);

-- ---------------------------------------------------------------------------
-- ANALISIS FINANCIEROS (biblioteca DCF / MonteCarlo / Stress)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS analisis_financieros (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tipo           TEXT,                 -- DCF | MonteCarlo | Stress | Yield | ...
    nombre         TEXT,
    property_name  TEXT,
    inputs         JSONB,                -- parametros de entrada
    outputs        JSONB,                -- resultados
    notes          TEXT,
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_analisis_user ON analisis_financieros(user_id);

-- ---------------------------------------------------------------------------
-- SMTP CONFIG (por usuario)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS smtp_config (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    server      TEXT,
    port        INTEGER DEFAULT 587,
    username    TEXT,
    password    TEXT,
    from_name   TEXT DEFAULT 'InmobilIA Pro',
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- AI USAGE (tracking consumo IA)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_usage (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature     TEXT,                 -- analyzer, scoring, memo, etc.
    tokens_in   INTEGER,
    tokens_out  INTEGER,
    cost        NUMERIC,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_user ON ai_usage(user_id);

-- ---------------------------------------------------------------------------
-- Row Level Security: desactivado porque la app valida user_id en codigo
-- (la clave anon/service es privada del servidor, no del cliente).
-- Si alguna vez expones la anon key al cliente, habilita RLS en TODAS
-- las tablas con polizas `user_id = auth.uid()`.
-- ---------------------------------------------------------------------------
-- Por defecto dejamos RLS desactivado en estas tablas.
ALTER TABLE users                DISABLE ROW LEVEL SECURITY;
ALTER TABLE propiedades          DISABLE ROW LEVEL SECURITY;
ALTER TABLE inquilinos           DISABLE ROW LEVEL SECURITY;
ALTER TABLE contratos            DISABLE ROW LEVEL SECURITY;
ALTER TABLE transacciones        DISABLE ROW LEVEL SECURITY;
ALTER TABLE tareas               DISABLE ROW LEVEL SECURITY;
ALTER TABLE mantenimiento        DISABLE ROW LEVEL SECURITY;
ALTER TABLE personal             DISABLE ROW LEVEL SECURITY;
ALTER TABLE eventos              DISABLE ROW LEVEL SECURITY;
ALTER TABLE documentos           DISABLE ROW LEVEL SECURITY;
ALTER TABLE reservas             DISABLE ROW LEVEL SECURITY;
ALTER TABLE seguros              DISABLE ROW LEVEL SECURITY;
ALTER TABLE oportunidades        DISABLE ROW LEVEL SECURITY;
ALTER TABLE leads                DISABLE ROW LEVEL SECURITY;
ALTER TABLE email_templates      DISABLE ROW LEVEL SECURITY;
ALTER TABLE analisis_financieros DISABLE ROW LEVEL SECURITY;
ALTER TABLE smtp_config          DISABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage             DISABLE ROW LEVEL SECURITY;

-- ============================================================================
-- FIN
-- ============================================================================
