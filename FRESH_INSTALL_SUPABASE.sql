-- ============================================================
-- InmobilIA Pro · INSTALACIÓN LIMPIA en un proyecto Supabase NUEVO
-- Generado: 2026-08-19 23:55
-- Pegar TODO en Supabase → SQL Editor → Run.
-- = FULL_SCHEMA_COMPLETO (núcleo ≤V62) + migraciones V63…V79
-- ============================================================

-- ############## BASE (núcleo + features ≤ V62) ##############
-- ============================================================
-- InmobilIA Pro · SQL COMPLETO v8 (audit_log unificado)
-- ============================================================

-- 1. SCHEMA BASE
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
ALTER TABLE mantenimiento        DISABLE ROW LEVEL SECURITY;
ALTER TABLE personal             DISABLE ROW LEVEL SECURITY;
ALTER TABLE eventos              DISABLE ROW LEVEL SECURITY;
ALTER TABLE documentos           DISABLE ROW LEVEL SECURITY;
ALTER TABLE reservas             DISABLE ROW LEVEL SECURITY;
ALTER TABLE oportunidades        DISABLE ROW LEVEL SECURITY;
ALTER TABLE leads                DISABLE ROW LEVEL SECURITY;
ALTER TABLE email_templates      DISABLE ROW LEVEL SECURITY;
ALTER TABLE analisis_financieros DISABLE ROW LEVEL SECURITY;
ALTER TABLE smtp_config          DISABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage             DISABLE ROW LEVEL SECURITY;

-- ============================================================================
-- FIN
-- ============================================================================

-- 2. PRE-FIX columnas
ALTER TABLE leads ADD COLUMN IF NOT EXISTS estado TEXT DEFAULT 'nuevo';
ALTER TABLE leads ADD COLUMN IF NOT EXISTS dni TEXT;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS verificado BOOLEAN DEFAULT FALSE;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS ocupacion TEXT;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS plazo_decision TEXT;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS habitaciones INT;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS presupuesto_max NUMERIC(12,2);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS asignado_a UUID;

ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS publicar_portales BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS destacada BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS referencia TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS estado TEXT DEFAULT 'activa';
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS operacion TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS tipo TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS habitaciones INT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS banos INT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS metros_cuadrados NUMERIC(7,2);
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS precio NUMERIC(12,2);
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS direccion TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS lat NUMERIC(10,7);
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS lng NUMERIC(10,7);
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS garaje BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS ascensor BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS terraza BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS piscina BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS amueblado BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS aire_acondicionado BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS eficiencia_energetica TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS propietario_id UUID;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS descripcion TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS fotos JSONB DEFAULT '[]'::jsonb;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS uso_catastral TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS ano_construccion INT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS codigo_postal TEXT;

ALTER TABLE inquilinos ADD COLUMN IF NOT EXISTS dni TEXT;

ALTER TABLE contratos ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS estado TEXT DEFAULT 'activo';
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS numero TEXT;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS fecha_inicio DATE;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS fecha_fin DATE;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS renta_mensual NUMERIC(10,2);
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS fianza NUMERIC(10,2);
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS fianza_devuelta BOOLEAN DEFAULT FALSE;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS inquilino_id UUID;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS propiedad_id UUID;

CREATE TABLE IF NOT EXISTS propietarios (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT,
    dni TEXT,
    email TEXT,
    telefono TEXT,
    direccion TEXT,
    iban TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS empresa TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS telefono TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS nif TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS direccion TEXT;

-- 3. MIGRACIONES SECUNDARIAS (sin definir audit_log)
-- --- migration_invoices.sql ---
-- ============================================================================
-- InmobilIA Pro · Migración facturación ES (Sprint 6)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

CREATE TABLE IF NOT EXISTS invoices (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    serie             TEXT DEFAULT 'A',
    numero            INTEGER NOT NULL,
    full_number       TEXT GENERATED ALWAYS AS (serie || '/' || lpad(numero::text, 5, '0')) STORED,
    fecha             DATE NOT NULL DEFAULT CURRENT_DATE,
    vencimiento       DATE,
    -- Emisor (de tu propia config)
    emisor_nombre     TEXT,
    emisor_nif        TEXT,
    emisor_direccion  TEXT,
    emisor_email      TEXT,
    emisor_iban       TEXT,
    -- Cliente
    cliente_nombre    TEXT NOT NULL,
    cliente_nif       TEXT,
    cliente_direccion TEXT,
    cliente_email     TEXT,
    -- Importes
    concepto          TEXT,
    base_imponible    NUMERIC(12,2) NOT NULL DEFAULT 0,
    iva_pct           NUMERIC(5,2) DEFAULT 21,
    iva_amount        NUMERIC(12,2) DEFAULT 0,
    irpf_pct          NUMERIC(5,2) DEFAULT 0,        -- Retención IRPF (típico 19% para alquiler)
    irpf_amount       NUMERIC(12,2) DEFAULT 0,
    total             NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Líneas (JSONB array: [{descripcion, cantidad, precio, base, iva_pct}])
    lineas            JSONB DEFAULT '[]'::jsonb,
    -- Estado
    status            TEXT DEFAULT 'Borrador',     -- Borrador, Emitida, Pagada, Vencida, Anulada
    fecha_pago        DATE,
    metodo_pago       TEXT,
    notas             TEXT,
    -- Vinculaciones
    property_name     TEXT,
    tenant_name       TEXT,
    contract_id       UUID REFERENCES contratos(id) ON DELETE SET NULL,
    -- Auditoría
    created_at        TIMESTAMPTZ DEFAULT NOW(),
    updated_at        TIMESTAMPTZ DEFAULT NOW(),
    created_by        UUID,
    updated_by        UUID
);

CREATE INDEX IF NOT EXISTS idx_invoices_user    ON invoices(user_id);
CREATE INDEX IF NOT EXISTS idx_invoices_status  ON invoices(status);
CREATE INDEX IF NOT EXISTS idx_invoices_fecha   ON invoices(fecha);
CREATE INDEX IF NOT EXISTS idx_invoices_serie   ON invoices(user_id, serie, numero);
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_seq ON invoices(user_id, serie, numero);

-- Auto-numerar (siguiente número de la serie por usuario)
CREATE OR REPLACE FUNCTION invoices_auto_number()
RETURNS TRIGGER AS $$
DECLARE
    next_num INTEGER;
BEGIN
    IF NEW.numero IS NULL OR NEW.numero = 0 THEN
        SELECT COALESCE(MAX(numero), 0) + 1 INTO next_num
          FROM invoices
         WHERE user_id = NEW.user_id AND serie = NEW.serie;
        NEW.numero := next_num;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_invoices_auto_number ON invoices;
CREATE TRIGGER trg_invoices_auto_number
    BEFORE INSERT ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_auto_number();

-- Verificación
SELECT 'Tabla invoices creada' AS info, count(*) AS facturas FROM invoices;

-- --- migration_verifactu.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 21: Verifactu + perfil empresa + recibos
-- RD 1007/2023 · Sistema de facturación verificable AEAT
-- ============================================================================

-- ─── Verifactu en facturas ───
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_hash       TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_prev_hash  TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_qr_payload TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS verifactu_chain_id   INTEGER;

CREATE INDEX IF NOT EXISTS idx_invoices_verifactu ON invoices(verifactu_chain_id);

-- ─── Perfil de empresa (configuración del admin/organización) ───
CREATE TABLE IF NOT EXISTS business_profile (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id          UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    -- Datos fiscales
    legal_name      TEXT,                         -- Razón social
    tax_id          TEXT,                         -- NIF/CIF
    address         TEXT,
    city            TEXT,
    postal_code     TEXT,
    province        TEXT,
    country         TEXT DEFAULT 'España',
    iban            TEXT,
    -- Contacto
    contact_name    TEXT,
    contact_email   TEXT,
    contact_phone   TEXT,
    website         TEXT,
    -- Branding
    logo_url        TEXT,                         -- URL del logo
    brand_color     TEXT DEFAULT '#1e40af',       -- color primario
    -- Horario y otros
    business_hours  TEXT,
    notes_invoice   TEXT,                         -- Notas legales por defecto en factura
    receipt_footer  TEXT,                         -- Pie por defecto en recibos
    -- Verifactu
    verifactu_seq   INTEGER DEFAULT 0,            -- secuencia para chain_id
    -- Auditoría
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Recibos de alquiler (sin IVA, distintos a facturas) ───
CREATE TABLE IF NOT EXISTS receipts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    serie           TEXT DEFAULT 'R',
    numero          INTEGER NOT NULL,
    full_number     TEXT GENERATED ALWAYS AS (serie || '/' || lpad(numero::text, 5, '0')) STORED,
    -- Período
    period_year     INTEGER,
    period_month    INTEGER,
    fecha_emision   DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_pago      DATE,
    -- Emisor (arrendador)
    emisor_nombre   TEXT,
    emisor_nif      TEXT,
    emisor_direccion TEXT,
    -- Receptor (arrendatario)
    inquilino_nombre TEXT NOT NULL,
    inquilino_nif    TEXT,
    inquilino_direccion TEXT,
    -- Inmueble + concepto
    property_name   TEXT,
    property_ref    TEXT,
    catastro_ref    TEXT,
    concepto        TEXT,
    -- Importes
    importe         NUMERIC(12,2) NOT NULL,
    irpf_pct        NUMERIC(5,2) DEFAULT 19,      -- retención típica del arrendador persona física
    irpf_amount     NUMERIC(12,2),
    total_pagado    NUMERIC(12,2),
    metodo_pago     TEXT,
    -- Estado
    status          TEXT DEFAULT 'Emitido',       -- Emitido, Pagado, Anulado
    notas           TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_receipts_user   ON receipts(user_id);
CREATE INDEX IF NOT EXISTS idx_receipts_period ON receipts(period_year, period_month);
CREATE UNIQUE INDEX IF NOT EXISTS uq_receipts_seq ON receipts(user_id, serie, numero);

-- Auto-numeración recibos
CREATE OR REPLACE FUNCTION receipts_auto_number()
RETURNS TRIGGER AS $$
DECLARE
    next_num INTEGER;
BEGIN
    IF NEW.numero IS NULL OR NEW.numero = 0 THEN
        SELECT COALESCE(MAX(numero), 0) + 1 INTO next_num
          FROM receipts
         WHERE user_id = NEW.user_id AND serie = NEW.serie;
        NEW.numero := next_num;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_receipts_auto_number ON receipts;
CREATE TRIGGER trg_receipts_auto_number
    BEFORE INSERT ON receipts
    FOR EACH ROW EXECUTE FUNCTION receipts_auto_number();

-- ─── Modelos AEAT extendidos: 184 (CB) y 347 ───
-- (Solo se añaden columnas necesarias en invoices/transacciones para luego generar)
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS for_modelo184 BOOLEAN DEFAULT false;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS for_modelo347 BOOLEAN DEFAULT false;

SELECT 'Migration verifactu + business + receipts completed' AS status;

-- --- migration_commissions.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 40: Comisiones comerciales + clonar propiedades
-- ============================================================================

-- Comisiones generadas por venta o alquiler
CREATE TABLE IF NOT EXISTS commissions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id          UUID NOT NULL,
    agent_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    agent_name      TEXT,
    operation_type  TEXT NOT NULL,                  -- 'venta', 'alquiler', 'gestión'
    property_id     UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    property_name   TEXT,
    client_name     TEXT,
    operation_value NUMERIC(12,2) NOT NULL,          -- ej. precio venta o renta anual
    commission_pct  NUMERIC(5,2) NOT NULL,           -- % de la comisión
    commission_amount NUMERIC(12,2) GENERATED ALWAYS AS (operation_value * commission_pct / 100) STORED,
    operation_date  DATE,
    status          TEXT DEFAULT 'Pendiente',        -- Pendiente, Pagada, Anulada
    paid_date       DATE,
    notes           TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_comm_org    ON commissions(org_id, operation_date DESC);
CREATE INDEX IF NOT EXISTS idx_comm_agent  ON commissions(agent_user_id);

-- Targets/objetivos por comercial
CREATE TABLE IF NOT EXISTS sales_targets (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id        UUID NOT NULL,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    year          INTEGER NOT NULL,
    month         INTEGER,                          -- null = anual
    target_amount NUMERIC(12,2),                    -- € de operaciones
    target_units  INTEGER,                          -- nº de cierres
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_targets_user_period ON sales_targets(user_id, year, month);

-- Multi-IBAN en business_profile (mejora)
ALTER TABLE business_profile ADD COLUMN IF NOT EXISTS iban_alt JSONB DEFAULT '[]'::jsonb;
-- Estructura: [{"name": "Cuenta principal", "iban": "ES00..."}, ...]

SELECT 'Migration commissions + multi-IBAN completed' AS status;

-- --- migration_roles.sql ---
-- ============================================================================
-- InmobilIA Pro · Migración multi-usuario y roles (Sprint 4)
-- ============================================================================
-- Uso:
--   1. SQL Editor de Supabase → New query → pega TODO esto → Run
--   2. Si tu cuenta admin ya existe, se autoasigna como organización propia
-- ============================================================================

-- ─── Columnas nuevas en users ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS role            TEXT DEFAULT 'admin';
ALTER TABLE users ADD COLUMN IF NOT EXISTS name            TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS active          BOOLEAN DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone           TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS position        TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS invited_by      UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login      TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_org    ON users(organization_id);
CREATE INDEX IF NOT EXISTS idx_users_role   ON users(role);
CREATE INDEX IF NOT EXISTS idx_users_active ON users(active);

-- ─── Auto-asignar organization_id al propio id si está NULL ───
-- (Cada usuario sin organización pasa a ser su propia organización = admin)
UPDATE users
   SET organization_id = id, role = COALESCE(role, 'admin')
 WHERE organization_id IS NULL;

-- ─── Trigger para que nuevos usuarios sin organization se autoasignen ───
CREATE OR REPLACE FUNCTION users_default_org()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.organization_id IS NULL THEN
        NEW.organization_id := NEW.id;
    END IF;
    IF NEW.role IS NULL THEN
        NEW.role := 'admin';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_default_org ON users;
CREATE TRIGGER trg_users_default_org
    BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION users_default_org();

-- ─── Auditoría: created_by, updated_by en tablas principales ───
DO $$
DECLARE
    t TEXT;
    tables TEXT[] := ARRAY[
        'propiedades','inquilinos','contratos','transacciones','tareas',
        'mantenimiento','personal','eventos','documentos','reservas',
        'seguros','oportunidades','leads','email_templates','analisis_financieros'
    ];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        BEGIN
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS created_by UUID', t);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_by UUID', t);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()', t);
        EXCEPTION WHEN undefined_table THEN NULL;
        END;
    END LOOP;
END $$;

-- ─── Verificación ───
SELECT 'users con role/org configurados:' AS info, count(*) FROM users WHERE role IS NOT NULL AND organization_id IS NOT NULL;

-- --- migration_photos.sql ---
-- ============================================================================
-- InmobilIA Pro · Migración fotos de propiedades (Sprint 5)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

-- Foto destacada y galería
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS featured_photo TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS photos         JSONB DEFAULT '[]'::jsonb;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS reference      TEXT;        -- código tipo "REF-2024-001"
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS publicada      BOOLEAN DEFAULT false;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS visitas_count  INTEGER DEFAULT 0;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS consultas_count INTEGER DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_propiedades_publicada ON propiedades(publicada);
CREATE INDEX IF NOT EXISTS idx_propiedades_reference ON propiedades(reference);

-- Auto-generar reference si está vacío al insertar
CREATE OR REPLACE FUNCTION propiedades_default_ref()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.reference IS NULL OR NEW.reference = '' THEN
        NEW.reference := 'REF-' || to_char(now(), 'YYYY') || '-' || lpad(floor(random()*9999)::text, 4, '0');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_propiedades_default_ref ON propiedades;
CREATE TRIGGER trg_propiedades_default_ref
    BEFORE INSERT ON propiedades
    FOR EACH ROW EXECUTE FUNCTION propiedades_default_ref();

-- Verificación
SELECT 'propiedades con campos nuevos:' AS info,
       count(*) FILTER (WHERE featured_photo IS NOT NULL) AS con_foto,
       count(*) FILTER (WHERE reference IS NOT NULL) AS con_ref,
       count(*) AS total
  FROM propiedades;

-- --- migration_security.sql ---
-- ============================================================================
-- InmobilIA Pro · Migración seguridad (Sprint 9)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

-- ─── Verificación de email ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified       BOOLEAN DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verify_token   TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verify_expires TIMESTAMPTZ;

-- ─── Recuperación de contraseña ───
CREATE TABLE IF NOT EXISTS password_resets (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email       TEXT NOT NULL,
    token       TEXT UNIQUE NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    ip_address  TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_pwd_resets_token ON password_resets(token);
CREATE INDEX IF NOT EXISTS idx_pwd_resets_user  ON password_resets(user_id);

-- ─── Auditoría: log de eventos importantes ───

-- ─── Cookies / consent (RGPD) ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_accepted_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS privacy_accepted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS marketing_consent  BOOLEAN DEFAULT false;

-- ─── Soft delete (no borrar usuario, solo marcarlo) ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at  TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_deleted ON users(deleted_at) WHERE deleted_at IS NULL;

SELECT 'Migration security completed' AS status;

-- --- migration_subscriptions.sql ---
-- ============================================================================
-- InmobilIA Pro · Migración suscripciones Stripe (Sprint 11)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

-- ─── Columnas de suscripción en users ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan                  TEXT DEFAULT 'free';
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_status           TEXT DEFAULT 'active';   -- active, trialing, past_due, canceled
ALTER TABLE users ADD COLUMN IF NOT EXISTS trial_ends_at         TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_ends_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id    TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_subscription_id TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_canceled_at      TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_plan       ON users(plan);
CREATE INDEX IF NOT EXISTS idx_users_stripe_cus ON users(stripe_customer_id);
CREATE INDEX IF NOT EXISTS idx_users_stripe_sub ON users(stripe_subscription_id);

-- ─── Trial automático de 14 días al registrarse ───
CREATE OR REPLACE FUNCTION users_default_trial()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.trial_ends_at IS NULL AND NEW.organization_id = NEW.id THEN
        NEW.trial_ends_at := NOW() + INTERVAL '14 days';
        IF NEW.plan IS NULL OR NEW.plan = '' THEN
            NEW.plan := 'free';
        END IF;
        IF NEW.plan_status IS NULL OR NEW.plan_status = '' THEN
            NEW.plan_status := 'trialing';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_default_trial ON users;
CREATE TRIGGER trg_users_default_trial
    BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION users_default_trial();

-- ─── Tabla de eventos de facturación Stripe (audit) ───
CREATE TABLE IF NOT EXISTS billing_events (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    stripe_event_id TEXT UNIQUE,
    event_type      TEXT NOT NULL,
    amount_cents    INTEGER,
    currency        TEXT,
    status          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_billing_events_user ON billing_events(user_id);
CREATE INDEX IF NOT EXISTS idx_billing_events_type ON billing_events(event_type);

-- ─── Aplicar trial a usuarios existentes que no lo tengan ───
UPDATE users
   SET trial_ends_at = NOW() + INTERVAL '14 days',
       plan = COALESCE(plan, 'free'),
       plan_status = COALESCE(plan_status, 'trialing')
 WHERE trial_ends_at IS NULL
   AND organization_id = id;

SELECT 'Migration subscriptions completed' AS status;

-- --- migration_notifications.sql ---
-- ============================================================================
-- InmobilIA Pro · Migración notificaciones in-app
-- ============================================================================

CREATE TABLE IF NOT EXISTS notifications (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,        -- 'info', 'success', 'warning', 'error'
    icon        TEXT,                  -- emoji o icono Lucide
    title       TEXT NOT NULL,
    message     TEXT,
    link        TEXT,                  -- URL a la que llevar al hacer click
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user    ON notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_unread  ON notifications(user_id, read_at) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_notifications_created ON notifications(created_at DESC);

SELECT 'Migration notifications completed' AS status;

-- --- migration_productivity.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 12: Comentarios, timeline, adjuntos
-- ============================================================================

-- Comentarios en cualquier entidad
CREATE TABLE IF NOT EXISTS comments (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id      UUID NOT NULL,
    entity_type TEXT NOT NULL,        -- 'inquilino', 'propiedad', 'lead', 'contrato', etc.
    entity_id   TEXT NOT NULL,
    body        TEXT NOT NULL,
    author_name TEXT,
    edited_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_comments_entity ON comments(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_comments_org    ON comments(org_id, created_at DESC);

-- Timeline de actividad
CREATE TABLE IF NOT EXISTS activity_log (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID,
    org_id      UUID NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   TEXT NOT NULL,
    action      TEXT NOT NULL,        -- 'created', 'updated', 'deleted', 'commented', 'attached'
    field       TEXT,                  -- nombre del campo modificado
    old_value   TEXT,
    new_value   TEXT,
    actor_name  TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_activity_entity ON activity_log(entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_org    ON activity_log(org_id, created_at DESC);

-- Adjuntos (URLs externos o uploads futuros)
CREATE TABLE IF NOT EXISTS attachments (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id      UUID NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   TEXT NOT NULL,
    name        TEXT NOT NULL,
    url         TEXT NOT NULL,
    file_type   TEXT,                  -- pdf, image, doc, sheet, other
    size_kb     INTEGER,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_attachments_entity ON attachments(entity_type, entity_id);

-- Plantillas reutilizables
CREATE TABLE IF NOT EXISTS templates (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,         -- 'email', 'contract_clause', 'description'
    name        TEXT NOT NULL,
    subject     TEXT,
    body        TEXT NOT NULL,
    variables   TEXT[],                -- ['inquilino', 'renta', 'mes']
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_templates_user ON templates(user_id, type);

SELECT 'Migration productivity completed' AS status;

-- --- migration_2fa.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 19: 2FA y seguridad enterprise
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret      TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled     BOOLEAN DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_backup_codes TEXT[];
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_logins      INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until       TIMESTAMPTZ;

-- Whitelist de IPs por organización (opcional)
CREATE TABLE IF NOT EXISTS ip_whitelist (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID NOT NULL,
    ip_cidr     TEXT NOT NULL,         -- '192.168.1.0/24' o '10.0.0.5'
    description TEXT,
    enabled     BOOLEAN DEFAULT true,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ip_whitelist_org ON ip_whitelist(org_id) WHERE enabled = true;

SELECT 'Migration 2FA completed' AS status;

-- --- migration_trash_filters.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 23: Papelera + filtros guardados
-- ============================================================================

-- Papelera: cuando se borra un registro, va aquí en lugar de desaparecer
CREATE TABLE IF NOT EXISTS trash (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID NOT NULL,
    user_id     UUID,
    table_name  TEXT NOT NULL,
    original_id TEXT NOT NULL,
    snapshot    JSONB NOT NULL,
    actor_name  TEXT,
    deleted_at  TIMESTAMPTZ DEFAULT NOW(),
    expires_at  TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '30 days')
);
CREATE INDEX IF NOT EXISTS idx_trash_org     ON trash(org_id);
CREATE INDEX IF NOT EXISTS idx_trash_table   ON trash(table_name);
CREATE INDEX IF NOT EXISTS idx_trash_expires ON trash(expires_at);

-- Filtros guardados por usuario
CREATE TABLE IF NOT EXISTS saved_filters (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    table_name  TEXT NOT NULL,
    name        TEXT NOT NULL,
    filters     JSONB NOT NULL,
    is_default  BOOLEAN DEFAULT false,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_filters_user_table ON saved_filters(user_id, table_name);

-- Tags / etiquetas libres
CREATE TABLE IF NOT EXISTS tags (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID NOT NULL,
    name        TEXT NOT NULL,
    color       TEXT DEFAULT '#3b82f6',
    icon        TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tags_org_name ON tags(org_id, name);

CREATE TABLE IF NOT EXISTS entity_tags (
    tag_id      UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL,
    entity_id   TEXT NOT NULL,
    PRIMARY KEY (tag_id, entity_type, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_entity_tags_lookup ON entity_tags(entity_type, entity_id);

SELECT 'Migration trash + filters + tags completed' AS status;

-- --- migration_drip_chat.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 35: Drip campaigns + chat interno
-- ============================================================================

-- Drip campaigns (secuencias de emails automáticas)
CREATE TABLE IF NOT EXISTS drip_campaigns (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    trigger     TEXT NOT NULL,           -- 'lead_created', 'visit_done', 'contract_signed'
    active      BOOLEAN DEFAULT true,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS drip_steps (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    campaign_id   UUID NOT NULL REFERENCES drip_campaigns(id) ON DELETE CASCADE,
    delay_days    INTEGER NOT NULL DEFAULT 0,
    subject       TEXT NOT NULL,
    body          TEXT NOT NULL,
    sort_order    INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_drip_steps_campaign ON drip_steps(campaign_id, sort_order);

-- Tracking: qué leads están en qué punto de la secuencia
CREATE TABLE IF NOT EXISTS drip_enrollments (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    campaign_id   UUID NOT NULL REFERENCES drip_campaigns(id) ON DELETE CASCADE,
    lead_id       UUID NOT NULL,
    org_id        UUID NOT NULL,
    enrolled_at   TIMESTAMPTZ DEFAULT NOW(),
    next_send_at  TIMESTAMPTZ,
    next_step     INTEGER DEFAULT 0,
    completed     BOOLEAN DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_drip_enrollments_pending ON drip_enrollments(next_send_at) WHERE completed = false;

-- ─── Chat interno entre miembros del equipo ───
CREATE TABLE IF NOT EXISTS team_messages (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id       UUID NOT NULL,
    from_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_user_id   UUID REFERENCES users(id) ON DELETE CASCADE,  -- null = canal general
    entity_type  TEXT,                                          -- contexto opcional
    entity_id    TEXT,
    body         TEXT NOT NULL,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_team_msg_org ON team_messages(org_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_team_msg_to  ON team_messages(to_user_id, read_at);

SELECT 'Migration drip + chat completed' AS status;

-- --- migration_api_keys.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 37: API pública con tokens
-- ============================================================================

CREATE TABLE IF NOT EXISTS api_keys (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id      UUID NOT NULL,
    name        TEXT NOT NULL,
    token_hash  TEXT UNIQUE NOT NULL,           -- bcrypt del token, nunca se guarda en plano
    prefix      TEXT NOT NULL,                   -- primeros 8 chars del token (visible en lista)
    scopes      TEXT[] DEFAULT ARRAY['read'],    -- 'read', 'write'
    last_used   TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_api_keys_user ON api_keys(user_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys(token_hash) WHERE revoked_at IS NULL;

SELECT 'Migration api keys completed' AS status;

-- --- migration_tenant_portal.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 26: Portal del inquilino
-- ============================================================================

-- Vincular un user con un inquilino (cuando el rol es 'tenant_portal')
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES inquilinos(id) ON DELETE SET NULL;

-- Solicitudes de reparación / mantenimiento que crean los inquilinos
CREATE TABLE IF NOT EXISTS tenant_requests (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    inquilino_id    UUID REFERENCES inquilinos(id) ON DELETE SET NULL,
    org_id          UUID NOT NULL,
    property_name   TEXT,
    category        TEXT,                          -- 'mantenimiento', 'incidencia', 'consulta', 'otro'
    title           TEXT NOT NULL,
    description     TEXT,
    photo_urls      JSONB DEFAULT '[]'::jsonb,
    priority        TEXT DEFAULT 'Media',
    status          TEXT DEFAULT 'Nueva',           -- Nueva, En revisión, En curso, Resuelta, Cerrada
    response        TEXT,
    responded_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tenant_req_org    ON tenant_requests(org_id, status);
CREATE INDEX IF NOT EXISTS idx_tenant_req_tenant ON tenant_requests(tenant_user_id);

-- Invitaciones a inquilinos para acceder al portal
CREATE TABLE IF NOT EXISTS tenant_invites (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    inquilino_id  UUID NOT NULL REFERENCES inquilinos(id) ON DELETE CASCADE,
    org_id        UUID NOT NULL,
    email         TEXT NOT NULL,
    token         TEXT UNIQUE NOT NULL,
    accepted_at   TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '14 days'),
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tenant_invites_token ON tenant_invites(token);

SELECT 'Migration tenant portal completed' AS status;

-- --- migration_dashboard_config.sql ---
-- ============================================================================
-- InmobilIA Pro · Sprint 45: Dashboard configurable
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS dashboard_config JSONB;

SELECT 'Migration dashboard_config completed' AS status;

-- 4. SPRINTS 47-110
-- --- V21__insurance.sql ---
-- Sprint 50: Gestión avanzada de seguros
CREATE TABLE IF NOT EXISTS seguros (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    contrato_id UUID REFERENCES contratos(id) ON DELETE SET NULL,
    inquilino_id UUID REFERENCES inquilinos(id) ON DELETE SET NULL,

    -- Datos póliza
    tipo TEXT NOT NULL,         -- hogar, vida, impago_alquiler, comunidad, responsabilidad_civil
    aseguradora TEXT NOT NULL,
    numero_poliza TEXT,
    tomador TEXT,                -- nombre del tomador
    asegurado TEXT,              -- a quien protege
    beneficiario TEXT,

    -- Coberturas
    capital_asegurado NUMERIC(12,2),
    franquicia NUMERIC(10,2) DEFAULT 0,
    coberturas TEXT,             -- texto libre con coberturas
    exclusiones TEXT,

    -- Económico
    prima_anual NUMERIC(10,2),
    forma_pago TEXT DEFAULT 'anual',  -- anual, semestral, trimestral, mensual
    comision_agente NUMERIC(10,2),    -- comisión que recibe nuestra inmobiliaria

    -- Fechas
    fecha_emision DATE,
    fecha_inicio DATE NOT NULL,
    fecha_vencimiento DATE NOT NULL,
    renovacion_automatica BOOLEAN DEFAULT TRUE,
    fecha_cancelacion DATE,
    motivo_cancelacion TEXT,

    -- Documentación
    documento_url TEXT,           -- PDF póliza
    condiciones_particulares_url TEXT,

    -- Siniestros
    siniestros_count INT DEFAULT 0,

    estado TEXT DEFAULT 'activa',  -- activa, suspendida, cancelada, expirada
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seguros_user ON seguros(user_id);
CREATE INDEX IF NOT EXISTS idx_seguros_vencimiento ON seguros(fecha_vencimiento) WHERE estado = 'activa';
CREATE INDEX IF NOT EXISTS idx_seguros_propiedad ON seguros(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_seguros_contrato ON seguros(contrato_id);

-- Tabla de siniestros
CREATE TABLE IF NOT EXISTS siniestros (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seguro_id UUID NOT NULL REFERENCES seguros(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    fecha_siniestro DATE NOT NULL,
    fecha_declaracion DATE,
    numero_expediente TEXT,
    tipo_dano TEXT,                  -- agua, incendio, robo, daños propios, etc.
    descripcion TEXT,
    importe_reclamado NUMERIC(10,2),
    importe_indemnizado NUMERIC(10,2),
    estado TEXT DEFAULT 'abierto',   -- abierto, en_tramite, pagado, rechazado, cerrado
    perito_asignado TEXT,
    fecha_resolucion DATE,
    documentos JSONB DEFAULT '[]'::jsonb,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_siniestros_seguro ON siniestros(seguro_id);
CREATE INDEX IF NOT EXISTS idx_siniestros_estado ON siniestros(estado);

-- --- V22__rentability_analysis.sql ---
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

-- --- V23__matching_engine.sql ---
-- Sprint 52: Motor de matching leads ↔ propiedades

-- Preferencias detalladas del lead/cliente
CREATE TABLE IF NOT EXISTS preferencias_busqueda (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lead_id UUID REFERENCES leads(id) ON DELETE CASCADE,
    inquilino_id UUID REFERENCES inquilinos(id) ON DELETE CASCADE,

    -- Tipo operación
    operacion TEXT,                     -- venta, alquiler, alquiler_temporal
    tipo_propiedad TEXT[],              -- piso, casa, atico, duplex, local, oficina, garaje, trastero, terreno

    -- Económico
    presupuesto_min NUMERIC(12,2),
    presupuesto_max NUMERIC(12,2),

    -- Ubicación
    zonas TEXT[],                       -- array de zonas/barrios deseados
    municipios TEXT[],
    radio_km NUMERIC(5,2),              -- radio desde un punto
    lat NUMERIC(10,7),
    lng NUMERIC(10,7),

    -- Características
    habitaciones_min INT,
    habitaciones_max INT,
    banos_min INT,
    superficie_min NUMERIC(8,2),
    superficie_max NUMERIC(8,2),

    -- Booleanos deseados
    requiere_garaje BOOLEAN DEFAULT FALSE,
    requiere_ascensor BOOLEAN DEFAULT FALSE,
    requiere_terraza BOOLEAN DEFAULT FALSE,
    requiere_piscina BOOLEAN DEFAULT FALSE,
    requiere_aire_acondicionado BOOLEAN DEFAULT FALSE,
    requiere_amueblado BOOLEAN DEFAULT FALSE,
    permite_mascotas BOOLEAN DEFAULT FALSE,
    obra_nueva BOOLEAN DEFAULT FALSE,

    -- Otros
    eficiencia_min TEXT,                -- A, B, C, D, E, F, G
    ano_construccion_min INT,
    notas TEXT,

    -- Sistema de matching
    auto_match BOOLEAN DEFAULT TRUE,    -- enviar emails automáticos
    notificar_email BOOLEAN DEFAULT TRUE,
    notificar_whatsapp BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prefs_user ON preferencias_busqueda(user_id);
CREATE INDEX IF NOT EXISTS idx_prefs_lead ON preferencias_busqueda(lead_id);

-- Tabla de matches realizados (para no spamear)
CREATE TABLE IF NOT EXISTS matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    preferencia_id UUID REFERENCES preferencias_busqueda(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    lead_id UUID,
    inquilino_id UUID,

    score NUMERIC(5,2),                 -- 0-100 afinidad
    razones JSONB DEFAULT '[]'::jsonb,  -- ["precio_ok", "habitaciones_match", ...]
    descartes JSONB DEFAULT '[]'::jsonb, -- ["zona_no_coincide"]

    estado TEXT DEFAULT 'nuevo',        -- nuevo, enviado, visto, interesado, descartado, visita
    notificado_at TIMESTAMPTZ,
    visto_at TIMESTAMPTZ,
    feedback TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_matches_pref_prop ON matches(preferencia_id, propiedad_id);
CREATE INDEX IF NOT EXISTS idx_matches_estado ON matches(estado);
CREATE INDEX IF NOT EXISTS idx_matches_score ON matches(score DESC);

-- --- V24__portal_feeds.sql ---
-- Sprint 53: Feeds XML para portales (Idealista, Fotocasa, Habitaclia, Pisos.com)
CREATE TABLE IF NOT EXISTS portal_feeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    feed_token TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),
    company_name TEXT,
    company_phone TEXT,
    company_email TEXT,
    company_logo_url TEXT,
    company_website TEXT,
    -- Activación por portal
    idealista_enabled BOOLEAN DEFAULT TRUE,
    fotocasa_enabled BOOLEAN DEFAULT TRUE,
    habitaclia_enabled BOOLEAN DEFAULT TRUE,
    pisoscom_enabled BOOLEAN DEFAULT TRUE,
    -- Filtros: solo propiedades publicables
    publicar_todas BOOLEAN DEFAULT FALSE,
    -- Estadísticas
    accesos_total INT DEFAULT 0,
    ultimo_acceso TIMESTAMPTZ,
    ultimo_ip TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_portal_feeds_token ON portal_feeds(feed_token);

-- Flag por propiedad para publicar en portales
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS publicar_portales BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS destacada BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS referencia TEXT;

-- Estadísticas de accesos por portal
CREATE TABLE IF NOT EXISTS portal_feed_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID,
    portal TEXT,
    ip TEXT,
    user_agent TEXT,
    propiedades_count INT,
    bytes_servidos BIGINT,
    duracion_ms INT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_portal_logs_user ON portal_feed_logs(user_id, created_at DESC);

-- --- V25__visits_system.sql ---
-- Sprint 54: Sistema completo de visitas a propiedades
CREATE TABLE IF NOT EXISTS visitas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,
    lead_id UUID REFERENCES leads(id) ON DELETE SET NULL,
    inquilino_id UUID REFERENCES inquilinos(id) ON DELETE SET NULL,
    agente_id UUID REFERENCES users(id) ON DELETE SET NULL,
    match_id UUID REFERENCES matches(id) ON DELETE SET NULL,

    -- Datos visitante
    visitante_nombre TEXT NOT NULL,
    visitante_email TEXT,
    visitante_telefono TEXT,
    visitante_dni TEXT,

    -- Fecha y duración
    fecha_inicio TIMESTAMPTZ NOT NULL,
    fecha_fin TIMESTAMPTZ NOT NULL,
    duracion_min INT DEFAULT 30,

    -- Estado
    estado TEXT DEFAULT 'programada',  -- programada, confirmada, realizada, cancelada, no_presentado
    tipo TEXT DEFAULT 'presencial',    -- presencial, virtual, open_house

    -- URLs
    confirmacion_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    feedback_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    reunion_url TEXT,                   -- Para visitas virtuales (Zoom/Meet)

    -- Recordatorios
    recordatorio_24h_enviado BOOLEAN DEFAULT FALSE,
    recordatorio_1h_enviado BOOLEAN DEFAULT FALSE,

    -- Confirmación
    confirmada_at TIMESTAMPTZ,
    cancelada_at TIMESTAMPTZ,
    motivo_cancelacion TEXT,

    -- Feedback post-visita (rellenado por el visitante o agente)
    interes_nivel INT,                  -- 1-5
    feedback_visitante TEXT,
    feedback_agente TEXT,
    objeciones TEXT,
    siguiente_paso TEXT,                -- segunda_visita, oferta, descartado, sin_decision
    fecha_siguiente_paso DATE,

    -- Conversión
    convirtio_en_oferta BOOLEAN DEFAULT FALSE,
    convirtio_en_reserva BOOLEAN DEFAULT FALSE,

    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_visitas_user ON visitas(user_id);
CREATE INDEX IF NOT EXISTS idx_visitas_propiedad ON visitas(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_visitas_agente ON visitas(agente_id);
CREATE INDEX IF NOT EXISTS idx_visitas_fecha ON visitas(fecha_inicio);
CREATE INDEX IF NOT EXISTS idx_visitas_estado ON visitas(estado);
CREATE INDEX IF NOT EXISTS idx_visitas_confirmacion_token ON visitas(confirmacion_token);
CREATE INDEX IF NOT EXISTS idx_visitas_feedback_token ON visitas(feedback_token);

-- Slots de disponibilidad del agente (semanal recurrente)
CREATE TABLE IF NOT EXISTS slots_disponibilidad (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    agente_id UUID REFERENCES users(id) ON DELETE CASCADE,
    dia_semana INT NOT NULL,            -- 0=domingo ... 6=sábado
    hora_inicio TIME NOT NULL,
    hora_fin TIME NOT NULL,
    activo BOOLEAN DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_slots_agente ON slots_disponibilidad(agente_id, dia_semana);

-- --- V26__esignature.sql ---
-- Sprint 55: Firma electrónica de contratos (Ley 6/2020 — simple eIDAS)

CREATE TABLE IF NOT EXISTS firmas_documento (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    -- Documento
    titulo TEXT NOT NULL,
    tipo TEXT NOT NULL,                  -- contrato_alquiler, contrato_venta, mandato, anexo, otros
    contenido_html TEXT NOT NULL,        -- HTML del contrato (snapshot inmutable)
    documento_hash TEXT NOT NULL,        -- SHA-256 del contenido para integridad
    contrato_id UUID REFERENCES contratos(id) ON DELETE SET NULL,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,

    -- Estado
    estado TEXT DEFAULT 'borrador',      -- borrador, enviado, parcialmente_firmado, firmado, rechazado, expirado
    fecha_envio TIMESTAMPTZ,
    fecha_completado TIMESTAMPTZ,
    fecha_expiracion TIMESTAMPTZ,        -- por defecto +30 días
    motivo_rechazo TEXT,

    -- Configuración
    requiere_dni BOOLEAN DEFAULT TRUE,
    requiere_otp_sms BOOLEAN DEFAULT FALSE,
    requiere_otp_email BOOLEAN DEFAULT TRUE,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_firmas_doc_user ON firmas_documento(user_id);
CREATE INDEX IF NOT EXISTS idx_firmas_doc_estado ON firmas_documento(estado);

-- Cada firmante del documento
CREATE TABLE IF NOT EXISTS firmas_firmante (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    documento_id UUID NOT NULL REFERENCES firmas_documento(id) ON DELETE CASCADE,

    -- Datos del firmante
    nombre TEXT NOT NULL,
    dni TEXT,
    email TEXT NOT NULL,
    telefono TEXT,
    rol TEXT,                            -- arrendador, arrendatario, fiador, comprador, vendedor, etc.
    orden INT DEFAULT 1,

    -- Tokens únicos
    token_firma TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),

    -- Estado firma
    estado TEXT DEFAULT 'pendiente',     -- pendiente, visto, firmado, rechazado
    visto_at TIMESTAMPTZ,
    firmado_at TIMESTAMPTZ,

    -- Datos de la firma (trazabilidad jurídica)
    firma_imagen TEXT,                   -- Base64 PNG del trazo
    firma_hash TEXT,                     -- SHA-256 hash de la firma
    ip_firma TEXT,
    user_agent TEXT,
    geolocalizacion TEXT,
    otp_verificado BOOLEAN DEFAULT FALSE,
    otp_codigo TEXT,                     -- código generado (caduca en 10min)
    otp_enviado_at TIMESTAMPTZ,
    otp_intentos INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_firmantes_doc ON firmas_firmante(documento_id);
CREATE INDEX IF NOT EXISTS idx_firmantes_token ON firmas_firmante(token_firma);

-- Auditoría completa (cada evento queda registrado, inmutable)
CREATE TABLE IF NOT EXISTS firmas_auditoria (
    id BIGSERIAL PRIMARY KEY,
    documento_id UUID NOT NULL REFERENCES firmas_documento(id) ON DELETE CASCADE,
    firmante_id UUID REFERENCES firmas_firmante(id) ON DELETE SET NULL,

    evento TEXT NOT NULL,                -- creado, enviado, visto, otp_enviado, otp_verificado, firmado, rechazado, expirado, descargado
    detalle TEXT,
    ip TEXT,
    user_agent TEXT,
    metadata JSONB,

    -- Cadena de hashes (blockchain-like para integridad)
    hash_anterior TEXT,
    hash_actual TEXT NOT NULL,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_doc ON firmas_auditoria(documento_id, created_at);

-- --- V27__marketing_campaigns.sql ---
-- Sprint 56: Campañas de marketing masivas
CREATE TABLE IF NOT EXISTS campanas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    descripcion TEXT,
    canal TEXT NOT NULL DEFAULT 'email', -- email, whatsapp, sms, mixto

    -- Contenido
    asunto TEXT,                          -- email subject
    contenido TEXT NOT NULL,              -- HTML email o texto WhatsApp
    from_name TEXT,
    from_email TEXT,
    preheader TEXT,                       -- texto preview email

    -- A/B testing
    ab_test BOOLEAN DEFAULT FALSE,
    asunto_b TEXT,
    contenido_b TEXT,
    ab_split_pct INT DEFAULT 50,         -- % que recibe versión A

    -- Segmentación
    segmento_tipo TEXT NOT NULL,         -- todos_leads, todos_inquilinos, todos_propietarios, lista_custom, segmentado
    segmento_filtros JSONB,              -- {"tipo": "comprador", "presupuesto_min": 200000, ...}
    lista_destinatarios JSONB,           -- ["email1", "email2"] si manual

    -- Programación
    estado TEXT DEFAULT 'borrador',      -- borrador, programada, enviando, enviada, pausada, cancelada
    enviar_at TIMESTAMPTZ,               -- cuando empezar a enviar
    enviado_at TIMESTAMPTZ,
    completado_at TIMESTAMPTZ,

    -- Métricas (agregadas)
    destinatarios_total INT DEFAULT 0,
    enviados INT DEFAULT 0,
    fallidos INT DEFAULT 0,
    abiertos INT DEFAULT 0,
    clicks INT DEFAULT 0,
    rebotes INT DEFAULT 0,
    bajas INT DEFAULT 0,
    conversiones INT DEFAULT 0,

    -- Configuración
    incluir_unsubscribe BOOLEAN DEFAULT TRUE,
    tracking_aperturas BOOLEAN DEFAULT TRUE,
    tracking_clicks BOOLEAN DEFAULT TRUE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,  -- si la campaña promociona una propiedad

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_campanas_user ON campanas(user_id);
CREATE INDEX IF NOT EXISTS idx_campanas_estado ON campanas(estado);
CREATE INDEX IF NOT EXISTS idx_campanas_enviar ON campanas(enviar_at) WHERE estado = 'programada';

-- Envíos individuales
CREATE TABLE IF NOT EXISTS campana_envios (
    id BIGSERIAL PRIMARY KEY,
    campana_id UUID NOT NULL REFERENCES campanas(id) ON DELETE CASCADE,
    user_id UUID,

    destinatario_email TEXT,
    destinatario_telefono TEXT,
    destinatario_nombre TEXT,
    lead_id UUID,
    inquilino_id UUID,
    variante TEXT DEFAULT 'A',           -- A o B (para A/B test)

    tracking_token TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),

    estado TEXT DEFAULT 'pendiente',    -- pendiente, enviado, abierto, click, rebote, baja, fallido
    enviado_at TIMESTAMPTZ,
    primer_abierto_at TIMESTAMPTZ,
    primer_click_at TIMESTAMPTZ,
    aperturas_count INT DEFAULT 0,
    clicks_count INT DEFAULT 0,
    error_mensaje TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_envios_campana ON campana_envios(campana_id);
CREATE INDEX IF NOT EXISTS idx_envios_token ON campana_envios(tracking_token);
CREATE INDEX IF NOT EXISTS idx_envios_estado ON campana_envios(estado);

-- Plantillas reutilizables
CREATE TABLE IF NOT EXISTS plantillas_email (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    categoria TEXT,                      -- bienvenida, propiedad_nueva, recordatorio, felicitacion, encuesta
    asunto TEXT,
    contenido TEXT NOT NULL,
    variables_usadas TEXT[],             -- ["nombre", "propiedad_direccion", ...]
    es_publica BOOLEAN DEFAULT FALSE,    -- true = compartida con otros usuarios del workspace
    veces_usada INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_plantillas_user ON plantillas_email(user_id);

-- Bajas (unsubscribes)
CREATE TABLE IF NOT EXISTS bajas_marketing (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    motivo TEXT,
    campana_origen_id UUID,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (user_id, email)
);

-- --- V28__workflow_automation.sql ---
-- Sprint 57: Motor de automatizaciones tipo Zapier
CREATE TABLE IF NOT EXISTS workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    descripcion TEXT,
    activo BOOLEAN DEFAULT TRUE,

    -- Trigger
    trigger_evento TEXT NOT NULL,        -- lead_creado, visita_programada, factura_impagada, contrato_firmado, propiedad_creada, etc.
    trigger_condiciones JSONB,           -- {"campo": "presupuesto_max", "operador": ">", "valor": 200000}

    -- Acciones (array ordenado)
    acciones JSONB NOT NULL,             -- [{"tipo": "send_email", "config": {...}}, {"tipo": "create_task", ...}]

    -- Ejecución
    ejecutar_inmediato BOOLEAN DEFAULT TRUE,
    delay_minutos INT DEFAULT 0,          -- esperar X min después del trigger
    solo_horario_laboral BOOLEAN DEFAULT FALSE,

    -- Estadísticas
    veces_ejecutado INT DEFAULT 0,
    veces_exito INT DEFAULT 0,
    veces_error INT DEFAULT 0,
    ultima_ejecucion TIMESTAMPTZ,
    ultimo_error TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflows_user ON workflows(user_id);
CREATE INDEX IF NOT EXISTS idx_workflows_trigger ON workflows(trigger_evento) WHERE activo = true;

-- Log de ejecuciones
CREATE TABLE IF NOT EXISTS workflow_ejecuciones (
    id BIGSERIAL PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    user_id UUID,
    trigger_evento TEXT,
    payload JSONB,                       -- datos del evento que disparó

    estado TEXT DEFAULT 'pendiente',     -- pendiente, ejecutando, exitoso, error
    iniciado_at TIMESTAMPTZ DEFAULT NOW(),
    completado_at TIMESTAMPTZ,
    duracion_ms INT,

    acciones_ejecutadas INT DEFAULT 0,
    acciones_total INT DEFAULT 0,
    resultado JSONB,                     -- log de cada acción
    error_mensaje TEXT,

    -- Para ejecución diferida
    ejecutar_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_workflow_exec_workflow ON workflow_ejecuciones(workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_exec_pending ON workflow_ejecuciones(ejecutar_at) WHERE estado = 'pendiente';
CREATE INDEX IF NOT EXISTS idx_workflow_exec_estado ON workflow_ejecuciones(estado);

-- Tareas creadas por workflows (también gestionables manualmente)
CREATE TABLE IF NOT EXISTS tareas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    asignado_a UUID REFERENCES users(id) ON DELETE SET NULL,
    workflow_origen_id UUID REFERENCES workflows(id) ON DELETE SET NULL,

    titulo TEXT NOT NULL,
    descripcion TEXT,
    prioridad TEXT DEFAULT 'media',      -- baja, media, alta, urgente
    estado TEXT DEFAULT 'pendiente',     -- pendiente, en_curso, completada, cancelada
    fecha_limite TIMESTAMPTZ,
    completada_at TIMESTAMPTZ,

    -- Referencias opcionales
    lead_id UUID,
    propiedad_id UUID,
    contrato_id UUID,
    visita_id UUID,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tareas_user ON tareas(user_id);
CREATE INDEX IF NOT EXISTS idx_tareas_asignado ON tareas(asignado_a);
CREATE INDEX IF NOT EXISTS idx_tareas_estado ON tareas(estado);
CREATE INDEX IF NOT EXISTS idx_tareas_fecha_limite ON tareas(fecha_limite) WHERE estado = 'pendiente';

-- --- V29__lead_scoring.sql ---
-- Sprint 58: Lead scoring + interacciones tracking
ALTER TABLE leads ADD COLUMN IF NOT EXISTS score INT DEFAULT 0;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS score_actualizado_at TIMESTAMPTZ;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS probabilidad_conversion NUMERIC(5,2);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS temperatura TEXT;       -- frio, tibio, caliente, hot
ALTER TABLE leads ADD COLUMN IF NOT EXISTS siguiente_accion TEXT;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS siguiente_accion_at TIMESTAMPTZ;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS score_desglose JSONB;   -- {"presupuesto": 25, "urgencia": 15, ...}

CREATE INDEX IF NOT EXISTS idx_leads_score ON leads(score DESC) WHERE estado != 'descartado';
CREATE INDEX IF NOT EXISTS idx_leads_temperatura ON leads(temperatura);

-- Tracking de cada interacción para alimentar el scoring
CREATE TABLE IF NOT EXISTS lead_interacciones (
    id BIGSERIAL PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    user_id UUID,

    tipo TEXT NOT NULL,                  -- email_abierto, email_click, web_visita, llamada, whatsapp, visita_realizada, formulario_extra
    canal TEXT,                           -- email, web, telefono, whatsapp, presencial
    detalle TEXT,
    valor_score INT DEFAULT 0,           -- puntos que aporta esta interacción

    propiedad_id UUID,                   -- si la interacción es sobre una propiedad concreta
    metadata JSONB,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lead_int_lead ON lead_interacciones(lead_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_lead_int_tipo ON lead_interacciones(tipo);

-- Historial de scoring (para gráfico evolución)
CREATE TABLE IF NOT EXISTS lead_score_historico (
    id BIGSERIAL PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    score INT,
    temperatura TEXT,
    desglose JSONB,
    motivo_cambio TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_score_hist_lead ON lead_score_historico(lead_id, created_at DESC);

-- --- V30__pipeline_kanban.sql ---
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

-- --- V31__document_templates.sql ---
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

-- --- V32__ai_chatbot.sql ---
-- Sprint 61: AI Chatbot embebible para web pública

-- Configuración del bot por usuario
CREATE TABLE IF NOT EXISTS chatbot_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    widget_token TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),

    activo BOOLEAN DEFAULT TRUE,

    -- Personalización
    nombre_bot TEXT DEFAULT 'Asistente Virtual',
    avatar_url TEXT,
    color_primario TEXT DEFAULT '#3b82f6',
    color_secundario TEXT DEFAULT '#1e40af',
    mensaje_bienvenida TEXT DEFAULT '👋 ¡Hola! Soy tu asistente virtual. ¿En qué puedo ayudarte?',
    posicion TEXT DEFAULT 'bottom-right',  -- bottom-right, bottom-left
    mostrar_avatar BOOLEAN DEFAULT TRUE,

    -- Comportamiento
    auto_abrir_segundos INT DEFAULT 0,     -- 0 = no auto-abrir
    horario_atencion TEXT,                  -- "09:00-20:00"
    mensaje_fuera_horario TEXT,
    idiomas TEXT[] DEFAULT ARRAY['es', 'en'],

    -- IA
    persona_prompt TEXT DEFAULT 'Eres un asistente inmobiliario amable y eficiente. Ayudas a buscar propiedades, agendar visitas y resolver dudas. Sé conciso, máximo 3 frases por respuesta.',
    permitir_buscar_propiedades BOOLEAN DEFAULT TRUE,
    permitir_agendar_visita BOOLEAN DEFAULT TRUE,
    permitir_calcular_hipoteca BOOLEAN DEFAULT TRUE,
    captar_email_pasos INT DEFAULT 3,      -- después de N mensajes, pedir email

    -- Configuración avanzada
    dominios_permitidos TEXT[],            -- whitelist de dominios CORS
    max_mensajes_por_dia INT DEFAULT 100,
    palabras_clave_alerta TEXT[],          -- si visitante dice "urgente", alertar agente

    -- Estadísticas
    total_conversaciones INT DEFAULT 0,
    total_leads_captados INT DEFAULT 0,
    total_visitas_agendadas INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chatbot_token ON chatbot_config(widget_token);

-- Conversaciones
CREATE TABLE IF NOT EXISTS chatbot_conversaciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    session_id TEXT NOT NULL,              -- ID anónimo del navegador
    visitor_token TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),

    -- Datos visitante (se rellenan progresivamente)
    visitor_nombre TEXT,
    visitor_email TEXT,
    visitor_telefono TEXT,

    -- Contexto
    pagina_origen TEXT,
    user_agent TEXT,
    ip TEXT,
    pais TEXT,

    -- Estado conversación
    estado TEXT DEFAULT 'activa',          -- activa, finalizada, abandonada, convertida_lead, convertida_visita
    lead_creado_id UUID,
    visita_creada_id UUID,

    intencion_detectada TEXT,              -- comprar, alquilar, valorar, info, otra
    presupuesto_detectado NUMERIC(12,2),
    zona_detectada TEXT,
    tipo_propiedad_detectada TEXT,

    -- Métricas
    mensajes_total INT DEFAULT 0,
    duracion_minutos INT DEFAULT 0,
    score_engagement INT DEFAULT 0,        -- 0-100, calidad de conversación

    iniciada_at TIMESTAMPTZ DEFAULT NOW(),
    finalizada_at TIMESTAMPTZ,
    ultima_actividad_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_conv_user ON chatbot_conversaciones(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_conv_visitor ON chatbot_conversaciones(visitor_token);
CREATE INDEX IF NOT EXISTS idx_chat_conv_estado ON chatbot_conversaciones(estado);

-- Mensajes
CREATE TABLE IF NOT EXISTS chatbot_mensajes (
    id BIGSERIAL PRIMARY KEY,
    conversacion_id UUID NOT NULL REFERENCES chatbot_conversaciones(id) ON DELETE CASCADE,
    rol TEXT NOT NULL,                     -- user, assistant, system, function
    contenido TEXT,
    funcion_llamada TEXT,                   -- buscar_propiedades, agendar_visita, calcular_hipoteca
    funcion_resultado JSONB,
    propiedades_mostradas UUID[],
    tokens_usados INT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_msg_conv ON chatbot_mensajes(conversacion_id, created_at);

-- --- V33__team_chat.sql ---
-- Sprint 62: Chat interno del equipo

-- Canales (públicos y privados) + DMs
CREATE TABLE IF NOT EXISTS chat_canales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    creador_id UUID REFERENCES users(id) ON DELETE SET NULL,

    nombre TEXT NOT NULL,
    descripcion TEXT,
    icono TEXT DEFAULT '#',                -- # para canales, @ para DMs
    tipo TEXT NOT NULL DEFAULT 'publico',  -- publico, privado, dm, grupo

    -- Para DMs/grupos: lista de miembros
    miembros_ids UUID[],

    archivado BOOLEAN DEFAULT FALSE,
    fijado BOOLEAN DEFAULT FALSE,

    ultimo_mensaje_at TIMESTAMPTZ,
    mensajes_count INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_canales_org ON chat_canales(org_id) WHERE archivado = false;
CREATE INDEX IF NOT EXISTS idx_chat_canales_miembros ON chat_canales USING GIN (miembros_ids);

-- Mensajes
CREATE TABLE IF NOT EXISTS chat_mensajes (
    id BIGSERIAL PRIMARY KEY,
    canal_id UUID NOT NULL REFERENCES chat_canales(id) ON DELETE CASCADE,
    org_id UUID,
    autor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    contenido TEXT,
    contenido_html TEXT,                   -- procesado con links, menciones, emojis
    tipo TEXT DEFAULT 'texto',             -- texto, sistema, archivo, imagen, propiedad, lead

    -- Adjuntos / referencias
    adjunto_url TEXT,
    adjunto_nombre TEXT,
    propiedad_id UUID,                     -- compartir propiedad
    lead_id UUID,

    -- Menciones
    menciones_ids UUID[],

    -- Reacciones (JSON: {"emoji": ["userId1", "userId2"]})
    reacciones JSONB DEFAULT '{}'::jsonb,

    -- Hilo (reply)
    hilo_padre_id BIGINT REFERENCES chat_mensajes(id) ON DELETE CASCADE,
    hilo_count INT DEFAULT 0,

    editado BOOLEAN DEFAULT FALSE,
    editado_at TIMESTAMPTZ,
    eliminado BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_msg_canal ON chat_mensajes(canal_id, created_at DESC) WHERE eliminado = false;
CREATE INDEX IF NOT EXISTS idx_chat_msg_autor ON chat_mensajes(autor_id);
CREATE INDEX IF NOT EXISTS idx_chat_msg_hilo ON chat_mensajes(hilo_padre_id);
CREATE INDEX IF NOT EXISTS idx_chat_msg_menciones ON chat_mensajes USING GIN (menciones_ids);

-- Lecturas: hasta qué mensaje ha leído cada usuario en cada canal
CREATE TABLE IF NOT EXISTS chat_lecturas (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    canal_id UUID NOT NULL REFERENCES chat_canales(id) ON DELETE CASCADE,
    ultimo_mensaje_id BIGINT,
    leido_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, canal_id)
);

-- --- V34__cobroking.sql ---
-- Sprint 63: Co-broking entre agencias (MLS)

CREATE TABLE IF NOT EXISTS cobroking_red (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    agencia_socia_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre_red TEXT,
    comision_default_pct NUMERIC(5,2) DEFAULT 50,
    estado TEXT DEFAULT 'pendiente',  -- pendiente, activa, suspendida, terminada
    invitacion_token TEXT UNIQUE,
    invitacion_email TEXT,
    aceptada_at TIMESTAMPTZ,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cobr_user ON cobroking_red(user_id);
CREATE INDEX IF NOT EXISTS idx_cobr_socia ON cobroking_red(agencia_socia_id);

-- Propiedades compartidas
CREATE TABLE IF NOT EXISTS cobroking_propiedades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,
    user_propietario_id UUID NOT NULL,    -- agencia dueña del listing
    user_compartido_id UUID,               -- agencia con quien se comparte (NULL = abierto a toda la red)
    comision_oferente_pct NUMERIC(5,2) DEFAULT 50,
    comision_captador_pct NUMERIC(5,2) DEFAULT 50,
    condiciones TEXT,
    visible_solo_red BOOLEAN DEFAULT TRUE,
    activa BOOLEAN DEFAULT TRUE,
    fecha_inicio DATE DEFAULT CURRENT_DATE,
    fecha_fin DATE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cobr_prop ON cobroking_propiedades(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_cobr_prop_user ON cobroking_propiedades(user_propietario_id) WHERE activa = true;

-- Operaciones de co-broking (registro de quién aportó qué)
CREATE TABLE IF NOT EXISTS cobroking_operaciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    propiedad_id UUID NOT NULL,
    user_captador_id UUID NOT NULL,        -- quien tiene el listing
    user_oferente_id UUID NOT NULL,        -- quien aportó el comprador
    lead_id UUID,
    visita_id UUID,
    oferta_id UUID,
    importe_operacion NUMERIC(12,2),
    comision_total NUMERIC(10,2),
    comision_captador NUMERIC(10,2),
    comision_oferente NUMERIC(10,2),
    estado TEXT DEFAULT 'en_curso',        -- en_curso, cerrada, cancelada
    fecha_cierre DATE,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cobr_op_captador ON cobroking_operaciones(user_captador_id);
CREATE INDEX IF NOT EXISTS idx_cobr_op_oferente ON cobroking_operaciones(user_oferente_id);

-- --- V35__energy_certs.sql ---
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

-- --- V36__open_house.sql ---
-- Sprint 65: Open House events (jornadas de puertas abiertas)
CREATE TABLE IF NOT EXISTS open_houses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,

    titulo TEXT NOT NULL,
    descripcion TEXT,
    fecha DATE NOT NULL,
    hora_inicio TIME NOT NULL,
    hora_fin TIME NOT NULL,

    publico BOOLEAN DEFAULT TRUE,
    pagina_publica_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),

    max_asistentes INT,
    requiere_registro BOOLEAN DEFAULT TRUE,

    incluye_aperitivo BOOLEAN DEFAULT FALSE,
    incluye_valoracion BOOLEAN DEFAULT FALSE,
    incluye_financiacion BOOLEAN DEFAULT FALSE,

    estado TEXT DEFAULT 'programado',     -- programado, en_curso, finalizado, cancelado
    asistentes_count INT DEFAULT 0,

    foto_portada_url TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_oh_user ON open_houses(user_id);
CREATE INDEX IF NOT EXISTS idx_oh_token ON open_houses(pagina_publica_token);

CREATE TABLE IF NOT EXISTS open_house_asistentes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    open_house_id UUID NOT NULL REFERENCES open_houses(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    email TEXT NOT NULL,
    telefono TEXT,
    num_acompanantes INT DEFAULT 0,
    interes TEXT,                          -- comprar, mirar, vender_otra, etc.
    asistio BOOLEAN DEFAULT FALSE,
    lead_id UUID,
    feedback TEXT,
    nota_interes INT,                       -- 1-5
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_oh_asistente ON open_house_asistentes(open_house_id);

-- --- V37__petty_cash.sql ---
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

-- --- V38__price_history.sql ---
-- Sprint 67: Histórico de precios de propiedad
CREATE TABLE IF NOT EXISTS precio_historico (
    id BIGSERIAL PRIMARY KEY,
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    precio_anterior NUMERIC(12,2),
    precio_nuevo NUMERIC(12,2) NOT NULL,
    diferencia NUMERIC(12,2),
    diferencia_pct NUMERIC(6,2),
    motivo TEXT,                          -- bajada_negociacion, reforma, mercado, otros
    cambiado_por UUID,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_precio_hist_prop ON precio_historico(propiedad_id, created_at DESC);

-- Función para guardar histórico automáticamente
CREATE OR REPLACE FUNCTION track_price_change()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.precio IS DISTINCT FROM NEW.precio AND NEW.precio IS NOT NULL THEN
        INSERT INTO precio_historico (propiedad_id, user_id, precio_anterior, precio_nuevo, diferencia, diferencia_pct)
        VALUES (
            NEW.id,
            NEW.user_id,
            OLD.precio,
            NEW.precio,
            NEW.precio - COALESCE(OLD.precio, 0),
            CASE WHEN OLD.precio > 0 THEN ((NEW.precio - OLD.precio) / OLD.precio * 100) ELSE NULL END
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_track_price ON propiedades;
CREATE TRIGGER tg_track_price
    AFTER UPDATE ON propiedades
    FOR EACH ROW EXECUTE FUNCTION track_price_change();

-- --- V39__reviews.sql ---
-- Sprint 69: Reviews y testimonios públicos
CREATE TABLE IF NOT EXISTS reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    autor_nombre TEXT NOT NULL,
    autor_email TEXT,
    autor_foto_url TEXT,
    autor_rol TEXT,                       -- inquilino, propietario, comprador, vendedor

    puntuacion INT NOT NULL CHECK (puntuacion BETWEEN 1 AND 5),
    titulo TEXT,
    comentario TEXT NOT NULL,

    propiedad_id UUID,
    agente_id UUID REFERENCES users(id) ON DELETE SET NULL,

    aprobada BOOLEAN DEFAULT FALSE,        -- moderación
    destacada BOOLEAN DEFAULT FALSE,        -- aparece en landing
    publica BOOLEAN DEFAULT TRUE,
    respuesta TEXT,                        -- respuesta del agente
    respuesta_at TIMESTAMPTZ,

    fuente TEXT DEFAULT 'directa',         -- directa, google, idealista, fotocasa
    fuente_url TEXT,

    -- Pedido vía email tras visita/contrato
    solicitud_token TEXT UNIQUE,
    solicitada_at TIMESTAMPTZ,
    respondida_at TIMESTAMPTZ DEFAULT NOW(),

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reviews_user ON reviews(user_id) WHERE aprobada = true;
CREATE INDEX IF NOT EXISTS idx_reviews_agente ON reviews(agente_id);
CREATE INDEX IF NOT EXISTS idx_reviews_token ON reviews(solicitud_token);

-- --- V40__referrals.sql ---
-- Sprint 70: Programa de referidos
CREATE TABLE IF NOT EXISTS referidos_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    activo BOOLEAN DEFAULT TRUE,
    premio_referente TEXT,                -- "200€ Amazon", "1 mes gratis", etc.
    premio_referido TEXT,                  -- premio para quien usa el código
    premio_tipo TEXT DEFAULT 'fijo',       -- fijo, porcentaje, mes_gratis
    premio_valor NUMERIC(10,2),
    requiere_cierre BOOLEAN DEFAULT TRUE,  -- solo se paga si hay venta
    mensaje_landing TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS referidos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    referente_nombre TEXT NOT NULL,
    referente_email TEXT NOT NULL,
    referente_telefono TEXT,
    codigo TEXT UNIQUE NOT NULL,           -- código único compartible

    -- Persona referida
    referido_nombre TEXT,
    referido_email TEXT,
    referido_telefono TEXT,
    lead_id UUID,

    estado TEXT DEFAULT 'pendiente',       -- pendiente, contactado, calificado, cerrado_ok, cerrado_ko
    fecha_referencia TIMESTAMPTZ DEFAULT NOW(),
    fecha_cierre TIMESTAMPTZ,
    valor_operacion NUMERIC(12,2),

    -- Premio
    premio_concedido BOOLEAN DEFAULT FALSE,
    premio_pagado_at TIMESTAMPTZ,
    premio_detalle TEXT,

    notas TEXT
);

CREATE INDEX IF NOT EXISTS idx_referidos_user ON referidos(user_id);
CREATE INDEX IF NOT EXISTS idx_referidos_codigo ON referidos(codigo);
CREATE INDEX IF NOT EXISTS idx_referidos_estado ON referidos(estado);

-- --- V41__custom_dashboards.sql ---
-- Sprint 71: Dashboards personalizados con widgets
CREATE TABLE IF NOT EXISTS dashboards_custom (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    descripcion TEXT,
    es_default BOOLEAN DEFAULT FALSE,
    visible_para TEXT DEFAULT 'yo',       -- yo, mi_equipo, publico
    icono TEXT DEFAULT '📊',
    widgets JSONB NOT NULL DEFAULT '[]',  -- array de widgets configurados
    columnas INT DEFAULT 12,              -- grid 12 columnas
    fondo_color TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dash_custom_user ON dashboards_custom(user_id);

-- --- V42__wiki.sql ---
-- Sprint 72: Wiki / Knowledge base interna
CREATE TABLE IF NOT EXISTS wiki_categorias (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    descripcion TEXT,
    icono TEXT DEFAULT '📁',
    color TEXT DEFAULT '#3b82f6',
    orden INT DEFAULT 0,
    parent_id UUID REFERENCES wiki_categorias(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wiki_cat_user ON wiki_categorias(user_id);

CREATE TABLE IF NOT EXISTS wiki_articulos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    categoria_id UUID REFERENCES wiki_categorias(id) ON DELETE SET NULL,
    autor_id UUID REFERENCES users(id) ON DELETE SET NULL,

    slug TEXT NOT NULL,
    titulo TEXT NOT NULL,
    resumen TEXT,
    contenido_markdown TEXT,
    contenido_html TEXT,

    publicado BOOLEAN DEFAULT TRUE,
    visible_publico BOOLEAN DEFAULT FALSE,  -- accesible sin login

    tags TEXT[],
    busqueda_tsv TSVECTOR,                 -- full text search

    veces_leido INT DEFAULT 0,
    util_count INT DEFAULT 0,
    no_util_count INT DEFAULT 0,

    fijado BOOLEAN DEFAULT FALSE,
    destacado BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, slug)
);

CREATE INDEX IF NOT EXISTS idx_wiki_art_user ON wiki_articulos(user_id);
CREATE INDEX IF NOT EXISTS idx_wiki_art_cat ON wiki_articulos(categoria_id);
CREATE INDEX IF NOT EXISTS idx_wiki_art_search ON wiki_articulos USING GIN(busqueda_tsv);

CREATE OR REPLACE FUNCTION wiki_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.busqueda_tsv := to_tsvector('spanish',
        coalesce(NEW.titulo,'') || ' ' || coalesce(NEW.resumen,'') || ' ' || coalesce(NEW.contenido_markdown,''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_wiki_tsv ON wiki_articulos;
CREATE TRIGGER tg_wiki_tsv BEFORE INSERT OR UPDATE ON wiki_articulos
    FOR EACH ROW EXECUTE FUNCTION wiki_tsv_update();

-- --- V43__audit_log.sql ---
-- Sprint 73: Auditoría avanzada de todas las acciones
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID,                          -- usuario afectado / propietario del dato
    actor_id UUID,                         -- quien hizo la acción
    actor_nombre TEXT,
    actor_rol TEXT,

    accion TEXT NOT NULL,                  -- crear, actualizar, eliminar, leer, login, exportar, etc.
    tabla TEXT,                            -- propiedades, leads, contratos...
    registro_id TEXT,                      -- ID del registro afectado
    descripcion TEXT,

    -- Datos del cambio
    valores_antes JSONB,
    valores_despues JSONB,
    campos_cambiados TEXT[],

    -- Contexto
    ip TEXT,
    user_agent TEXT,
    metodo TEXT,                           -- GET, POST, PUT, DELETE
    url TEXT,

    -- Severidad / categoría
    nivel TEXT DEFAULT 'info',             -- info, warning, critical, security
    categoria TEXT,                        -- datos, seguridad, financiero, gdpr, exportacion

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tabla ON audit_log(tabla, registro_id);
CREATE INDEX IF NOT EXISTS idx_audit_nivel ON audit_log(nivel, created_at DESC) WHERE nivel != 'info';

-- --- V44__sepa.sql ---
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

-- --- V45__bank_reconciliation.sql ---
-- Sprint 76: Conciliación bancaria
CREATE TABLE IF NOT EXISTS bank_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    cuenta_id UUID REFERENCES cuentas_caja(id) ON DELETE SET NULL,
    nombre_archivo TEXT,
    formato TEXT,                          -- csv, mt940, n43
    num_lineas INT,
    num_conciliadas INT DEFAULT 0,
    num_pendientes INT DEFAULT 0,
    fecha_desde DATE, fecha_hasta DATE,
    saldo_inicial NUMERIC(12,2), saldo_final NUMERIC(12,2),
    estado TEXT DEFAULT 'pendiente',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS bank_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id UUID REFERENCES bank_imports(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    fecha DATE NOT NULL,
    valor_fecha DATE,
    importe NUMERIC(12,2) NOT NULL,
    concepto TEXT,
    referencia TEXT,
    contraparte_nombre TEXT,
    contraparte_iban TEXT,
    saldo NUMERIC(12,2),

    estado TEXT DEFAULT 'pendiente',       -- pendiente, conciliada, ignorada, manual
    factura_id UUID,
    movimiento_caja_id UUID,
    mandato_sepa_id UUID,
    score_match NUMERIC(5,2),              -- 0-100 confianza
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bank_tx_user ON bank_transactions(user_id, fecha DESC);
CREATE INDEX IF NOT EXISTS idx_bank_tx_estado ON bank_transactions(estado);

-- --- V46__integraciones.sql ---
-- Sprint 77: Tabla unificada de integraciones externas
CREATE TABLE IF NOT EXISTS integraciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    proveedor TEXT NOT NULL,               -- holded, sage, a3, google_calendar, gmail, etc.
    api_key TEXT,
    refresh_token TEXT,
    access_token TEXT,
    token_expira_at TIMESTAMPTZ,
    config_extra JSONB,
    activo BOOLEAN DEFAULT TRUE,
    ultimo_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, proveedor)
);
CREATE INDEX IF NOT EXISTS idx_integ_user ON integraciones(user_id);

-- --- V47__imap_inbox.sql ---
-- Sprint 79: IMAP Inbox
CREATE TABLE IF NOT EXISTS imap_cuentas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    imap_host TEXT NOT NULL,
    imap_port INT DEFAULT 993,
    imap_user TEXT,
    imap_pass TEXT,
    smtp_host TEXT,
    smtp_port INT DEFAULT 587,
    activa BOOLEAN DEFAULT TRUE,
    ultimo_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS emails_inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cuenta_id UUID REFERENCES imap_cuentas(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    message_id TEXT,
    de_email TEXT, de_nombre TEXT,
    para TEXT[], cc TEXT[],
    asunto TEXT, cuerpo_texto TEXT, cuerpo_html TEXT,
    leido BOOLEAN DEFAULT FALSE,
    estrella BOOLEAN DEFAULT FALSE,
    archivado BOOLEAN DEFAULT FALSE,
    lead_id UUID, propiedad_id UUID,
    etiqueta TEXT,                         -- inbox, sent, draft, trash
    fecha TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_emails_user ON emails_inbox(user_id, fecha DESC);
CREATE INDEX IF NOT EXISTS idx_emails_unread ON emails_inbox(user_id) WHERE leido = false;

-- --- V48__whitelabel.sql ---
-- Sprint 82: Whitelabel
CREATE TABLE IF NOT EXISTS whitelabel_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,

    nombre_marca TEXT,
    subdominio TEXT UNIQUE,                -- agencia.inmobilia-pro.com

    logo_url TEXT,
    logo_dark_url TEXT,
    favicon_url TEXT,
    color_primario TEXT DEFAULT '#3b82f6',
    color_secundario TEXT DEFAULT '#1e40af',
    color_acento TEXT DEFAULT '#10b981',

    email_remitente TEXT,
    email_pie TEXT,
    soporte_telefono TEXT,
    soporte_email TEXT,

    sitio_web TEXT,
    redes_sociales JSONB,

    css_personalizado TEXT,
    js_personalizado TEXT,

    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wl_subdom ON whitelabel_config(subdominio);

-- --- V49__owner_statements.sql ---
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

-- --- V50__virtual_tours.sql ---
-- Sprint 88: Tours virtuales 360
CREATE TABLE IF NOT EXISTS tours_virtuales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    titulo TEXT,
    descripcion TEXT,
    public_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    embed_url TEXT,                        -- URL Matterport/iguide/3DVista
    proveedor TEXT,                        -- matterport, iguide, eyespy, custom
    escenas JSONB DEFAULT '[]',            -- [{"id":"1","nombre":"Salón","imagen_url":"...","hotspots":[...]}]
    activo BOOLEAN DEFAULT TRUE,
    visitas_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tours_user ON tours_virtuales(user_id);
CREATE INDEX IF NOT EXISTS idx_tours_token ON tours_virtuales(public_token);

-- --- V51__nps.sql ---
-- Sprint 91: NPS surveys
CREATE TABLE IF NOT EXISTS nps_respuestas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    cliente_nombre TEXT,
    cliente_email TEXT,
    contrato_id UUID, visita_id UUID,
    puntuacion INT CHECK (puntuacion BETWEEN 0 AND 10),
    comentario TEXT,
    categoria TEXT,                       -- detractor (0-6), pasivo (7-8), promotor (9-10)
    token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    solicitada_at TIMESTAMPTZ DEFAULT NOW(),
    respondida_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_nps_user ON nps_respuestas(user_id);
CREATE INDEX IF NOT EXISTS idx_nps_token ON nps_respuestas(token);

-- --- V52__agent_expenses.sql ---
-- Sprint 92: Gastos de agente
CREATE TABLE IF NOT EXISTS gastos_agente (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    agente_id UUID REFERENCES users(id) ON DELETE CASCADE,
    fecha DATE DEFAULT CURRENT_DATE,
    tipo TEXT NOT NULL,                   -- kilometraje, dieta_comida, parking, peaje, combustible, hotel, transporte, otros
    concepto TEXT,
    importe NUMERIC(10,2),
    km NUMERIC(8,2),                       -- si es kilometraje
    importe_por_km NUMERIC(5,3) DEFAULT 0.26,
    origen TEXT, destino TEXT,
    propiedad_id UUID, visita_id UUID,
    ticket_url TEXT,
    estado TEXT DEFAULT 'pendiente',      -- pendiente, aprobado, rechazado, pagado
    aprobado_por UUID, aprobado_at TIMESTAMPTZ,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_gastos_ag ON gastos_agente(agente_id, fecha DESC);

-- --- V53__virtual_staging.sql ---
-- Sprint 95: Staging digital (mobiliario virtual)
CREATE TABLE IF NOT EXISTS staging_proyectos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    nombre TEXT,
    imagen_original_url TEXT,
    imagen_staged_url TEXT,
    estilo TEXT,                            -- moderno, nordico, industrial, clasico, minimalista
    tipo_habitacion TEXT,
    estado TEXT DEFAULT 'pendiente',        -- pendiente, procesando, completado, error
    coste NUMERIC(8,2),
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_staging_user ON staging_proyectos(user_id);

-- --- V54__whatsapp_business.sql ---
-- Sprint 96: WhatsApp Business Cloud API (Meta)
CREATE TABLE IF NOT EXISTS whatsapp_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    phone_number_id TEXT,
    business_account_id TEXT,
    access_token TEXT,
    webhook_verify_token TEXT,
    numero_emisor TEXT,                    -- +34 600 000 000
    nombre_emisor TEXT,
    activo BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS whatsapp_plantillas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,                  -- bienvenida_lead
    nombre_meta TEXT,                       -- nombre aprobado por Meta
    categoria TEXT,                         -- MARKETING, UTILITY, AUTHENTICATION
    idioma TEXT DEFAULT 'es',
    cuerpo TEXT,                            -- "Hola {{1}}, gracias por contactar..."
    variables JSONB,
    estado_aprobacion TEXT DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS whatsapp_mensajes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    chat_id TEXT,                           -- número receptor formateado
    direccion TEXT,                         -- entrante, saliente
    tipo TEXT,                              -- texto, imagen, plantilla, audio, video, documento, ubicacion
    mensaje TEXT,
    media_url TEXT,
    template_name TEXT,
    template_params JSONB,
    estado TEXT DEFAULT 'enviado',          -- enviado, entregado, leido, fallido
    wa_message_id TEXT,
    lead_id UUID,
    inquilino_id UUID,
    propietario_id UUID,
    leido BOOLEAN DEFAULT FALSE,
    fecha TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_wa_msg_user ON whatsapp_mensajes(user_id, fecha DESC);
CREATE INDEX IF NOT EXISTS idx_wa_msg_chat ON whatsapp_mensajes(chat_id);

-- --- V55__public_microsite.sql ---
-- Sprint 97: Microsite público
CREATE TABLE IF NOT EXISTS microsites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    slug TEXT UNIQUE NOT NULL,                  -- nombre.inmobilia-pro.com o /m/{slug}
    nombre_publico TEXT,
    tagline TEXT,
    descripcion TEXT,

    hero_imagen TEXT,
    hero_titulo TEXT,
    hero_subtitulo TEXT,
    hero_cta_texto TEXT DEFAULT 'Ver propiedades',
    hero_cta_url TEXT,

    sobre_nosotros_titulo TEXT,
    sobre_nosotros_texto TEXT,
    sobre_nosotros_foto TEXT,

    servicios JSONB DEFAULT '[]',                -- [{titulo, descripcion, icono}]
    equipo JSONB DEFAULT '[]',                   -- [{nombre, cargo, foto, email}]
    testimonios_destacados UUID[],                -- referencias a reviews

    direccion TEXT,
    telefono TEXT,
    email TEXT,
    horario TEXT,
    google_maps_url TEXT,

    redes_facebook TEXT,
    redes_instagram TEXT,
    redes_linkedin TEXT,
    redes_twitter TEXT,
    redes_youtube TEXT,

    seo_title TEXT,
    seo_description TEXT,
    seo_keywords TEXT,
    seo_og_image TEXT,
    google_analytics_id TEXT,

    color_primario TEXT DEFAULT '#3b82f6',
    fuente TEXT DEFAULT 'Inter',
    publicado BOOLEAN DEFAULT FALSE,

    visitas_count INT DEFAULT 0,
    leads_capturados INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_microsite_slug ON microsites(slug);

-- --- V56__promociones.sql ---
-- Sprint 98: Promociones de obra nueva
CREATE TABLE IF NOT EXISTS promociones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    slug TEXT,
    promotora TEXT,
    arquitecto TEXT,
    constructora TEXT,

    direccion TEXT,
    municipio TEXT, provincia TEXT, codigo_postal TEXT,
    lat NUMERIC(10,7), lng NUMERIC(10,7),

    tipo TEXT,                              -- residencial, comercial, mixto
    num_unidades INT,
    num_unidades_vendidas INT DEFAULT 0,

    fecha_inicio_obras DATE,
    fecha_entrega DATE,
    fase TEXT DEFAULT 'preventa',           -- preventa, obras, terminado, entregado

    precio_desde NUMERIC(12,2),
    precio_hasta NUMERIC(12,2),

    memoria_calidades TEXT,
    plano_general_url TEXT,
    render_principal_url TEXT,
    galeria_renders TEXT[],
    tour_360_url TEXT,
    video_url TEXT,

    seo_descripcion TEXT,
    landing_slug TEXT UNIQUE,
    publicada BOOLEAN DEFAULT FALSE,

    leads_capturados INT DEFAULT 0,
    visitas_count INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_promociones_user ON promociones(user_id);
CREATE INDEX IF NOT EXISTS idx_promociones_landing ON promociones(landing_slug);

-- Unidades de la promoción
CREATE TABLE IF NOT EXISTS promocion_unidades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promocion_id UUID NOT NULL REFERENCES promociones(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    bloque TEXT,
    planta INT,
    letra TEXT,
    portal TEXT,
    tipologia TEXT,                         -- 1d, 2d, 3d, atico, duplex
    num_habitaciones INT,
    num_banos INT,
    superficie_util NUMERIC(7,2),
    superficie_construida NUMERIC(7,2),
    terraza_m2 NUMERIC(6,2),
    orientacion TEXT,
    precio NUMERIC(12,2),
    plaza_garaje BOOLEAN DEFAULT FALSE,
    trastero BOOLEAN DEFAULT FALSE,
    estado TEXT DEFAULT 'disponible',       -- disponible, reservada, vendida
    cliente_id UUID,
    fecha_venta DATE,
    plano_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_unidades_promo ON promocion_unidades(promocion_id);

-- --- V57__newsletter.sql ---
-- Sprint 100: Newsletter + suscriptores
CREATE TABLE IF NOT EXISTS newsletter_suscriptores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    nombre TEXT,
    intereses TEXT[],                    -- ["alquiler", "venta", "obra_nueva"]
    zona TEXT,
    presupuesto_min NUMERIC(12,2),
    presupuesto_max NUMERIC(12,2),
    fuente TEXT,                          -- web, microsite, importado
    confirmado BOOLEAN DEFAULT FALSE,
    confirmacion_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    confirmado_at TIMESTAMPTZ,
    baja_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, email)
);
CREATE INDEX IF NOT EXISTS idx_news_user ON newsletter_suscriptores(user_id) WHERE baja_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_news_token ON newsletter_suscriptores(confirmacion_token);

CREATE TABLE IF NOT EXISTS newsletter_envios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    asunto TEXT,
    contenido_html TEXT,
    enviados INT DEFAULT 0,
    abiertos INT DEFAULT 0,
    fecha_envio TIMESTAMPTZ DEFAULT NOW()
);

-- --- V58__sms.sql ---
-- Sprint 101: SMS gateway
CREATE TABLE IF NOT EXISTS sms_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    proveedor TEXT DEFAULT 'twilio',         -- twilio, esendex, lleida
    account_sid TEXT,
    auth_token TEXT,
    numero_emisor TEXT,
    sender_id TEXT,                          -- ID alfanumérico (sólo Esendex/Lleida)
    activo BOOLEAN DEFAULT FALSE,
    credito NUMERIC(8,2),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sms_mensajes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    destinatario TEXT NOT NULL,
    mensaje TEXT NOT NULL,
    estado TEXT DEFAULT 'pendiente',         -- pendiente, enviado, entregado, fallido
    proveedor_id TEXT,
    coste NUMERIC(6,4),
    lead_id UUID,
    error_mensaje TEXT,
    enviado_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sms_user ON sms_mensajes(user_id, enviado_at DESC);

-- --- V59__online_reservations.sql ---
-- Sprint 105: Reservas online con pago Stripe
CREATE TABLE IF NOT EXISTS reservas_online (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    promocion_unidad_id UUID,

    -- Cliente
    nombre TEXT NOT NULL,
    email TEXT NOT NULL,
    telefono TEXT,
    dni TEXT,

    -- Pago
    importe NUMERIC(10,2) NOT NULL,        -- señal/reserva
    stripe_payment_intent TEXT,
    stripe_charge_id TEXT,
    moneda TEXT,

    estado TEXT DEFAULT 'pendiente',       -- pendiente, pagada, cancelada, devuelta, convertida
    fecha_caducidad TIMESTAMPTZ,
    fecha_pago TIMESTAMPTZ,

    contrato_arras_id UUID,
    token_acceso TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),

    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_reservas_user ON reservas_online(user_id);
CREATE INDEX IF NOT EXISTS idx_reservas_token ON reservas_online(token_acceso);

-- --- V60__busqueda_inversa.sql ---
-- Sprint 108: Búsqueda inversa (clientes que reciben alertas)
CREATE TABLE IF NOT EXISTS busquedas_guardadas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lead_id UUID,
    cliente_nombre TEXT,
    cliente_email TEXT,
    cliente_telefono TEXT,
    nombre_busqueda TEXT,

    -- Criterios
    operacion TEXT,                          -- venta, alquiler
    tipo TEXT,                                -- piso, casa, etc.
    municipio TEXT,
    zonas TEXT[],
    precio_min NUMERIC(12,2),
    precio_max NUMERIC(12,2),
    metros_min NUMERIC(7,2),
    metros_max NUMERIC(7,2),
    habitaciones_min INT,
    banos_min INT,
    requiere_garaje BOOLEAN,
    requiere_ascensor BOOLEAN,
    requiere_terraza BOOLEAN,

    -- Notificaciones
    notificar_email BOOLEAN DEFAULT TRUE,
    notificar_whatsapp BOOLEAN DEFAULT FALSE,
    frecuencia TEXT DEFAULT 'inmediata',     -- inmediata, diaria, semanal

    activa BOOLEAN DEFAULT TRUE,
    matches_count INT DEFAULT 0,
    ultima_notificacion TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_busq_user ON busquedas_guardadas(user_id) WHERE activa = true;

-- --- V61__granular_permissions.sql ---
-- Sprint 109: Permisos granulares
CREATE TABLE IF NOT EXISTS permisos_personalizados (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID REFERENCES users(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    -- Permisos por módulo (CRUD)
    propiedades_leer BOOLEAN DEFAULT TRUE, propiedades_crear BOOLEAN DEFAULT TRUE, propiedades_editar BOOLEAN DEFAULT TRUE, propiedades_borrar BOOLEAN DEFAULT FALSE,
    leads_leer BOOLEAN DEFAULT TRUE, leads_crear BOOLEAN DEFAULT TRUE, leads_editar BOOLEAN DEFAULT TRUE, leads_borrar BOOLEAN DEFAULT FALSE,
    contratos_leer BOOLEAN DEFAULT TRUE, contratos_crear BOOLEAN DEFAULT FALSE, contratos_editar BOOLEAN DEFAULT FALSE, contratos_borrar BOOLEAN DEFAULT FALSE,
    facturas_leer BOOLEAN DEFAULT FALSE, facturas_crear BOOLEAN DEFAULT FALSE,
    inquilinos_leer BOOLEAN DEFAULT TRUE, inquilinos_editar BOOLEAN DEFAULT TRUE,
    propietarios_leer BOOLEAN DEFAULT FALSE,

    -- Permisos especiales
    puede_ver_precios BOOLEAN DEFAULT TRUE,
    puede_ver_comisiones BOOLEAN DEFAULT FALSE,
    puede_enviar_emails BOOLEAN DEFAULT TRUE,
    puede_aprobar_gastos BOOLEAN DEFAULT FALSE,
    puede_acceder_caja BOOLEAN DEFAULT FALSE,
    puede_exportar_datos BOOLEAN DEFAULT FALSE,
    puede_gestionar_usuarios BOOLEAN DEFAULT FALSE,
    puede_acceder_auditoria BOOLEAN DEFAULT FALSE,

    -- Restricciones
    solo_sus_propiedades BOOLEAN DEFAULT FALSE,
    solo_sus_leads BOOLEAN DEFAULT FALSE,
    max_leads_dia INT,
    max_emails_dia INT,
    horario_acceso TEXT,                       -- "09:00-20:00"

    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id)
);

-- --- V62__voice_notes.sql ---
-- Sprint 110: Notas de voz con transcripción
CREATE TABLE IF NOT EXISTS notas_voz (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    autor_id UUID REFERENCES users(id) ON DELETE SET NULL,

    audio_url TEXT,
    duracion_segundos INT,
    transcripcion TEXT,
    transcripcion_idioma TEXT DEFAULT 'es',
    resumen_ia TEXT,

    lead_id UUID, propiedad_id UUID, contrato_id UUID, visita_id UUID,

    fecha_grabacion TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_voz_user ON notas_voz(user_id, fecha_grabacion DESC);
CREATE INDEX IF NOT EXISTS idx_voz_lead ON notas_voz(lead_id);

-- 5. DIFERENCIADORES
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

-- 6. AUDIT BACKFILL
DO $$
DECLARE t TEXT;
  tables TEXT[] := ARRAY['tareas','seguros','siniestros','ofertas','visitas','firmas_documento','workflows','pipelines','campanas','reviews','referidos','promociones','reservas_online','busquedas_guardadas','notas_voz'];
BEGIN
  FOREACH t IN ARRAY tables LOOP
    BEGIN
      EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS created_by UUID', t);
      EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_by UUID', t);
      EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()', t);
    EXCEPTION WHEN undefined_table THEN NULL;
    END;
  END LOOP;
END $$;

-- ############## V63__account_requests.sql ##############
-- Sprint 112: Solicitudes de cuenta (modelo aprobación manual)
CREATE TABLE IF NOT EXISTS solicitudes_cuenta (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre TEXT NOT NULL,
    empresa TEXT NOT NULL,
    email TEXT NOT NULL,
    telefono TEXT NOT NULL,
    plan TEXT DEFAULT 'pro',                -- free, pro, agency, enterprise
    num_propiedades TEXT,
    mensaje TEXT,
    origen TEXT DEFAULT 'landing',          -- landing, microsite, referido
    ip TEXT,
    user_agent TEXT,

    estado TEXT DEFAULT 'pendiente',        -- pendiente, aprobada, rechazada, contactada
    aprobada_por UUID REFERENCES users(id) ON DELETE SET NULL,
    aprobada_at TIMESTAMPTZ,
    user_creado_id UUID REFERENCES users(id) ON DELETE SET NULL,
    motivo_rechazo TEXT,
    notas_admin TEXT,

    -- Para email automático
    password_temporal TEXT,
    email_enviado_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_solicitudes_estado ON solicitudes_cuenta(estado, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_solicitudes_email ON solicitudes_cuenta(email);

-- ############## V64__solicitudes_approval_token.sql ##############
-- Sprint extra: tokens de aprobación/rechazo desde email
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS approval_token TEXT;
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS token_expires_at TIMESTAMPTZ;
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS token_used_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_solicitudes_token ON solicitudes_cuenta(approval_token) WHERE token_used_at IS NULL;

-- ############## V65__valoracion_avm.sql ##############
-- Sprint diferenciación: Valoración instantánea (AVM) como imán de captación
CREATE TABLE IF NOT EXISTS valoraciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    -- Datos del inmueble valorado
    tipo TEXT,                       -- piso, casa, atico, chalet, local...
    municipio TEXT,
    provincia TEXT,
    zona TEXT,
    metros NUMERIC(8,2),
    habitaciones INT,
    banos INT,
    estado TEXT,                     -- nuevo, reformado, buen_estado, a_reformar
    planta INT,
    ascensor BOOLEAN,
    garaje BOOLEAN,
    terraza BOOLEAN,
    ano_construccion INT,

    -- Resultado de la valoración
    precio_estimado NUMERIC(12,2),
    precio_min NUMERIC(12,2),
    precio_max NUMERIC(12,2),
    eur_m2 NUMERIC(8,2),
    confianza TEXT,                  -- alta, media, baja
    metodo TEXT,                     -- avm_tabla, avm_ia, comparables

    -- Datos del propietario (lead capturado)
    propietario_nombre TEXT,
    propietario_email TEXT,
    propietario_telefono TEXT,
    quiere_vender TEXT,              -- ahora, 3-6m, explorando, no
    lead_id UUID,

    origen TEXT DEFAULT 'valoracion_publica',
    ip TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_valoraciones_user ON valoraciones(user_id, created_at DESC);

-- Token público por agencia para compartir/embeber la valoración
ALTER TABLE users ADD COLUMN IF NOT EXISTS valoracion_token TEXT;

-- ############## V66__portal_propietario.sql ##############
-- Sprint diferenciación: Portal de Transparencia del Propietario
-- El propietario ve en tiempo real qué está pasando con su inmueble
-- (visitas, interés, feedback anónimo, ofertas) → renueva la exclusiva y no se siente ignorado.

ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_token TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_activo BOOLEAN DEFAULT TRUE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_vistas INT DEFAULT 0;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_ultima_vista TIMESTAMPTZ;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_mensaje_agente TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS propietario_nombre TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS propietario_email TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_prop_portal_token ON propiedades(portal_token) WHERE portal_token IS NOT NULL;

-- Mensajes del propietario al agente desde el portal
CREATE TABLE IF NOT EXISTS portal_mensajes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    autor TEXT DEFAULT 'propietario',   -- propietario, agente
    mensaje TEXT NOT NULL,
    leido BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_portal_mensajes_prop ON portal_mensajes(propiedad_id, created_at DESC);

-- ############## V67__captacion_farming.sql ##############
-- Sprint diferenciación: Captación proactiva / Farming por zona con IA
-- El agente sale a buscar exclusivas: elige zona + ángulo, la IA redacta los
-- mensajes (carta/WhatsApp/email) con el enlace de valoración, y gestiona los
-- destinatarios como un mini-pipeline de captación. Cierra el embudo:
-- Farming (outbound) → Valoración (captura) → Portal (retención).

CREATE TABLE IF NOT EXISTS captacion_campanas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    zona TEXT,
    tipo_objetivo TEXT,              -- propietarios_piso, chalets, locales, herederos...
    angulo TEXT,                     -- tengo_comprador, valoracion_gratis, vendi_cerca, mercado_alto...

    mensaje_carta TEXT,
    mensaje_whatsapp TEXT,
    mensaje_email TEXT,

    estado TEXT DEFAULT 'activa',    -- activa, pausada, cerrada
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_captacion_campanas_user ON captacion_campanas(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS captacion_destinatarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    campana_id UUID REFERENCES captacion_campanas(id) ON DELETE CASCADE,

    nombre TEXT,
    direccion TEXT,
    telefono TEXT,
    email TEXT,

    estado TEXT DEFAULT 'pendiente', -- pendiente, contactado, respondido, captado, descartado
    notas TEXT,
    lead_id UUID,
    contactado_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_captacion_dest_campana ON captacion_destinatarios(campana_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_captacion_dest_user ON captacion_destinatarios(user_id);

-- ############## V68__informe_mercado.sql ##############
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

-- ############## V69__coaching_objeciones.sql ##############
-- Sprint diferenciación: Entrenador IA de objeciones (role-play coach)
-- El agente practica objeciones reales de captación/venta contra un cliente IA
-- que se mantiene en personaje, y recibe una evaluación puntuada con feedback.
-- Diferenciador de formación: muy pocos CRM inmobiliarios tienen coach de ventas.

CREATE TABLE IF NOT EXISTS coaching_sesiones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    agente_id UUID,

    escenario TEXT,                  -- clave del escenario practicado
    escenario_titulo TEXT,
    rol_cliente TEXT,                -- propietario, comprador
    dificultad TEXT DEFAULT 'media', -- facil, media, dificil

    transcripcion_json TEXT,         -- historial completo (JSON)
    n_turnos INT DEFAULT 0,
    puntuacion INT,                  -- 0-100
    fortalezas TEXT,
    mejoras TEXT,
    respuesta_modelo TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_coaching_user ON coaching_sesiones(user_id, created_at DESC);

-- ############## V70__simulador_hipoteca.sql ##############
-- Sprint diferenciación: Simulador de hipoteca / capacidad de compra
-- Imán de captación de COMPRADORES (espejo de la valoración, que capta vendedores).
-- El comprador calcula su cuota y su capacidad máxima de compra y queda captado
-- como lead con su presupuesto y financiación → alimenta el motor de matching.

CREATE TABLE IF NOT EXISTS simulaciones_hipoteca (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    -- Datos introducidos
    precio_objetivo NUMERIC(12,2),
    ahorro NUMERIC(12,2),
    ingresos_mensuales NUMERIC(10,2),
    plazo_anos INT,
    tipo_interes NUMERIC(5,2),

    -- Resultado
    cuota_estimada NUMERIC(10,2),
    capital_prestamo NUMERIC(12,2),
    capacidad_max NUMERIC(12,2),       -- precio máximo de compra estimado
    esfuerzo_pct NUMERIC(5,2),         -- % de ingresos que va a la cuota
    viable BOOLEAN,

    -- Lead capturado
    comprador_nombre TEXT,
    comprador_email TEXT,
    comprador_telefono TEXT,
    zona_interes TEXT,
    lead_id UUID,

    ip TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_simulaciones_user ON simulaciones_hipoteca(user_id, created_at DESC);

-- Token público por agencia para compartir el simulador
ALTER TABLE users ADD COLUMN IF NOT EXISTS simulador_token TEXT;

-- ############## V71__portal_comprador.sql ##############
-- Sprint diferenciación: Portal del Comprador (selección curada compartible)
-- Espejo del portal del propietario, pero para compradores: el agente arma una
-- selección de inmuebles a medida y la comparte por enlace; el comprador marca
-- ❤️ favorito / 👎 descarto / 🗓️ pedir visita y el agente recibe el feedback.
-- Sustituye el "te mando links de Idealista por WhatsApp" por una experiencia de marca.

CREATE TABLE IF NOT EXISTS selecciones_comprador (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lead_id UUID,

    token TEXT UNIQUE,
    titulo TEXT,
    mensaje_agente TEXT,
    comprador_nombre TEXT,
    activo BOOLEAN DEFAULT TRUE,
    vistas INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_selecciones_user ON selecciones_comprador(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_selecciones_token ON selecciones_comprador(token) WHERE token IS NOT NULL;

CREATE TABLE IF NOT EXISTS seleccion_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    seleccion_id UUID REFERENCES selecciones_comprador(id) ON DELETE CASCADE,
    propiedad_id UUID,

    orden INT DEFAULT 0,
    reaccion TEXT,                  -- favorito, descartado, visita (null = sin reaccionar)
    comentario TEXT,
    reaccion_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_seleccion_items_sel ON seleccion_items(seleccion_id, orden);

-- ############## V72__marketplace_tinder.sql ##############
-- Sprint diferenciación: "Tinder de pisos" (marketplace de compradores)
-- En la misma web pública, un buscador gratuito tipo swipe para compradores finales.
-- Solo aparecen inmuebles de agencias que activan el add-on (paga +25% sobre su plan).
-- Cada "me gusta" con contacto se convierte en lead para la agencia → monetización.

-- Add-on del marketplace por agencia (+25% sobre el plan de pago)
ALTER TABLE users ADD COLUMN IF NOT EXISTS tinder_activo BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS tinder_swipes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id UUID REFERENCES users(id) ON DELETE CASCADE,   -- dueño del inmueble
    propiedad_id UUID,

    accion TEXT,                    -- like, superlike, pass
    buyer_device TEXT,              -- id de dispositivo (localStorage) para deduplicar
    buyer_nombre TEXT,
    buyer_email TEXT,
    buyer_telefono TEXT,
    buyer_zona TEXT,

    lead_id UUID,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tinder_swipes_agency ON tinder_swipes(agency_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tinder_swipes_prop ON tinder_swipes(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_tinder_swipes_accion ON tinder_swipes(accion);

-- ############## V73__marketplace_match.sql ##############
-- Marketplace: modo "match" (la agencia también acepta al comprador)
-- Cuando el agente hace match, el comprador recibe un email tipo "¡es un match!".
ALTER TABLE tinder_swipes ADD COLUMN IF NOT EXISTS matched BOOLEAN DEFAULT FALSE;
ALTER TABLE tinder_swipes ADD COLUMN IF NOT EXISTS matched_at TIMESTAMPTZ;

-- ############## V74__agente_autopiloto.sql ##############
-- Sprint diferenciación: Autopiloto del Agente (human-in-the-loop)
-- La IA analiza la cartera y PROPONE acciones concretas ya preparadas (mensaje
-- redactado, precio calculado, tarea) en una bandeja. El agente aprueba o
-- descarta cada una con un clic; al aprobar, se ejecuta de verdad.

CREATE TABLE IF NOT EXISTS agente_acciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    tipo TEXT NOT NULL,          -- mensaje_lead, enviar_email, bajar_precio, crear_tarea, marcar_contactado, avisar_match
    prioridad TEXT DEFAULT 'media', -- alta, media, baja
    titulo TEXT,                 -- resumen de la acción para la bandeja
    motivo TEXT,                 -- por qué la propone
    borrador TEXT,               -- contenido editable (mensaje redactado, etc.)
    payload_json TEXT,           -- datos para ejecutar (JSON)

    lead_id UUID,
    propiedad_id UUID,

    estado TEXT DEFAULT 'propuesta',  -- propuesta, aprobada, descartada, ejecutada, error
    resultado TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    resuelta_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_agente_acciones_user ON agente_acciones(user_id, estado, created_at DESC);

-- ############## V75__seguimiento_automatico.sql ##############
-- Sprint diferenciación: Seguimiento Automático Inteligente (Auto-nurture)
-- Cada lead entra en una cadencia que el Agente ejecuta en piloto automático:
-- bienvenida (día 0) → recordatorio (día 2) → aporta valor (día 5) → llamar (día 9).
-- Cada mensaje lo redacta la IA. La cadencia se para sola cuando el lead avanza.

-- Config por agencia
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_seguimiento BOOLEAN DEFAULT FALSE;
-- Modo: false = proponer en el Autopiloto (revisar); true = enviar automáticamente
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_seguimiento_envia BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS cadencia_inscripciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lead_id UUID,
    lead_nombre TEXT,

    tipo TEXT DEFAULT 'comprador',   -- comprador | vendedor
    paso INT DEFAULT 0,              -- índice del próximo paso a ejecutar
    estado TEXT DEFAULT 'activa',    -- activa | completada | pausada | parada
    proximo_at TIMESTAMPTZ,          -- cuándo toca el próximo paso
    ultimo_at TIMESTAMPTZ,
    pasos_dados INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cadencia_user ON cadencia_inscripciones(user_id, estado);
CREATE INDEX IF NOT EXISTS idx_cadencia_lead ON cadencia_inscripciones(lead_id);
CREATE INDEX IF NOT EXISTS idx_cadencia_proximo ON cadencia_inscripciones(estado, proximo_at);

-- ############## V76__autopiloto_auto.sql ##############
-- Autopiloto proactivo: generar propuestas automáticamente cada mañana
ALTER TABLE users ADD COLUMN IF NOT EXISTS autopiloto_auto BOOLEAN DEFAULT FALSE;

-- ############## V77__notas_encargo.sql ##############
-- Sprint diferenciación: Nota de Encargo de venta (cierra la captación)
-- Genera el documento del encargo/exclusiva con los datos reales (agencia,
-- propietario, inmueble, comisión, duración) listo para imprimir y firmar.
-- Es una plantilla estándar editable, NO asesoramiento jurídico.

CREATE TABLE IF NOT EXISTS notas_encargo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID,

    -- Propietario
    propietario_nombre TEXT,
    propietario_dni TEXT,
    propietario_email TEXT,
    propietario_telefono TEXT,
    propietario_direccion TEXT,

    -- Inmueble (snapshot)
    inmueble_texto TEXT,
    precio NUMERIC(12,2),

    -- Condiciones del encargo
    comision_pct NUMERIC(5,2) DEFAULT 3,
    exclusiva BOOLEAN DEFAULT TRUE,
    duracion_meses INT DEFAULT 6,
    observaciones TEXT,

    estado TEXT DEFAULT 'borrador',   -- borrador, firmado
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notas_encargo_user ON notas_encargo(user_id, created_at DESC);

-- ############## V78__hojas_visita.sql ##############
-- Sprint diferenciación: Hoja de visita firmable (protege la comisión)
-- El visitante firma que la agencia le enseñó el inmueble → evita que el
-- comprador vaya directo al vendedor saltándose al agente. Documento imprimible.

CREATE TABLE IF NOT EXISTS hojas_visita (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID,
    visita_id UUID,

    visitante_nombre TEXT,
    visitante_dni TEXT,
    visitante_telefono TEXT,
    visitante_email TEXT,

    inmueble_texto TEXT,
    fecha_visita DATE,
    observaciones TEXT,

    estado TEXT DEFAULT 'pendiente',   -- pendiente, firmada
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_hojas_visita_user ON hojas_visita(user_id, created_at DESC);

-- ############## V79__leads_asignacion.sql ##############
-- Asignación de leads a agentes (habilita reparto y panel de rendimiento del equipo)
ALTER TABLE leads ADD COLUMN IF NOT EXISTS asignado_a UUID;
CREATE INDEX IF NOT EXISTS idx_leads_asignado ON leads(asignado_a);

-- ===== Versión Latinoamérica (V100) =====
ALTER TABLE users ADD COLUMN IF NOT EXISTS pais TEXT DEFAULT 'MX';
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS pais TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS moneda TEXT;
ALTER TABLE portal_feeds ADD COLUMN IF NOT EXISTS feed_enabled BOOLEAN DEFAULT true;
