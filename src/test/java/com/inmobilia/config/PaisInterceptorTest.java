package com.inmobilia.config;

import com.inmobilia.service.PaisService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** El país va a todas las vistas, pero nunca a la URL de una redirección. */
class PaisInterceptorTest {

    private final PaisInterceptor interceptor = new PaisInterceptor(mock(PaisService.class));

    @Test
    void vistaNormal_llevaPaisYMoneda() {
        ModelAndView mav = new ModelAndView("dashboard");
        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null, mav);
        assertNotNull(mav.getModel().get("pais"));
        assertNotNull(mav.getModel().get("mon"));
    }

    @Test
    void redireccion_noLlevaNadaQueAcabeEnLaUrl() {
        ModelAndView porNombre = new ModelAndView("redirect:/cadencias");
        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null, porNombre);
        assertTrue(porNombre.getModel().isEmpty());
        ModelAndView porVista = new ModelAndView(new RedirectView("/feeds"));
        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null, porVista);
        assertTrue(porVista.getModel().isEmpty());
    }
}
