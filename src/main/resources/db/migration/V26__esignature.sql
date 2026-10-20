-- Sprint 55: Firma electrónica de contratos (Ley 6/2020 — simple eIDAS)

CREATE TABLE IF NOT EXISTS firmas_documento (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    -- Documento
    titulo TEXT NOT NULL,
    tipo TEXT NOT NULL,                  -- contrato_alquiler, contrato_venta, mandato, anexo, otros
    contenido_html TEXT NOT NULL,        -- HTML del contrato (snapshot inmutable)
    documento_hash TEXT NOT NULL,        -- SHA-256 del contenido para integridad
    contrato_id UUID REFERENCES contratos(id) ON DELETE SET NULL,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,

    -- Estado
    estado TEXT DEFAULT 'borrador',      -- borrador, enviado, parcialmente_firmado, firmado, rechazado, expirado
    fecha_envio TIMESTAMPTZ,
    fecha_completado TIMESTAMPTZ,
    fecha_expiracion TIMESTAMPTZ,        -- por defecto +30 días
    motivo_rechazo TEXT,

    -- Configuración
    requiere_dni BOOLEAN DEFAULT TRUE,
    requiere_otp_sms BOOLEAN DEFAULT FALSE,
    requiere_otp_email BOOLEAN DEFAULT TRUE,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_firmas_doc_user ON firmas_documento(user_id);
CREATE INDEX IF NOT EXISTS idx_firmas_doc_estado ON firmas_documento(estado);

-- Cada firmante del documento
CREATE TABLE IF NOT EXISTS firmas_firmante (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    documento_id UUID NOT NULL REFERENCES firmas_documento(id) ON DELETE CASCADE,

    -- Datos del firmante
    nombre TEXT NOT NULL,
    dni TEXT,
    email TEXT NOT NULL,
    telefono TEXT,
    rol TEXT,                            -- arrendador, arrendatario, fiador, comprador, vendedor, etc.
    orden INT DEFAULT 1,

    -- Tokens únicos
    token_firma TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),

    -- Estado firma
    estado TEXT DEFAULT 'pendiente',     -- pendiente, visto, firmado, rechazado
    visto_at TIMESTAMPTZ,
    firmado_at TIMESTAMPTZ,

    -- Datos de la firma (trazabilidad jurídica)
    firma_imagen TEXT,                   -- Base64 PNG del trazo
    firma_hash TEXT,                     -- SHA-256 hash de la firma
    ip_firma TEXT,
    user_agent TEXT,
    geolocalizacion TEXT,
    otp_verificado BOOLEAN DEFAULT FALSE,
    otp_codigo TEXT,                     -- código generado (caduca en 10min)
    otp_enviado_at TIMESTAMPTZ,
    otp_intentos INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_firmantes_doc ON firmas_firmante(documento_id);
CREATE INDEX IF NOT EXISTS idx_firmantes_token ON firmas_firmante(token_firma);

-- Auditoría completa (cada evento queda registrado, inmutable)
CREATE TABLE IF NOT EXISTS firmas_auditoria (
    id BIGSERIAL PRIMARY KEY,
    documento_id UUID NOT NULL REFERENCES firmas_documento(id) ON DELETE CASCADE,
    firmante_id UUID REFERENCES firmas_firmante(id) ON DELETE SET NULL,

    evento TEXT NOT NULL,                -- creado, enviado, visto, otp_enviado, otp_verificado, firmado, rechazado, expirado, descargado
    detalle TEXT,
    ip TEXT,
    user_agent TEXT,
    metadata JSONB,

    -- Cadena de hashes (blockchain-like para integridad)
    hash_anterior TEXT,
    hash_actual TEXT NOT NULL,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_doc ON firmas_auditoria(documento_id, created_at);
