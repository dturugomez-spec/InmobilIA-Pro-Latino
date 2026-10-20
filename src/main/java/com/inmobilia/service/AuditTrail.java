package com.inmobilia.service;

import com.inmobilia.util.ClientIp;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Registro de actividad: quién hizo qué, cuándo y desde dónde.
 *
 * Saca IP, navegador y usuario de la petición AQUÍ (en el hilo de la petición) y
 * delega la escritura en AuditService, que es asíncrono: la petición no espera a
 * la base de datos y el HttpServletRequest nunca se usa desde otro hilo.
 *
 * user_id = la inmobiliaria (organization_id), para que su admin vea el registro
 * en /auditoria; actor_id = la persona que lo hizo.
 */
@Component
@RequiredArgsConstructor
public class AuditTrail {

    public static final String SEGURIDAD = "seguridad";
    public static final String DATOS = "datos";
    public static final String EXPORTACION = "exportacion";
    public static final String EQUIPO = "equipo";

    private final AuditService audit;

    /** Evento hecho por el usuario de la sesión actual. */
    public void registrar(HttpServletRequest req, HttpSession session, String accion,
                          String tabla, String registroId, String descripcion,
                          String nivel, String categoria) {
        String org = null, actor = null, nombre = null, rol = null;
        if (session != null && SessionUtils.isLoggedIn(session)) {
            org = SessionUtils.uid(session);
            actor = SessionUtils.realUid(session);
            nombre = SessionUtils.name(session);
            rol = SessionUtils.role(session);
        }
        registrarPara(req, org, actor, nombre, rol, accion, tabla, registroId, descripcion, nivel, categoria);
    }

    /** Evento de seguridad del usuario de la sesión actual. */
    public void seguridad(HttpServletRequest req, HttpSession session, String accion,
                          String descripcion, String nivel) {
        registrar(req, session, accion, "users", null, descripcion, nivel, SEGURIDAD);
    }

    /** Evento con inmobiliaria y persona explícitas (p. ej. login fallido, aún sin sesión). */
    public void registrarPara(HttpServletRequest req, String orgId, String actorId, String actorNombre,
                              String actorRol, String accion, String tabla, String registroId,
                              String descripcion, String nivel, String categoria) {
        String ip = null, ua = null, metodo = null, url = null;
        if (req != null) {
            ip = ClientIp.of(req);
            ua = recortar(req.getHeader("User-Agent"), 300);
            metodo = req.getMethod();
            url = recortar(req.getRequestURI(), 300); // sin query string: puede llevar tokens
        }
        audit.log(vacioANull(orgId), vacioANull(actorId), actorNombre, actorRol, accion, tabla, registroId,
                recortar(descripcion, 500), null, null, ip, ua, metodo, url, nivel, categoria);
    }

    private static String vacioANull(String s) {
        return (s == null || s.isBlank() || "null".equals(s)) ? null : s;
    }

    private static String recortar(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
