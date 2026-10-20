package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Integraciones con servicios externos:
 * - Banco: importar movimientos CSV y auto-conciliar con cobros esperados
 * - Portales: generar feed XML (Idealista/Fotocasa) con propiedades publicadas
 * - AEAT: exportar CSV para modelos 303 y 100
 * - WhatsApp Business: plantillas de mensajes con enlaces wa.me
 */
@Controller
@RequestMapping("/integrations")
@RequiredArgsConstructor
@Slf4j
public class IntegrationsController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    // ─── HUB ───
    @GetMapping
    public String index(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Integraciones");
        return "integrations/index";
    }

    // ─── BANCO ───
    @GetMapping("/bank")
    public String bank(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/integrations";
        model.addAttribute("page", "Importar movimientos bancarios");
        return "integrations/bank";
    }

    @PostMapping("/bank/import")
    public String bankImport(@RequestParam("file") MultipartFile file,
                             @RequestParam(defaultValue = ";") String separator,
                             @RequestParam(defaultValue = "0") int colDate,
                             @RequestParam(defaultValue = "1") int colDesc,
                             @RequestParam(defaultValue = "2") int colAmount,
                             @RequestParam(defaultValue = "1") int skipRows,
                             HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/integrations";

        if (file.isEmpty()) {
            SessionUtils.flash(session, "ERROR: archivo vacío");
            return "redirect:/integrations/bank";
        }

        String uid = SessionUtils.uid(session);
        var inquilinos = supabase.query("inquilinos", uid);
        char sep = separator.isEmpty() ? ';' : separator.charAt(0);

        int imported = 0, skipped = 0, conciliated = 0;
        StringBuilder errors = new StringBuilder();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line; int rowNum = 0;
            while ((line = br.readLine()) != null) {
                rowNum++;
                if (rowNum <= skipRows) continue;
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line, sep);
                if (cols.length <= Math.max(colDate, Math.max(colDesc, colAmount))) { skipped++; continue; }

                String dateStr = cols[colDate].trim();
                String desc    = cols[colDesc].trim().replaceAll("\"", "");
                String amtStr  = cols[colAmount].trim().replace(".", "").replace(",", ".").replaceAll("[^\\d.\\-]", "");

                LocalDate d = parseAnyDate(dateStr);
                if (d == null) { skipped++; continue; }
                double amt;
                try { amt = Double.parseDouble(amtStr); } catch (Exception e) { skipped++; continue; }

                String type = amt > 0 ? "Ingreso" : "Gasto";
                String absAmt = String.valueOf(Math.abs(amt));
                String category = "Importado banco";
                String propertyName = null;

                // Auto-conciliación: si el importe coincide con la renta de un inquilino
                if (amt > 0) {
                    for (var inq : inquilinos) {
                        try {
                            double rent = Double.parseDouble(String.valueOf(inq.getOrDefault("rent_amount", "0")));
                            if (rent > 0 && Math.abs(amt - rent) < rent * 0.05) {  // 5% tolerancia
                                propertyName = String.valueOf(inq.getOrDefault("property_name", ""));
                                category = "Alquiler";
                                desc = "Cobro alquiler · " + inq.get("name") + " (auto-conciliado)";
                                conciliated++;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                Map<String, Object> tx = new LinkedHashMap<>();
                tx.put("user_id", uid);
                tx.put("type", type);
                tx.put("category", category);
                tx.put("amount", Math.abs(amt));
                tx.put("date", d.toString());
                tx.put("description", desc.length() > 200 ? desc.substring(0, 200) : desc);
                if (propertyName != null) tx.put("property_name", propertyName);
                tx.put("created_at", OffsetDateTime.now().toString());

                Map<String, Object> res = supabase.insert("transacciones", tx);
                if (res != null && !res.isEmpty()) imported++;
                else { skipped++; if (errors.length() < 200) errors.append(rowNum).append(", "); }
            }
        } catch (Exception e) {
            log.error("bank import error: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR procesando archivo: " + e.getMessage());
            return "redirect:/integrations/bank";
        }

        SessionUtils.flash(session, String.format(
                "Importación completada · %d transacciones · %d auto-conciliadas con alquileres · %d filas omitidas",
                imported, conciliated, skipped));
        return "redirect:/crud/transacciones";
    }

    // ─── PORTALES ───
    @GetMapping("/portals")
    public String portals(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "propiedades")) return "redirect:/integrations";

        String uid = SessionUtils.uid(session);
        var publicadas = supabase.query("propiedades", uid).stream()
                .filter(p -> Boolean.TRUE.equals(p.get("publicada")) || "true".equalsIgnoreCase(String.valueOf(p.get("publicada"))))
                .toList();

        model.addAttribute("page", "Portales inmobiliarios");
        model.addAttribute("publicadas", publicadas);
        return "integrations/portals";
    }

    @GetMapping(value = "/portals/feed.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public void portalsFeed(HttpSession session, HttpServletResponse response) throws Exception {
        if (!SessionUtils.isLoggedIn(session)) { response.sendError(401); return; }
        String uid = SessionUtils.uid(session);
        var publicadas = supabase.query("propiedades", uid).stream()
                .filter(p -> Boolean.TRUE.equals(p.get("publicada")) || "true".equalsIgnoreCase(String.valueOf(p.get("publicada"))))
                .toList();

        response.setContentType("application/xml; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=feed-propiedades.xml");
        try (PrintWriter w = response.getWriter()) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<properties generated=\"" + LocalDate.now() + "\" total=\"" + publicadas.size() + "\">");
            for (var p : publicadas) {
                w.println("  <property>");
                w.println("    <reference>" + esc(p.get("reference")) + "</reference>");
                w.println("    <name>" + esc(p.get("name")) + "</name>");
                w.println("    <type>" + esc(p.get("type")) + "</type>");
                w.println("    <status>" + esc(p.get("status")) + "</status>");
                w.println("    <address>" + esc(p.get("address")) + "</address>");
                w.println("    <city>" + esc(p.get("city")) + "</city>");
                w.println("    <postal_code>" + esc(p.get("postal_code")) + "</postal_code>");
                w.println("    <area_m2>" + esc(p.get("area_m2")) + "</area_m2>");
                w.println("    <bedrooms>" + esc(p.get("bedrooms")) + "</bedrooms>");
                w.println("    <bathrooms>" + esc(p.get("bathrooms")) + "</bathrooms>");
                w.println("    <year_built>" + esc(p.get("year_built")) + "</year_built>");
                w.println("    <price>" + esc(p.get("current_value")) + "</price>");
                w.println("    <rental_income>" + esc(p.get("rental_income")) + "</rental_income>");
                w.println("    <photo>" + esc(p.get("featured_photo")) + "</photo>");
                w.println("    <description><![CDATA[" + (p.get("notes") != null ? p.get("notes") : "") + "]]></description>");
                w.println("  </property>");
            }
            w.println("</properties>");
        }
    }

    // ─── AEAT ───
    @GetMapping("/aeat")
    public String aeat(@RequestParam(required = false) Integer year, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "transacciones")) return "redirect:/integrations";

        int y = year != null ? year : LocalDate.now().getYear() - 1;
        model.addAttribute("page", "Exportar a AEAT");
        model.addAttribute("year", y);
        model.addAttribute("yearAnt", y - 1);
        model.addAttribute("yearSig", y + 1);
        return "integrations/aeat";
    }

    @GetMapping(value = "/aeat/303.csv", produces = "text/csv; charset=UTF-8")
    public void aeat303(@RequestParam int year, HttpSession session, HttpServletResponse response) throws Exception {
        if (!SessionUtils.isLoggedIn(session)) { response.sendError(401); return; }
        String uid = SessionUtils.uid(session);
        var invoices = supabase.query("invoices", uid);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=modelo303-" + year + ".csv");
        try (PrintWriter w = response.getWriter()) {
            w.println("﻿Numero;Fecha;Cliente;NIF;Base;IVA pct;IVA importe;IRPF pct;IRPF importe;Total;Estado");
            for (var i : invoices) {
                String fecha = String.valueOf(i.getOrDefault("fecha", ""));
                if (fecha.length() < 4 || !String.valueOf(year).equals(fecha.substring(0, 4))) continue;
                w.println(String.join(";",
                        s(i.get("full_number")),
                        s(i.get("fecha")),
                        s(i.get("cliente_nombre")),
                        s(i.get("cliente_nif")),
                        s(i.get("base_imponible")),
                        s(i.get("iva_pct")),
                        s(i.get("iva_amount")),
                        s(i.get("irpf_pct")),
                        s(i.get("irpf_amount")),
                        s(i.get("total")),
                        s(i.get("status"))
                ));
            }
        }
    }

    @GetMapping(value = "/aeat/184.csv", produces = "text/csv; charset=UTF-8")
    public void aeat184(@RequestParam int year, HttpSession session, HttpServletResponse response) throws Exception {
        if (!SessionUtils.isLoggedIn(session)) { response.sendError(401); return; }
        String uid = SessionUtils.uid(session);
        var props = supabase.query("propiedades", uid);
        var transacciones = supabase.query("transacciones", uid);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=modelo184-cb-" + year + ".csv");
        try (PrintWriter w = response.getWriter()) {
            w.println("﻿Inmueble;Direccion;Catastro;Ingresos brutos;Gastos deducibles;Rendimiento neto;Porcentaje participacion");
            for (var p : props) {
                String name = String.valueOf(p.getOrDefault("name", ""));
                double ingresos = transacciones.stream()
                        .filter(t -> name.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", "")))
                                  && "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))
                                  && String.valueOf(t.getOrDefault("date", "")).startsWith(String.valueOf(year)))
                        .mapToDouble(t -> parseDouble(t.get("amount"))).sum();
                double gastos = transacciones.stream()
                        .filter(t -> name.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", "")))
                                  && !"Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))
                                  && String.valueOf(t.getOrDefault("date", "")).startsWith(String.valueOf(year)))
                        .mapToDouble(t -> parseDouble(t.get("amount"))).sum();
                w.println(String.join(";",
                        s(name), s(p.get("address")), s(p.get("referencia_catastral")),
                        String.format(java.util.Locale.US, "%.2f", ingresos),
                        String.format(java.util.Locale.US, "%.2f", gastos),
                        String.format(java.util.Locale.US, "%.2f", ingresos - gastos),
                        "100"
                ));
            }
        }
    }

    @GetMapping(value = "/aeat/347.csv", produces = "text/csv; charset=UTF-8")
    public void aeat347(@RequestParam int year, HttpSession session, HttpServletResponse response) throws Exception {
        if (!SessionUtils.isLoggedIn(session)) { response.sendError(401); return; }
        String uid = SessionUtils.uid(session);
        var invoices = supabase.query("invoices", uid);

        // Agrupar por NIF cliente y sumar (>3000$ son los que se declaran)
        Map<String, double[]> byClient = new java.util.LinkedHashMap<>();
        Map<String, String> clientName = new java.util.HashMap<>();
        for (var i : invoices) {
            String fecha = String.valueOf(i.getOrDefault("fecha", ""));
            if (!fecha.startsWith(String.valueOf(year))) continue;
            if ("Anulada".equalsIgnoreCase(String.valueOf(i.get("status")))) continue;
            String nif = String.valueOf(i.getOrDefault("cliente_nif", "")).trim();
            if (nif.isEmpty()) continue;
            double total = parseDouble(i.get("total"));
            byClient.computeIfAbsent(nif, k -> new double[1])[0] += total;
            clientName.putIfAbsent(nif, String.valueOf(i.getOrDefault("cliente_nombre", "")));
        }

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=modelo347-" + year + ".csv");
        try (PrintWriter w = response.getWriter()) {
            w.println(("﻿NIF;Nombre cliente;Total facturado anual;Declarable (>3000 " + PaisActual.moneda() + ")"));
            for (var entry : byClient.entrySet()) {
                double total = entry.getValue()[0];
                w.println(String.join(";",
                        s(entry.getKey()),
                        s(clientName.get(entry.getKey())),
                        String.format(java.util.Locale.US, "%.2f", total),
                        total > 3000 ? "SÍ" : "no"
                ));
            }
        }
    }

    @GetMapping(value = "/aeat/100.csv", produces = "text/csv; charset=UTF-8")
    public void aeat100(@RequestParam int year, HttpSession session, HttpServletResponse response) throws Exception {
        if (!SessionUtils.isLoggedIn(session)) { response.sendError(401); return; }
        String uid = SessionUtils.uid(session);
        var transacciones = supabase.query("transacciones", uid);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=modelo100-rentas-" + year + ".csv");
        try (PrintWriter w = response.getWriter()) {
            w.println("﻿Fecha;Tipo;Categoria;Propiedad;Inquilino;Importe;Deducible;Descripcion");
            for (var t : transacciones) {
                String fecha = String.valueOf(t.getOrDefault("date", ""));
                if (fecha.length() < 4 || !String.valueOf(year).equals(fecha.substring(0, 4))) continue;
                w.println(String.join(";",
                        s(t.get("date")),
                        s(t.get("type")),
                        s(t.get("category")),
                        s(t.get("property_name")),
                        s(t.get("tenant_name")),
                        s(t.get("amount")),
                        s(t.get("deductible")),
                        s(t.get("description"))
                ));
            }
        }
    }

    // ─── WHATSAPP ───
    @GetMapping("/whatsapp")
    public String whatsapp(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var inquilinos = supabase.query("inquilinos", uid).stream()
                .filter(i -> {
                    String p = String.valueOf(i.getOrDefault("phone", ""));
                    return p != null && !p.isBlank() && !"null".equals(p);
                })
                .toList();
        model.addAttribute("page", "WhatsApp Business");
        model.addAttribute("inquilinos", inquilinos);
        return "integrations/whatsapp";
    }

    // ─── HELPERS ───
    private static String esc(Object o) {
        if (o == null) return "";
        return String.valueOf(o)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
    private static String s(Object o) {
        if (o == null) return "";
        String v = String.valueOf(o);
        if (v.contains(";") || v.contains("\"")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
    private static double parseDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
    private static String[] splitCsv(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if (c == sep && !inQuotes) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
    private static LocalDate parseAnyDate(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.replaceAll("\"", "").trim();
        String[] formats = { "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy/MM/dd" };
        for (String f : formats) {
            try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(f)); } catch (Exception ignored) {}
        }
        return null;
    }
}
