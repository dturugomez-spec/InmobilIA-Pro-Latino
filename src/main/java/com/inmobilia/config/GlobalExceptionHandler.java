package com.inmobilia.config;

import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;

/**
 * Manejo global de excepciones — convierte stack traces feos en páginas 4xx/5xx amigables.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Ruta o archivo inexistente → 404 real (antes salía 200, y NoResourceFoundException se registraba como 500). */
    @ExceptionHandler({NoHandlerFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public Object handle404(HttpServletRequest req, HttpServletResponse resp, Model model) {
        if (isApi(req)) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Not found"));
        resp.setStatus(404);
        model.addAttribute("status", 404);
        model.addAttribute("message", "Página no encontrada");
        model.addAttribute("path", req.getRequestURI());
        return "error";
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public Object handleBadRequest(Exception e, HttpServletRequest req, HttpServletResponse resp, Model model) {
        log.warn("Bad request en {}: {}", req.getRequestURI(), e.getMessage());
        if (isApi(req)) return ResponseEntity.status(400).body(Map.of("ok", false, "error", e.getMessage()));
        resp.setStatus(400);
        model.addAttribute("status", 400);
        model.addAttribute("message", "Datos no válidos: " + e.getMessage());
        model.addAttribute("path", req.getRequestURI());
        return "error";
    }

    /** Cuerpo de la petición ilegible (JSON inválido, codificación errónea) → 400, no 500. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public Object handleUnreadable(Exception e, HttpServletRequest req, HttpServletResponse resp, Model model) {
        log.warn("Cuerpo no legible en {}: {}", req.getRequestURI(), e.getMessage());
        String uri = req.getRequestURI();
        boolean json = isApi(req) || (uri != null && uri.startsWith("/agente"));
        if (json) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "Petición mal formada (JSON o codificación inválida)."));
        }
        resp.setStatus(400);
        model.addAttribute("status", 400);
        model.addAttribute("message", "Petición mal formada.");
        model.addAttribute("path", uri);
        return "error";
    }

    @ExceptionHandler(Exception.class)
    public Object handleAll(Exception e, HttpServletRequest req, HttpServletResponse resp, HttpSession session, Model model) {
        // Las excepciones de Spring traen su código (405 método no permitido, ResponseStatusException
        // de /debug → 404…). Solo lo que de verdad es fallo del servidor es 500 y se registra como error.
        int code = (e instanceof org.springframework.web.ErrorResponse er) ? er.getStatusCode().value() : 500;
        String uid = session != null ? SessionUtils.realUid(session) : "anonymous";
        if (code >= 500) log.error("Error {} en {} (user={}): {}", code, req.getRequestURI(), uid, e.getMessage(), e);
        else log.debug("{} en {}: {}", code, req.getRequestURI(), e.getMessage());

        if (isApi(req)) {
            return ResponseEntity.status(code)
                    .body(Map.of("ok", false, "error", code >= 500 ? "Error interno. Lo hemos registrado." : "Petición no válida."));
        }
        resp.setStatus(code);
        model.addAttribute("status", code);
        model.addAttribute("message", code == 404 ? "Página no encontrada"
                : code == 405 ? "Método no permitido"
                : code >= 500 ? "Error interno del servidor"
                : "No se pudo completar la petición");
        model.addAttribute("path", req.getRequestURI());
        model.addAttribute("ref", "REQ-" + Long.toHexString(System.currentTimeMillis()).toUpperCase());
        return "error";
    }

    private static boolean isApi(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri != null && (uri.startsWith("/api/") || uri.startsWith("/stripe/webhook"));
    }
}
