package com.inmobilia.controller;

import com.inmobilia.service.EjemplosService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.*;

/**
 * Ejemplos para demos: carga en la cuenta una inmobiliaria ficticia con datos en todas las
 * herramientas, para enseñar la app a clientes; y los quita (solo los de ejemplo) al terminar.
 * Solo el administrador de la cuenta.
 */
@Controller
@RequiredArgsConstructor
public class EjemplosController {

    private final EjemplosService ejemplos;
    private final com.inmobilia.service.PlanService planService;

    @GetMapping("/ejemplos")
    public String pantalla(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!esAdmin(session)) return sinPermiso(session);
        EjemplosService.Estado e = ejemplos.estado(uid);
        model.addAttribute("page", "Ejemplos para demos");
        model.addAttribute("estado", e);
        model.addAttribute("filas", e.enCurso() ? e.filas() : ejemplos.filasCargadas(uid));
        if (!e.enCurso()) {
            Map<String, Integer> porTabla = ejemplos.porTabla(uid);
            List<Map<String, Object>> herramientas = new ArrayList<>();
            for (String[] h : HERRAMIENTAS) {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("nombre", h[0]);
                fila.put("ruta", h[2]);
                fila.put("n", porTabla.getOrDefault(h[1], 0));
                herramientas.add(fila);
            }
            model.addAttribute("herramientas", herramientas);
            model.addAttribute("errores", ejemplos.errores(uid));
        }
        return "ejemplos";
    }

    /** Herramienta, tabla donde van sus ejemplos y pantalla: para ver de un vistazo qué falta. */
    static final String[][] HERRAMIENTAS = {
            {"Inmuebles", "propiedades", "/propiedades/grid"}, {"Leads y pipeline", "leads", "/kanban/leads"},
            {"Oportunidades", "oportunidades", "/kanban/oportunidades"}, {"Visitas", "visitas", "/visitas"},
            {"Hoja de visita", "hojas_visita", "/hoja-visita"}, {"Nota de encargo", "notas_encargo", "/nota-encargo"},
            {"Captación por zona", "captacion_campanas", "/captacion"}, {"Open house", "open_houses", "/open-house"},
            {"Reservas online", "reservas_online", "/reservas-online"}, {"Co-broking", "cobroking_propiedades", "/cobroking"},
            {"Portal del comprador", "selecciones_comprador", "/portal-comprador"}, {"Inbox unificado", "emails_inbox", "/inbox"},
            {"SMS", "sms_mensajes", "/sms"}, {"Web pública", "microsites", "/microsite/admin"},
            {"Tours 360º", "tours_virtuales", "/tours-360"}, {"Staging digital", "staging_proyectos", "/staging-digital"},
            {"Chatbot", "chatbot_conversaciones", "/chatbot"}, {"Referidos", "referidos", "/referidos"},
            {"NPS", "nps_respuestas", "/nps"}, {"Solicitudes de inquilinos", "tenant_requests", "/admin/tenant-requests"},
            {"Facturas", "invoices", "/invoices"}, {"Comisiones", "commissions", "/commissions"},
            {"SEPA", "sepa_remesas", "/sepa"}, {"Conciliación bancaria", "bank_imports", "/conciliacion"},
            {"Caja", "movimientos_caja", "/caja"}, {"Extractos a propietarios", "extractos_propietario", "/extractos"},
            {"Firmas eIDAS", "firmas_documento", "/firmas"}, {"Plantillas de email", "templates", "/templates"},
            {"Seguros", "seguros", "/seguros"}, {"Wiki interna", "wiki_articulos", "/wiki"},
            {"Empleados", "users", "/equipo"}, {"Automatizaciones", "workflows", "/workflows"},
            {"Rentabilidad", "analisis_rentabilidad", "/rentabilidad"}};

    @PostMapping("/ejemplos/cargar")
    public String cargar(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!esAdmin(session)) return sinPermiso(session);
        ejemplos.cargar(uid);
        return "redirect:/ejemplos";
    }

    @PostMapping("/ejemplos/quitar")
    public String quitar(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!esAdmin(session)) return sinPermiso(session);
        ejemplos.quitarEnSegundoPlano(uid);
        return "redirect:/ejemplos";
    }

    /**
     * Solo el propietario de la plataforma (ADMIN_EMAILS), en su cuenta de demostración. Antes
     * podía cualquier administrador de inmobiliaria y mezclar datos ficticios con los suyos.
     */
    private boolean esAdmin(HttpSession session) {
        return "admin".equals(SessionUtils.role(session)) && planService.esAdminPlataforma(SessionUtils.email(session));
    }

    private static String sinPermiso(HttpSession session) {
        SessionUtils.flash(session, "Los ejemplos para demos solo están disponibles en la cuenta de demostración de InmobilIA.");
        return "redirect:/dashboard";
    }
}
