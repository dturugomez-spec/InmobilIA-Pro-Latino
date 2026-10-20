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
