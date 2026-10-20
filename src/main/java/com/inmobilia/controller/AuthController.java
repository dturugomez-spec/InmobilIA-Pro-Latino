package com.inmobilia.controller;

import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.AuthService;
import com.inmobilia.service.DemoDataService;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.RateLimiter;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.TwoFactorService;
import com.inmobilia.util.ClientIp;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    /** Ventana de los contadores de fallos de login. */
    private static final Duration VENTANA = Duration.ofMinutes(15);
    /** Fallos permitidos por IP (una oficina entera comparte IP) y por cuenta. */
    private static final int MAX_FALLOS_IP = 20;
    private static final int MAX_FALLOS_CUENTA = 8;
    /** Tiempo para meter el código de la app tras acertar la contraseña. */
    private static final long PENDIENTE_2FA_MS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_INTENTOS_2FA = 5;

    private final AuthService authService;
    private final EmailService emailService;
    private final DemoDataService demoDataService;
    private final NotificationService notifications;
    private final RateLimiter rateLimiter;
    private final PlanService planService;
    private final TwoFactorService twoFactor;
    private final SupabaseService supabase;
    private final AuditTrail audit;

    @Value("${app.base-url:https://inmobiliapro.es}")
    private String appBaseUrl;

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String reset,
                        @RequestParam(required = false) String verified,
                        @RequestParam(required = false) String sesion,
                        Model model) {
        model.addAttribute("page", "Iniciar Sesion");
        if ("ok".equals(reset)) model.addAttribute("regOk", "Contraseña guardada. Ya puedes iniciar sesión.");
        if ("ok".equals(verified)) model.addAttribute("regOk", "Email verificado correctamente. Ya puedes iniciar sesión.");
        if ("cerrada".equals(sesion)) model.addAttribute("error", "Tu acceso se ha desactivado o tu sesión ha caducado. Vuelve a iniciar sesión.");
        if ("2fa".equals(sesion)) model.addAttribute("error", "Ha caducado el paso del código. Vuelve a iniciar sesión.");
        return "auth";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String email,
                          @RequestParam String password,
                          HttpServletRequest req,
                          HttpSession session,
                          Model model) {
        model.addAttribute("page", "Iniciar Sesion");
        email = email == null ? "" : email.trim().toLowerCase();
        String ip = ClientIp.of(req);
        String kIp = "login-ip:" + ip;
        String kCuenta = "login-cuenta:" + email;

        // Fuerza bruta: se cuentan solo los FALLOS, por IP y por cuenta
        if (rateLimiter.bloqueado(kIp, MAX_FALLOS_IP, VENTANA) || rateLimiter.bloqueado(kCuenta, MAX_FALLOS_CUENTA, VENTANA)) {
            registrarIntentoFallido(req, email, "login_bloqueado", "Inicio de sesión bloqueado por demasiados intentos fallidos", "critical");
            model.addAttribute("error", "Demasiados intentos fallidos. Por seguridad, espera 15 minutos o recupera tu contraseña.");
            return "auth";
        }

        Optional<Map<String, Object>> u = authService.login(email, password);
        if (u.isEmpty()) {
            rateLimiter.registrarFallo(kIp, VENTANA);
            rateLimiter.registrarFallo(kCuenta, VENTANA);
            registrarIntentoFallido(req, email, "login_fallido", "Contraseña incorrecta", "warning");
            model.addAttribute("error", "Credenciales incorrectas");
            return "auth";
        }
        rateLimiter.reset(kCuenta);
        Map<String, Object> user = u.get();
        boolean pwCorta = password != null && password.length() < AuthService.MIN_PASSWORD;

        // Verificación en dos pasos: falta el código de la app
        if (TwoFactorService.activo(user)) {
            req.changeSessionId();
            session.setAttribute(SessionUtils.KEY_PEND_2FA_UID, String.valueOf(user.get("id")));
            session.setAttribute(SessionUtils.KEY_PEND_2FA_AT, System.currentTimeMillis());
            session.setAttribute(SessionUtils.KEY_PEND_2FA_INTENTOS, 0);
            session.setAttribute(SessionUtils.KEY_PEND_PW_CORTA, pwCorta);
            return "redirect:/login/2fa";
        }
        return completarLogin(user, req, session, pwCorta, false);
    }

    @GetMapping("/login/2fa")
    public String form2fa(HttpSession session, Model model) {
        if (pendiente2fa(session) == null) return "redirect:/login?sesion=2fa";
        model.addAttribute("page", "Verificación en dos pasos");
        return "auth_2fa";
    }

    @PostMapping("/login/2fa")
    public String verificar2fa(@RequestParam(required = false) String codigo,
                               HttpServletRequest req,
                               HttpSession session,
                               Model model) {
        String uid = pendiente2fa(session);
        if (uid == null) return "redirect:/login?sesion=2fa";
        model.addAttribute("page", "Verificación en dos pasos");

        String kIp = "login-ip:" + ClientIp.of(req);
        Object previos = session.getAttribute(SessionUtils.KEY_PEND_2FA_INTENTOS);
        int intentos = (previos instanceof Integer i ? i : 0) + 1;
        session.setAttribute(SessionUtils.KEY_PEND_2FA_INTENTOS, intentos);

        Optional<Map<String, Object>> fresh = authService.findById(uid);
        if (fresh.isEmpty() || rateLimiter.bloqueado(kIp, MAX_FALLOS_IP, VENTANA)) {
            limpiarPendiente(session);
            return "redirect:/login?sesion=2fa";
        }
        Map<String, Object> user = fresh.get();
        String c = codigo == null ? "" : codigo.trim();

        boolean ok = false;
        boolean conRecuperacion = false;
        if (c.replaceAll("\\s", "").matches("\\d{6}")) {
            String secreto = twoFactor.secretoDe(user);
            ok = twoFactor.verificarCodigo(uid, secreto, c);
            // Secretos antiguos guardados en claro: se cifran al primer uso correcto
            if (ok && twoFactor.necesitaCifrar(user)) {
                supabase.update("users", uid, Map.of("totp_secret", twoFactor.secretoParaGuardar(secreto)), uid);
            }
        } else if (!c.isEmpty()) {
            String quedan = TwoFactorService.consumirCodigoRecuperacion(user, c);
            if (quedan != null && supabase.update("users", uid, Map.of("totp_recovery_codes", quedan), uid)) {
                ok = true;
                conRecuperacion = true;
                user.put("totp_recovery_codes", quedan);
            }
        }

        if (!ok) {
            rateLimiter.registrarFallo(kIp, VENTANA);
            audit.registrarPara(req, AuthService.orgDe(user), uid, nombreDe(user), rolDe(user),
                    "2fa_fallido", "users", uid, "Código de verificación incorrecto", "warning", AuditTrail.SEGURIDAD);
            if (intentos >= MAX_INTENTOS_2FA) {
                limpiarPendiente(session);
                model.addAttribute("error", "Demasiados códigos incorrectos. Vuelve a iniciar sesión.");
                return "auth";
            }
            model.addAttribute("error", "Código incorrecto. Te quedan " + (MAX_INTENTOS_2FA - intentos) + " intentos.");
            return "auth_2fa";
        }

        boolean pwCorta = Boolean.TRUE.equals(session.getAttribute(SessionUtils.KEY_PEND_PW_CORTA));
        limpiarPendiente(session);
        String destino = completarLogin(user, req, session, pwCorta, true);
        if (conRecuperacion) {
            int restantes = TwoFactorService.codigosRestantes(user);
            audit.seguridad(req, session, "2fa_codigo_recuperacion",
                    "Entró con un código de recuperación (quedan " + restantes + ")", "warning");
            SessionUtils.flash(session, "Has entrado con un código de recuperación. Te quedan " + restantes
                    + ". Si perdiste el móvil, genera códigos nuevos en Seguridad.");
        }
        return destino;
    }

    /** Contraseña (y código si aplica) correctos: abre la sesión. */
    private String completarLogin(Map<String, Object> user, HttpServletRequest req, HttpSession session,
                                  boolean pwCorta, boolean con2fa) {
        // Sesión nueva al entrar: un identificador de sesión fijado de antemano no sirve de nada
        req.changeSessionId();

        String myUid = String.valueOf(user.get("id"));
        String orgUid = AuthService.orgDe(user);
        String role = rolDe(user);
        String email = String.valueOf(user.getOrDefault("email", ""));
        SessionUtils.loginFull(session, myUid, orgUid, email, nombreDe(user), role);
        session.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());

        audit.seguridad(req, session, "login",
                con2fa ? "Inicio de sesión con verificación en dos pasos" : "Inicio de sesión", "info");

        // Portal del inquilino: solo su espacio
        if ("tenant_portal".equals(role)) return "redirect:/portal";

        // La inmobiliaria exige 2FA y esta persona aún no lo tiene → primero lo activa
        if (!TwoFactorService.activo(user) && twoFactor.exigidoEnOrg(orgUid)) {
            session.setAttribute(SessionUtils.KEY_FORZAR_2FA, true);
            SessionUtils.flash(session, "Tu inmobiliaria exige la verificación en dos pasos. Actívala para continuar (2 minutos).");
            return "redirect:/seguridad";
        }

        // Cliente sin suscripción → primero la pantalla de pago (tarjeta + 14 días gratis)
        if (planService.requierePago(orgUid)) {
            SessionUtils.flash(session, "👋 Activa tu suscripción para empezar: 14 días gratis, no se te cobra nada hasta que terminen. Cancela cuando quieras.");
            return "redirect:/upgrade";
        }
        if (pwCorta) {
            SessionUtils.flash(session, "🔐 Tu contraseña es corta. Cámbiala por una de al menos "
                    + AuthService.MIN_PASSWORD + " caracteres en Seguridad.");
        }
        return "redirect:/dashboard";
    }

    /** uid pendiente de 2FA si sigue vigente; si caducó, limpia y devuelve null. */
    private static String pendiente2fa(HttpSession session) {
        Object uid = session.getAttribute(SessionUtils.KEY_PEND_2FA_UID);
        Object at = session.getAttribute(SessionUtils.KEY_PEND_2FA_AT);
        if (uid == null || !(at instanceof Long t)) return null;
        if (System.currentTimeMillis() - t > PENDIENTE_2FA_MS) {
            limpiarPendiente(session);
            return null;
        }
        return String.valueOf(uid);
    }

    private static void limpiarPendiente(HttpSession session) {
        session.removeAttribute(SessionUtils.KEY_PEND_2FA_UID);
        session.removeAttribute(SessionUtils.KEY_PEND_2FA_AT);
        session.removeAttribute(SessionUtils.KEY_PEND_2FA_INTENTOS);
        session.removeAttribute(SessionUtils.KEY_PEND_PW_CORTA);
    }

    /** Apunta el intento fallido en el registro de la inmobiliaria dueña de esa cuenta (si existe). */
    private void registrarIntentoFallido(HttpServletRequest req, String email, String accion, String descripcion, String nivel) {
        try {
            Optional<Map<String, Object>> cuenta = authService.findByEmail(email);
            if (cuenta.isEmpty()) {
                audit.registrarPara(req, null, null, email, null, accion, "users", null,
                        descripcion + " (usuario inexistente)", nivel, AuditTrail.SEGURIDAD);
                return;
            }
            Map<String, Object> c = cuenta.get();
            audit.registrarPara(req, AuthService.orgDe(c), String.valueOf(c.get("id")), nombreDe(c), rolDe(c),
                    accion, "users", String.valueOf(c.get("id")), descripcion, nivel, AuditTrail.SEGURIDAD);
        } catch (Exception e) {
            log.debug("No se pudo registrar el intento fallido: {}", e.getMessage());
        }
    }

    private static String rolDe(Map<String, Object> user) {
        Object r = user.get("role");
        return (r == null || String.valueOf(r).isBlank() || "null".equals(String.valueOf(r))) ? "admin" : String.valueOf(r);
    }

    private static String nombreDe(Map<String, Object> user) {
        Object n = user.get("name");
        return (n != null && !String.valueOf(n).isBlank() && !"null".equals(String.valueOf(n)))
                ? String.valueOf(n) : String.valueOf(user.getOrDefault("email", ""));
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam String password,
                           HttpSession session,
                           Model model) {
        // El registro libre está DESHABILITADO. Todo debe pasar por aprobación.
        // Redirigir a la landing de solicitud de cuenta.
        log.info(">>> REGISTER intento (BLOQUEADO, redirige a /#contacto)");
        return "redirect:/#contacto";
    }

    /** Registro INTERNO usado por AccountRequestsController.aprobar. NO accesible públicamente. */
    public String registerInterno(String email, String password, HttpSession session, Model model) {
        // Normalizar email aquí también
        email = email == null ? "" : email.trim().toLowerCase();
        if (!authService.validUser(email)) {
            model.addAttribute("regError", "Solo letras, números y _ (mín 3 caracteres)");
            model.addAttribute("page", "Crear Cuenta");
            return "auth";
        }
        String problema = AuthService.problemaPassword(password);
        if (problema != null) {
            model.addAttribute("regError", problema);
            model.addAttribute("page", "Crear Cuenta");
            return "auth";
        }
        Map<String, String> reg = authService.register(email, password);
        if (reg == null) {
            model.addAttribute("regError", "Usuario ya registrado o error guardando");
            model.addAttribute("page", "Crear Cuenta");
            return "auth";
        }

        // Sembrar datos demo en background
        try {
            demoDataService.seedDemoData(reg.get("id"));
        } catch (Exception e) {
            log.warn("Demo data seed failed: {}", e.getMessage());
        }

        // Notificación de bienvenida
        try {
            notifications.create(reg.get("id"), "info", "🎉",
                    "¡Bienvenido a InmobilIA Pro!",
                    "Tienes 14 días de Plan Agency gratis. Configura tu cuenta para empezar.",
                    "/welcome");
        } catch (Exception ignored) {}

        // Enviar email de verificación (si SMTP está configurado)
        try {
            String verifyLink = appBaseUrl + "/verify?token=" + reg.get("token");
            String body = "Hola " + email + ",\n\n" +
                    "Bienvenido a InmobilIA Pro 🏢\n\n" +
                    "Hemos creado tu cuenta y añadido datos de ejemplo (3 propiedades, 2 inquilinos, " +
                    "una visita programada y una tarea) para que veas cómo funciona la plataforma.\n\n" +
                    "Para verificar tu email y desbloquear todas las funciones, haz click aquí:\n" +
                    verifyLink + "\n\n" +
                    "Este enlace caduca en 7 días. Si no fuiste tú, ignora este email.\n\n" +
                    "— Equipo InmobilIA Pro";
            emailService.send(null, email, "Bienvenido a InmobilIA Pro 🏢", body);
        } catch (Exception e) {
            log.warn("Email de bienvenida no enviado: {}", e.getMessage());
        }

        // Iniciar sesión automáticamente y enviar al wizard de bienvenida
        SessionUtils.loginFull(session, reg.get("id"), reg.get("id"), email, email, "admin");
        return "redirect:/welcome";
    }

    @GetMapping("/verify")
    public String verifyEmail(@RequestParam(required = false) String token, Model model) {
        if (token == null || token.isBlank()) {
            return "redirect:/login";
        }
        boolean ok = authService.verifyEmail(token);
        if (ok) return "redirect:/login?verified=ok";
        model.addAttribute("page", "Verificar email");
        model.addAttribute("error", "El enlace de verificación es inválido o ha caducado. Inicia sesión y solicita uno nuevo.");
        return "auth_verify_failed";
    }

    @GetMapping("/logout")
    public String logout(HttpServletRequest req, HttpSession session) {
        if (SessionUtils.isLoggedIn(session)) audit.seguridad(req, session, "logout", "Cierre de sesión", "info");
        SessionUtils.logout(session);
        return "redirect:/login";
    }
}
