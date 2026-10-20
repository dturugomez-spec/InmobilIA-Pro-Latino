package com.inmobilia.config;

import com.inmobilia.service.AuthService;
import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.TwoFactorService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 🛡️ Guardián de sesión (se ejecuta en todas las peticiones con sesión iniciada):
 *
 *  1. Cada minuto vuelve a mirar la cuenta en BD: si un admin desactiva a un empleado
 *     o le cambia el rol, surte efecto en menos de un minuto (no a las 4 h de sesión).
 *  2. Inquilinos (rol tenant_portal): solo pueden usar su portal. Su sesión apunta a la
 *     inmobiliaria, así que sin esto podrían abrir el CRM entero.
 *  3. 2FA obligatorio: si la inmobiliaria lo exige y esta persona no lo tiene, solo
 *     puede ir a /seguridad hasta activarlo.
 *  4. Permisos por sección (rol + ajustes que el administrador haya dado a esta persona):
 *     leer exige permiso de lectura y guardar o borrar exige permiso de escritura
 *     (matriz de PermissionService). Cubre también /api/**.
 */
@Component
@RequiredArgsConstructor
public class SessionGuardInterceptor implements HandlerInterceptor {

    private static final long REVISION_MS = 60_000;

    private final SupabaseService supabase;
    private final TwoFactorService twoFactor;
    private final PermissionService permissions;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        PermissionService.limpiarAjustes();
        boolean sigue = false;
        try {
            sigue = comprobar(req, resp);
            return sigue;
        } finally {
            // Si se corta aquí, Spring no llama a afterCompletion: limpiar ya
            if (!sigue) PermissionService.limpiarAjustes();
        }
    }

    private boolean comprobar(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HttpSession session = req.getSession(false);
        if (session == null || !SessionUtils.isLoggedIn(session)) return true;
        String uri = req.getRequestURI();

        // 1. ¿La cuenta sigue activa? ¿Ha cambiado su rol?
        Object revisado = session.getAttribute(SessionUtils.KEY_REVISADO);
        long ahora = System.currentTimeMillis();
        if (!(revisado instanceof Long r) || ahora - r > REVISION_MS) {
            if (!revisar(session)) {
                session.invalidate();
                resp.sendRedirect("/login?sesion=cerrada");
                return false;
            }
            session.setAttribute(SessionUtils.KEY_REVISADO, ahora);
        }

        String role = SessionUtils.role(session);
        // Ajustes por persona: valen para toda la petición (rutas, menú, controladores)
        PermissionService.ajustesActuales(SessionUtils.ajustesPermisos(session));

        // 2. Inquilinos: solo su portal (su sesión apunta a la inmobiliaria: sin esto
        //    podrían leer el CRM, también por /api/search o /api/ai)
        if ("tenant_portal".equals(role) && !permitidoInquilino(uri)) {
            if (esPeticionDeDatos(req)) return prohibido(resp, "Acceso no permitido desde el portal del inquilino.");
            resp.sendRedirect("/portal");
            return false;
        }

        // 3. 2FA obligatorio pendiente de activar
        if (Boolean.TRUE.equals(session.getAttribute(SessionUtils.KEY_FORZAR_2FA)) && !permitidoSin2fa(uri)) {
            if (esPeticionDeDatos(req)) return prohibido(resp, "Activa la verificación en dos pasos para continuar.");
            resp.sendRedirect("/seguridad");
            return false;
        }

        // 4. Permisos del rol (y de la persona) en cada sección
        String seccion = seccionDe(uri);
        if (seccion != null) {
            boolean escritura = !SECCIONES_SOLO_LECTURA.contains(seccion)
                    && (!("GET".equalsIgnoreCase(req.getMethod()) || "HEAD".equalsIgnoreCase(req.getMethod()))
                        || uri.endsWith("/new") || uri.endsWith("/edit") || uri.endsWith("/nueva") || uri.endsWith("/nuevo"));
            boolean ok = escritura ? permissions.canWrite(role, seccion) : permissions.canRead(role, seccion);
            if (!ok) {
                String msg = "No tienes permiso para "
                        + (escritura ? "modificar" : "ver") + " esta sección. Pídeselo al administrador.";
                if (esPeticionDeDatos(req)) {
                    return prohibido(resp, msg);
                } else {
                    SessionUtils.flash(session, "🔒 " + msg);
                    resp.sendRedirect(uri.startsWith("/crud/") && permissions.canRead(role, seccion)
                            ? "/crud/" + seccion : "/dashboard");
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        PermissionService.limpiarAjustes(); // el hilo vuelve al pool: que no herede ajustes de otra persona
    }

    private static boolean prohibido(HttpServletResponse resp, String msg) throws java.io.IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"ok\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}");
        return false;
    }

    /**
     * Sección de la matriz de permisos a la que pertenece una ruta (null = sin restricción por rol:
     * páginas públicas, calculadoras, dashboard, ayuda…). Los administradores lo pueden todo.
     */
    public static String seccionDe(String uri) {
        if (uri == null) return null;
        for (String pub : RUTAS_PUBLICAS) if (uri.startsWith(pub)) return null;
        String[] p = uri.split("[/?]");
        String s1 = p.length > 1 ? p[1] : "";
        String s2 = p.length > 2 ? p[2] : "";
        if ("crud".equals(s1)) return PermissionService.SECTIONS.contains(s2) ? s2 : null;
        if ("entity".equals(s1)) return RUTA_ENTIDAD.get(s2);
        if ("admin".equals(s1)) return "tenant-requests".equals(s2) ? "inquilinos" : null;
        if ("api".equals(s1)) {
            // Comentarios, adjuntos, etiquetas e historial de una ficha: los de su sección
            if (Set.of("comments", "attachments", "entity-tags", "timeline").contains(s2)) {
                return seccionDeFicha(p.length > 3 ? p[3] : "");
            }
            return RUTA_API.get(s2);
        }
        return SECCION_POR_RUTA.get(s1);
    }

    /** "lead", "leads", "propiedad", "property"… → sección; desconocido → null. */
    static String seccionDeFicha(String tipo) {
        String t = tipo == null ? "" : tipo.toLowerCase();
        if (PermissionService.SECTIONS.contains(t)) return t;
        return RUTA_ENTIDAD.get(t);
    }

    private static final java.util.Map<String, String> RUTA_ENTIDAD = java.util.Map.ofEntries(
            java.util.Map.entry("lead", "leads"), java.util.Map.entry("propiedad", "propiedades"),
            java.util.Map.entry("property", "propiedades"), java.util.Map.entry("properties", "propiedades"),
            java.util.Map.entry("inquilino", "inquilinos"), java.util.Map.entry("tenant", "inquilinos"),
            java.util.Map.entry("tenants", "inquilinos"), java.util.Map.entry("contrato", "contratos"),
            java.util.Map.entry("contract", "contratos"), java.util.Map.entry("oportunidad", "oportunidades"),
            java.util.Map.entry("factura", "transacciones"), java.util.Map.entry("invoice", "transacciones"));

    /** Rutas /api/{x} de la app (las /api/v1 y /api/v2 van con clave de API, no con sesión). */
    private static final java.util.Map<String, String> RUTA_API = java.util.Map.of(
            "ai", "ia", "photos", "propiedades");

    /** Páginas públicas bajo prefijos internos: las abre cualquiera (con o sin sesión). */
    private static final List<String> RUTAS_PUBLICAS = List.of(
            "/chatbot/api/", "/chatbot/widget.js", "/reviews/publico/", "/nps/responder/",
            "/referidos/codigo/", "/reservas-online/p/", "/reservas-online/webhook", "/informe/");

    /** Informes: generar uno (aunque sea un POST) es leer, no modificar datos. */
    private static final java.util.Set<String> SECCIONES_SOLO_LECTURA = java.util.Set.of("reportes");

    private static final java.util.Map<String, String> SECCION_POR_RUTA = java.util.Map.ofEntries(
            // Inteligencia artificial: el agente puede ejecutar acciones (crear, enviar…)
            java.util.Map.entry("agente", "ia"), java.util.Map.entry("autopiloto", "ia"),
            java.util.Map.entry("ai", "ia"), java.util.Map.entry("ia", "ia"),
            java.util.Map.entry("notas-ia", "ia"), java.util.Map.entry("imagenes-ia", "ia"),
            java.util.Map.entry("descripcion-ia", "ia"), java.util.Map.entry("coach", "ia"),
            java.util.Map.entry("optimizador", "ia"), java.util.Map.entry("reconocer-habitaciones", "ia"),
            java.util.Map.entry("staging-digital", "ia"),
            // Leads y captación
            java.util.Map.entry("leads", "leads"), java.util.Map.entry("bandeja", "leads"),
            java.util.Map.entry("embudo", "leads"), java.util.Map.entry("captacion", "leads"),
            java.util.Map.entry("leads-scoring", "leads"), java.util.Map.entry("cadencias", "leads"),
            java.util.Map.entry("busquedas-guardadas", "leads"), java.util.Map.entry("inbox", "leads"),
            java.util.Map.entry("whatsapp", "leads"), java.util.Map.entry("sms", "leads"),
            java.util.Map.entry("matches", "leads"), java.util.Map.entry("chatbot", "leads"),
            java.util.Map.entry("portal-comprador", "leads"), java.util.Map.entry("notas-voz", "ia"),
            java.util.Map.entry("valoracion", "leads"),
            // Oportunidades y embudo de ventas
            java.util.Map.entry("oportunidades", "oportunidades"), java.util.Map.entry("kanban", "oportunidades"),
            java.util.Map.entry("pipelines", "oportunidades"), java.util.Map.entry("cobroking", "oportunidades"),
            // Cartera
            java.util.Map.entry("propiedades", "propiedades"), java.util.Map.entry("tours-360", "propiedades"),
            java.util.Map.entry("precios-historico", "propiedades"), java.util.Map.entry("comparar", "propiedades"),
            java.util.Map.entry("promociones", "propiedades"), java.util.Map.entry("cee", "propiedades"),
            java.util.Map.entry("rentabilidad", "propiedades"), java.util.Map.entry("marketplace", "propiedades"),
            java.util.Map.entry("feeds", "email_templates"), // publicación en portales: marketing
            java.util.Map.entry("inquilinos", "inquilinos"),
            // Contratos, encargos y firmas
            java.util.Map.entry("contratos", "contratos"), java.util.Map.entry("nota-encargo", "contratos"),
            java.util.Map.entry("firmas", "contratos"), java.util.Map.entry("plantillas-doc", "contratos"),
            // Dinero
            java.util.Map.entry("invoices", "transacciones"), java.util.Map.entry("receipts", "transacciones"),
            java.util.Map.entry("commissions", "transacciones"), java.util.Map.entry("payments", "transacciones"),
            java.util.Map.entry("gastos-agente", "transacciones"), java.util.Map.entry("caja", "transacciones"),
            java.util.Map.entry("conciliacion", "transacciones"), java.util.Map.entry("sepa", "transacciones"),
            java.util.Map.entry("holded", "transacciones"), java.util.Map.entry("extractos", "transacciones"),
            java.util.Map.entry("calendario-fiscal", "transacciones"),
            java.util.Map.entry("seguros", "seguros"),
            // Empresa, cuenta y datos (antes cualquiera podía cambiar el IBAN o cancelar la suscripción)
            java.util.Map.entry("business", "empresa"),
            java.util.Map.entry("billing", "suscripcion"), java.util.Map.entry("creditos", "suscripcion"),
            java.util.Map.entry("import", "datos"), java.util.Map.entry("importar", "datos"),
            java.util.Map.entry("workflows", "automatizaciones"),
            // Herramientas avanzadas
            java.util.Map.entry("herramientas", "herramientas"), java.util.Map.entry("finanzas", "herramientas"),
            java.util.Map.entry("catastro", "herramientas"),
            // Informes y analítica
            java.util.Map.entry("reports", "reportes"), java.util.Map.entry("informes", "reportes"),
            java.util.Map.entry("analytics", "reportes"), java.util.Map.entry("impacto", "reportes"),
            java.util.Map.entry("rendimiento", "reportes"), java.util.Map.entry("cartera", "reportes"),
            java.util.Map.entry("dashboards", "reportes"), java.util.Map.entry("mapa-global", "reportes"),
            // Comunicación con clientes
            java.util.Map.entry("campanas", "email_templates"), java.util.Map.entry("plantillas", "email_templates"),
            java.util.Map.entry("templates", "email_templates"), java.util.Map.entry("newsletter", "email_templates"),
            java.util.Map.entry("redes", "email_templates"), java.util.Map.entry("email-builder", "email_templates"),
            java.util.Map.entry("microsite", "email_templates"), java.util.Map.entry("reviews", "email_templates"),
            java.util.Map.entry("nps", "email_templates"), java.util.Map.entry("referidos", "email_templates"),
            // Agenda
            java.util.Map.entry("calendario", "calendario"), java.util.Map.entry("calendar", "calendario"),
            java.util.Map.entry("visitas", "calendario"), java.util.Map.entry("open-house", "calendario"),
            java.util.Map.entry("hoja-visita", "calendario"), java.util.Map.entry("preparacion", "calendario"),
            java.util.Map.entry("google-calendar", "calendario"),
            java.util.Map.entry("reservas-online", "reservas"),
            // Configuración y equipo
            java.util.Map.entry("config", "config"), java.util.Map.entry("integrations", "config"),
            java.util.Map.entry("api-keys", "config"), java.util.Map.entry("whitelabel", "config"),
            java.util.Map.entry("equipo", "equipo"), java.util.Map.entry("permisos-granulares", "equipo"),
            // Documentación de clientes
            java.util.Map.entry("ocr-dni", "documentos"));

    /** Llamadas desde JavaScript (fetch/XHR) o API: mejor un 403 en JSON que una redirección. */
    private static boolean esPeticionDeDatos(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        return "XMLHttpRequest".equals(req.getHeader("X-Requested-With"))
                || (accept != null && accept.contains("application/json") && !accept.contains("text/html"))
                || req.getRequestURI().contains("/api/");
    }

    /** false = cerrar la sesión (cuenta desactivada o eliminada). Si la BD no responde, no echa a nadie. */
    private boolean revisar(HttpSession session) {
        String yo = SessionUtils.realUid(session);
        if (yo == null || !supabase.isEnabled()) return true;
        List<Map<String, Object>> rows = supabase.findBy("users", Map.of("id", yo));
        if (rows.isEmpty()) return true; // fallo de red o BD: fail-open (la sesión caduca sola)
        Map<String, Object> u = rows.get(0);

        Object active = u.get("active");
        if (active != null && (Boolean.FALSE.equals(active) || "false".equalsIgnoreCase(String.valueOf(active)))) return false;
        Object deleted = u.get("deleted_at");
        if (deleted != null && !String.valueOf(deleted).isBlank() && !"null".equals(String.valueOf(deleted))) return false;

        // Rol actualizado (p. ej. el admin lo bajó a "solo lectura")
        Object r = u.get("role");
        String rolBd = (r == null || String.valueOf(r).isBlank() || "null".equals(String.valueOf(r))) ? "admin" : String.valueOf(r);
        session.setAttribute(SessionUtils.KEY_ROLE, rolBd);

        // ¿Le toca activar 2FA? (el admin puede haberlo hecho obligatorio después del login)
        String org = AuthService.orgDe(u);

        // Ajustes de permisos que el administrador le ha dado a esta persona
        session.setAttribute(SessionUtils.KEY_AJUSTES_PERMISOS, cargarAjustes(yo, org));
        boolean debe = !TwoFactorService.activo(u)
                && (org.equals(yo) ? truthy(u.get("require_2fa")) : twoFactor.exigidoEnOrg(org));
        if (debe) session.setAttribute(SessionUtils.KEY_FORZAR_2FA, true);
        else session.removeAttribute(SessionUtils.KEY_FORZAR_2FA);
        return true;
    }

    /** Ajustes por sección de esta persona (solo valores válidos). Si la BD falla, ninguno. */
    private java.util.Map<String, String> cargarAjustes(String userId, String orgId) {
        java.util.Map<String, String> res = new java.util.HashMap<>();
        try {
            List<Map<String, Object>> filas = supabase.selectFilter("permisos_personalizados",
                    "select=secciones&user_id=eq." + userId + "&org_id=eq." + orgId);
            if (!filas.isEmpty() && filas.get(0).get("secciones") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    String s = String.valueOf(k);
                    PermissionService.Access a = PermissionService.parse(v);
                    if (PermissionService.SECTIONS.contains(s) && a != null) res.put(s, a.name());
                });
            }
        } catch (Exception ignored) { }
        return res;
    }

    private static boolean permitidoInquilino(String uri) {
        return uri.equals("/portal") || uri.startsWith("/portal/") || uri.equals("/logout")
                || uri.startsWith("/login") || uri.equals("/error")
                || uri.equals("/seguridad") || uri.startsWith("/seguridad/");
    }

    private static boolean permitidoSin2fa(String uri) {
        return uri.equals("/seguridad") || uri.startsWith("/seguridad/") || uri.equals("/logout")
                || uri.startsWith("/login") || uri.equals("/error") || uri.equals("/mi-ip");
    }

    private static boolean truthy(Object o) {
        return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)));
    }
}
