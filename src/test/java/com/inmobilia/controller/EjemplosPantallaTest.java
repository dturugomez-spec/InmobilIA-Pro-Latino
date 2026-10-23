package com.inmobilia.controller;

import com.inmobilia.service.EjemplosService;
import com.inmobilia.util.SessionUtils;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pantalla de ejemplos para demos. */
class EjemplosPantallaTest {

    private final EjemplosService ejemplos = mock(EjemplosService.class);
    private final com.inmobilia.service.PlanService plan = mock(com.inmobilia.service.PlanService.class);
    private final EjemplosController ctrl = new EjemplosController(ejemplos, plan);

    {
        when(plan.esAdminPlataforma("a@b.es")).thenReturn(true);   // la cuenta de demostración
    }

    private String render(ExtendedModelMap model) {
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
        return e.process("ejemplos", Set.of("div[layout:fragment='content']"), c);
    }

    private MockHttpSession sesion(String rol) {
        return sesion(rol, "a@b.es");
    }

    private MockHttpSession sesion(String rol, String email) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, "u1", "org1", email, "Ana", rol);
        return s;
    }

    @Test
    void soloLaCuentaDeDemostracion_noLosAdministradoresDeLosClientes() {
        assertEquals("redirect:/dashboard", ctrl.pantalla(sesion("admin", "duena@inmobiliaria-cliente.es"), new ExtendedModelMap()));
        assertEquals("redirect:/dashboard", ctrl.cargar(sesion("admin", "duena@inmobiliaria-cliente.es")));
        verifyNoInteractions(ejemplos);
    }

    @Test
    void sinEjemplos_conEjemplosYCargando() {
        when(ejemplos.estado("org1")).thenReturn(new EjemplosService.Estado(false, 0, null, Map.of()));
        when(ejemplos.filasCargadas("org1")).thenReturn(0);
        ExtendedModelMap m = new ExtendedModelMap();
        assertEquals("ejemplos", ctrl.pantalla(sesion("admin"), m));
        String html = render(m);
        assertTrue(html.contains("Cargar ejemplos"));
        assertFalse(html.contains("Quitar ejemplos"));

        when(ejemplos.estado("org1")).thenReturn(new EjemplosService.Estado(false, 312, "Ejemplos cargados: 312 registros",
                Map.of("leads", 8, "microsites_error", "duplicate key")));
        when(ejemplos.filasCargadas("org1")).thenReturn(312);
        m = new ExtendedModelMap();
        ctrl.pantalla(sesion("admin"), m);
        html = render(m);
        assertTrue(html.contains("Quitar ejemplos"));
        assertTrue(html.contains("312"));
        assertTrue(html.contains("microsites_error"), "se ve qué no se cargó");

        when(ejemplos.estado("org1")).thenReturn(new EjemplosService.Estado(true, 120, "Cargando ejemplos… 120 registros", Map.of()));
        m = new ExtendedModelMap();
        ctrl.pantalla(sesion("admin"), m);
        html = render(m);
        assertTrue(html.contains("Cargando ejemplos… 120 registros"));
        assertTrue(html.contains("location.reload"));
        assertFalse(html.contains("Cargar ejemplos</button>"));
    }

    @Test
    void soloElAdministradorDeLaCuenta() {
        assertEquals("redirect:/dashboard", ctrl.cargar(sesion("agente")));
        assertEquals("redirect:/dashboard", ctrl.quitar(sesion("director")));
        verifyNoInteractions(ejemplos);
        assertEquals("redirect:/ejemplos", ctrl.cargar(sesion("admin")));
        verify(ejemplos).cargar("org1");
    }
}
