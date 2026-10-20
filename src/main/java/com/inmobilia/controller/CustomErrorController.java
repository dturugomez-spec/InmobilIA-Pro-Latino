package com.inmobilia.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Reemplaza la Whitelabel Error Page por una pagina branded.
 */
@Controller
@Slf4j
public class CustomErrorController implements ErrorController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @RequestMapping("/error")
    public String handle(HttpServletRequest req, HttpServletResponse resp, Model model) {
        Object statusObj = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusObj != null ? Integer.parseInt(statusObj.toString()) : 500;
        Object path = req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // Devolver el código real (antes salía 200 → Google indexaba páginas de error como reales)
        resp.setStatus(status);
        String traceId = "REQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        if (status >= 500) {
            // Registrar la causa real para poder diagnosticar desde los logs de Railway
            Object ex = req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            if (ex instanceof Throwable t) log.error("[{}] {} {} → {}", traceId, status, path, t.toString(), t);
            else log.error("[{}] {} {} (sin excepción adjunta)", traceId, status, path);
        }

        String title;
        String message;
        switch (status) {
            case 404 -> {
                title = "Pagina no encontrada";
                message = "La ruta solicitada no existe o ha sido movida.";
            }
            case 403 -> {
                title = "Acceso denegado";
                message = "No tienes permisos para acceder a este recurso.";
            }
            case 401 -> {
                title = "Sesion expirada";
                message = "Tu sesion ha caducado. Inicia sesion nuevamente.";
            }
            case 500 -> {
                title = "Error interno del servidor";
                message = "Se ha producido un fallo procesando tu peticion. El equipo ha sido notificado.";
            }
            default -> {
                HttpStatus hs = HttpStatus.resolve(status);
                title = hs != null ? hs.getReasonPhrase() : "Error";
                message = "Se ha producido una incidencia.";
            }
        }

        model.addAttribute("status", status);
        model.addAttribute("title", title);
        model.addAttribute("message", message);
        model.addAttribute("traceId", traceId);
        model.addAttribute("timestamp", LocalDateTime.now().format(TS));
        model.addAttribute("path", path != null ? path.toString() : "—");
        return "error";
    }
}
