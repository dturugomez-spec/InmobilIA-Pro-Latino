-- Bandeja de entrada: ahora sí trae los correos por IMAP (ImapSyncService)
-- Motivo del último fallo al leer la cuenta (contraseña incorrecta, servidor caído...), para enseñarlo en la bandeja
ALTER TABLE imap_cuentas ADD COLUMN IF NOT EXISTS ultimo_error TEXT;

-- Un mismo correo no se guarda dos veces en la misma cuenta
CREATE UNIQUE INDEX IF NOT EXISTS ux_emails_inbox_cuenta_msg ON emails_inbox(cuenta_id, message_id);
