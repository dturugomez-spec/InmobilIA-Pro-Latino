package com.inmobilia.demo;

import com.inmobilia.service.EjemplosService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo local (ver {@link DemoLocalConfig}): al arrancar crea la inmobiliaria de ejemplo de Ciudad
 * de México con todas las herramientas rellenas, y /entrar abre la sesión sin contraseña.
 */
@Controller
@Profile("demo-local")
@RequiredArgsConstructor
@Slf4j
public class DemoLocalController {

    public static final String ORG = "00000000-0000-4000-8000-0000000de701";
    public static final String EMAIL = "demo_local@demo.inmobiliapro.es";   // cuenta demo: sin candado de pago
    public static final String NOMBRE = "Ana Demo";

    private final FakeSupabase bd;
    private final EjemplosService ejemplos;
    private volatile boolean listo;

    @EventListener(ApplicationReadyEvent.class)
    public void preparar() {
        try {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("id", ORG);
            u.put("email", EMAIL);
            u.put("name", NOMBRE);
            u.put("role", "admin");
            u.put("active", true);
            u.put("email_verified", true);
            u.put("plan", "agency");             // todas las herramientas
            u.put("plan_status", "active");
            u.put("ai_credits", 100);
            u.put("pais", "MX");
            u.put("moneda", "MXN");
            u.put("ciudad", "MX:Ciudad de México");
            u.put("created_at", OffsetDateTime.now().minusMonths(8).toString());
            bd.tabla("users").add(u);
            log.info("Demo local: {}", ejemplos.cargarAhora(ORG).get("mensaje"));
        } catch (Exception e) {
            log.error("Demo local: no se pudieron cargar los ejemplos: {}", e.getMessage(), e);
        } finally {
            listo = true;
        }
    }

    public boolean listo() { return listo; }

    /** Entra directamente en la inmobiliaria de ejemplo (sin contraseña: solo existe en la demo local). */
    @GetMapping("/entrar")
    public ResponseEntity<String> entrar(HttpServletRequest req, HttpSession session) {
        if (!listo) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("""
                    <!doctype html><html lang="es"><meta charset="utf-8"><meta http-equiv="refresh" content="1">
                    <title>Preparando la demo…</title>
                    <body style="font-family:system-ui,sans-serif;display:grid;place-items:center;height:90vh;color:#334155">
                    <p>Preparando la demo de InmobilIA Pro…</p></body></html>""");
        }
        req.changeSessionId();
        SessionUtils.loginFull(session, ORG, ORG, EMAIL, NOMBRE, "admin");
        session.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        SessionUtils.flash(session, "Demo: inmobiliaria de ejemplo en Ciudad de México. Lo que cambies se borra al cerrar la demo.");
        return ResponseEntity.status(302).location(URI.create("/dashboard")).build();
    }

    /** Fotos y documentos subidos durante la demo (Storage en memoria). */
    @GetMapping("/storage/v1/object/public/**")
    public ResponseEntity<byte[]> fichero(HttpServletRequest req) {
        String ruta = URLDecoder.decode(req.getRequestURI().substring("/storage/v1/object/public/".length()), StandardCharsets.UTF_8);
        FakeSupabase.Fichero f = bd.ficheros.get(ruta);
        if (f == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.tipo()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")   // un SVG subido no ejecuta nada
                .body(f.datos());
    }
}
