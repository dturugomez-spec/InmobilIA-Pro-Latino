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
