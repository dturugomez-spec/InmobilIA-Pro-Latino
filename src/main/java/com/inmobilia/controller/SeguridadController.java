package com.inmobilia.controller;

import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.AuthService;
import com.inmobilia.service.RateLimiter;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.TwoFactorService;
import com.inmobilia.util.ClientIp;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 🔐 Seguridad de la cuenta: verificación en dos pasos (2FA), códigos de recuperación,
 * 2FA obligatorio para todo el equipo (solo el admin) y cambio de contraseña.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class SeguridadController {

    private static final String KEY_SETUP_SECRETO = "setup2fa_secreto";
    private static final String KEY_CODIGOS_NUEVOS = "codigos_recuperacion_nuevos";
    private static final Duration VENTANA = Duration.ofMinutes(15);

    private final AuthService authService;
    private final TwoFactorService twoFactor;
    private final SupabaseService supabase;
    private final RateLimiter rateLimiter;
    private final AuditTrail audit;

    @GetMapping("/seguridad")
    public String pagina(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String yo = SessionUtils.realUid(session);
        String org = SessionUtils.uid(session);
        Optional<Map<String, Object>> me = authService.findById(yo);
        if (me.isEmpty()) return "redirect:/login";
        Map<String, Object> user = me.get();

        boolean activo = TwoFactorService.activo(user);
        boolean esAdminOrg = esAdminDeLaOrg(session);
        boolean exigido = twoFactor.exigidoEnOrg(org);

        model.addAttribute("page", "Seguridad");
        model.addAttribute("activo2fa", activo);
        model.addAttribute("exigido", exigido);
        model.addAttribute("esAdminOrg", esAdminOrg);
        model.addAttribute("forzado", Boolean.TRUE.equals(session.getAttribute(SessionUtils.KEY_FORZAR_2FA)));
        model.addAttribute("codigosRestantes", TwoFactorService.codigosRestantes(user));
        model.addAttribute("minPassword", AuthService.MIN_PASSWORD);

        // Alta en curso: QR + clave para teclear
        Object secreto = session.getAttribute(KEY_SETUP_SECRETO);
        if (!activo && secreto != null) {
            String url = twoFactor.urlAlta(String.valueOf(user.getOrDefault("email", "")), String.valueOf(secreto));
            model.addAttribute("qrSvg", twoFactor.qrSvg(url));
            model.addAttribute("secretoLegible", TwoFactorService.formatearSecreto(String.valueOf(secreto)));
        }

        // Códigos de recuperación recién generados: se enseñan UNA sola vez
        Object nuevos = session.getAttribute(KEY_CODIGOS_NUEVOS);
        if (nuevos != null) {
            session.removeAttribute(KEY_CODIGOS_NUEVOS);
            model.addAttribute("codigosNuevos", nuevos);
        }

        // Equipo: quién tiene 2FA (solo lo ve el admin)
        if (esAdminOrg) {
            List<Map<String, Object>> equipo = new ArrayList<>();
            equipo.add(fichaMiembro(user));
            for (Map<String, Object> m : authService.teamMembers(org)) {
                if ("tenant_portal".equals(String.valueOf(m.get("role")))) continue;
                if (String.valueOf(m.get("id")).equals(yo)) continue;
                if (inactivo(m)) continue;
                equipo.add(fichaMiembro(m));
            }
            model.addAttribute("equipo", equipo);
            model.addAttribute("equipoSin2fa", equipo.stream().filter(f -> !Boolean.TRUE.equals(f.get("con2fa"))).count());
        }

        // Mis últimos accesos y eventos de seguridad (hora del país de la inmobiliaria)
        List<Map<String, Object>> eventos = new ArrayList<>();
        for (Map<String, Object> e : supabase.selectFilter("audit_log",
                "actor_id=eq." + yo + "&categoria=eq.seguridad&order=created_at.desc&limit=12")) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("fecha", fechaLocal(e.get("created_at")));
            ev.put("descripcion", e.get("descripcion"));
            ev.put("ip", e.get("ip"));
            ev.put("nivel", e.get("nivel"));
            eventos.add(ev);
        }
        model.addAttribute("eventos", eventos);
        return "seguridad";
    }

    private static final java.time.format.DateTimeFormatter FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static String fechaLocal(Object v) {
        if (v == null) return "";
        try {
            return java.time.OffsetDateTime.parse(String.valueOf(v)).atZoneSameInstant(com.inmobilia.util.PaisActual.get().zonaId()).format(FMT);
        } catch (Exception e) {
            String s = String.valueOf(v);
            return s.length() >= 16 ? s.substring(0, 16).replace('T', ' ') : s;
        }
    }

    @PostMapping("/seguridad/2fa/iniciar")
    public String iniciar(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        session.setAttribute(KEY_SETUP_SECRETO, twoFactor.nuevoSecreto());
        return "redirect:/seguridad#activar";
    }

    @PostMapping("/seguridad/2fa/cancelar")
    public String cancelar(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        session.removeAttribute(KEY_SETUP_SECRETO);
        return "redirect:/seguridad";
    }

    @PostMapping("/seguridad/2fa/activar")
    public String activar(@RequestParam String codigo, HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String yo = SessionUtils.realUid(session);
        Object secreto = session.getAttribute(KEY_SETUP_SECRETO);
        if (secreto == null) return "redirect:/seguridad";
        if (limitado(session, req)) return "redirect:/seguridad#activar";

        if (!twoFactor.verificarCodigo(yo, String.valueOf(secreto), codigo)) {
            fallo(session, req);
            SessionUtils.flash(session, "❌ El código no coincide. Comprueba que la hora del móvil es automática y escribe el código que se ve ahora.");
            return "redirect:/seguridad#activar";
        }

        List<String> codigos = twoFactor.generarCodigosRecuperacion();
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("totp_secret", twoFactor.secretoParaGuardar(String.valueOf(secreto)));
        upd.put("totp_enabled", true);
        upd.put("totp_recovery_codes", TwoFactorService.hashesParaGuardar(codigos));
        if (!supabase.update("users", yo, upd, yo)) {
            log.error("No se pudo activar 2FA para {}: {}", yo, supabase.lastError());
            SessionUtils.flash(session, "❌ No se pudo guardar. Inténtalo de nuevo en unos minutos.");
            return "redirect:/seguridad#activar";
        }
        session.removeAttribute(KEY_SETUP_SECRETO);
        session.removeAttribute(SessionUtils.KEY_FORZAR_2FA);
        session.setAttribute(KEY_CODIGOS_NUEVOS, codigos);
        audit.seguridad(req, session, "2fa_activado", "Activó la verificación en dos pasos", "security");
        SessionUtils.flash(session, "✅ Verificación en dos pasos activada. Guarda ahora tus códigos de recuperación.");
        return "redirect:/seguridad#codigos";
    }

    @PostMapping("/seguridad/2fa/desactivar")
    public String desactivar(@RequestParam String password, @RequestParam String codigo,
                             HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String yo = SessionUtils.realUid(session);
        if (twoFactor.exigidoEnOrg(SessionUtils.uid(session))) {
            SessionUtils.flash(session, "Tu inmobiliaria exige la verificación en dos pasos: no se puede desactivar.");
            return "redirect:/seguridad";
        }
        if (limitado(session, req)) return "redirect:/seguridad";
        Optional<Map<String, Object>> me = authService.findById(yo);
        if (me.isEmpty()) return "redirect:/login";
        Map<String, Object> user = me.get();

        boolean pwOk = authService.checkPw(password, String.valueOf(user.get("password")));
        boolean codigoOk = twoFactor.verificarCodigo(yo, twoFactor.secretoDe(user), codigo)
                || TwoFactorService.consumirCodigoRecuperacion(user, codigo) != null;
        if (!pwOk || !codigoOk) {
            fallo(session, req);
            audit.seguridad(req, session, "2fa_desactivar_fallido", "Intento de desactivar 2FA con datos incorrectos", "warning");
            SessionUtils.flash(session, "❌ Contraseña o código incorrectos.");
            return "redirect:/seguridad";
        }
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("totp_enabled", false);
        upd.put("totp_secret", "");
        upd.put("totp_recovery_codes", "");
        supabase.update("users", yo, upd, yo);
        audit.seguridad(req, session, "2fa_desactivado", "Desactivó la verificación en dos pasos", "critical");
        SessionUtils.flash(session, "Verificación en dos pasos desactivada. Tu cuenta queda protegida solo con la contraseña.");
        return "redirect:/seguridad";
    }

    @PostMapping("/seguridad/2fa/codigos")
    public String regenerarCodigos(@RequestParam String codigo, HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String yo = SessionUtils.realUid(session);
        if (limitado(session, req)) return "redirect:/seguridad";
        Optional<Map<String, Object>> me = authService.findById(yo);
        if (me.isEmpty() || !TwoFactorService.activo(me.get())) return "redirect:/seguridad";
        if (!twoFactor.verificarCodigo(yo, twoFactor.secretoDe(me.get()), codigo)) {
            fallo(session, req);
            SessionUtils.flash(session, "❌ Código incorrecto.");
            return "redirect:/seguridad";
        }
        List<String> codigos = twoFactor.generarCodigosRecuperacion();
        supabase.update("users", yo, Map.of("totp_recovery_codes", TwoFactorService.hashesParaGuardar(codigos)), yo);
        session.setAttribute(KEY_CODIGOS_NUEVOS, codigos);
        audit.seguridad(req, session, "2fa_codigos_regenerados", "Generó códigos de recuperación nuevos (los anteriores dejan de valer)", "security");
        SessionUtils.flash(session, "✅ Códigos nuevos generados. Los anteriores ya no sirven.");
        return "redirect:/seguridad#codigos";
    }

    /** El admin obliga (o deja de obligar) a todo su equipo a usar 2FA. */
    @PostMapping("/seguridad/exigir-2fa")
    public String exigir(@RequestParam(defaultValue = "false") boolean exigir,
                         HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!esAdminDeLaOrg(session)) return "redirect:/seguridad";
        String org = SessionUtils.uid(session);
        if (exigir) {
            Optional<Map<String, Object>> me = authService.findById(SessionUtils.realUid(session));
            if (me.isEmpty() || !TwoFactorService.activo(me.get())) {
                SessionUtils.flash(session, "Activa primero tu propia verificación en dos pasos.");
                return "redirect:/seguridad";
            }
        }
        if (!supabase.update("users", org, Map.of("require_2fa", exigir), org)) {
            SessionUtils.flash(session, "❌ No se pudo guardar. Inténtalo de nuevo en unos minutos.");
            return "redirect:/seguridad";
        }
        audit.seguridad(req, session, exigir ? "2fa_obligatorio_on" : "2fa_obligatorio_off",
                exigir ? "Hizo obligatoria la verificación en dos pasos para todo el equipo"
                       : "Quitó la obligación de verificación en dos pasos", exigir ? "security" : "critical");
        SessionUtils.flash(session, exigir
                ? "✅ Hecho. Quien no la tenga deberá activarla la próxima vez que use la app."
                : "La verificación en dos pasos ya no es obligatoria para el equipo.");
        return "redirect:/seguridad";
    }

    @PostMapping("/seguridad/password")
    public String cambiarPassword(@RequestParam String actual, @RequestParam String nueva,
                                  @RequestParam String repetir, HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String yo = SessionUtils.realUid(session);
        if (limitado(session, req)) return "redirect:/seguridad#password";
        Optional<Map<String, Object>> me = authService.findById(yo);
        if (me.isEmpty()) return "redirect:/login";

        if (!authService.checkPw(actual, String.valueOf(me.get().get("password")))) {
            fallo(session, req);
            audit.seguridad(req, session, "password_cambio_fallido", "Contraseña actual incorrecta al intentar cambiarla", "warning");
            SessionUtils.flash(session, "❌ La contraseña actual no es correcta.");
            return "redirect:/seguridad#password";
        }
        String problema = AuthService.problemaPassword(nueva);
        if (problema == null && !nueva.equals(repetir)) problema = "Las dos contraseñas nuevas no coinciden.";
        if (problema == null && nueva.equals(actual)) problema = "La nueva contraseña debe ser distinta de la actual.";
        if (problema != null) {
            SessionUtils.flash(session, "❌ " + problema);
            return "redirect:/seguridad#password";
        }
        if (!supabase.update("users", yo, Map.of("password", authService.hashPw(nueva)), yo)) {
            SessionUtils.flash(session, "❌ No se pudo guardar. Inténtalo de nuevo en unos minutos.");
            return "redirect:/seguridad#password";
        }
        audit.seguridad(req, session, "password_cambiada", "Cambió su contraseña", "security");
        SessionUtils.flash(session, "✅ Contraseña cambiada.");
        return "redirect:/seguridad";
    }

    /** IP con la que nos ve el servidor (diagnóstico; solo devuelve la del propio visitante). */
    @GetMapping(value = "/mi-ip", produces = "text/plain")
    @ResponseBody
    public String miIp(HttpServletRequest req) {
        return ClientIp.of(req);
    }

    // ─── helpers ───

    /** El admin "dueño" de la inmobiliaria (su id es el de la organización). */
    private static boolean esAdminDeLaOrg(HttpSession session) {
        return "admin".equals(SessionUtils.role(session))
                && SessionUtils.realUid(session) != null
                && SessionUtils.realUid(session).equals(SessionUtils.uid(session));
    }

    /** 5 errores (código o contraseña) cada 15 min por persona en esta pantalla. */
    private boolean limitado(HttpSession session, HttpServletRequest req) {
        if (rateLimiter.bloqueado("seguridad:" + SessionUtils.realUid(session), 5, VENTANA)) {
            audit.seguridad(req, session, "seguridad_bloqueado", "Demasiados intentos fallidos en la pantalla de seguridad", "critical");
            SessionUtils.flash(session, "Demasiados intentos fallidos. Espera 15 minutos.");
            return true;
        }
        return false;
    }

    private void fallo(HttpSession session, HttpServletRequest req) {
        rateLimiter.registrarFallo("seguridad:" + SessionUtils.realUid(session), VENTANA);
    }

    private static Map<String, Object> fichaMiembro(Map<String, Object> u) {
        Map<String, Object> f = new LinkedHashMap<>();
        Object n = u.get("name");
        f.put("nombre", (n == null || String.valueOf(n).isBlank() || "null".equals(String.valueOf(n))) ? u.get("email") : n);
        f.put("email", u.get("email"));
        f.put("rol", u.get("role") == null ? "admin" : u.get("role"));
        f.put("con2fa", TwoFactorService.activo(u));
        return f;
    }

    private static boolean inactivo(Map<String, Object> u) {
        Object a = u.get("active");
        if (a != null && (Boolean.FALSE.equals(a) || "false".equalsIgnoreCase(String.valueOf(a)))) return true;
        Object d = u.get("deleted_at");
        return d != null && !String.valueOf(d).isBlank() && !"null".equals(String.valueOf(d));
    }
}
