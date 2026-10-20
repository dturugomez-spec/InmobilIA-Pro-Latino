-- Sprint diferenciación: Entrenador IA de objeciones (role-play coach)
-- El agente practica objeciones reales de captación/venta contra un cliente IA
-- que se mantiene en personaje, y recibe una evaluación puntuada con feedback.
-- Diferenciador de formación: muy pocos CRM inmobiliarios tienen coach de ventas.

CREATE TABLE IF NOT EXISTS coaching_sesiones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    agente_id UUID,

    escenario TEXT,                  -- clave del escenario practicado
    escenario_titulo TEXT,
    rol_cliente TEXT,                -- propietario, comprador
    dificultad TEXT DEFAULT 'media', -- facil, media, dificil

    transcripcion_json TEXT,         -- historial completo (JSON)
    n_turnos INT DEFAULT 0,
    puntuacion INT,                  -- 0-100
    fortalezas TEXT,
    mejoras TEXT,
    respuesta_modelo TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_coaching_user ON coaching_sesiones(user_id, created_at DESC);
