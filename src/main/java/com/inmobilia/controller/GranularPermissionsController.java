package com.inmobilia.controller;

import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.PermissionService;
import com.inmobilia.service.PermissionService.Access;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Permisos por persona: el administrador de la inmobiliaria decide, sección a sección, qué
 * puede ver o modificar cada empleado por encima de lo que da su rol (más o menos acceso).
 * Se guardan en permisos_personalizados.secciones y los aplica SessionGuardInterceptor en
 * menos de un minuto. Solo el rol administrador puede cambiarlos, y nunca a otro administrador
 * (los administradores tienen siempre acceso total).
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/permisos-granulares")
public class GranularPermissionsController {

    private final SupabaseService supabase;
    private final PermissionService permissions;
    private final AuditTrail audit;

    /** Campos de la ficha del usuario que se pueden enseñar en estas pantallas. */
    private static final Set<String> CAMPOS_VISIBLES = Set.of("id", "email", "name", "role", "active", "created_at");

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!esAdministrador(session)) return sinPermiso(session);

        List<Map<String, Object>> usuarios = new ArrayList<>();
        for (Map<String, Object> u : supabase.selectFilter("users", "organization_id=eq." + uid + "&active=eq.true&order=name.asc")) {
            Map<String, Object> f = visible(u);
            String rol = String.valueOf(u.get("role"));
            Map<String, String> ajustes = ajustesDe(String.valueOf(u.get("id")), uid);
            f.put("rolNombre", permissions.label(rol));
            f.put("esAdmin", PermissionService.ADMIN.equals(rol));
            f.put("numAjustes", ajustes.size());
            usuarios.add(f);
        }
        model.addAttribute("usuarios", usuarios);
        model.addAttribute("matriz", matrizDeRoles());
        model.addAttribute("roles", PermissionService.ALL_ROLES);
        model.addAttribute("roleLabels", PermissionService.ROLE_LABELS);
        return "granular_permissions";
    }

    @GetMapping("/{userId}")
    public String editar(@PathVariable String userId, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!esAdministrador(session)) return sinPermiso(session);
        Map<String, Object> usuario = delEquipo(userId, uid);
        String problema = problemaCon(usuario, session);
        if (problema != null) {
            SessionUtils.flash(session, problema);
            return "redirect:/permisos-granulares";
        }
        String rol = String.valueOf(usuario.get("role"));
        Map<String, String> ajustes = ajustesDe(userId, uid);

        List<Map<String, Object>> filas = new ArrayList<>();
        PermissionService.SECTION_INFO.forEach((seccion, info) -> {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("seccion", seccion);
            fila.put("nombre", info[0]);
            fila.put("descripcion", info[1]);
            fila.put("delRol", permissions.accesoDelRol(rol, seccion).name());
            fila.put("ajuste", ajustes.getOrDefault(seccion, "rol"));
            filas.add(fila);
        });
        model.addAttribute("usuario", visible(usuario));
        model.addAttribute("rolNombre", permissions.label(rol));
        model.addAttribute("filas", filas);
        return "granular_permissions_edit";
    }

    @PostMapping("/{userId}/guardar")
    public String save(@PathVariable String userId, @RequestParam Map<String, String> form,
                       HttpServletRequest req, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!esAdministrador(session)) return sinPermiso(session);
        Map<String, Object> usuario = delEquipo(userId, uid);
        String problema = problemaCon(usuario, session);
        if (problema != null) {
            SessionUtils.flash(session, problema);
            return "redirect:/permisos-granulares";
        }
        String rol = String.valueOf(usuario.get("role"));

        Map<String, String> antes = ajustesDe(userId, uid);
        Map<String, String> ajustes = ajustesDelFormulario(form, rol);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("org_id", uid);
        data.put("user_id", userId);
        data.put("secciones", ajustes);
        data.put("updated_at", OffsetDateTime.now().toString());
        List<Map<String, Object>> ex = supabase.selectFilter("permisos_personalizados",
                "user_id=eq." + userId + "&org_id=eq." + uid);
        boolean ok;
        if (ex.isEmpty()) {
            Map<String, Object> creado = supabase.insert("permisos_personalizados", data);
            ok = creado != null && !creado.isEmpty();
        } else {
            ok = supabase.update("permisos_personalizados", String.valueOf(ex.get(0).get("id")), data, null);
        }

        if (!ok) {
            SessionUtils.flash(session, "No se pudieron guardar los permisos. ¿Está ejecutada la migración V84 en Supabase?");
            return "redirect:/permisos-granulares/" + userId;
        }
        audit.registrar(req, session, "permisos_cambiados", "users", userId,
                "Cambió los permisos de " + usuario.get("email") + ": " + resumenCambios(antes, ajustes),
                "security", AuditTrail.EQUIPO);
        SessionUtils.flash(session, "Permisos de " + usuario.get("name") + " guardados. Se aplican en menos de un minuto.");
        return "redirect:/permisos-granulares";
    }

    /**
     * Del formulario a los ajustes: solo se guarda lo que difiere del rol (elegir lo mismo que
     * ya da el rol equivale a "según su rol", así un cambio de rol posterior sigue mandando).
     */
    Map<String, String> ajustesDelFormulario(Map<String, String> form, String rol) {
        Map<String, String> ajustes = new TreeMap<>();
        for (String seccion : PermissionService.SECTION_INFO.keySet()) {
            Access elegido = PermissionService.parse(form.get("s_" + seccion));
            if (elegido != null && elegido != permissions.accesoDelRol(rol, seccion)) {
                ajustes.put(seccion, elegido.name());
            }
        }
        return ajustes;
    }

    static String resumenCambios(Map<String, String> antes, Map<String, String> despues) {
        List<String> partes = new ArrayList<>();
        Set<String> todas = new TreeSet<>(antes.keySet());
        todas.addAll(despues.keySet());
        for (String s : todas) {
            String a = antes.getOrDefault(s, "rol"), d = despues.getOrDefault(s, "rol");
            if (!a.equals(d)) partes.add(nombre(s) + ": " + texto(a) + " → " + texto(d));
        }
        return partes.isEmpty() ? "sin cambios" : String.join("; ", partes);
    }

    private static String nombre(String seccion) {
        String[] info = PermissionService.SECTION_INFO.get(seccion);
        return info != null ? info[0] : seccion;
    }

    private static String texto(String acceso) {
        return switch (acceso) {
            case "NONE" -> "sin acceso";
            case "READ" -> "ver";
            case "WRITE" -> "ver y modificar";
            default -> "según su rol";
        };
    }

    /** Matriz rol × sección para la tabla de referencia. */
    private List<Map<String, Object>> matrizDeRoles() {
        List<Map<String, Object>> filas = new ArrayList<>();
        PermissionService.SECTION_INFO.forEach((seccion, info) -> {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("nombre", info[0]);
            Map<String, String> porRol = new LinkedHashMap<>();
            for (String r : PermissionService.ALL_ROLES) porRol.put(r, permissions.accesoDelRol(r, seccion).name());
            fila.put("porRol", porRol);
            filas.add(fila);
        });
        return filas;
    }

    private Map<String, String> ajustesDe(String userId, String orgId) {
        Map<String, String> res = new TreeMap<>();
        try {
            List<Map<String, Object>> filas = supabase.selectFilter("permisos_personalizados",
                    "select=secciones&user_id=eq." + userId + "&org_id=eq." + orgId);
            if (!filas.isEmpty() && filas.get(0).get("secciones") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    Access a = PermissionService.parse(v);
                    if (a != null && PermissionService.SECTIONS.contains(String.valueOf(k))) res.put(String.valueOf(k), a.name());
                });
            }
        } catch (Exception ignored) { }
        return res;
    }

    /** null si se pueden ajustar sus permisos; si no, el motivo. */
    private static String problemaCon(Map<String, Object> usuario, HttpSession session) {
        if (usuario == null) return "Esa persona no pertenece a tu equipo.";
        if (String.valueOf(usuario.get("id")).equals(SessionUtils.realUid(session)))
            return "No puedes cambiar tus propios permisos.";
        if (PermissionService.ADMIN.equals(String.valueOf(usuario.get("role"))))
            return "Los administradores tienen siempre acceso total.";
        return null;
    }

    private static boolean esAdministrador(HttpSession session) {
        return PermissionService.ADMIN.equals(SessionUtils.role(session));
    }

    private static String sinPermiso(HttpSession session) {
        SessionUtils.flash(session, "Solo el administrador puede cambiar los permisos del equipo.");
        return "redirect:/dashboard";
    }

    /** El usuario solo si pertenece a esta inmobiliaria (si no, null). */
    private Map<String, Object> delEquipo(String userId, String orgId) {
        if (userId == null || !userId.matches("[0-9a-fA-F-]{36}")) return null;
        List<Map<String, Object>> us = supabase.selectFilter("users", "id=eq." + userId + "&organization_id=eq." + orgId);
        return us.isEmpty() ? null : us.get(0);
    }

    /** Nunca pasar a la vista contraseñas, secretos de 2FA ni tokens. */
    private static Map<String, Object> visible(Map<String, Object> u) {
        Map<String, Object> f = new LinkedHashMap<>();
        u.forEach((k, v) -> { if (CAMPOS_VISIBLES.contains(k)) f.put(k, v); });
        return f;
    }
}
