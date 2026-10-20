package com.inmobilia.util;

import jakarta.servlet.http.HttpSession;

/**
 * Helpers de sesion (equivalentes al st.session_state del Python).
 *
 * IMPORTANTE: el "uid" que se usa para hacer queries a Supabase apunta al
 * organization_id del admin, no al user real. Asi todos los empleados del
 * mismo equipo comparten la misma vista de datos. El user real (para auditoria)
 * se guarda en KEY_REAL_UID.
 */
public final class SessionUtils {
    private SessionUtils() {}

    public static final String KEY_UID      = "user_id";        // organization_id (admin del equipo)
    public static final String KEY_REAL_UID = "real_user_id";   // mi propio user real
    public static final String KEY_EMAIL    = "user_email";
    public static final String KEY_NAME     = "user_name";
    public static final String KEY_ROLE     = "user_role";
    public static final String KEY_API_KEY  = "api_key";
    public static final String KEY_LOGGED   = "logged_in";
    public static final String KEY_FLASH    = "msg_ok";
    public static final String KEY_SMTP     = "smtp_cfg";
    public static final String KEY_PAGE     = "current_page";

    // Seguridad
    /** Login a medias: contraseña correcta, falta el código de la app (2FA). */
    public static final String KEY_PEND_2FA_UID      = "pend2fa_uid";
    public static final String KEY_PEND_2FA_AT       = "pend2fa_at";
    public static final String KEY_PEND_2FA_INTENTOS = "pend2fa_intentos";
    public static final String KEY_PEND_PW_CORTA     = "pend2fa_pw_corta";
    /** La inmobiliaria exige 2FA y este usuario aún no lo tiene: solo puede ir a /seguridad. */
    public static final String KEY_FORZAR_2FA        = "forzar_2fa";
    /** Última vez (ms) que se comprobó en BD que la cuenta sigue activa. */
    public static final String KEY_REVISADO          = "sesion_revisada_at";
    /** Ajustes de permisos que el administrador ha dado a esta persona: sección → NONE|READ|WRITE. */
    public static final String KEY_AJUSTES_PERMISOS  = "ajustes_permisos";

    @SuppressWarnings("unchecked")
    public static java.util.Map<String, String> ajustesPermisos(HttpSession session) {
        Object o = session.getAttribute(KEY_AJUSTES_PERMISOS);
        return o instanceof java.util.Map<?, ?> m ? (java.util.Map<String, String>) m : java.util.Map.of();
    }

    /** Devuelve el organization_id (= admin del equipo). Usar para queries Supabase. */
    public static String uid(HttpSession session)      { return (String) session.getAttribute(KEY_UID); }
    /** Devuelve el user_id real (de quien inició sesión). Usar para auditoria. */
    public static String realUid(HttpSession session)  { return (String) session.getAttribute(KEY_REAL_UID); }
    public static String email(HttpSession session)    { return (String) session.getAttribute(KEY_EMAIL); }
    public static String name(HttpSession session)     { return (String) session.getAttribute(KEY_NAME); }
    public static String role(HttpSession session)     {
        Object r = session.getAttribute(KEY_ROLE);
        return r == null ? "admin" : String.valueOf(r);
    }
    public static String apiKey(HttpSession session)   { return (String) session.getAttribute(KEY_API_KEY); }

    public static boolean isLoggedIn(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute(KEY_LOGGED)) && uid(session) != null;
    }

    /** Login antiguo (3 args) — se mantiene por compatibilidad. */
    public static void login(HttpSession s, String uid, String email, String name) {
        loginFull(s, uid, uid, email, name, "admin");
    }

    /** Login completo: distingue user real vs organization. */
    public static void loginFull(HttpSession s, String realUid, String orgUid, String email, String name, String role) {
        s.setAttribute(KEY_LOGGED, true);
        s.setAttribute(KEY_UID, orgUid);          // queries van por aquí
        s.setAttribute(KEY_REAL_UID, realUid);    // auditoría
        s.setAttribute(KEY_EMAIL, email);
        s.setAttribute(KEY_NAME, name);
        s.setAttribute(KEY_ROLE, role != null ? role : "admin");
    }

    public static void logout(HttpSession s) {
        s.invalidate();
    }

    public static void flash(HttpSession s, String msg) {
        s.setAttribute(KEY_FLASH, msg);
    }

    public static String consumeFlash(HttpSession s) {
        String m = (String) s.getAttribute(KEY_FLASH);
        if (m != null) s.removeAttribute(KEY_FLASH);
        return m;
    }
}
