package com.inmobilia.config;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

/** Guardián de sesión (inquilinos, 2FA obligatorio, permisos por rol) y filtro anti-CSRF por origen. */
class SeguridadSesionTest {

    // Supabase sin configurar: la revisión periódica de la cuenta no echa a nadie (fail-open)
    private final SessionGuardInterceptor guard = new SessionGuardInterceptor(
            new SupabaseService(null, null, null, "", ""), null, new PermissionService());

    private static MockHttpSession sesion(String rol) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, "u-1", "org-1", "ana@inmo.es", "Ana", rol);
        return s;
    }

    private MockHttpServletResponse pasar(String metodo, String uri, MockHttpSession s, boolean esperaOk) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
        req.setSession(s);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertEquals(esperaOk, guard.preHandle(req, resp, null), metodo + " " + uri);
        return resp;
    }

    @Test
    void inquilino_soloPuedeUsarSuPortal() throws Exception {
        MockHttpSession s = sesion("tenant_portal");
        pasar("GET", "/portal", s, true);
        pasar("POST", "/portal/request", s, true);
        assertEquals("/portal", pasar("GET", "/dashboard", s, false).getRedirectedUrl());
        assertEquals("/portal", pasar("GET", "/crud/propiedades", s, false).getRedirectedUrl());
        pasar("GET", "/leads", s, false);
    }

    @Test
    void dosPasosObligatorio_soloDejaIrASeguridad() throws Exception {
        MockHttpSession s = sesion("admin");
        s.setAttribute(SessionUtils.KEY_FORZAR_2FA, true);
        pasar("GET", "/seguridad", s, true);
        pasar("POST", "/seguridad/2fa/activar", s, true);
        pasar("GET", "/logout", s, true);
        assertEquals("/seguridad", pasar("GET", "/dashboard", s, false).getRedirectedUrl());
    }

    @Test
    void permisosDelRol_seAplicanEnElCrud() throws Exception {
        MockHttpSession lectura = sesion("lectura");
        pasar("GET", "/crud/propiedades", lectura, true);                  // puede ver
        pasar("POST", "/crud/propiedades", lectura, false);                // no puede guardar
        pasar("POST", "/crud/propiedades/abc/delete", lectura, false);     // ni borrar
        pasar("GET", "/crud/propiedades/new", lectura, false);             // ni abrir el alta

        MockHttpSession tecnico = sesion("tecnico");
        assertEquals("/dashboard", pasar("GET", "/crud/leads", tecnico, false).getRedirectedUrl());
        pasar("POST", "/crud/mantenimiento", tecnico, true);

        MockHttpSession admin = sesion("admin");
        pasar("POST", "/crud/leads/abc/delete", admin, true);
    }

    @Test
    void permisosDelRol_enAgenteIaLeadsFacturasEInformes() throws Exception {
        MockHttpSession contable = sesion("contable");
        pasar("GET", "/invoices", contable, true);
        pasar("POST", "/invoices/new", contable, true);
        assertEquals("/dashboard", pasar("GET", "/bandeja", contable, false).getRedirectedUrl()); // leads: sin acceso
        pasar("GET", "/agente", contable, true);                   // IA: puede ver…
        pasar("POST", "/agente/chat", contable, false);            // …pero no hacer que el agente actúe
        pasar("POST", "/reports/pnl", contable, true);             // generar informe es leer

        MockHttpSession tecnico = sesion("tecnico");
        pasar("GET", "/agente", tecnico, false);
        pasar("GET", "/commissions", tecnico, false);
        pasar("GET", "/entity/lead/abc", tecnico, false);
        pasar("GET", "/calendario", tecnico, true);
        pasar("GET", "/herramientas/radar", tecnico, false);       // herramientas avanzadas: no van en su rol
        pasar("GET", "/calculadora-hipoteca", tecnico, true);      // calculadoras: libres
        pasar("GET", "/herramientas/radar", sesion("comercial"), true);
        pasar("GET", "/dashboard", tecnico, true);

        MockHttpSession comercial = sesion("comercial");
        pasar("POST", "/agente/chat", comercial, true);
        pasar("POST", "/config/smtp", comercial, false);
        pasar("GET", "/permisos-granulares", comercial, false);

        // Llamada desde JavaScript: 403 en JSON en vez de redirección
        MockHttpServletRequest xhr = new MockHttpServletRequest("POST", "/agente/api/chat");
        xhr.setSession(sesion("lectura"));
        xhr.addHeader("Accept", "application/json");
        MockHttpServletResponse r = new MockHttpServletResponse();
        assertFalse(guard.preHandle(xhr, r, null));
        assertEquals(403, r.getStatus());
        assertTrue(r.getContentAsString().contains("\"ok\":false"));

        assertNull(SessionGuardInterceptor.seccionDe("/p/piso-centro"));     // página pública
        assertNull(SessionGuardInterceptor.seccionDe("/crud/desconocida"));
    }

    @Test
    void origen_bloqueaPeticionesDeOtraWebConSesion() throws Exception {
        OrigenFilter f = new OrigenFilter();

        MockHttpServletRequest ajena = new MockHttpServletRequest("POST", "/crud/leads/abc/delete");
        ajena.setServerName("www.inmobiliapro.es");
        ajena.setSession(sesion("admin"));
        ajena.addHeader("Origin", "https://web-maliciosa.com");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        f.doFilter(ajena, r1, new MockFilterChain());
        assertEquals(403, r1.getStatus());

        MockHttpServletRequest propia = new MockHttpServletRequest("POST", "/crud/leads/abc/delete");
        propia.setServerName("www.inmobiliapro.es");
        propia.setSession(sesion("admin"));
        propia.addHeader("Origin", "https://inmobiliapro.es");
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        MockFilterChain c2 = new MockFilterChain();
        f.doFilter(propia, r2, c2);
        assertEquals(200, r2.getStatus());
        assertNotNull(c2.getRequest(), "la petición propia continúa");

        // Formulario público sin sesión (p. ej. web de un cliente): no se toca
        MockHttpServletRequest publica = new MockHttpServletRequest("POST", "/solicitar-cuenta");
        publica.addHeader("Origin", "https://otra-web.com");
        MockFilterChain c3 = new MockFilterChain();
        f.doFilter(publica, new MockHttpServletResponse(), c3);
        assertNotNull(c3.getRequest());
    }
}
