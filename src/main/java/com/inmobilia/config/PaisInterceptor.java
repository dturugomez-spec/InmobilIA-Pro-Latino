package com.inmobilia.config;

import com.inmobilia.service.PaisService;
import com.inmobilia.util.Pais;
import com.inmobilia.util.PaisActual;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Fija el país de la inmobiliaria para toda la petición (moneda, impuesto, documentos, zona horaria).
 * Sin sesión se usa el país por defecto; las páginas públicas de una inmobiliaria llaman a PaisService.usar.
 */
@Component
@RequiredArgsConstructor
public class PaisInterceptor implements HandlerInterceptor {

    private final PaisService paises;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        PaisActual.limpiar();
        HttpSession session = req.getSession(false);
        if (session != null && SessionUtils.isLoggedIn(session)) {
            PaisActual.set(paises.deOrganizacion(SessionUtils.uid(session)));
        }
        return true;
    }

    /** ${pais} y ${mon} ("MXN", "COP"…) para todas las vistas, con el país ya decidido por el controlador. */
    @Override
    public void postHandle(HttpServletRequest req, HttpServletResponse resp, Object handler, ModelAndView mav) {
        // En una redirección, el modelo acabaría en la URL (?mon=MXN&ciudadEjemplo=…)
        if (mav == null || mav.getView() instanceof org.springframework.web.servlet.view.RedirectView
                || (mav.getViewName() != null && mav.getViewName().startsWith("redirect:"))) return;
        Pais pais = PaisActual.get();
        mav.addObject("pais", pais);
        mav.addObject("mon", pais.moneda());
        if (!mav.getModel().containsKey("ciudadEjemplo")) {
            mav.addObject("ciudadEjemplo", com.inmobilia.util.PreciosReferencia.ciudadPrincipal(pais));
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        PaisActual.limpiar(); // el hilo vuelve al pool: que no herede el país de otra inmobiliaria
    }
}
