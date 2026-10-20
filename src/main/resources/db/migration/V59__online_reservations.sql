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
    moneda TEXT DEFAULT 'EUR',

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
