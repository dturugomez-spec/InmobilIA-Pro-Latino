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
