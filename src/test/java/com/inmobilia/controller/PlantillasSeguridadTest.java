package com.inmobilia.controller;

import com.inmobilia.service.TotpService;
import com.inmobilia.service.TwoFactorService;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renderiza las pantallas de seguridad con datos de ejemplo: un error de expresión
 * en una plantilla daría un 500 en producción, así que lo cazamos aquí.
 */
class PlantillasSeguridadTest {

    private final SpringTemplateEngine engine = motor();

    private static SpringTemplateEngine motor() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        e.addDialect(new LayoutDialect());
        return e;
    }

    /** Solo el contenido de la página (sin el layout de la app, que necesita sesión). */
    private String contenido(String plantilla, Context ctx) {
        return engine.process(plantilla, Set.of("div[layout:fragment='content']"), ctx);
    }

    @Test
    void seguridad_sin2fa_y_conAltaEnCurso() {
        TwoFactorService tf = new TwoFactorService(new TotpService(), null, new com.inmobilia.service.CifradoService(""));
        Context c = new Context();
        c.setVariable("activo2fa", false);
        c.setVariable("exigido", false);
        c.setVariable("esAdminOrg", true);
        c.setVariable("forzado", true);
        c.setVariable("codigosRestantes", 0);
        c.setVariable("eventos", List.of());
        c.setVariable("equipo", List.of(
                Map.of("nombre", "Ana", "email", "ana@inmo.es", "rol", "admin", "con2fa", false)));
        String html = contenido("seguridad", c);
        assertTrue(html.contains("Activar verificación en dos pasos"));
        assertTrue(html.contains("exige la verificación"));
        assertTrue(html.contains("ana@inmo.es"));

        c.setVariable("qrSvg", tf.qrSvg(tf.urlAlta("ana@inmo.es", "JBSWY3DPEHPK3PXP")));
        c.setVariable("secretoLegible", "JBSW Y3DP EHPK 3PXP");
        html = contenido("seguridad", c);
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("JBSW Y3DP EHPK 3PXP"));
        assertTrue(html.contains("/seguridad/2fa/activar"));
    }

    @Test
    void seguridad_activa_conCodigosNuevosYEventos() {
        Context c = new Context();
        c.setVariable("activo2fa", true);
        c.setVariable("exigido", true);
        c.setVariable("esAdminOrg", false);
        c.setVariable("forzado", false);
        c.setVariable("codigosRestantes", 10);
        c.setVariable("codigosNuevos", List.of("abcd-2345", "efgh-6789"));
        java.util.Map<String, Object> ev = new java.util.HashMap<>();
        ev.put("fecha", "08/10/2026 10:15");
        ev.put("descripcion", "Inicio de sesión");
        ev.put("ip", null);
        ev.put("nivel", "info");
        c.setVariable("eventos", List.of(ev));
        String html = contenido("seguridad", c);
        assertTrue(html.contains("abcd-2345"));
        assertTrue(html.contains("no se puede desactivar"));
        assertTrue(html.contains("08/10/2026 10:15"));
        assertFalse(html.contains("Seguridad del equipo"));
    }

    @Test
    void paginasLegales_seguridadDpaYPrivacidad() {
        for (String region : new String[]{null, "Unión Europea (Fráncfort)"}) {
            Context c = new Context();
            c.setVariable("regionDatos", region);
            PlantillasPermisosTest.conPais(c);
            c.setVariable("copiasDatos", region == null ? null : "copia diaria");
            c.setVariable("cifradoActivo", region != null);
            String seg = engine.process("legal/seguridad", c);
            assertTrue(seg.contains("Stripe, Inc."));
            assertEquals(region != null, seg.contains("Fráncfort"));
            String dpa = engine.process("legal/dpa", c);
            assertTrue(dpa.contains("Anexo II"));
            assertTrue(engine.process("legal/privacy", c).contains("OpenAI"));
        }
    }

    @Test
    void pantallasPublicas_2fa_y_crearContrasena() {
        Context c = new Context();
        c.setVariable("error", "Código incorrecto. Te quedan 4 intentos.");
        String html = engine.process("auth_2fa", c);
        assertTrue(html.contains("Código incorrecto"));
        assertTrue(html.contains("/login/2fa"));

        Context r = new Context();
        r.setVariable("token", "abc123");
        r.setVariable("bienvenida", true);
        html = engine.process("auth_reset", r);
        assertTrue(html.contains("Crea tu contraseña"));
        assertTrue(html.contains("name=\"bienvenida\""));

        r.setVariable("bienvenida", false);
        html = engine.process("auth_reset", r);
        assertTrue(html.contains("Nueva contraseña"));
        assertFalse(html.contains("name=\"bienvenida\""));
    }

    @Test
    void selloGrupoKivasa_enPaginasPublicas() {
        for (String p : new String[]{"landing", "auth", "auth_forgot", "auth_2fa", "auth_reset", "features_index",
                "marketing/para_agencias", "marketing/para_particulares", "marketing/para_asset_managers"}) {
            String html = engine.process(p, new Context());
            assertTrue(html.contains("Grupo Kivasa"), p + " debe mostrar el sello del grupo");
            assertTrue(html.contains("/brand/grupo-kivasa.svg"), p + " debe mostrar el logo del grupo");
        }
    }
}
