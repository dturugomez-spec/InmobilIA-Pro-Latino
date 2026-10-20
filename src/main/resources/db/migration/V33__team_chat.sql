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
