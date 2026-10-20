-- Sprint 110: Notas de voz con transcripción
CREATE TABLE IF NOT EXISTS notas_voz (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    autor_id UUID REFERENCES users(id) ON DELETE SET NULL,

    audio_url TEXT,
    duracion_segundos INT,
    transcripcion TEXT,
    transcripcion_idioma TEXT DEFAULT 'es',
    resumen_ia TEXT,

    lead_id UUID, propiedad_id UUID, contrato_id UUID, visita_id UUID,

    fecha_grabacion TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_voz_user ON notas_voz(user_id, fecha_grabacion DESC);
CREATE INDEX IF NOT EXISTS idx_voz_lead ON notas_voz(lead_id);
