package com.inmobilia.controller;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Páginas públicas que abre el cliente desde un enlace (encuesta NPS, reseña, puertas abiertas,
 * referidos, reserva). Se renderizan tal cual llegan del controlador: un "!variable" sobre una
 * variable que no viene rompe la página con un 500 justo en el caso normal.
 */
class PlantillasPublicasTest {

    private final SpringTemplateEngine engine = motor();

    private static SpringTemplateEngine motor() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        e.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override
            protected String computeContextPath(org.thymeleaf.context.IExpressionContext ctx, String base,
                                                Map<String, Object> params) { return ""; }
        });
        return e;
    }

    private String pagina(String plantilla, Map<String, Object> vars) {
        Context c = new Context();
        PlantillasPermisosTest.conPais(c); // inmobiliaria de Colombia (COP, separadores 1.234,56)
        vars.forEach(c::setVariable);
        return engine.process(plantilla, c);
    }

    private static Map<String, Object> fila(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void encuestaNps_alAbrirElEnlace() {
        String html = pagina("nps_public", Map.of("r", fila("id", "1", "token", "t", "cliente_nombre", "Ana")));
        assertTrue(html.contains("<form"), "debe verse el formulario");
        assertTrue(pagina("nps_public", Map.of("gracias", true)).contains("racias"));
    }

    @Test
    void resena_alAbrirElEnlace_yAvisoSinEstrellas() {
        Map<String, Object> review = fila("id", "1", "solicitud_token", "t", "autor_nombre", "Ana");
        assertTrue(pagina("reviews_public", Map.of("review", review)).contains("Enviar review"));
        String aviso = pagina("reviews_public", Map.of("review", review, "aviso", "Marca de 1 a 5 estrellas antes de enviar."));
        assertTrue(aviso.contains("Marca de 1 a 5 estrellas") && aviso.contains("Enviar review"), "aviso y formulario a la vez");
        assertFalse(pagina("reviews_public", Map.of("error", "Enlace no válido")).contains("Enviar review"));
    }

    @Test
    void puertasAbiertas_alAbrirElEnlace() {
        Map<String, Object> oh = fila("id", "1", "pagina_publica_token", "t", "titulo", "Jornada", "fecha", "2026-11-01T10:00:00+00:00",
                "propiedad", fila("name", "Piso centro", "address", "C/ Mayor 1"));
        String html = pagina("open_house_public", Map.of("oh", oh));
        assertTrue(html.contains("<form"), "debe verse el formulario de inscripción");
        assertFalse(pagina("open_house_public", Map.of("error", "Evento no encontrado")).contains("<form"));
    }

    @Test
    void referidos_alAbrirElEnlace() {
        Map<String, Object> ref = fila("id", "1", "codigo", "ABC", "referente_nombre", "Luis", "user_id", "org1");
        String html = pagina("referrals_landing", Map.of("ref", ref));
        assertTrue(html.contains("<form"), "debe verse el formulario");
        assertFalse(pagina("referrals_landing", Map.of("error", "Código no válido")).contains("<form"));
    }

    @Test
    void reservaOnline_formularioYConfirmacion() {
        Map<String, Object> p = fila("id", "p1", "name", "Piso centro", "address", "C/ Mayor 1", "current_value", 250000);
        String html = pagina("online_reservation_public", Map.of("propiedad", p, "precio", 250000.0,
                "direccion", "Cra. 7 # 12-30, Bogotá", "importeSugerido", 2500.0));
        assertTrue(html.contains("Cra. 7 # 12-30, Bogotá"));
        assertTrue(html.contains("250.000 COP"));
        assertTrue(html.contains("value=\"2500\""));
        assertFalse(html.contains("Stripe"), "no se promete un pago con tarjeta que no existe");
        String ok = pagina("online_reservation_pay", Map.of("reserva", fila("importe", 2500), "direccion", "C/ Mayor 1"));
        assertTrue(ok.contains("Solicitud de reserva enviada"));
        assertTrue(ok.contains("2.500,00 COP"));
        assertFalse(ok.contains("checkout.stripe.com"));
    }
}
