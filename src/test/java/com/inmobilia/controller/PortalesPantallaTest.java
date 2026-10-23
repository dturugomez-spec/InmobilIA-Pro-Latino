package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pantalla de portales y enlaces públicos de exportación. */
class PortalesPantallaTest {

    private final SupabaseService supabase = mock(SupabaseService.class);
    private final com.inmobilia.service.PaisService paises = mock(com.inmobilia.service.PaisService.class);
    private final PortalFeedController ctrl = new PortalFeedController(supabase, paises);

    PortalesPantallaTest() {
        when(paises.usar(anyString())).thenReturn(com.inmobilia.util.Pais.de("CO"));
    }

    private static Map<String, Object> prop(String id, String nombre, Object precio, boolean publicar, String estado) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("user_id", "org1");
        p.put("name", nombre);
        p.put("city", "Bogotá");
        p.put("postal_code", "110111");
        p.put("current_value", precio);
        p.put("area_m2", 70);
        p.put("notes", "Exterior con ascensor");
        p.put("publicar_portales", publicar);
        p.put("status", estado);
        p.put("created_at", "2026-10-01T10:00:00+00:00");
        return p;
    }

    private void datos() {
        Map<String, Object> feed = new HashMap<>(Map.of("id", "f1", "user_id", "org1", "feed_token", "abcdef1234567890",
                "accesos_total", 3, "feed_enabled", true, "publicar_todas", false));
        when(supabase.selectFilter(eq("portal_feeds"), anyString())).thenReturn(List.of(feed));
        when(supabase.selectFilter(eq("propiedades"), anyString())).thenReturn(List.of(
                prop("aaaaaaaa-0000-0000-0000-000000000001", "Depto. Chapinero", 320000, true, "Libre"),
                prop("aaaaaaaa-0000-0000-0000-000000000002", "Penthouse Usaquén", null, true, "Libre"),
                prop("aaaaaaaa-0000-0000-0000-000000000003", "Local Teusaquillo", 150000, false, "Libre"),
                prop("aaaaaaaa-0000-0000-0000-000000000004", "Dúplex Cedritos", 500000, true, "Reservado")));
        when(supabase.selectFilter(eq("attachments"), anyString())).thenReturn(List.of(
                Map.of("entity_id", "aaaaaaaa-0000-0000-0000-000000000001", "url", "https://cdn.x.com/sol.jpg")));
        when(supabase.selectFilter(eq("portal_feed_logs"), anyString())).thenReturn(List.of());
    }

    @Test
    void pantalla_seRenderizaYDiceQueSaleYQueFalta() {
        datos();
        MockHttpSession sesion = new MockHttpSession();
        SessionUtils.login(sesion, "org1", "a@b.es", "Ana");
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("portal_feeds_config", ctrl.config(sesion, model));
        assertEquals(1, model.get("enPortales"), "solo el depto.: el penthouse no tiene precio, el local no está marcado, el dúplex está reservado");

        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        e.addDialect(new LayoutDialect());
        Context c = new Context();
        PlantillasPermisosTest.conPais(c); // inmobiliaria de Colombia
        model.forEach(c::setVariable);
        String html = e.process("portal_feeds_config", Set.of("div[layout:fragment='content']"), c);
        assertTrue(html.contains("/portal/abcdef1234567890/feed.xml"));
        assertTrue(html.contains("LISTO"));
        assertTrue(html.contains("Falta: precio"));
        assertTrue(html.contains("320.000 COP"));
        assertTrue(html.contains("Finca Raíz"), "portales habituales del país");
        assertTrue(html.contains("name=\"op_aaaaaaaa-0000-0000-0000-000000000001\""));
    }

    @Test
    void enlacePublico_feedConLoPublicableYPortalDesconocido404() {
        datos();
        var resp = ctrl.feed("abcdef1234567890", "feed", new MockHttpServletRequest());
        assertEquals(200, resp.getStatusCode().value());
        String xml = resp.getBody();
        assertTrue(xml.contains("<reference>aaaaaaaa-0000-0000-0000-000000000001</reference>"));
        assertTrue(xml.contains("<currency>COP</currency>") && xml.contains("<country>CO</country>"));
        assertTrue(xml.contains("https://cdn.x.com/sol.jpg"), "fotos de la galería");
        assertFalse(xml.contains("Penthouse Usaquén"), "sin precio no sale");
        assertFalse(xml.contains("000000000004"), "reservado no sale");
        assertEquals(404, ctrl.feed("abcdef1234567890", "inventado", new MockHttpServletRequest()).getStatusCode().value());
        assertEquals(404, ctrl.feed("../x", "feed", new MockHttpServletRequest()).getStatusCode().value());
    }

    @Test
    void guardarSeleccion_soloCambiaLoQueCambia() {
        datos();
        when(supabase.update(eq("propiedades"), anyString(), anyMap(), eq("org1"))).thenReturn(true);
        MockHttpSession sesion = new MockHttpSession();
        SessionUtils.login(sesion, "org1", "a@b.es", "Ana");
        Map<String, String> form = new HashMap<>();
        for (int i = 1; i <= 4; i++) form.put("op_aaaaaaaa-0000-0000-0000-00000000000" + i, "venta");
        form.put("op_aaaaaaaa-0000-0000-0000-000000000003", "alquiler");
        // Marcados: 1 (ya estaba) y 3 (nuevo); 2 y 4 se desmarcan
        ctrl.guardarInmuebles(List.of("aaaaaaaa-0000-0000-0000-000000000001", "aaaaaaaa-0000-0000-0000-000000000003"), form, sesion);
        verify(supabase, never()).update(eq("propiedades"), eq("aaaaaaaa-0000-0000-0000-000000000001"), anyMap(), anyString());
        verify(supabase).update(eq("propiedades"), eq("aaaaaaaa-0000-0000-0000-000000000003"),
                eq(Map.of("publicar_portales", true, "operacion", "alquiler")), eq("org1"));
        verify(supabase).update(eq("propiedades"), eq("aaaaaaaa-0000-0000-0000-000000000002"),
                eq(Map.of("publicar_portales", false)), eq("org1"));
    }
}
