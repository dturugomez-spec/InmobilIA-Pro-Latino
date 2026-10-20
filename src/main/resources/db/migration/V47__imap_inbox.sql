-- Sprint 79: IMAP Inbox
CREATE TABLE IF NOT EXISTS imap_cuentas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    imap_host TEXT NOT NULL,
    imap_port INT DEFAULT 993,
    imap_user TEXT,
    imap_pass TEXT,
    smtp_host TEXT,
    smtp_port INT DEFAULT 587,
    activa BOOLEAN DEFAULT TRUE,
    ultimo_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS emails_inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cuenta_id UUID REFERENCES imap_cuentas(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    message_id TEXT,
    de_email TEXT, de_nombre TEXT,
    para TEXT[], cc TEXT[],
    asunto TEXT, cuerpo_texto TEXT, cuerpo_html TEXT,
    leido BOOLEAN DEFAULT FALSE,
    estrella BOOLEAN DEFAULT FALSE,
    archivado BOOLEAN DEFAULT FALSE,
    lead_id UUID, propiedad_id UUID,
    etiqueta TEXT,                         -- inbox, sent, draft, trash
    fecha TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_emails_user ON emails_inbox(user_id, fecha DESC);
CREATE INDEX IF NOT EXISTS idx_emails_unread ON emails_inbox(user_id) WHERE leido = false;
