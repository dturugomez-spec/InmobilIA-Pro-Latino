package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Autenticacion + gestion de usuarios + invitacion de empleados al equipo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    // Acepta usernames (a-z, 0-9, _) o emails completos (foo@bar.com)
    private static final Pattern VALID_USER = Pattern.compile(
            "^([a-zA-Z0-9_]{3,}|[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})$");
    private final BCryptPasswordEncoder encoder;
    private final SupabaseService supabase;

    @Value("${ai.credits.default:50}")
    private int defaultCredits;

    public static final int MIN_PASSWORD = 10;

    private static final java.util.Set<String> PASSWORDS_COMUNES = java.util.Set.of(
            "1234567890", "12345678910", "0123456789", "1111111111", "123456789a", "abc1234567",
            "qwertyuiop", "qwerty1234", "asdfghjkl1", "abcdefghij", "password123", "password1234",
            "passwordpassword", "contraseña1", "contrasena1", "contraseña123", "contrasena123",
            "inmobiliaria", "inmobiliaria1", "inmobilia1", "inmobiliapro", "administrador", "admin12345");

    /**
     * Política de contraseñas (NIST 800-63B): longitud mínima, nada de contraseñas
     * triviales o muy comunes. Devuelve el motivo si no vale, o null si es válida.
     */
    public static String problemaPassword(String pw) {
        if (pw == null || pw.length() < MIN_PASSWORD)
            return "La contraseña debe tener al menos " + MIN_PASSWORD + " caracteres.";
        if (pw.getBytes(StandardCharsets.UTF_8).length > 72)
            return "La contraseña es demasiado larga (máximo 72 caracteres).";
        if (pw.chars().distinct().count() < 4)
            return "La contraseña es demasiado simple: mezcla letras, números o símbolos distintos.";
        if (PASSWORDS_COMUNES.contains(pw.toLowerCase()))
            return "Esa contraseña es de las más usadas y se adivina enseguida. Elige otra.";
        return null;
    }

    /** Contraseña aleatoria que nadie ve (cuentas creadas por aprobación: el cliente crea la suya por enlace). */
    public static String passwordAleatoria() {
        return randomToken().substring(0, 40);
    }

    /**
     * Crea un enlace de un solo uso para fijar contraseña (recuperación o bienvenida).
     * Devuelve el token, o null si no se pudo guardar.
     */
    public String crearTokenPassword(String userId, String email, java.time.Duration validez) {
        String token = randomToken();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", userId);
        data.put("email", email);
        data.put("token", token);
        data.put("expires_at", OffsetDateTime.now().plus(validez).toString());
        data.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> res = supabase.insert("password_resets", data);
        return (res == null || res.isEmpty()) ? null : token;
    }

    public Optional<Map<String, Object>> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        List<Map<String, Object>> r = supabase.findBy("users", Map.of("email", email.trim().toLowerCase()));
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Inmobiliaria a la que pertenece un usuario (su organization_id, o él mismo si es el admin). */
    public static String orgDe(Map<String, Object> user) {
        Object org = user.get("organization_id");
        return (org == null || String.valueOf(org).isBlank() || "null".equals(String.valueOf(org)))
                ? String.valueOf(user.get("id")) : String.valueOf(org);
    }

    public boolean validUser(String user) {
        return user != null && VALID_USER.matcher(user).matches();
    }

    public String hashPw(String pw) { return encoder.encode(pw); }

    public boolean checkPw(String pw, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) return false;
        if (storedHash.startsWith("$2")) {
            try { return encoder.matches(pw, storedHash); } catch (Exception e) { return false; }
        }
        return sha256(pw).equalsIgnoreCase(storedHash);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Login. Devuelve el user (o vacio). */
    public Optional<Map<String, Object>> login(String email, String password) {
        if (email == null) return Optional.empty();
        // Normalizar email: trim + lowercase (evita problemas de mayúsculas/espacios)
        email = email.trim().toLowerCase();

        List<Map<String, Object>> users = supabase.findBy("users", Map.of("email", email));
        if (users.isEmpty()) return Optional.empty();
        Map<String, Object> u = users.get(0);

        // Usuario desactivado o eliminado
        Object active = u.get("active");
        if (active != null && (active.equals(false) || "false".equalsIgnoreCase(String.valueOf(active)))) {
            log.debug("Login rechazado: usuario desactivado");
            return Optional.empty();
        }
        Object deleted = u.get("deleted_at");
        if (deleted != null && !String.valueOf(deleted).isBlank() && !"null".equals(String.valueOf(deleted))) {
            log.debug("Login rechazado: cuenta eliminada");
            return Optional.empty();
        }

        String stored = String.valueOf(u.getOrDefault("password", ""));
        if (stored == null || stored.isBlank() || "null".equals(stored)) {
            log.warn("Login rechazado: usuario sin contraseña en BD (id={})", u.get("id"));
            return Optional.empty();
        }
        if (password == null || !checkPw(password, stored)) {
            return Optional.empty();
        }

        // migracion bcrypt si era SHA-256
        if (!stored.startsWith("$2")) {
            try {
                supabase.update("users", String.valueOf(u.get("id")),
                        Map.of("password", hashPw(password)),
                        String.valueOf(u.get("id")));
            } catch (Exception ignored) {}
        }
        return Optional.of(u);
    }

    /** Registro inicial: crea un nuevo admin (su propia organización). Devuelve el id+token o null. */
    public Map<String, String> register(String email, String password) {
        if (email == null) return null;
        // Normalizar igual que en login
        email = email.trim().toLowerCase();

        if (!validUser(email) || problemaPassword(password) != null) {
            log.warn("Registro rechazado: email_valido={}, password_valida={}",
                    validUser(email), problemaPassword(password) == null);
            return null;
        }
        List<Map<String, Object>> existing = supabase.findBy("users", Map.of("email", email));
        if (!existing.isEmpty()) {
            log.warn(">>> AuthService.register usuario ya existe: {}", email);
            return null;
        }

        String token = randomToken();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", email);
        data.put("password", hashPw(password));
        data.put("ai_credits", defaultCredits);
        data.put("role", "admin");
        data.put("active", true);
        data.put("email_verified", false);
        data.put("email_verify_token", token);
        data.put("email_verify_expires", OffsetDateTime.now().plusDays(7).toString());
        data.put("terms_accepted_at", OffsetDateTime.now().toString());
        data.put("privacy_accepted_at", OffsetDateTime.now().toString());

        Map<String, Object> res = supabase.insert("users", data);
        if (res == null || res.isEmpty()) return null;
        Map<String, String> out = new java.util.HashMap<>();
        out.put("id", String.valueOf(res.get("id")));
        out.put("token", token);
        return out;
    }

    /** Verifica el email a partir del token. Devuelve true si OK. */
    public boolean verifyEmail(String token) {
        if (token == null || token.isBlank()) return false;
        List<Map<String, Object>> users = supabase.findBy("users", Map.of("email_verify_token", token));
        if (users.isEmpty()) return false;
        Map<String, Object> u = users.get(0);

        // Comprobar que no haya caducado
        try {
            String exp = String.valueOf(u.get("email_verify_expires"));
            if (exp != null && !exp.isBlank() && !"null".equals(exp)) {
                OffsetDateTime e = OffsetDateTime.parse(exp.length() > 25 ? exp.substring(0, 25) : exp);
                if (e.isBefore(OffsetDateTime.now())) return false;
            }
        } catch (Exception ignored) {}

        String userId = String.valueOf(u.get("id"));
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("email_verified", true);
        upd.put("email_verify_token", null);
        upd.put("email_verify_expires", null);
        return supabase.update("users", userId, upd, userId);
    }

    /** Elimina la cuenta del usuario (soft delete). */
    public boolean deleteAccount(String userId) {
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("deleted_at", OffsetDateTime.now().toString());
        upd.put("active", false);
        return supabase.update("users", userId, upd, userId);
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Invitar empleado al equipo (organization_id = admin que invita). */
    public boolean invite(String email, String password, String name, String role,
                          String orgId, String invitedBy) {
        if (email != null) email = email.trim().toLowerCase();
        if (!validUser(email) || problemaPassword(password) != null) return false;
        List<Map<String, Object>> existing = supabase.findBy("users", Map.of("email", email));
        if (!existing.isEmpty()) return false;
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("email", email);
        data.put("password", hashPw(password));
        data.put("name", name);
        data.put("role", role != null ? role : "comercial");
        data.put("organization_id", orgId);
        data.put("invited_by", invitedBy);
        data.put("ai_credits", 0);
        data.put("active", true);
        Map<String, Object> res = supabase.insert("users", data);
        return res != null && !res.isEmpty();
    }

    /** Busca un usuario por id. */
    public Optional<Map<String, Object>> findById(String uid) {
        List<Map<String, Object>> r = supabase.findBy("users", Map.of("id", uid));
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Empleados del equipo (organization_id). Los inquilinos con acceso al portal no son empleados. */
    public List<Map<String, Object>> teamMembers(String orgId) {
        return supabase.findBy("users", Map.of("organization_id", orgId)).stream()
                .filter(u -> !"tenant_portal".equals(String.valueOf(u.get("role"))))
                .toList();
    }

    /** Cambia el rol de un miembro. */
    public boolean changeRole(String userId, String newRole, String orgId) {
        List<Map<String, Object>> users = supabase.findBy("users", Map.of("id", userId, "organization_id", orgId));
        if (users.isEmpty()) return false;
        return supabase.update("users", userId, Map.of("role", newRole), userId);
    }

    /** Activa o desactiva un miembro. */
    public boolean setActive(String userId, boolean active, String orgId) {
        List<Map<String, Object>> users = supabase.findBy("users", Map.of("id", userId, "organization_id", orgId));
        if (users.isEmpty()) return false;
        return supabase.update("users", userId, Map.of("active", active), userId);
    }
}
