package com.inmobilia.controller;

import com.inmobilia.config.NavConfig;
import com.inmobilia.service.AIService;
import com.inmobilia.service.OfficialLinksService;
import com.inmobilia.service.PermissionService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.CellTone;
import com.inmobilia.util.FormatUtils;
import com.inmobilia.util.SessionUtils;
import com.inmobilia.util.ToolRoutes;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Atributos globales para todas las vistas Thymeleaf: navegacion, saludo, creditos IA, flash.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final AIService aiService;
    private final OfficialLinksService officialLinks;
    private final PermissionService permissions;
    private final PlanService planService;
    private final SupabaseService supabase;

    private static final List<Map<String, String>> PALETTE = buildPalette();

    private static List<Map<String, String>> buildPalette() {
        List<Map<String, String>> out = new ArrayList<>();
        NavConfig.CATEGORIES.forEach((catKey, cat) -> {
            for (NavConfig.Tool t : cat.tools()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", t.name());
                m.put("cat", cat.name());
                m.put("icon", t.icon());
                m.put("url", ToolRoutes.routeFor(t.name()));
                out.add(m);
            }
        });
        return out;
    }

    @ModelAttribute
    public void enrich(Model model, HttpSession session) {
        model.addAttribute("navCats", NavConfig.CATEGORIES);
        model.addAttribute("toolRoutes", new ToolRoutes());
        model.addAttribute("cellTone", new CellTone());
        model.addAttribute("paletteTools", PALETTE);
        model.addAttribute("officialLinks", officialLinks);
        model.addAttribute("platformAiEnabled", aiService.hasPlatformKey());

        boolean logged = SessionUtils.isLoggedIn(session);
        model.addAttribute("logged", logged);
        if (logged) {
            String uid = SessionUtils.uid(session);
            String userRole = SessionUtils.role(session);
            model.addAttribute("uid", uid);
            model.addAttribute("userEmail", SessionUtils.email(session));
            model.addAttribute("userName", SessionUtils.name(session));
            model.addAttribute("userRole", userRole);
            model.addAttribute("userRoleLabel", permissions.label(userRole));
            model.addAttribute("userRoleIcon", permissions.icon(userRole));
            int credits = aiService.hasPlatformKey() ? aiService.getCredits(uid) : 0;
            model.addAttribute("credits", credits);
            model.addAttribute("creditColor",
                    credits > 10 ? "#05CD99" : credits > 0 ? "#FFB547" : "#EE5D50");

            // Mapa de permisos: section → "WRITE" | "READ" | "NONE" (rol + ajustes de la persona)
            Map<String, String> perms = new HashMap<>();
            for (String s : PermissionService.SECTIONS) {
                perms.put(s, permissions.access(userRole, s).name());
            }
            model.addAttribute("perms", perms);
            model.addAttribute("nav", new NavPermisos(permissions, userRole,
                    planService.esAdminPlataforma(SessionUtils.email(session))));

            // Estado del plan (para banners de trial, etc.)
            try {
                String realUid = com.inmobilia.util.SessionUtils.realUid(session);
                if (realUid != null) {
                    var users = supabase.findBy("users", Map.of("id", realUid));
                    if (!users.isEmpty()) {
                        Map<String, Object> me = users.get(0);
                        model.addAttribute("planStatus", planService.statusFor(me));
                    }
                }
            } catch (Exception ignored) {}
        }
        // ${pais} y ${mon} los añade PaisInterceptor.postHandle (después del controlador, que en
        // páginas públicas fija el país de la inmobiliaria dueña de la página)
        model.addAttribute("paises", com.inmobilia.util.Pais.todos());
        model.addAttribute("ciudadesPorPais", com.inmobilia.util.Ciudad.porPais());
        // Precio por usuario y mes de los planes (USD) para la web, la facturación y los términos
        model.addAttribute("precioPro", PlanService.PRECIO_PRO_USD);
        model.addAttribute("precioAgency", PlanService.PRECIO_AGENCY_USD);
        model.addAttribute("flash", SessionUtils.consumeFlash(session));
        model.addAttribute("saludo", FormatUtils.saludo());
        model.addAttribute("fechaLarga", FormatUtils.fechaLarga());
    }

    /** Menú lateral: solo se enseñan los enlaces que esta persona puede abrir (mismas reglas que el guardián). */
    public static final class NavPermisos {
        private final PermissionService permissions;
        private final String role;
        private final boolean propietarioPlataforma;

        NavPermisos(PermissionService permissions, String role) {
            this(permissions, role, false);
        }

        NavPermisos(PermissionService permissions, String role, boolean propietarioPlataforma) {
            this.permissions = permissions;
            this.role = role;
            this.propietarioPlataforma = propietarioPlataforma;
        }

        public boolean ve(String href) {
            // Ejemplos para demos y "Entrar a la demo": solo en la cuenta del propietario de la plataforma
            if (href != null && (href.startsWith("/ejemplos") || href.equals("/demo"))) return propietarioPlataforma && "admin".equals(role);
            String seccion = com.inmobilia.config.SessionGuardInterceptor.seccionDe(href);
            return seccion == null || permissions.canRead(role, seccion);
        }
    }
}
