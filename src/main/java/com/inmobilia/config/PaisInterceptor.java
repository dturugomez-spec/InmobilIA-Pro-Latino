package com.inmobilia.config;

import com.inmobilia.service.PaisService;
import com.inmobilia.util.Pais;
import com.inmobilia.util.PaisActual;
import com.inmobilia.util.PreciosPlataforma;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.*;

/**
 * Fija el país de la inmobiliaria para toda la petición (moneda, impuesto, documentos, zona horaria).
 * Sin sesión, el del visitante: el que eligió en la web (?pais=CO, que se recuerda en una cookie)
 * o el de su navegador (es-CO → Colombia); si no, el país por defecto. Las páginas públicas de una
 * inmobiliaria llaman a PaisService.usar, que manda sobre esto.
 */
@Component
@RequiredArgsConstructor
public class PaisInterceptor implements HandlerInterceptor {

    static final String COOKIE = "pais";

    private final PaisService paises;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        PaisActual.limpiar();
        HttpSession session = req.getSession(false);
        if (session != null && SessionUtils.isLoggedIn(session)) {
            PaisActual.set(paises.deOrganizacion(SessionUtils.uid(session)));
        } else {
            Pais visitante = paisVisitante(req, resp);
            if (visitante != null) PaisActual.set(visitante);
        }
        return true;
    }

    /** País de quien visita la web sin cuenta, o null si no se sabe. */
    static Pais paisVisitante(HttpServletRequest req, HttpServletResponse resp) {
        String elegido = req.getParameter("pais");
        if (elegido != null && elegido.trim().length() == 2 && Pais.existe(elegido)) {
            String codigo = elegido.trim().toUpperCase(Locale.ROOT);
            Cookie c = new Cookie(COOKIE, codigo);
            c.setPath("/");
            c.setMaxAge(60 * 60 * 24 * 180);
            c.setHttpOnly(true);
            resp.addCookie(c);
            return Pais.de(codigo);
        }
        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (COOKIE.equals(c.getName()) && c.getValue() != null && c.getValue().length() == 2 && Pais.existe(c.getValue())) {
                    return Pais.de(c.getValue());
                }
            }
        }
        for (Locale l : Collections.list(req.getLocales())) {
            String region = l.getCountry();
            if (region != null && region.length() == 2 && Pais.existe(region)) return Pais.de(region);
        }
        return null;
    }

    /**
     * ${pais} y ${mon} ("MXN", "COP"…) para todas las vistas, con el país ya decidido por el controlador;
     * ${tarifa} con lo que cuesta InmobilIA Pro en su moneda y ${paisesPrecios} para elegir país en la web.
     */
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
        mav.addObject("tarifa", PreciosPlataforma.vista());
        mav.addObject("paisesPrecios", PAISES_PRECIOS);
    }

    /** Países para el selector de precios de la web: código, bandera, nombre y moneda en la que pagan. */
    private static final List<Map<String, String>> PAISES_PRECIOS = Pais.todos().stream()
            .sorted(Comparator.comparing(Pais::nombre))
            .map(p -> Map.of("codigo", p.codigo(), "bandera", p.bandera(), "nombre", p.nombre(),
                    "moneda", PreciosPlataforma.monedaDe(p)))
            .toList();

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        PaisActual.limpiar(); // el hilo vuelve al pool: que no herede el país de otra inmobiliaria
    }
}
