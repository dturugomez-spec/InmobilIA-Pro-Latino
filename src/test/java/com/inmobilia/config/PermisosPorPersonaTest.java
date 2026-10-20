package com.inmobilia.config;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.PermissionService.Access;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Permisos por persona (encima del rol) y rutas que antes cualquier empleado podía usar. */
class PermisosPorPersonaTest {

    private final PermissionService permisos = new PermissionService();
    private final SessionGuardInterceptor guard = new SessionGuardInterceptor(
            new SupabaseService(null, null, null, "", ""), null, permisos);

    @AfterEach
    void limpiar() { PermissionService.limpiarAjustes(); }

    private static MockHttpSession sesion(String rol, Map<String, String> ajustes) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, "u-1", "org-1", "ana@inmo.es", "Ana", rol);
        if (ajustes != null) s.setAttribute(SessionUtils.KEY_AJUSTES_PERMISOS, ajustes);
        return s;
    }

    private boolean pasa(String metodo, String uri, MockHttpSession s) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
        req.setSession(s);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = guard.preHandle(req, resp, null);
        guard.afterCompletion(req, resp, null, null);
        return ok;
    }

    @Test
    void elAjusteDaMasOMenosAccesoQueElRol() throws Exception {
        // Un comercial no ve seguros por su rol…
        assertFalse(pasa("GET", "/seguros", sesion("comercial", null)));
        // …pero el administrador se los puede abrir solo a él
        MockHttpSession conSeguros = sesion("comercial", Map.of("seguros", "WRITE"));
        assertTrue(pasa("GET", "/seguros", conSeguros));
        assertTrue(pasa("POST", "/seguros/guardar", conSeguros));
        // Y quitarle algo que su rol sí le da
        MockHttpSession sinLeads = sesion("comercial", Map.of("leads", "NONE"));
        assertFalse(pasa("GET", "/bandeja", sinLeads));
        assertTrue(pasa("GET", "/kanban/oportunidades", sinLeads));
    }

    @Test
    void verNoEsModificar() throws Exception {
        MockHttpSession s = sesion("tecnico", Map.of("propiedades", "READ"));
        assertTrue(pasa("GET", "/crud/propiedades", s));
        assertFalse(pasa("POST", "/crud/propiedades", s));
    }

    @Test
    void losAjustesNoSeQuedanEnElHiloParaLaSiguientePersona() throws Exception {
        assertTrue(pasa("GET", "/seguros", sesion("comercial", Map.of("seguros", "READ"))));
        assertEquals(Access.NONE, permisos.access("comercial", "seguros"), "tras la petición, solo el rol");
        // Aunque la petición se corte en el guardián (Spring no llama entonces a afterCompletion)
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/bandeja");
        req.setSession(sesion("comercial", Map.of("leads", "NONE", "seguros", "WRITE")));
        assertFalse(guard.preHandle(req, new MockHttpServletResponse(), null));
        assertEquals(Access.NONE, permisos.access("comercial", "seguros"));
    }

    @Test
    void elAdministradorSiempreLoPuedeTodo() {
        PermissionService.ajustesActuales(Map.of("empresa", "NONE", "equipo", "NONE"));
        assertEquals(Access.WRITE, permisos.access("admin", "empresa"));
        assertEquals(Access.WRITE, permisos.access("admin", "equipo"));
    }

    @Test
    void ajustesNoValidosSeIgnoran() {
        PermissionService.ajustesActuales(Map.of("seguros", "TODO", "inventada", "WRITE"));
        assertEquals(Access.NONE, permisos.access("comercial", "seguros"));
        assertEquals(Access.NONE, permisos.access("comercial", "inventada"));
    }

    @Test
    void datosBancariosYSuscripcion_soloQuienDebe() throws Exception {
        // Mi negocio (IBAN): lo ve todo el equipo, lo cambian admin, director y contable
        for (String rol : new String[]{"comercial", "tecnico", "rrhh", "lectura"}) {
            assertTrue(pasa("GET", "/business", sesion(rol, null)), rol + " puede verlo");
            assertFalse(pasa("POST", "/business", sesion(rol, null)), rol + " no puede cambiarlo");
        }
        for (String rol : new String[]{"admin", "director", "contable"}) {
            assertTrue(pasa("POST", "/business", sesion(rol, null)), rol + " puede cambiarlo");
        }
        // Suscripción: un comercial ya no puede abrir el portal de Stripe (cancelar) ni comprar packs
        assertFalse(pasa("POST", "/billing/portal", sesion("comercial", null)));
        assertFalse(pasa("POST", "/billing/credits/s", sesion("comercial", null)));
        assertFalse(pasa("POST", "/billing/checkout/agency", sesion("director", null)));
        assertTrue(pasa("GET", "/billing", sesion("director", null)));
        assertTrue(pasa("POST", "/billing/portal", sesion("admin", null)));
        // /upgrade sigue abierto: los candados de suscripción redirigen ahí (sin bucles)
        assertTrue(pasa("GET", "/upgrade", sesion("comercial", null)));
    }

    @Test
    void importarYAutomatizaciones() throws Exception {
        assertFalse(pasa("POST", "/importar/propiedades", sesion("comercial", null)));
        assertTrue(pasa("POST", "/importar/propiedades", sesion("director", null)));
        assertTrue(pasa("GET", "/workflows", sesion("comercial", null)));
        assertFalse(pasa("POST", "/workflows/guardar", sesion("comercial", null)));
        assertTrue(pasa("POST", "/workflows/guardar", sesion("director", null)));
    }

    @Test
    void lasRutasApiTambienPasanPorElGuardian() throws Exception {
        assertFalse(pasa("POST", "/api/ai/ask", sesion("rrhh", null)), "RR. HH. no tiene IA");
        assertTrue(pasa("POST", "/api/ai/ask", sesion("comercial", null)));
        assertFalse(pasa("GET", "/api/comments/lead/abc", sesion("tecnico", null)));
        assertTrue(pasa("GET", "/api/comments/propiedad/abc", sesion("tecnico", null)));
        // El inquilino no puede usar las API del CRM (antes /api/search devolvía toda la agencia)
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/search");
        req.setSession(sesion("tenant_portal", null));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(guard.preHandle(req, resp, null));
        assertEquals(403, resp.getStatus());
    }

    @Test
    void seccionDe_rutasQueAntesNoTeniaNinguna() {
        assertEquals("empresa", SessionGuardInterceptor.seccionDe("/business"));
        assertEquals("suscripcion", SessionGuardInterceptor.seccionDe("/billing/portal"));
        assertEquals("suscripcion", SessionGuardInterceptor.seccionDe("/creditos"));
        assertNull(SessionGuardInterceptor.seccionDe("/upgrade"));
        assertEquals("datos", SessionGuardInterceptor.seccionDe("/importar/leads"));
        assertEquals("automatizaciones", SessionGuardInterceptor.seccionDe("/workflows/nuevo"));
        assertEquals("herramientas", SessionGuardInterceptor.seccionDe("/finanzas/dcf"));
        assertEquals("inquilinos", SessionGuardInterceptor.seccionDe("/admin/tenant-requests"));
        assertNull(SessionGuardInterceptor.seccionDe("/admin/solicitudes"), "las de la plataforma tienen su propio control");
        assertEquals("ia", SessionGuardInterceptor.seccionDe("/api/ai/ask"));
        assertEquals("leads", SessionGuardInterceptor.seccionDe("/api/timeline/lead/1"));
        assertEquals("transacciones", SessionGuardInterceptor.seccionDe("/calendario-fiscal"));
        assertEquals("email_templates", SessionGuardInterceptor.seccionDe("/reviews"));
        // Páginas públicas bajo prefijos internos: para cualquiera
        assertNull(SessionGuardInterceptor.seccionDe("/reviews/publico/tok"));
        assertNull(SessionGuardInterceptor.seccionDe("/nps/responder/tok"));
        assertNull(SessionGuardInterceptor.seccionDe("/chatbot/api/mensaje"));
        assertNull(SessionGuardInterceptor.seccionDe("/informe/tok"));
        assertEquals("reportes", SessionGuardInterceptor.seccionDe("/informes"));
    }

    @Test
    void matrizDeLasSeccionesNuevas() {
        assertEquals(Access.WRITE, permisos.accesoDelRol("contable", "empresa"));
        assertEquals(Access.WRITE, permisos.accesoDelRol("director", "empresa"));
        assertEquals(Access.READ, permisos.accesoDelRol("comercial", "empresa"));
        assertEquals(Access.NONE, permisos.accesoDelRol("comercial", "suscripcion"));
        assertEquals(Access.NONE, permisos.accesoDelRol("lectura", "personal"), "solo lectura no ve datos de RR. HH.");
        assertEquals(Access.NONE, permisos.accesoDelRol("lectura", "suscripcion"));
        // Todas las secciones tienen nombre para la pantalla de permisos
        for (String s : PermissionService.SECTIONS) {
            if (!"dashboard".equals(s)) assertTrue(PermissionService.SECTION_INFO.containsKey(s), s);
        }
    }
}
