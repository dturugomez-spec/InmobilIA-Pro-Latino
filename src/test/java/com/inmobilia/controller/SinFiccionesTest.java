package com.inmobilia.controller;

import com.inmobilia.service.*;
import com.inmobilia.util.SessionUtils;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Herramientas que antes aparentaban funcionar: staging, comparador de renta, reconocimiento de estancias. */
class SinFiccionesTest {

    private final SupabaseService supabase = mock(SupabaseService.class);
    private final AIService ai = mock(AIService.class);

    private static String pintar(String plantilla, ExtendedModelMap model) {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        e.addDialect(new LayoutDialect());
        Context c = new Context();
        model.forEach(c::setVariable);
        return e.process(plantilla, Set.of("div[layout:fragment='content']"), c);
    }

    private static MockHttpSession sesion() {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.login(s, "org1", "a@b.es", "Ana");
        return s;
    }

    // ── Staging digital ──

    @Test
    void staging_siLaIAFalla_seDevuelvenLosCreditosYQuedaEnError() {
        NotificationService notif = mock(NotificationService.class);
        StagingService staging = new StagingService(supabase, mock(StorageService.class), ai, notif);
        when(ai.editarImagen(any(), anyString(), anyString())).thenThrow(new IllegalStateException("OpenAI ha rechazado la foto"));
        staging.procesar("s1", "org1", "Salón", new byte[]{1, 2}, "image/jpeg", "nordico", "salon");
        verify(ai).devolverCreditos("org1", StagingService.CREDITOS);
        verify(supabase).update(eq("staging_proyectos"), eq("s1"),
                argThat(m -> "error".equals(m.get("estado")) && m.get("metadata") instanceof Map<?, ?> md
                        && "OpenAI ha rechazado la foto".equals(md.get("error"))), eq("org1"));
    }

    @Test
    void staging_atascadoTrasUnReinicio_seCierraYDevuelveSoloLoCobrado() {
        StagingService staging = new StagingService(supabase, mock(StorageService.class), ai, mock(NotificationService.class));
        String hace1h = OffsetDateTime.now().minusHours(1).toString();
        List<Map<String, Object>> ps = new ArrayList<>(List.of(
                new HashMap<>(Map.of("id", "a", "estado", "procesando", "created_at", hace1h, "metadata", Map.of("creditos", 5))),
                new HashMap<>(Map.of("id", "b", "estado", "pendiente", "created_at", hace1h)),            // antiguo: no cobró
                new HashMap<>(Map.of("id", "c", "estado", "procesando", "created_at", OffsetDateTime.now().toString()))));
        staging.cerrarAtascados(ps, "org1");
        verify(ai, times(1)).devolverCreditos("org1", 5);
        assertEquals("error", ps.get(0).get("estado"));
        assertEquals("error", ps.get(1).get("estado"));
        assertEquals("procesando", ps.get(2).get("estado"), "el reciente sigue en marcha");
    }

    @Test
    void staging_pantallaConTodosLosEstados() {
        StagingService staging = mock(StagingService.class);
        VirtualStagingController ctrl = new VirtualStagingController(supabase, mock(StorageService.class), staging, ai);
        when(supabase.selectFilter(eq("staging_proyectos"), anyString())).thenReturn(List.of(
                new HashMap<>(Map.of("id", "1", "nombre", "Salón", "estado", "completado", "estilo", "nordico", "tipo_habitacion", "salon",
                        "imagen_original_url", "https://x/a.jpg", "imagen_staged_url", "https://x/b.jpg")),
                new HashMap<>(Map.of("id", "2", "nombre", "Dormitorio", "estado", "procesando", "estilo", "moderno", "tipo_habitacion", "dormitorio")),
                new HashMap<>(Map.of("id", "3", "nombre", "Cocina", "estado", "error", "estilo", "boho", "tipo_habitacion", "cocina",
                        "metadata", Map.of("error", "La IA no ha respondido a tiempo")))));
        when(supabase.selectFilter(eq("propiedades"), anyString())).thenReturn(List.of(Map.of("id", "p1", "direccion", "Calle Mayor 1")));
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("virtual_staging", ctrl.panel(sesion(), model));
        assertEquals(true, model.get("enCurso"));
        String html = pintar("virtual_staging", model);
        assertTrue(html.contains("enctype=\"multipart/form-data\""));
        assertTrue(html.contains("Recreación virtual"));
        assertTrue(html.contains("La IA no ha respondido a tiempo"));
        assertTrue(html.contains("location.reload"));
        assertFalse(html.contains("imagen_original_url\" required"), "ya no se pide una URL suelta");
    }

    @Test
    void staging_sinCreditos_noSeSubeNada() {
        StorageService storage = mock(StorageService.class);
        VirtualStagingController ctrl = new VirtualStagingController(supabase, storage, mock(StagingService.class), ai);
        when(ai.gastarCreditos("org1", StagingService.CREDITOS)).thenReturn(false);
        var foto = new org.springframework.mock.web.MockMultipartFile("foto", "salon.jpg", "image/jpeg", new byte[]{1, 2, 3});
        assertEquals("redirect:/staging-digital", ctrl.crear(Map.of("nombre", "x", "estilo", "moderno", "tipo_habitacion", "salon"), foto, sesion()));
        verifyNoInteractions(storage);
        verify(supabase, never()).insert(anyString(), anyMap());
    }

    // ── Renta vs mercado ──

    @Test
    void reconocimiento_usaLaFotoDeVerdad() {
        RoomDetectionController ctrl = new RoomDetectionController(ai, new com.fasterxml.jackson.databind.ObjectMapper());
        String foto = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";
        when(ai.callConImagen(anyString(), anyString(), eq(foto), anyInt(), eq("org1")))
                .thenReturn("```json\n{\"tipo_habitacion\":\"cocina\",\"estado\":\"reformada\",\"sugerencias\":[\"más luz\"]}\n```");
        Map<String, Object> r = ctrl.clasificar(Map.of("imagen", foto, "descripcion", ""), sesion());
        assertEquals(true, r.get("ok"));
        assertEquals(true, r.get("conFoto"));
        assertEquals("cocina", ((Map<?, ?>) r.get("resultado")).get("tipo_habitacion"));
        verify(ai, never()).call(any(), anyString(), anyString(), anyInt(), anyString());

        assertEquals(false, ctrl.clasificar(Map.of("imagen", "data:text/html;base64,PHNjcmlwdD4="), sesion()).get("ok"));
        assertEquals(false, ctrl.clasificar(Map.of(), sesion()).get("ok"));
        assertFalse(pintar("room_detection", new ExtendedModelMap()).contains("[["), "sin expresiones inline sueltas");
    }
}
