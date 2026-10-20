package com.inmobilia.controller;

import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 📤 Exportación de datos a CSV (leads y cartera). "Tus datos son tuyos".
 * Cada exportación respeta los permisos del rol y queda en el registro de actividad.
 */
@Controller
@RequiredArgsConstructor
public class ExportController {

    private final SupabaseService supabase;
    private final PermissionService permissions;
    private final AuditTrail audit;

    @GetMapping("/exportar")
    public String panel(HttpSession session) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "exportar";
    }

    @GetMapping("/exportar/leads.csv")
    @ResponseBody
    public void leadsCsv(HttpServletRequest req, HttpSession session, HttpServletResponse resp) throws IOException {
        String uid = SessionUtils.uid(session);
        if (uid == null) { resp.sendError(401); return; }
        if (!permitido(req, session, resp, "leads")) return;
        List<Map<String, Object>> leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&order=created_at.desc&limit=5000");
        String[] cols = {"nombre", "email", "telefono", "interes", "etapa", "presupuesto", "zona", "tipo_activo", "origen", "score", "notas", "created_at"};
        audit.registrar(req, session, "exportacion_csv", "leads", null,
                "Exportó " + leads.size() + " leads a CSV", "warning", AuditTrail.EXPORTACION);
        escribir(resp, "leads", cols, leads);
    }

    @GetMapping("/exportar/propiedades.csv")
    @ResponseBody
    public void propiedadesCsv(HttpServletRequest req, HttpSession session, HttpServletResponse resp) throws IOException {
        String uid = SessionUtils.uid(session);
        if (uid == null) { resp.sendError(401); return; }
        if (!permitido(req, session, resp, "propiedades")) return;
        List<Map<String, Object>> props = supabase.selectFilter("propiedades", "user_id=eq." + uid + "&order=created_at.desc&limit=5000");
        String[] cols = {"name", "address", "city", "postal_code", "type", "status", "bedrooms", "bathrooms", "area_m2", "current_value", "purchase_price", "reference", "created_at"};
        audit.registrar(req, session, "exportacion_csv", "propiedades", null,
                "Exportó " + props.size() + " propiedades a CSV", "warning", AuditTrail.EXPORTACION);
        escribir(resp, "cartera", cols, props);
    }

    private boolean permitido(HttpServletRequest req, HttpSession session, HttpServletResponse resp, String seccion) throws IOException {
        if (permissions.canRead(SessionUtils.role(session), seccion)) return true;
        audit.registrar(req, session, "exportacion_denegada", seccion, null,
                "Intentó exportar " + seccion + " sin permiso", "warning", AuditTrail.EXPORTACION);
        resp.sendError(403);
        return false;
    }

    private void escribir(HttpServletResponse resp, String nombre, String[] cols, List<Map<String, Object>> filas) throws IOException {
        resp.setContentType("text/csv; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"inmobiliapro-" + nombre + ".csv\"");
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');  // BOM para que Excel respete UTF-8
        sb.append(String.join(";", cols)).append("\r\n");
        for (Map<String, Object> f : filas) {
            List<String> vals = new ArrayList<>();
            for (String c : cols) vals.add(esc(f.get(c)));
            sb.append(String.join(";", vals)).append("\r\n");
        }
        resp.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String esc(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        if ("null".equals(s)) return "";
        // Anti "CSV injection": un lead puede llamarse "=HIPERVINCULO(...)" (los formularios
        // públicos los rellena cualquiera). Con la comilla delante, Excel lo muestra como texto.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+([.,]\\d+)?")) {
            s = "'" + s;
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
