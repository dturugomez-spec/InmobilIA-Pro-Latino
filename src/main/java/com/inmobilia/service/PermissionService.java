package com.inmobilia.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sistema de roles y permisos.
 * Cada rol tiene una matriz de acceso por seccion: NONE, READ, WRITE.
 *
 * Encima del rol, el administrador puede ajustar secciones concretas a cada persona
 * (permisos_personalizados.secciones). SessionGuardInterceptor deja esos ajustes en
 * {@link #ajustesActuales} al empezar cada petición, así que todas las comprobaciones de
 * la petición (rutas, menú, buscador, controladores) los respetan sin pasarlos a mano.
 * Fuera de una petición (tareas programadas) solo cuenta el rol.
 */
@Service
public class PermissionService {

    public enum Access { NONE, READ, WRITE }

    /** Ajustes por persona de la petición en curso: sección → NONE|READ|WRITE. */
    private static final ThreadLocal<Map<String, String>> AJUSTES = new ThreadLocal<>();

    public static void ajustesActuales(Map<String, String> ajustes) {
        if (ajustes == null || ajustes.isEmpty()) AJUSTES.remove(); else AJUSTES.set(ajustes);
    }
    public static void limpiarAjustes() { AJUSTES.remove(); }

    public static final String ADMIN     = "admin";       // Dueño / administrador total
    public static final String DIRECTOR  = "director";    // Ve todo, no toca admin
    public static final String COMERCIAL = "comercial";   // Leads, oportunidades, visitas
    public static final String CONTABLE  = "contable";    // Finanzas, facturas, reportes
    public static final String RRHH      = "rrhh";        // Personal, equipo, accesos
    public static final String TECNICO   = "tecnico";     // Mantenimiento, tareas
    public static final String LECTURA   = "lectura";     // Solo lectura

    public static final List<String> ALL_ROLES = List.of(ADMIN, DIRECTOR, COMERCIAL, CONTABLE, RRHH, TECNICO, LECTURA);

    public static final Map<String, String> ROLE_LABELS = Map.of(
        ADMIN,     "Administrador",
        DIRECTOR,  "Director",
        COMERCIAL, "Comercial",
        CONTABLE,  "Contable",
        RRHH,      "Recursos Humanos",
        TECNICO,   "Técnico / Mantenimiento",
        LECTURA,   "Solo lectura"
    );

    public static final Map<String, String> ROLE_ICONS = Map.of(
        ADMIN,     "👑",
        DIRECTOR,  "🎯",
        COMERCIAL, "💼",
        CONTABLE,  "💰",
        RRHH,      "👥",
        TECNICO,   "🔧",
        LECTURA,   "👁"
    );

    /** Secciones del producto (para mapear permisos). */
    public static final Set<String> SECTIONS = Set.of(
        "dashboard", "propiedades", "inquilinos", "contratos", "transacciones",
        "tareas", "mantenimiento", "personal", "eventos", "documentos",
        "reservas", "seguros", "oportunidades", "leads", "email_templates",
        "calendario", "equipo", "config", "ia", "herramientas", "reportes",
        "empresa", "suscripcion", "datos", "automatizaciones"
    );

    /** Nombre y descripción de cada sección, en el orden en que se enseñan al administrador. */
    public static final java.util.LinkedHashMap<String, String[]> SECTION_INFO = new java.util.LinkedHashMap<>();
    static {
        SECTION_INFO.put("propiedades",      new String[]{"Cartera de inmuebles", "Fichas, mapa, valoración, rentabilidad, portales"});
        SECTION_INFO.put("leads",            new String[]{"Leads y captación", "Bandeja, captación, chatbot, WhatsApp, búsquedas guardadas"});
        SECTION_INFO.put("oportunidades",    new String[]{"Oportunidades y pipeline", "Embudo de ventas, kanban, co-broking"});
        SECTION_INFO.put("calendario",       new String[]{"Agenda y visitas", "Calendario, visitas, hoja de visita, open house"});
        SECTION_INFO.put("inquilinos",       new String[]{"Inquilinos", "Fichas de inquilinos y sus solicitudes"});
        SECTION_INFO.put("contratos",        new String[]{"Contratos y firmas", "Contratos, notas de encargo, firma, plantillas"});
        SECTION_INFO.put("transacciones",    new String[]{"Finanzas", "Facturas, recibos, cobros, SEPA, conciliación, caja, comisiones"});
        SECTION_INFO.put("seguros",          new String[]{"Seguros", "Pólizas, cargos y siniestros"});
        SECTION_INFO.put("empresa",          new String[]{"Datos de la empresa y bancarios", "Mi negocio: datos fiscales, logo e IBAN de la empresa"});
        SECTION_INFO.put("reportes",         new String[]{"Informes y analítica", "Informes, analítica, rendimiento del equipo"});
        SECTION_INFO.put("email_templates",  new String[]{"Marketing y comunicación", "Campañas, newsletter, redes, microsite, reseñas"});
        SECTION_INFO.put("ia",               new String[]{"Agente IA", "Agente, Autopiloto, herramientas de IA (gastan créditos)"});
        SECTION_INFO.put("automatizaciones", new String[]{"Automatizaciones", "Reglas que crean tareas o envían emails solas"});
        SECTION_INFO.put("herramientas",     new String[]{"Herramientas avanzadas", "Análisis de inversión, datos oficiales, Catastro"});
        SECTION_INFO.put("tareas",           new String[]{"Tareas", "Tareas del equipo"});
        SECTION_INFO.put("mantenimiento",    new String[]{"Mantenimiento", "Incidencias y reparaciones"});
        SECTION_INFO.put("reservas",         new String[]{"Reservas", "Reservas y señales online"});
        SECTION_INFO.put("documentos",       new String[]{"Documentos", "Documentos y OCR de identificaciones"});
        SECTION_INFO.put("eventos",          new String[]{"Eventos", "Eventos de la agenda"});
        SECTION_INFO.put("personal",         new String[]{"Personal", "Fichas del personal (datos de RR. HH.)"});
        SECTION_INFO.put("equipo",           new String[]{"Equipo", "Invitar empleados, cambiar roles, activar o desactivar"});
        SECTION_INFO.put("datos",            new String[]{"Importar datos", "Importación masiva desde Excel o CSV"});
        SECTION_INFO.put("config",           new String[]{"Configuración", "Integraciones, claves de API, marca blanca"});
        SECTION_INFO.put("suscripcion",      new String[]{"Plan y pagos de la cuenta", "Suscripción a InmobilIA Pro y packs de créditos"});
    }

    // ─── Matriz de permisos (clave: rol, valor: mapa seccion → access) ───
    private static final Map<String, Map<String, Access>> MATRIX = Map.of(
        ADMIN, allWrite(),

        DIRECTOR, Map.ofEntries(
            Map.entry("dashboard", Access.READ),
            Map.entry("propiedades", Access.WRITE),
            Map.entry("inquilinos", Access.WRITE),
            Map.entry("contratos", Access.WRITE),
            Map.entry("transacciones", Access.WRITE),
            Map.entry("tareas", Access.WRITE),
            Map.entry("mantenimiento", Access.WRITE),
            Map.entry("personal", Access.READ),
            Map.entry("eventos", Access.WRITE),
            Map.entry("documentos", Access.WRITE),
            Map.entry("reservas", Access.WRITE),
            Map.entry("seguros", Access.WRITE),
            Map.entry("oportunidades", Access.WRITE),
            Map.entry("leads", Access.WRITE),
            Map.entry("email_templates", Access.WRITE),
            Map.entry("calendario", Access.WRITE),
            Map.entry("equipo", Access.READ),
            Map.entry("config", Access.READ),
            Map.entry("ia", Access.WRITE),
            Map.entry("herramientas", Access.WRITE),
            Map.entry("reportes", Access.WRITE),
            Map.entry("empresa", Access.WRITE),
            Map.entry("suscripcion", Access.READ),
            Map.entry("datos", Access.WRITE),
            Map.entry("automatizaciones", Access.WRITE)
        ),

        COMERCIAL, Map.ofEntries(
            Map.entry("dashboard", Access.READ),
            Map.entry("propiedades", Access.WRITE),
            Map.entry("inquilinos", Access.WRITE),
            Map.entry("contratos", Access.READ),
            Map.entry("transacciones", Access.NONE),
            Map.entry("tareas", Access.WRITE),
            Map.entry("mantenimiento", Access.READ),
            Map.entry("personal", Access.NONE),
            Map.entry("eventos", Access.WRITE),
            Map.entry("documentos", Access.WRITE),
            Map.entry("reservas", Access.WRITE),
            Map.entry("seguros", Access.NONE),
            Map.entry("oportunidades", Access.WRITE),
            Map.entry("leads", Access.WRITE),
            Map.entry("email_templates", Access.WRITE),
            Map.entry("calendario", Access.WRITE),
            Map.entry("equipo", Access.NONE),
            Map.entry("config", Access.READ),
            Map.entry("ia", Access.WRITE),
            Map.entry("herramientas", Access.READ),
            Map.entry("reportes", Access.READ),
            Map.entry("empresa", Access.READ),
            Map.entry("suscripcion", Access.NONE),
            Map.entry("datos", Access.NONE),
            Map.entry("automatizaciones", Access.READ)
        ),

        CONTABLE, Map.ofEntries(
            Map.entry("dashboard", Access.READ),
            Map.entry("propiedades", Access.READ),
            Map.entry("inquilinos", Access.READ),
            Map.entry("contratos", Access.WRITE),
            Map.entry("transacciones", Access.WRITE),
            Map.entry("tareas", Access.WRITE),
            Map.entry("mantenimiento", Access.READ),
            Map.entry("personal", Access.NONE),
            Map.entry("eventos", Access.WRITE),
            Map.entry("documentos", Access.WRITE),
            Map.entry("reservas", Access.NONE),
            Map.entry("seguros", Access.WRITE),
            Map.entry("oportunidades", Access.NONE),
            Map.entry("leads", Access.NONE),
            Map.entry("email_templates", Access.READ),
            Map.entry("calendario", Access.WRITE),
            Map.entry("equipo", Access.NONE),
            Map.entry("config", Access.READ),
            Map.entry("ia", Access.READ),
            Map.entry("herramientas", Access.READ),
            Map.entry("reportes", Access.WRITE),
            Map.entry("empresa", Access.WRITE),
            Map.entry("suscripcion", Access.READ),
            Map.entry("datos", Access.WRITE),
            Map.entry("automatizaciones", Access.READ)
        ),

        RRHH, Map.ofEntries(
            Map.entry("dashboard", Access.READ),
            Map.entry("propiedades", Access.NONE),
            Map.entry("inquilinos", Access.NONE),
            Map.entry("contratos", Access.NONE),
            Map.entry("transacciones", Access.NONE),
            Map.entry("tareas", Access.WRITE),
            Map.entry("mantenimiento", Access.NONE),
            Map.entry("personal", Access.WRITE),
            Map.entry("eventos", Access.WRITE),
            Map.entry("documentos", Access.WRITE),
            Map.entry("reservas", Access.NONE),
            Map.entry("seguros", Access.NONE),
            Map.entry("oportunidades", Access.NONE),
            Map.entry("leads", Access.NONE),
            Map.entry("email_templates", Access.WRITE),
            Map.entry("calendario", Access.WRITE),
            Map.entry("equipo", Access.WRITE),
            Map.entry("config", Access.READ),
            Map.entry("ia", Access.NONE),
            Map.entry("herramientas", Access.NONE),
            Map.entry("reportes", Access.READ),
            Map.entry("empresa", Access.READ),
            Map.entry("suscripcion", Access.NONE),
            Map.entry("datos", Access.NONE),
            Map.entry("automatizaciones", Access.NONE)
        ),

        TECNICO, Map.ofEntries(
            Map.entry("dashboard", Access.READ),
            Map.entry("propiedades", Access.READ),
            Map.entry("inquilinos", Access.NONE),
            Map.entry("contratos", Access.NONE),
            Map.entry("transacciones", Access.NONE),
            Map.entry("tareas", Access.WRITE),
            Map.entry("mantenimiento", Access.WRITE),
            Map.entry("personal", Access.NONE),
            Map.entry("eventos", Access.WRITE),
            Map.entry("documentos", Access.READ),
            Map.entry("reservas", Access.NONE),
            Map.entry("seguros", Access.READ),
            Map.entry("oportunidades", Access.NONE),
            Map.entry("leads", Access.NONE),
            Map.entry("email_templates", Access.NONE),
            Map.entry("calendario", Access.WRITE),
            Map.entry("equipo", Access.NONE),
            Map.entry("config", Access.READ),
            Map.entry("ia", Access.NONE),
            Map.entry("herramientas", Access.NONE),
            Map.entry("reportes", Access.NONE),
            Map.entry("empresa", Access.READ),
            Map.entry("suscripcion", Access.NONE),
            Map.entry("datos", Access.NONE),
            Map.entry("automatizaciones", Access.NONE)
        ),

        LECTURA, allRead()
    );

    private static Map<String, Access> allWrite() {
        var m = new java.util.HashMap<String, Access>();
        for (String s : SECTIONS) m.put(s, Access.WRITE);
        return m;
    }

    private static Map<String, Access> allRead() {
        var m = new java.util.HashMap<String, Access>();
        for (String s : SECTIONS) m.put(s, Access.READ);
        m.put("equipo", Access.NONE);
        m.put("config", Access.NONE);
        m.put("personal", Access.NONE);     // datos de RR. HH.
        m.put("suscripcion", Access.NONE);
        m.put("datos", Access.NONE);
        return m;
    }

    /**
     * Acceso efectivo a la sección: el del rol, salvo que el administrador haya ajustado esa
     * sección a esta persona. Los administradores lo pueden todo siempre.
     */
    public Access access(String role, String section) {
        Access delRol = accesoDelRol(role, section);
        if (role == null || ADMIN.equalsIgnoreCase(role) || "tenant_portal".equals(role)) return delRol;
        Map<String, String> ajustes = AJUSTES.get();
        if (ajustes != null && SECTIONS.contains(section)) {
            Access a = parse(ajustes.get(section));
            if (a != null) return a;
        }
        return delRol;
    }

    /** Lo que da el rol por sí solo (sin ajustes por persona). */
    public Access accesoDelRol(String role, String section) {
        if (role == null) return Access.NONE;
        Map<String, Access> roleMap = MATRIX.get(role.toLowerCase());
        if (roleMap == null) return Access.NONE;
        return roleMap.getOrDefault(section, Access.NONE);
    }

    /** "NONE" / "READ" / "WRITE" → Access; cualquier otra cosa (p. ej. "rol") → null. */
    public static Access parse(Object v) {
        if (v == null) return null;
        try { return Access.valueOf(String.valueOf(v).trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public boolean canRead(String role, String section)  { return access(role, section) != Access.NONE; }
    public boolean canWrite(String role, String section) { return access(role, section) == Access.WRITE; }

    public String label(String role) {
        return ROLE_LABELS.getOrDefault(role == null ? "" : role.toLowerCase(), "Sin rol");
    }
    public String icon(String role) {
        return ROLE_ICONS.getOrDefault(role == null ? "" : role.toLowerCase(), "👤");
    }
}
