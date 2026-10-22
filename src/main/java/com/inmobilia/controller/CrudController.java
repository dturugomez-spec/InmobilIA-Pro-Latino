package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.AuditTrail;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Controlador generico CRUD para todas las tablas simples (propiedades, inquilinos,
 * contratos, transacciones, tareas, mantenimiento, personal, eventos, documentos,
 * reservas, seguros, oportunidades, leads, email_templates).
 *
 * Cada tabla se expone en /crud/{tabla}[/new | /{id}/edit | /{id}/delete].
 */
@Controller
@RequestMapping("/crud")
@RequiredArgsConstructor
@Slf4j
public class CrudController {

    private final SupabaseService supabase;
    private final PlanService planService;
    private final AuditTrail audit;
    private final com.inmobilia.service.TrashService trash;

    /** Ejemplos reales por campo (declarado ANTES que TABLES para evitar NPE en clinit). */
    private static final Map<String, String> PLACEHOLDERS = Map.ofEntries(
        Map.entry("name",            "Ej: Depto. Reforma 222, 3B"),
        Map.entry("reference",       "Ej: REF-2026-0042 (se autogenera si lo dejas vacío)"),
        Map.entry("featured_photo",  "URL de la foto destacada (https://...)"),
        Map.entry("address",         "Ej: Av. Paseo de la Reforma 222, 3B"),
        Map.entry("city",            "Ej: Guadalajara"),
        Map.entry("postal_code",     "Ej: 28013"),
        Map.entry("type",            "Ej: Residencial, Comercial, Oficina"),
        Map.entry("status",          "Ej: Ocupado, Libre, En Reforma"),
        Map.entry("area_m2",         "Ej: 85.5"),
        Map.entry("bedrooms",        "Ej: 3"),
        Map.entry("bathrooms",       "Ej: 2"),
        Map.entry("year_built",      "Ej: 1998"),
        Map.entry("purchase_price",  "Ej: 235000.00  (EUR)"),
        Map.entry("current_value",   "Ej: 270000.00  (EUR)"),
        Map.entry("rental_income",   "Ej: 1100.00  (EUR/mes bruto)"),
        Map.entry("monthly_expenses","Ej: 180.00  (mantenimiento+predial/12+...)"),
        Map.entry("mortgage_payment","Ej: 650.00  (cuota mensual)"),
        Map.entry("floor",           "Ej: 3B / Atico / Bajo"),
        Map.entry("email",           "Ej: juan.perez@email.com"),
        Map.entry("phone",           "Ej: +52 55 1234 5678"),
        Map.entry("id_number",       "Ej: documento de identidad o número fiscal"),
        Map.entry("nationality",     "Ej: Espanola"),
        Map.entry("property_name",   "Ej: Depto. Reforma 222, 3B"),
        Map.entry("rent_amount",     "Ej: 1100.00"),
        Map.entry("deposit",         "Ej: 2200.00  (2 mensualidades)"),
        Map.entry("lease_start",     "Ej: 2024-01-15"),
        Map.entry("lease_end",       "Ej: 2029-01-14"),
        Map.entry("guarantor_name",  "Ej: Maria Lopez (aval)"),
        Map.entry("guarantor_phone", "Ej: +57 310 123 4567"),
        Map.entry("score",           "Ej: 85  (0-100)"),
        Map.entry("tenant_name",     "Ej: Juan Perez Garcia"),
        Map.entry("start_date",      "Ej: 2024-01-15"),
        Map.entry("end_date",        "Ej: 2029-01-14"),
        Map.entry("rent",            "Ej: 1100.00"),
        Map.entry("signed_date",     "Ej: 2024-01-10"),
        Map.entry("terms",           "Ej: Duracion 1 ano, ajuste anual por inflacion, 1 mes de deposito en garantia..."),
        Map.entry("amount",          "Ej: 1100.00"),
        Map.entry("date",            "Ej: 2026-04-01"),
        Map.entry("category",        "Ej: Renta, Predial, Reforma, Mantenimiento"),
        Map.entry("invoice_number",  "Ej: F-2026/0127"),
        Map.entry("vat_pct",         "Ej: 21  (% IVA)"),
        Map.entry("title",           "Ej: Revisar calentador depto. Reforma"),
        Map.entry("description",     "Ej: Inquilino reporta agua tibia en ducha. Llamar a Tecnicos XYZ, tel. 91 ..."),
        Map.entry("priority",        "1 = baja · 2 = media · 3 = alta · 4 = urgente"),
        Map.entry("assigned_to",     "Ej: Carlos (fontaneria)"),
        Map.entry("due_date",        "Ej: 2026-04-30"),
        Map.entry("cost",            "Ej: 180.00"),
        Map.entry("scheduled_date",  "Ej: 2026-05-02"),
        Map.entry("completed_date",  "Ej: 2026-05-03"),
        Map.entry("role",            "Ej: Administrador, Tecnico, Comercial"),
        Map.entry("salary",          "Ej: 1800.00  (bruto/mes)"),
        Map.entry("time",            "Ej: 17:30"),
        Map.entry("attendees",       "Ej: Juan Perez, Maria L."),
        Map.entry("location",        "Ej: Oficina / Direccion del inmueble"),
        Map.entry("reminder_days",   "Ej: 1  (dias antes)"),
        Map.entry("doc_type",        "Ej: Contrato, Factura, Certificado, Nota simple"),
        Map.entry("url",             "Ej: https://drive.google.com/..."),
        Map.entry("file_size_kb",    "Ej: 1024"),
        Map.entry("expiry_date",     "Ej: 2027-12-31"),
        Map.entry("client_name",     "Ej: Ana Garcia"),
        Map.entry("client_phone",    "Ej: +52 55 1234 5678"),
        Map.entry("client_email",    "Ej: ana.garcia@email.com"),
        Map.entry("agent_name",      "Ej: Laura (comercial)"),
        Map.entry("feedback",        "Ej: Le gusto la cocina, pidio rebaja 5%..."),
        Map.entry("company",         "Ej: Mapfre, Mutua, Linea Directa"),
        Map.entry("policy_number",   "Ej: POL-2024-008421"),
        Map.entry("annual_cost",     "Ej: 280.00  (prima anual)"),
        Map.entry("coverage",        "Ej: 100000.00  (cobertura EUR)"),
        Map.entry("contact_phone",   "Ej: 900 123 456"),
        Map.entry("price",           "Ej: 235000.00"),
        Map.entry("cap_rate",        "Ej: 5.8  (%)"),
        Map.entry("gross_yield",     "Ej: 6.2  (%)"),
        Map.entry("source",          "Ej: Portal inmobiliario, Off-market, Agente X"),
        Map.entry("contact",         "Ej: Pedro Gomez +52 ..."),
        Map.entry("expected_irr",    "Ej: 9.5  (% TIR esperada)"),
        Map.entry("nombre",          "Ej: Ana Garcia"),
        Map.entry("telefono",        "Ej: +52 55 1234 5678"),
        Map.entry("origen",          "Ej: Web, Portal, Referido, WhatsApp"),
        Map.entry("interes",         "Ej: Compra depto. 2 hab en Roma Norte"),
        Map.entry("presupuesto",     "Ej: 280000.00"),
        Map.entry("zona",            "Ej: Polanco, Del Valle"),
        Map.entry("tipo_activo",     "Ej: Residencial, Comercial"),
        Map.entry("etapa",           "Ej: Nuevo, Contactado, Visita, Oferta, Cerrado"),
        Map.entry("notas",           "Ej: Llamar lunes tarde, prefiere whatsapp..."),
        Map.entry("notes",           "Ej: Notas libres sobre el registro..."),
        Map.entry("next_followup",   "Ej: 2026-04-25"),
        Map.entry("asunto",          "Ej: Recordatorio de pago - {{mes}}"),
        Map.entry("cuerpo",          "Ej: Hola {{inquilino}}, te recordamos que la renta de {{mes}}..."),
        Map.entry("categoria",       "Ej: Cobranza, Bienvenida, Renovacion")
    );

    /** Hint opcional debajo del input. */
    private static final Map<String, String> HINTS = Map.of(
        "has_insurance", "Marca si el inquilino tiene seguro de hogar",
        "ipc_revision",  "Marca si el contrato lleva ajuste anual por inflacion",
        "deductible",    "Marca si el gasto es fiscalmente deducible",
        "auto_renew",    "Marca si la poliza se renueva automaticamente",
        "publicada",     "Marca para hacer publica la propiedad en la cartera"
    );

    /** Metadatos: titulo legible y campos de cada tabla (para renderizar el formulario). */
    private static final Map<String, TableMeta> TABLES = Map.ofEntries(
        Map.entry("propiedades",    TableMeta.of("Cartera de Activos", fields("name","reference","featured_photo","publicada","address","city","postal_code","type","status","area_m2","bedrooms","bathrooms","year_built","purchase_price","current_value","rental_income","monthly_expenses","mortgage_payment","notes"))),
        Map.entry("inquilinos",     TableMeta.of("CRM de Inquilinos",  fields("name","email","phone","id_number","nationality","property_name","rent_amount","deposit","lease_start","lease_end","status","guarantor_name","guarantor_phone","has_insurance","score","notes"))),
        Map.entry("contratos",      TableMeta.of("Gestion de Contratos", fields("property_name","tenant_name","start_date","end_date","rent","deposit","ipc_revision","status","terms","signed_date"))),
        Map.entry("transacciones",  TableMeta.of("Contabilidad & Finanzas", fields("type","category","amount","date","property_name","description","invoice_number","vat_pct","deductible"))),
        Map.entry("tareas",         TableMeta.of("Gestion de Tareas", fields("title","description","priority","assigned_to","due_date","status","property_name"))),
        Map.entry("mantenimiento",  TableMeta.of("Mantenimiento de Activos", fields("title","description","property_name","priority","status","assigned_to","cost","scheduled_date","completed_date"))),
        Map.entry("personal",       TableMeta.of("Equipo y RRHH", fields("name","role","email","phone","salary","start_date","status","notes"))),
        Map.entry("eventos",        TableMeta.of("Agenda & Calendario", fields("title","date","time","type","description","property_name","attendees","location","reminder_days"))),
        Map.entry("documentos",     TableMeta.of("Archivo Documental", fields("name","doc_type","property_name","url","file_size_kb","expiry_date","notes"))),
        Map.entry("reservas",       TableMeta.of("Visitas & Reservas", fields("client_name","client_phone","client_email","property_name","date","time","type","status","agent_name","feedback","notes"))),
        Map.entry("seguros",        TableMeta.of("Polizas & Seguros", fields("type","company","policy_number","property_name","annual_cost","coverage","start_date","expiry_date","auto_renew","contact_phone","notes"))),
        Map.entry("oportunidades",  TableMeta.of("Pipeline de Operaciones", fields("property_name","address","city","price","cap_rate","gross_yield","area_m2","type","status","source","contact","expected_irr","priority","notes"))),
        Map.entry("leads",          TableMeta.of("Pipeline CRM Leads", fields("nombre","email","telefono","origen","interes","presupuesto","zona","tipo_activo","etapa","score","notas","next_followup"))),
        Map.entry("email_templates",TableMeta.of("Plantillas de Email", fields("nombre","asunto","cuerpo","categoria")))
    );

    /**
     * Tablas con pantalla propia: el CRUD genérico usaba columnas que la tabla real no tiene
     * (seguros: type/company/expiry_date… frente a tipo/aseguradora/fecha_vencimiento de /seguros),
     * así que crear o editar desde aquí fallaba. Se manda a su pantalla.
     */
    static final Map<String, String> PANTALLA_PROPIA = Map.of("seguros", "/seguros");

    @GetMapping("/{table}")
    public String list(@PathVariable String table, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (PANTALLA_PROPIA.containsKey(table)) return "redirect:" + PANTALLA_PROPIA.get(table);
        TableMeta meta = TABLES.get(table);
        if (meta == null) return "redirect:/dashboard";
        String uid = SessionUtils.uid(session);
        List<Map<String, Object>> rows = supabase.query(table, uid);
        model.addAttribute("table", table);
        model.addAttribute("page", meta.title());
        model.addAttribute("fields", meta.fields());
        model.addAttribute("rows", rows);
        model.addAttribute("stats", computeStats(table, rows));
        model.addAttribute("statusValues", distinctValues(rows, "status"));
        return "crud/list";
    }

    /** Mini-KPIs por tabla. */
    private List<Map<String, String>> computeStats(String table, List<Map<String, Object>> rows) {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(kpi("Total", String.valueOf(rows.size()), null));
        switch (table) {
            case "propiedades" -> {
                double val = sum(rows, "current_value");
                double rent = sum(rows, "rental_income");
                long occ = rows.stream().filter(r -> "Ocupado".equals(r.get("status"))).count();
                out.add(kpi("Valor cartera", eur(val), "pos"));
                out.add(kpi("Renta mensual", eur(rent), "pos"));
                out.add(kpi("Ocupadas", occ + "/" + rows.size(), rows.isEmpty() ? null : (occ * 1.0 / rows.size() >= 0.8 ? "pos" : "warn")));
            }
            case "inquilinos" -> {
                long moros = rows.stream().filter(r -> "Moroso".equals(r.get("status"))).count();
                double rent = sum(rows, "rent_amount");
                out.add(kpi("Renta total", eur(rent), "pos"));
                out.add(kpi("Morosos", String.valueOf(moros), moros > 0 ? "neg" : null));
            }
            case "contratos" -> {
                long venc = rows.stream().filter(r -> isExpiringSoon(r, "end_date", 60)).count();
                double rent = sum(rows, "rent");
                out.add(kpi("Renta contratada", eur(rent), "pos"));
                out.add(kpi("Vencen <60d", String.valueOf(venc), venc > 0 ? "warn" : null));
            }
            case "transacciones" -> {
                double ing = rows.stream().filter(r -> "Ingreso".equals(r.get("type"))).mapToDouble(r -> num(r.get("amount"))).sum();
                double gas = rows.stream().filter(r -> "Gasto".equals(r.get("type"))).mapToDouble(r -> num(r.get("amount"))).sum();
                out.add(kpi("Ingresos", eur(ing), "pos"));
                out.add(kpi("Gastos", eur(gas), "neg"));
                out.add(kpi("Neto", eur(ing - gas), ing - gas >= 0 ? "pos" : "neg"));
            }
            case "tareas" -> {
                long abiertas = rows.stream().filter(r -> !"Completada".equals(r.get("status"))).count();
                long urg = rows.stream().filter(r -> com.inmobilia.util.Prioridad.esUrgente(r.get("priority"))).count();
                out.add(kpi("Abiertas", String.valueOf(abiertas), abiertas > 0 ? "warn" : null));
                out.add(kpi("Urgentes", String.valueOf(urg), urg > 0 ? "neg" : null));
            }
            case "mantenimiento" -> {
                long abiertas = rows.stream().filter(r -> !"Completado".equals(r.get("status")) && !"Cerrado".equals(r.get("status"))).count();
                double cost = sum(rows, "cost");
                out.add(kpi("Abiertas", String.valueOf(abiertas), abiertas > 0 ? "warn" : null));
                out.add(kpi("Coste total", eur(cost), null));
            }
            case "seguros" -> {
                double total = sum(rows, "annual_cost");
                long venc = rows.stream().filter(r -> isExpiringSoon(r, "expiry_date", 60)).count();
                out.add(kpi("Prima anual", eur(total), null));
                out.add(kpi("Vencen <60d", String.valueOf(venc), venc > 0 ? "warn" : null));
            }
            case "oportunidades" -> {
                double inv = sum(rows, "price");
                double capAvg = rows.stream().mapToDouble(r -> num(r.get("cap_rate"))).filter(v -> v > 0).average().orElse(0);
                out.add(kpi("Inversion total", eur(inv), null));
                out.add(kpi("Cap rate medio", String.format(java.util.Locale.US, "%.2f%%", capAvg), capAvg >= 5 ? "pos" : "warn"));
            }
            case "leads" -> {
                long calientes = rows.stream().filter(r -> "Oferta".equals(r.get("etapa")) || "Visita".equals(r.get("etapa"))).count();
                double pres = sum(rows, "presupuesto");
                out.add(kpi("Calientes", String.valueOf(calientes), calientes > 0 ? "pos" : null));
                out.add(kpi("Presupuesto total", eur(pres), null));
            }
            default -> {}
        }
        return out;
    }

    private static Map<String, String> kpi(String label, String value, String tone) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("label", label); m.put("value", value);
        if (tone != null) m.put("tone", tone);
        return m;
    }
    private static double sum(List<Map<String, Object>> rows, String col) {
        return rows.stream().mapToDouble(r -> num(r.get(col))).sum();
    }
    private static double num(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    private static String eur(double v) {
        if (v == 0) return ("0 " + PaisActual.moneda());
        double abs = Math.abs(v);
        if (abs >= 1_000_000) return String.format(java.util.Locale.GERMAN, ("%,.2fM " + PaisActual.moneda()), v / 1_000_000);
        if (abs >= 1_000)     return String.format(java.util.Locale.GERMAN, ("%,.1fK " + PaisActual.moneda()), v / 1_000);
        return String.format(java.util.Locale.GERMAN, ("%,.0f " + PaisActual.moneda()), v);
    }
    private static boolean isExpiringSoon(Map<String, Object> r, String col, int days) {
        String s = (String) r.get(col);
        if (s == null || s.isBlank()) return false;
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(s.substring(0, 10));
            long diff = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d);
            return diff >= 0 && diff <= days;
        } catch (Exception e) { return false; }
    }
    private static List<String> distinctValues(List<Map<String, Object>> rows, String col) {
        return rows.stream().map(r -> r.get(col)).filter(java.util.Objects::nonNull).map(Object::toString)
                .filter(s -> !s.isBlank()).distinct().sorted().toList();
    }

    @GetMapping("/{table}/new")
    public String formNew(@PathVariable String table, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (PANTALLA_PROPIA.containsKey(table)) return "redirect:" + PANTALLA_PROPIA.get(table) + "/nuevo";
        TableMeta meta = TABLES.get(table);
        if (meta == null) return "redirect:/dashboard";

        // Gate de plan: si es propiedad y ha alcanzado el límite, ir a upgrade
        if ("propiedades".equals(table)) {
            String orgId = SessionUtils.uid(session);
            if (!planService.canAddProperty(orgId)) {
                SessionUtils.flash(session, "Has alcanzado el límite de propiedades de tu plan. Mejora a un plan superior.");
                return "redirect:/upgrade";
            }
        }

        model.addAttribute("table", table);
        model.addAttribute("page", meta.title());
        model.addAttribute("fields", meta.fields());
        model.addAttribute("row", new HashMap<>());
        model.addAttribute("editing", false);
        loadAutocompleteOptions(meta, session, model);
        return "crud/form";
    }

    @GetMapping("/{table}/{id}/edit")
    public String formEdit(@PathVariable String table, @PathVariable String id,
                           HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (PANTALLA_PROPIA.containsKey(table)) return "redirect:" + PANTALLA_PROPIA.get(table) + "/" + id;
        TableMeta meta = TABLES.get(table);
        if (meta == null) return "redirect:/dashboard";
        String uid = SessionUtils.uid(session);
        var rows = supabase.findBy(table, Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/crud/" + table;
        model.addAttribute("table", table);
        model.addAttribute("page", meta.title());
        model.addAttribute("fields", meta.fields());
        model.addAttribute("row", rows.get(0));
        model.addAttribute("editing", true);
        loadAutocompleteOptions(meta, session, model);
        return "crud/form";
    }

    /**
     * Carga listas de opciones para autocompletar (datalist) en el formulario.
     * Si la tabla tiene un campo property_name → carga propiedades.
     * Si tiene tenant_name → carga inquilinos.
     * Si tiene client_name → carga inquilinos + leads.
     */
    private void loadAutocompleteOptions(TableMeta meta, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        boolean needsProperties = false, needsTenants = false, needsClients = false;
        for (Field f : meta.fields()) {
            String n = f.name();
            if ("property_name".equals(n)) needsProperties = true;
            if ("tenant_name".equals(n))   needsTenants = true;
            if ("client_name".equals(n))   needsClients = true;
        }

        if (needsProperties) {
            try {
                var props = supabase.query("propiedades", uid).stream()
                        .map(p -> String.valueOf(p.getOrDefault("name", "")))
                        .filter(s -> s != null && !s.isBlank() && !"null".equals(s))
                        .distinct().sorted().toList();
                model.addAttribute("autocompleteProperties", props);
            } catch (Exception e) {
                model.addAttribute("autocompleteProperties", java.util.Collections.emptyList());
            }
        }
        if (needsTenants) {
            try {
                var tenants = supabase.query("inquilinos", uid).stream()
                        .map(t -> String.valueOf(t.getOrDefault("name", "")))
                        .filter(s -> s != null && !s.isBlank() && !"null".equals(s))
                        .distinct().sorted().toList();
                model.addAttribute("autocompleteTenants", tenants);
            } catch (Exception e) {
                model.addAttribute("autocompleteTenants", java.util.Collections.emptyList());
            }
        }
        if (needsClients) {
            try {
                var inq = supabase.query("inquilinos", uid).stream()
                        .map(t -> String.valueOf(t.getOrDefault("name", "")));
                var leads = supabase.query("leads", uid).stream()
                        .map(t -> String.valueOf(t.getOrDefault("nombre", "")));
                var clients = java.util.stream.Stream.concat(inq, leads)
                        .filter(s -> s != null && !s.isBlank() && !"null".equals(s))
                        .distinct().sorted().toList();
                model.addAttribute("autocompleteClients", clients);
            } catch (Exception e) {
                model.addAttribute("autocompleteClients", java.util.Collections.emptyList());
            }
        }
    }

    @PostMapping("/{table}")
    public String save(@PathVariable String table,
                       HttpServletRequest request,
                       HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (PANTALLA_PROPIA.containsKey(table)) return "redirect:" + PANTALLA_PROPIA.get(table);
        TableMeta meta = TABLES.get(table);
        if (meta == null) return "redirect:/dashboard";
        String uid = SessionUtils.uid(session);
        String id  = request.getParameter("id");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        for (Field f : meta.fields()) {
            String raw = request.getParameter(f.name());
            if (raw == null) continue;
            // Campo vaciado: al editar se borra el valor (con null a secas se quedaba el antiguo)
            if (raw.isBlank() && f.type() != FieldType.BOOL) { data.put(f.name(), SupabaseService.VACIAR); continue; }
            switch (f.type()) {
                case NUM  -> data.put(f.name(), safeDouble(raw));
                case INT  -> data.put(f.name(), safeInt(raw));
                case BOOL -> data.put(f.name(), "on".equalsIgnoreCase(raw) || "true".equalsIgnoreCase(raw));
                case DATE -> data.put(f.name(), normalizeDate(raw));
                default   -> {
                    // proteccion defensiva: si el TEXT parece una fecha, normalizamos por si acaso
                    String maybeDate = looksLikeDate(raw) ? normalizeDate(raw) : raw;
                    data.put(f.name(), maybeDate);
                }
            }
        }

        if (id == null || id.isBlank()) {
            data.put("created_at", OffsetDateTime.now().toString());
            log.info("CRUD insert {} -> payload keys={}", table, data.keySet());
            Map<String, Object> saved = supabase.insert(table, data);
            if (saved == null || saved.isEmpty()) {
                String err = supabase.lastError();
                log.warn("CRUD insert {} FALLO. lastError={}", table, err);
                SessionUtils.flash(session, meta.title() + ": ERROR guardando. " +
                        (err != null ? err : "Revisa los logs del servidor."));
            } else {
                audit.registrar(request, session, "crear", table, String.valueOf(saved.get("id")),
                        meta.title() + ": registro creado", "info", AuditTrail.DATOS);
                SessionUtils.flash(session, meta.title() + ": registro creado");
            }
        } else {
            boolean ok = supabase.update(table, id, data, uid);
            if (!ok) {
                String err = supabase.lastError();
                SessionUtils.flash(session, meta.title() + ": ERROR actualizando. " +
                        (err != null ? err : "Revisa los logs del servidor."));
            } else {
                audit.registrar(request, session, "actualizar", table, id,
                        meta.title() + ": registro actualizado", "info", AuditTrail.DATOS);
                SessionUtils.flash(session, meta.title() + ": registro actualizado");
            }
        }
        return "redirect:/crud/" + table;
    }

    @PostMapping("/{table}/{id}/delete")
    public String delete(@PathVariable String table, @PathVariable String id,
                         HttpServletRequest request,
                         HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        TableMeta meta = TABLES.get(table);
        if (meta == null) return "redirect:/dashboard";
        String uid = SessionUtils.uid(session);
        // Copia de seguridad en la papelera (30 días) antes de borrar
        boolean enPapelera = trash.guardar(table, id, uid, SessionUtils.realUid(session), SessionUtils.name(session));
        boolean ok = supabase.delete(table, id, uid);
        if (ok) audit.registrar(request, session, "eliminar", table, id,
                meta.title() + ": registro eliminado" + (enPapelera ? " (recuperable 30 días en la papelera)" : ""),
                "warning", AuditTrail.DATOS);
        SessionUtils.flash(session, meta.title() + (enPapelera
                ? ": registro movido a la papelera. Puedes restaurarlo durante 30 días."
                : ": registro eliminado"));
        return "redirect:/crud/" + table;
    }

    // ─── meta ───
    private static Field[] fields(String... names) {
        Field[] out = new Field[names.length];
        for (int i = 0; i < names.length; i++) out[i] = Field.forName(names[i]);
        return out;
    }

    private static Double safeDouble(String s) {
        try { return Double.parseDouble(s.replace(',', '.')); } catch (Exception e) { return null; }
    }

    private static Integer safeInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) {
            // Aceptar decimales tipo "2.0" o "2,0" → truncar a entero
            try { return (int) Double.parseDouble(s.trim().replace(',', '.')); }
            catch (Exception e2) { return null; }
        }
    }

    /** Detecta si un string parece una fecha (cualquier formato comun). */
    private static boolean looksLikeDate(String s) {
        if (s == null || s.length() < 6) return false;
        return s.matches("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}$");
    }

    /**
     * Normaliza una fecha a formato ISO yyyy-MM-dd (lo que espera PostgreSQL).
     * Acepta: yyyy-MM-dd, dd-MM-yyyy, dd/MM/yyyy, dd.MM.yyyy, dd-MM-yy, etc.
     * Si ya esta en formato ISO o no se puede parsear, devuelve el original.
     */
    private static String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim();
        // Ya esta en ISO yyyy-MM-dd (lo que devuelve <input type="date">)
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return s.length() > 10 ? s.substring(0, 10) : s;
        // Separadores - / .
        String[] parts = s.split("[-/.]");
        if (parts.length != 3) return raw;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int day, month, year;
            if (parts[0].length() == 4) {                          // yyyy-MM-dd raro
                year = a; month = b; day = c;
            } else if (parts[2].length() == 4) {                   // dd-MM-yyyy (tipico ES)
                day = a; month = b; year = c;
            } else {                                               // dd-MM-yy
                day = a; month = b; year = c < 50 ? 2000 + c : 1900 + c;
            }
            if (month < 1 || month > 12 || day < 1 || day > 31) return raw;
            return String.format("%04d-%02d-%02d", year, month, day);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    public enum FieldType { TEXT, TEXTAREA, NUM, INT, DATE, TIME, BOOL }

    public record Field(String name, String label, FieldType type, String placeholder, String hint) {
        static Field forName(String name) {
            String label = guessLabel(name);
            FieldType type = guessType(name);
            String ph = PLACEHOLDERS.getOrDefault(name, defaultPlaceholder(name, type));
            String hint = HINTS.get(name);
            return new Field(name, label, type, ph, hint);
        }
        private static String guessLabel(String n) {
            String s = n.replace('_', ' ');
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        private static FieldType guessType(String n) {
            // Patrones de fecha (cubre _date, _start, _end, fecha_*, *_fecha, lease_*, signed_*, expiry_*, etc.)
            if (n.endsWith("_date") || n.equals("date") || n.equals("next_followup") || n.equals("last_contact")
                || n.equals("lease_start") || n.equals("lease_end")
                || n.equals("start_date") || n.equals("end_date")
                || n.startsWith("fecha_") || n.endsWith("_fecha")
                || n.equals("fecha") || n.equals("fecha_compra") || n.equals("fecha_inicio") || n.equals("fecha_vencimiento")
                || n.equals("ultima_actualizacion") || n.equals("proxima_actualizacion")
                || n.equals("cee_fecha_emision") || n.equals("cee_fecha_caducidad")
                || n.equals("hipoteca_proxima_revision"))
                return FieldType.DATE;
            if (n.equals("time")) return FieldType.TIME;
            if (n.equals("has_insurance") || n.equals("ipc_revision") || n.equals("deductible") || n.equals("auto_renew") || n.equals("publicada")) return FieldType.BOOL;
            if (n.equals("description") || n.equals("notes") || n.equals("notas") || n.equals("terms") || n.equals("cuerpo") || n.equals("feedback")) return FieldType.TEXTAREA;
            // Enteros (INTEGER en BBDD)
            if (n.equals("score") || n.equals("priority") || n.equals("year_built")
                || n.equals("reminder_days") || n.equals("file_size_kb")
                || n.equals("bedrooms") || n.equals("bathrooms")
                || n.equals("scoring_credit") || n.equals("dias_morosidad") || n.equals("impagos_count")
                || n.equals("visitas_count") || n.equals("consultas_count"))
                return FieldType.INT;
            // Decimales (NUMERIC en BBDD)
            if (n.equals("area_m2") || n.equals("amount")
                || n.equals("salary") || n.contains("price") || n.contains("rent") || n.contains("value")
                || n.contains("income") || n.contains("expenses") || n.contains("cost") || n.contains("rate")
                || n.equals("deposit") || n.equals("vat_pct") || n.equals("gross_yield") || n.equals("cap_rate")
                || n.equals("expected_irr") || n.equals("coverage") || n.equals("annual_cost")
                || n.equals("presupuesto") || n.equals("mortgage_payment"))
                return FieldType.NUM;
            return FieldType.TEXT;
        }
        private static String defaultPlaceholder(String n, FieldType t) {
            return switch (t) {
                case NUM  -> "0.00";
                case INT  -> "0";
                case DATE -> "AAAA-MM-DD";
                case TIME -> "HH:MM";
                case TEXTAREA -> "Escribe aqui...";
                default   -> "";
            };
        }
    }

    private record TableMeta(String title, Field[] fields) {
        static TableMeta of(String t, Field[] f) { return new TableMeta(t, f); }
    }
}
