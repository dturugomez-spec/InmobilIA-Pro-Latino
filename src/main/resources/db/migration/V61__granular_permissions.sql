-- Sprint 109: Permisos granulares
CREATE TABLE IF NOT EXISTS permisos_personalizados (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID REFERENCES users(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    -- Permisos por módulo (CRUD)
    propiedades_leer BOOLEAN DEFAULT TRUE, propiedades_crear BOOLEAN DEFAULT TRUE, propiedades_editar BOOLEAN DEFAULT TRUE, propiedades_borrar BOOLEAN DEFAULT FALSE,
    leads_leer BOOLEAN DEFAULT TRUE, leads_crear BOOLEAN DEFAULT TRUE, leads_editar BOOLEAN DEFAULT TRUE, leads_borrar BOOLEAN DEFAULT FALSE,
    contratos_leer BOOLEAN DEFAULT TRUE, contratos_crear BOOLEAN DEFAULT FALSE, contratos_editar BOOLEAN DEFAULT FALSE, contratos_borrar BOOLEAN DEFAULT FALSE,
    facturas_leer BOOLEAN DEFAULT FALSE, facturas_crear BOOLEAN DEFAULT FALSE,
    inquilinos_leer BOOLEAN DEFAULT TRUE, inquilinos_editar BOOLEAN DEFAULT TRUE,
    propietarios_leer BOOLEAN DEFAULT FALSE,

    -- Permisos especiales
    puede_ver_precios BOOLEAN DEFAULT TRUE,
    puede_ver_comisiones BOOLEAN DEFAULT FALSE,
    puede_enviar_emails BOOLEAN DEFAULT TRUE,
    puede_aprobar_gastos BOOLEAN DEFAULT FALSE,
    puede_acceder_caja BOOLEAN DEFAULT FALSE,
    puede_exportar_datos BOOLEAN DEFAULT FALSE,
    puede_gestionar_usuarios BOOLEAN DEFAULT FALSE,
    puede_acceder_auditoria BOOLEAN DEFAULT FALSE,

    -- Restricciones
    solo_sus_propiedades BOOLEAN DEFAULT FALSE,
    solo_sus_leads BOOLEAN DEFAULT FALSE,
    max_leads_dia INT,
    max_emails_dia INT,
    horario_acceso TEXT,                       -- "09:00-20:00"

    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id)
);
