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
