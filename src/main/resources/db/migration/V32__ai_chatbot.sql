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
