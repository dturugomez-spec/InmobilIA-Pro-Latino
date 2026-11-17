-- Cobro manual (nov-2026): mientras haya pocos clientes se cobra por transferencia o en efectivo
-- y se hace factura desde la propia app. En la web no se paga con tarjeta (COBRO_ONLINE=false).
-- Ejecutar a mano en Supabase (SQL Editor): no hay Flyway. Se puede ejecutar varias veces.

-- Hasta qué día está pagado el plan. Pasada la fecha hay 10 días de margen; después la cuenta
-- funciona como Free (sin perder datos) hasta que se apunte el siguiente cobro.
-- Vacío = sin fecha de fin (p. ej. un Enterprise pactado o un socio fundador).
ALTER TABLE users ADD COLUMN IF NOT EXISTS pagado_hasta DATE;

-- Cobros recibidos de cada cliente. Los apunta el propietario de la plataforma en /admin/clientes.
CREATE TABLE IF NOT EXISTS cobros_plataforma (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id UUID NOT NULL,                    -- users.id del titular de la cuenta
    fecha DATE NOT NULL DEFAULT CURRENT_DATE,    -- día en que se recibió el dinero
    metodo TEXT NOT NULL DEFAULT 'transferencia',-- transferencia | efectivo | bizum | otro
    concepto TEXT,
    base NUMERIC(12,2) NOT NULL DEFAULT 0,       -- sin IVA
    iva_pct NUMERIC(5,2) DEFAULT 21,
    total NUMERIC(12,2) NOT NULL DEFAULT 0,      -- lo cobrado
    periodo_desde DATE,
    periodo_hasta DATE,
    referencia TEXT,                             -- referencia de la transferencia, recibí…
    factura_id UUID,                             -- factura emitida por este cobro
    registrado_por UUID,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cobros_plataforma_cliente ON cobros_plataforma(cliente_id, fecha DESC);

-- Lo que pide cada cliente desde la app (plan, extras o créditos). No se cobra nada al pedirlo.
CREATE TABLE IF NOT EXISTS peticiones_plan (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id UUID NOT NULL,                    -- users.id del titular de la cuenta
    solicitante_id UUID,                         -- quien lo pidió (titular o un administrador)
    plan TEXT,
    periodo TEXT,                                -- mensual | anual
    activos_extra INTEGER,
    sociedades_extra INTEGER,
    modulos TEXT[],
    packs_creditos INTEGER DEFAULT 0,
    mensaje TEXT,
    estado TEXT DEFAULT 'pendiente',             -- pendiente | atendida
    atendida_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_peticiones_plan_cliente ON peticiones_plan(cliente_id, created_at DESC);

-- Solo la aplicación (service_role) lee y escribe estas tablas
ALTER TABLE cobros_plataforma ENABLE ROW LEVEL SECURITY;
ALTER TABLE peticiones_plan ENABLE ROW LEVEL SECURITY;

NOTIFY pgrst, 'reload schema';
