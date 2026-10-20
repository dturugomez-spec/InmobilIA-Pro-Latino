-- Sprint diferenciación: Autopiloto del Agente (human-in-the-loop)
-- La IA analiza la cartera y PROPONE acciones concretas ya preparadas (mensaje
-- redactado, precio calculado, tarea) en una bandeja. El agente aprueba o
-- descarta cada una con un clic; al aprobar, se ejecuta de verdad.

CREATE TABLE IF NOT EXISTS agente_acciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    tipo TEXT NOT NULL,          -- mensaje_lead, enviar_email, bajar_precio, crear_tarea, marcar_contactado, avisar_match
    prioridad TEXT DEFAULT 'media', -- alta, media, baja
    titulo TEXT,                 -- resumen de la acción para la bandeja
    motivo TEXT,                 -- por qué la propone
    borrador TEXT,               -- contenido editable (mensaje redactado, etc.)
    payload_json TEXT,           -- datos para ejecutar (JSON)

    lead_id UUID,
    propiedad_id UUID,

    estado TEXT DEFAULT 'propuesta',  -- propuesta, aprobada, descartada, ejecutada, error
    resultado TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    resuelta_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_agente_acciones_user ON agente_acciones(user_id, estado, created_at DESC);
