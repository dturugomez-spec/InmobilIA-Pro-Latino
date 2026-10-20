-- Permisos por persona + control de cargos de seguros
-- Ejecutar a mano en Supabase (SQL Editor). Se puede ejecutar varias veces sin romper nada.

-- 1. Permisos por persona: sección → 'NONE' | 'READ' | 'WRITE' (lo que no aparece, según su rol)
--    La tabla viene de la V61; por si no se llegó a ejecutar, se crea aquí con lo necesario.
CREATE TABLE IF NOT EXISTS permisos_personalizados (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID REFERENCES users(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id)
);
ALTER TABLE permisos_personalizados ADD COLUMN IF NOT EXISTS secciones JSONB DEFAULT '{}'::jsonb;
-- Solo el servidor la lee y escribe (service_role salta el RLS)
ALTER TABLE permisos_personalizados ENABLE ROW LEVEL SECURITY;

-- 2. Seguros: con qué cuenta se paga, si está domiciliado y cuándo es el próximo cargo
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS cuenta_cargo TEXT;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS domiciliado BOOLEAN DEFAULT FALSE;
ALTER TABLE seguros ADD COLUMN IF NOT EXISTS proximo_cargo DATE;
CREATE INDEX IF NOT EXISTS idx_seguros_proximo_cargo ON seguros(proximo_cargo) WHERE estado = 'activa';

-- 3. Siniestros: por si alguno se creó sin inmobiliaria (ahora solo se editan los propios)
UPDATE siniestros s SET user_id = g.user_id
FROM seguros g
WHERE s.seguro_id = g.id AND s.user_id IS NULL;
