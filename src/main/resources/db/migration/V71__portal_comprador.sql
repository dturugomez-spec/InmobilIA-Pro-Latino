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
