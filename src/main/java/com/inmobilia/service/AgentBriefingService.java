package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 🤖 AGENTE IA AUTÓNOMO
 *
 * Actúa como un asistente que trabaja solo: cada mañana analiza TODA la cartera
 * de la agencia, detecta lo urgente, CREA TAREAS automáticamente y envía un
 * briefing diario por email + notificación.
 *
 * Cosas que vigila:
 *   - Leads HOT/calientes sin contactar
 *   - Visitas de hoy y mañana
 *   - Contratos que vencen pronto
 *   - Facturas impagadas
 *   - Propiedades estancadas (muchos días sin visitas)
 *   - Tareas vencidas
 *
 * Si hay clave de OpenAI, redacta el briefing en lenguaje natural; si no,
 * genera un resumen estructurado igualmente útil (degradación elegante).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentBriefingService {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final NotificationService notif;
    private final AIService aiService;
    private final com.inmobilia.service.PaisService paises;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    /** Cron: cada día laborable a las 8:00 genera el briefing de cada agencia. */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "${app.zona-horaria:America/Mexico_City}")
    public void cronDiario() {
        log.info("[AGENTE IA] Generando briefings diarios...");
        try {
            // Una agencia = un usuario admin (org propietaria)
            List<Map<String, Object>> admins = supabase.selectFilter(
                    "users", "active=eq.true&role=eq.admin&limit=500");
            int n = 0;
            for (Map<String, Object> admin : admins) {
                try {
                    String uid = String.valueOf(admin.get("id"));
                    paises.usar(uid);
                    Briefing b = analizar(uid);
                    if (b.tieneAlgo()) {
                        ejecutarAcciones(uid, b);
                        enviarEmail(admin, b);
                        notif.create(uid, "🤖 Briefing del día",
                                b.resumenCorto(), "/agente", "media");
                        n++;
                    }
                } catch (Exception e) {
                    log.warn("[AGENTE IA] Error con admin {}: {}", admin.get("id"), e.getMessage());
                }
            }
            log.info("[AGENTE IA] {} briefings enviados", n);
        } catch (Exception e) {
            log.error("[AGENTE IA] Error general: {}", e.getMessage());
        }
    }

    /** Análisis bajo demanda (para el botón "Generar ahora" del panel). */
    public Briefing generarAhora(String uid) {
        return analizar(uid);
    }

    /** Cron semanal: lunes 9:00 → informe estratégico de la semana anterior. */
    @Scheduled(cron = "0 0 9 * * MON", zone = "${app.zona-horaria:America/Mexico_City}")
    public void cronSemanal() {
        log.info("[AGENTE IA] Generando informes semanales...");
        try {
            List<Map<String, Object>> admins = supabase.selectFilter(
                    "users", "active=eq.true&role=eq.admin&limit=500");
            for (Map<String, Object> admin : admins) {
                try {
                    String uid = String.valueOf(admin.get("id"));
                    paises.usar(uid);
                    String html = informeSemanal(uid);
                    if (html != null) {
                        String email = String.valueOf(admin.get("email"));
                        if (email != null && !email.isBlank() && !"null".equals(email)) {
                            emailService.send(null, email, "📊 Tu informe semanal · InmobilIA Pro", html);
                        }
                        notif.create(uid, "📊 Informe semanal listo",
                                "Resumen y recomendaciones de la semana", "/agente", "media");
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("[AGENTE IA] Error informe semanal: {}", e.getMessage());
        }
    }

    /** Calcula KPIs de los últimos 7 días + recomendaciones IA. */
    public String informeSemanal(String uid) {
        LocalDate hoy = LocalDate.now();
        String desde = hoy.minusDays(7).toString();

        int leadsNuevos = contar("leads", uid, "created_at=gte." + desde);
        int visitasSemana = contar("visitas", uid, "fecha_inicio=gte." + desde);
        int contratosSemana = contar("contratos", uid, "created_at=gte." + desde);
        int propsNuevas = contar("propiedades", uid, "created_at=gte." + desde);

        // Conversión visitas→contratos (aprox)
        double conv = visitasSemana > 0 ? (contratosSemana * 100.0 / visitasSemana) : 0;

        String datos = String.format(
            "Leads nuevos: %d\nVisitas: %d\nContratos: %d\nPropiedades captadas: %d\nConversión visita→contrato: %.0f%%",
            leadsNuevos, visitasSemana, contratosSemana, propsNuevas, conv);

        String recomendaciones;
        try {
            recomendaciones = aiService.call(null,
                "KPIs de la última semana de una inmobiliaria:\n" + datos +
                "\n\nComo jefe de ventas, da 3 recomendaciones concretas para mejorar la próxima semana. Máx 100 palabras, español.",
                "Eres director comercial de una inmobiliaria. Directo y accionable.", 250, uid);
        } catch (Exception e) {
            recomendaciones = null;
        }

        StringBuilder h = new StringBuilder();
        h.append("<div style='font-family:sans-serif;max-width:640px;margin:auto'>");
        h.append("<h2>📊 Tu semana en InmobilIA Pro</h2>");
        h.append("<table style='width:100%;border-collapse:collapse;margin:16px 0'>");
        fila(h, "📋 Leads nuevos", leadsNuevos);
        fila(h, "📅 Visitas", visitasSemana);
        fila(h, "✍️ Contratos", contratosSemana);
        fila(h, "🏠 Propiedades captadas", propsNuevas);
        fila(h, "📈 Conversión visita→contrato", Math.round(conv) + "%");
        h.append("</table>");
        if (recomendaciones != null && !recomendaciones.isBlank()) {
            h.append("<h3>💡 Recomendaciones de tu director IA</h3>");
            h.append("<div style='background:#eff6ff;border-left:4px solid #3b82f6;padding:14px;border-radius:8px'>")
             .append(recomendaciones.replace("\n", "<br>")).append("</div>");
        }
        h.append("<p style='text-align:center;margin-top:20px'><a href='").append(BASE_URL)
         .append("/agente' style='display:inline-block;padding:12px 24px;background:#0f172a;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold'>Abrir panel →</a></p>");
        h.append("</div>");
        return h.toString();
    }

    private int contar(String tabla, String uid, String filtroExtra) {
        try {
            return supabase.selectFilter(tabla, "user_id=eq." + uid + "&" + filtroExtra + "&limit=1000").size();
        } catch (Exception e) { return 0; }
    }
    private void fila(StringBuilder h, String label, Object valor) {
        h.append("<tr><td style='padding:8px;border-bottom:1px solid #e2e8f0'>").append(label)
         .append("</td><td style='padding:8px;border-bottom:1px solid #e2e8f0;text-align:right;font-weight:bold;font-size:18px'>")
         .append(valor).append("</td></tr>");
    }

    // ════════════════════════════════════════════════════════════════
    //  ANÁLISIS DE LA CARTERA
    // ════════════════════════════════════════════════════════════════
    public Briefing analizar(String uid) {
        Briefing b = new Briefing();
        LocalDate hoy = LocalDate.now();

        // ─── Leads HOT/calientes sin contactar ───
        try {
            List<Map<String, Object>> leads = supabase.selectFilter("leads",
                    "user_id=eq." + uid + "&etapa=in.(Nuevo,Contactado)&order=score.desc&limit=100");
            for (Map<String, Object> l : leads) {
                String temp = String.valueOf(l.getOrDefault("temperatura", ""));
                int score = parseInt(l.get("score"));
                if ("hot".equals(temp) || "caliente".equals(temp) || score >= 60) {
                    long diasSinTocar = diasDesde(l.get("score_actualizado_at"), hoy);
                    if ("Nuevo".equals(l.get("etapa")) || diasSinTocar >= 2) {
                        b.leadsCalientes.add(l);
                    }
                }
            }
        } catch (Exception ignored) {}

        // ─── Visitas hoy y mañana (de AMBAS tablas: visitas + reservas) ───
        // La app guarda visitas en dos sitios según el flujo (VisitsController→visitas,
        // calendario/reservas→reservas). Miramos las dos para no perder ninguna.
        try {
            List<Map<String, Object>> visitas = supabase.selectFilter("visitas",
                    "user_id=eq." + uid + "&estado=in.(programada,confirmada)&order=fecha_inicio.asc&limit=50");
            for (Map<String, Object> v : visitas) {
                LocalDate f = parseFecha(v.get("fecha_inicio"));
                if (f != null && (f.equals(hoy) || f.equals(hoy.plusDays(1)))) {
                    b.visitasProximas.add(v);
                }
            }
        } catch (Exception ignored) {}
        try {
            List<Map<String, Object>> reservas = supabase.selectFilter("reservas",
                    "user_id=eq." + uid + "&order=date.asc&limit=100");
            for (Map<String, Object> r : reservas) {
                String est = String.valueOf(r.getOrDefault("status", ""));
                if ("Cancelada".equalsIgnoreCase(est) || "Realizada".equalsIgnoreCase(est)) continue;
                LocalDate f = parseFecha(r.get("date"));
                if (f != null && (f.equals(hoy) || f.equals(hoy.plusDays(1)))) {
                    // Normalizamos a la forma que espera planDelDia (visitante_nombre / fecha_inicio)
                    Map<String, Object> v = new LinkedHashMap<>(r);
                    v.put("visitante_nombre", r.getOrDefault("client_name", r.getOrDefault("property_name", "un cliente")));
                    v.put("fecha_inicio", String.valueOf(r.get("date")) + " " + String.valueOf(r.getOrDefault("time", "")).trim());
                    b.visitasProximas.add(v);
                }
            }
        } catch (Exception ignored) {}

        // ─── Contratos que vencen en ≤30 días ───
        try {
            List<Map<String, Object>> contratos = supabase.selectFilter("contratos",
                    "user_id=eq." + uid + "&estado=eq.activo&limit=200");
            for (Map<String, Object> c : contratos) {
                LocalDate fin = parseFecha(c.get("fecha_fin"));
                if (fin != null) {
                    long dias = ChronoUnit.DAYS.between(hoy, fin);
                    if (dias >= 0 && dias <= 30) b.contratosPorVencer.add(c);
                }
            }
        } catch (Exception ignored) {}

        // ─── Facturas impagadas / morosas ───
        try {
            List<Map<String, Object>> facturas = supabase.selectFilter("invoices",
                    "user_id=eq." + uid + "&limit=300");
            for (Map<String, Object> f : facturas) {
                String est = String.valueOf(f.getOrDefault("status", f.getOrDefault("estado", "")));
                if (est.toLowerCase().contains("impag") || est.toLowerCase().contains("moros")
                        || est.toLowerCase().contains("pendiente")) {
                    b.facturasImpagadas.add(f);
                }
            }
        } catch (Exception ignored) {}

        // ─── Propiedades estancadas: activas y creadas hace > 45 días ───
        try {
            List<Map<String, Object>> props = supabase.selectFilter("propiedades",
                    "user_id=eq." + uid + "&limit=300");
            for (Map<String, Object> p : props) {
                if ("Reservado".equalsIgnoreCase(String.valueOf(p.get("status")))) continue;
                long dias = diasDesde(p.get("created_at"), hoy);
                if (dias >= 45) b.propiedadesEstancadas.add(p);
            }
        } catch (Exception ignored) {}

        // ─── Tareas vencidas ───
        try {
            List<Map<String, Object>> tareas = supabase.selectFilter("tareas",
                    "user_id=eq." + uid + "&estado=eq.pendiente&limit=200");
            for (Map<String, Object> t : tareas) {
                LocalDate lim = parseFecha(t.get("fecha_limite"));
                if (lim != null && lim.isBefore(hoy)) b.tareasVencidas.add(t);
            }
        } catch (Exception ignored) {}

        // ─── Sugerencias de bajada de precio (propiedades muy estancadas) ───
        for (Map<String, Object> p : b.propiedadesEstancadas) {
            long dias = diasDesde(p.get("created_at"), hoy);
            int pct = dias >= 120 ? 10 : dias >= 90 ? 7 : dias >= 60 ? 5 : 3;
            double precio = parseDouble(p.get("current_value"));
            if (precio <= 0) precio = parseDouble(p.get("purchase_price"));
            if (precio > 0) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", p.get("id"));
                s.put("direccion", p.getOrDefault("name", p.getOrDefault("address", "?")));
                s.put("dias", dias);
                s.put("precio_actual", precio);
                s.put("pct", pct);
                s.put("precio_sugerido", Math.round(precio * (100 - pct) / 100.0));
                b.sugerenciasPrecio.add(s);
            }
        }

        // ─── Leads en riesgo de fuga (calientes/tibios sin actividad >7 días) ───
        try {
            List<Map<String, Object>> leads = supabase.selectFilter("leads",
                    "user_id=eq." + uid + "&etapa=in.(Contactado,Visita,Oferta)&order=score.desc&limit=100");
            for (Map<String, Object> l : leads) {
                long dias = diasDesde(l.get("score_actualizado_at"), hoy);
                int score = parseInt(l.get("score"));
                if (score >= 40 && dias >= 7) b.leadsEnRiesgo.add(l);
            }
        } catch (Exception ignored) {}

        // ─── Match proactivo: propiedades nuevas (≤7 días) × leads que encajan ───
        try {
            String hace7 = hoy.minusDays(7).toString();
            List<Map<String, Object>> propsNuevas = supabase.selectFilter("propiedades",
                    "user_id=eq." + uid + "&created_at=gte." + hace7 + "&limit=30");
            if (!propsNuevas.isEmpty()) {
                List<Map<String, Object>> leadsActivos = supabase.selectFilter("leads",
                        "user_id=eq." + uid + "&limit=200");
                leadsActivos.removeIf(l -> {
                    String et = String.valueOf(l.getOrDefault("etapa", ""));
                    return "Perdido".equalsIgnoreCase(et) || "Firmado".equalsIgnoreCase(et) || "Cerrado".equalsIgnoreCase(et);
                });
                for (Map<String, Object> prop : propsNuevas) {
                    double precio = parseDouble(prop.get("current_value"));
                    if (precio <= 0) precio = parseDouble(prop.get("purchase_price"));
                    String tipoP = lower(prop.get("type"));
                    String muniP = lower(prop.get("city"));
                    List<Map<String, Object>> encajan = new ArrayList<>();
                    for (Map<String, Object> l : leadsActivos) {
                        double presup = parseDouble(l.get("presupuesto"));
                        boolean okPrecio = presup <= 0 || precio <= 0 || precio <= presup * 1.05;
                        boolean okTipo = tipoP.isEmpty() || lower(l.get("tipo_activo")).isEmpty() || lower(l.get("tipo_activo")).contains(tipoP) || tipoP.contains(lower(l.get("tipo_activo")));
                        boolean okZona = muniP.isEmpty() || lower(l.get("zona")).isEmpty() || lower(l.get("zona")).contains(muniP) || muniP.contains(lower(l.get("zona")));
                        if (okPrecio && okTipo && okZona) encajan.add(l);
                        if (encajan.size() >= 5) break;
                    }
                    if (!encajan.isEmpty()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("propiedad_id", prop.get("id"));
                        m.put("propiedad", prop.getOrDefault("name", prop.getOrDefault("address", "?")));
                        m.put("precio", precio);
                        m.put("num_leads", encajan.size());
                        m.put("leads", encajan);
                        b.matchesProactivos.add(m);
                    }
                }
            }
        } catch (Exception ignored) {}

        // ─── Oportunidades dormidas: contratos cerrados hace ≥3 años ───
        try {
            String hace3a = hoy.minusYears(3).toString();
            List<Map<String, Object>> antiguos = supabase.selectFilter("contratos",
                    "user_id=eq." + uid + "&created_at=lte." + hace3a + "&limit=50");
            for (Map<String, Object> c : antiguos) {
                b.oportunidadesDormidas.add(c);
                if (b.oportunidadesDormidas.size() >= 10) break;
            }
        } catch (Exception ignored) {}

        // ─── Previsión de cierres del mes (ofertas activas × probabilidad) ───
        try {
            List<Map<String, Object>> ofertas = supabase.selectFilter("ofertas",
                    "user_id=eq." + uid + "&estado=in.(nueva,en_negociacion,contraoferta,aceptada)&limit=200");
            double forecast = 0;
            for (Map<String, Object> o : ofertas) {
                double importe = parseDouble(o.get("importe"));
                String est = String.valueOf(o.getOrDefault("estado", ""));
                double prob = switch (est) {
                    case "aceptada" -> 0.9;
                    case "contraoferta" -> 0.5;
                    case "en_negociacion" -> 0.35;
                    default -> 0.15;
                };
                forecast += importe * prob;
            }
            b.forecastMes = Math.round(forecast);
            b.ofertasActivas = ofertas.size();
        } catch (Exception ignored) {}

        return b;
    }

    // ════════════════════════════════════════════════════════════════
    //  ACCIONES AUTOMÁTICAS (el agente "hace cosas", no solo informa)
    // ════════════════════════════════════════════════════════════════
    private void ejecutarAcciones(String uid, Briefing b) {
        // Crear tarea por cada lead caliente sin contactar (máx 10 para no saturar)
        int creadas = 0;
        for (Map<String, Object> l : b.leadsCalientes) {
            if (creadas >= 10) break;
            try {
                // ¿ya existe tarea para este lead hoy? (evitar duplicar)
                Map<String, Object> tarea = new LinkedHashMap<>();
                tarea.put("user_id", uid);
                tarea.put("titulo", "📞 Contactar a " + l.getOrDefault("nombre", "lead") + " (caliente)");
                tarea.put("descripcion", "Generada por el Agente IA. Score " + l.getOrDefault("score", "?") +
                        ". " + l.getOrDefault("siguiente_accion", ""));
                tarea.put("prioridad", "alta");
                tarea.put("estado", "pendiente");
                tarea.put("lead_id", l.get("id"));
                if (l.get("asignado_a") != null) tarea.put("asignado_a", l.get("asignado_a"));
                tarea.put("fecha_limite", LocalDate.now().toString());
                tarea.put("created_at", OffsetDateTime.now().toString());
                supabase.insert("tareas", tarea);
                creadas++;
            } catch (Exception ignored) {}
        }
        b.tareasCreadas = creadas;
    }

    // ════════════════════════════════════════════════════════════════
    //  EMAIL DEL BRIEFING
    // ════════════════════════════════════════════════════════════════
    private void enviarEmail(Map<String, Object> admin, Briefing b) {
        String email = String.valueOf(admin.get("email"));
        if (email == null || email.isBlank() || "null".equals(email)) return;

        String cuerpo = b.htmlNarrado(narrarConIA(b, String.valueOf(admin.get("id"))));
        try {
            emailService.send(null, email, "🤖 Tu briefing del día · InmobilIA Pro", cuerpo);
        } catch (Exception ignored) {}
    }

    /** Narración con IA (si hay clave); si no, devuelve null y se usa el resumen estructurado. */
    private String narrarConIA(Briefing b, String uid) {
        try {
            String datos = b.resumenParaIA();
            if (datos.isBlank()) return null;
            String prompt = "Datos de la cartera de una agencia inmobiliaria hoy:\n" + datos +
                    "\n\nEscribe un briefing matutino motivador y MUY accionable (máx 120 palabras) " +
                    "para el agente, priorizando lo más urgente. Tono directo y profesional, en español.";
            return aiService.call(null, prompt,
                    "Eres el jefe de ventas de una inmobiliaria. Conciso y orientado a la acción.", 250, uid);
        } catch (Exception e) {
            return null;  // degradación elegante
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════
    private static long diasDesde(Object fecha, LocalDate hoy) {
        LocalDate f = parseFecha(fecha);
        return f == null ? 0 : ChronoUnit.DAYS.between(f, hoy);
    }
    private static LocalDate parseFecha(Object o) {
        if (o == null) return null;
        try { return LocalDate.parse(String.valueOf(o).substring(0, 10)); } catch (Exception e) { return null; }
    }
    private static int parseInt(Object o) {
        try { return o == null ? 0 : Integer.parseInt(String.valueOf(o).split("\\.")[0]); } catch (Exception e) { return 0; }
    }
    private static double parseDouble(Object o) {
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
    private static String lower(Object o) {
        return o == null ? "" : String.valueOf(o).toLowerCase().trim();
    }

    // ════════════════════════════════════════════════════════════════
    //  Modelo del briefing
    // ════════════════════════════════════════════════════════════════
    public static class Briefing {
        public List<Map<String, Object>> leadsCalientes = new ArrayList<>();
        public List<Map<String, Object>> visitasProximas = new ArrayList<>();
        public List<Map<String, Object>> contratosPorVencer = new ArrayList<>();
        public List<Map<String, Object>> facturasImpagadas = new ArrayList<>();
        public List<Map<String, Object>> propiedadesEstancadas = new ArrayList<>();
        public List<Map<String, Object>> tareasVencidas = new ArrayList<>();
        public List<Map<String, Object>> sugerenciasPrecio = new ArrayList<>();
        public List<Map<String, Object>> leadsEnRiesgo = new ArrayList<>();
        public List<Map<String, Object>> matchesProactivos = new ArrayList<>();
        public List<Map<String, Object>> oportunidadesDormidas = new ArrayList<>();
        public long forecastMes = 0;
        public int ofertasActivas = 0;
        public int tareasCreadas = 0;

        public boolean tieneAlgo() {
            return !leadsCalientes.isEmpty() || !visitasProximas.isEmpty()
                || !contratosPorVencer.isEmpty() || !facturasImpagadas.isEmpty()
                || !propiedadesEstancadas.isEmpty() || !tareasVencidas.isEmpty()
                || !leadsEnRiesgo.isEmpty();
        }

        public String resumenCorto() {
            return leadsCalientes.size() + " leads calientes · " + visitasProximas.size() +
                   " visitas · " + contratosPorVencer.size() + " contratos por vencer";
        }

        /**
         * 📋 Plan del día: las acciones concretas de la jornada ordenadas por impacto.
         * Prioridad: agenda de hoy > dinero en juego > leads que se enfrían > cartera.
         */
        public List<Map<String, Object>> planDelDia() {
            List<Map<String, Object>> plan = new ArrayList<>();
            for (Map<String, Object> v : visitasProximas) {
                plan.add(paso("📅", "Confirma la visita de " + v.getOrDefault("visitante_nombre", "un cliente"),
                        String.valueOf(v.getOrDefault("fecha_inicio", "")).replace("T", " a las "), "/visitas"));
                if (plan.size() >= 3) break;
            }
            int n = 0;
            for (Map<String, Object> l : leadsCalientes) {
                plan.add(paso("🔥", "Llama a " + l.getOrDefault("nombre", "un lead caliente"),
                        "score " + l.getOrDefault("score", "?") + " · aún sin contactar", "/agente"));
                if (++n >= 3) break;
            }
            for (Map<String, Object> m : matchesProactivos) {
                plan.add(paso("🎯", "Avisa a los " + m.get("num_leads") + " leads que encajan con " + m.get("propiedad"),
                        "propiedad nueva: el primero en avisar gana", "/agente"));
                if (plan.size() >= 8) break;
            }
            n = 0;
            for (Map<String, Object> l : leadsEnRiesgo) {
                plan.add(paso("🚨", "Reactiva a " + l.getOrDefault("nombre", "un lead"),
                        "más de 7 días sin actividad, se enfría", "/agente"));
                if (++n >= 2) break;
            }
            if (!facturasImpagadas.isEmpty()) {
                plan.add(paso("⚠️", "Reclama " + facturasImpagadas.size() + " factura(s) impagada(s)",
                        "dinero pendiente de cobro", "/agente"));
            }
            if (!contratosPorVencer.isEmpty()) {
                plan.add(paso("📄", "Prepara la renovación de " + contratosPorVencer.size() + " contrato(s)",
                        "vencen en menos de 30 días", "/agente"));
            }
            n = 0;
            for (Map<String, Object> s : sugerenciasPrecio) {
                plan.add(paso("💸", "Propón bajar el precio de " + s.get("direccion"),
                        s.get("dias") + " días en mercado → " + String.format("%,d", ((Number) s.get("precio_sugerido")).longValue()) + (" " + PaisActual.moneda()), "/agente"));
                if (++n >= 2) break;
            }
            if (!tareasVencidas.isEmpty()) {
                plan.add(paso("⏰", "Ponte al día con " + tareasVencidas.size() + " tarea(s) vencida(s)", "", "/agente"));
            }
            return plan.size() > 10 ? plan.subList(0, 10) : plan;
        }

        private static Map<String, Object> paso(String icono, String accion, String motivo, String link) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("icono", icono);
            p.put("accion", accion);
            p.put("motivo", motivo);
            p.put("link", link);
            return p;
        }

        public String resumenParaIA() {
            StringBuilder s = new StringBuilder();
            if (!leadsCalientes.isEmpty()) s.append("- ").append(leadsCalientes.size()).append(" leads calientes sin contactar\n");
            if (!visitasProximas.isEmpty()) s.append("- ").append(visitasProximas.size()).append(" visitas hoy/mañana\n");
            if (!contratosPorVencer.isEmpty()) s.append("- ").append(contratosPorVencer.size()).append(" contratos vencen en 30 días\n");
            if (!facturasImpagadas.isEmpty()) s.append("- ").append(facturasImpagadas.size()).append(" facturas impagadas\n");
            if (!propiedadesEstancadas.isEmpty()) s.append("- ").append(propiedadesEstancadas.size()).append(" propiedades estancadas (>45 días)\n");
            if (!leadsEnRiesgo.isEmpty()) s.append("- ").append(leadsEnRiesgo.size()).append(" leads en riesgo de fuga (sin actividad >7 días)\n");
            if (!matchesProactivos.isEmpty()) s.append("- ").append(matchesProactivos.size()).append(" propiedades nuevas con leads compatibles esperando aviso\n");
            if (!oportunidadesDormidas.isEmpty()) s.append("- ").append(oportunidadesDormidas.size()).append(" clientes antiguos (3+ años) para reactivar\n");
            if (!tareasVencidas.isEmpty()) s.append("- ").append(tareasVencidas.size()).append(" tareas vencidas\n");
            if (forecastMes > 0) s.append("- Previsión de cierres este mes: ").append(forecastMes).append((" " + PaisActual.moneda() + " (")).append(ofertasActivas).append(" ofertas activas)\n");
            return s.toString();
        }

        public String htmlNarrado(String narracionIA) {
            StringBuilder h = new StringBuilder();
            h.append("<div style='font-family:sans-serif;max-width:640px;margin:auto'>");
            h.append("<h2>🤖 Briefing del día</h2>");
            if (narracionIA != null && !narracionIA.isBlank()) {
                h.append("<div style='background:#eff6ff;border-left:4px solid #3b82f6;padding:14px;border-radius:8px;margin:16px 0'>")
                 .append(narracionIA.replace("\n", "<br>")).append("</div>");
            }
            // 📋 Plan del día (acciones ordenadas por impacto)
            List<Map<String, Object>> plan = planDelDia();
            if (!plan.isEmpty()) {
                h.append("<h3 style='margin-top:18px'>📋 Tu plan del día</h3><ol style='padding-left:20px'>");
                for (Map<String, Object> p : plan) {
                    h.append("<li style='margin:6px 0'>").append(p.get("icono")).append(" <strong>")
                     .append(p.get("accion")).append("</strong>");
                    if (p.get("motivo") != null && !String.valueOf(p.get("motivo")).isBlank()) {
                        h.append(" <span style='color:#64748b;font-size:13px'>— ").append(p.get("motivo")).append("</span>");
                    }
                    h.append("</li>");
                }
                h.append("</ol>");
            }
            if (forecastMes > 0) {
                h.append("<div style='background:#f0fdf4;border:1px solid #86efac;padding:14px;border-radius:8px;margin:16px 0'>")
                 .append("💰 <strong>Previsión de cierres este mes:</strong> ")
                 .append(String.format("%,d", forecastMes)).append((" " + PaisActual.moneda() + " "))
                 .append("<span style='color:#64748b'>(").append(ofertasActivas).append(" ofertas activas, ponderadas por probabilidad)</span></div>");
            }
            // Match proactivo (formato propio)
            if (!matchesProactivos.isEmpty()) {
                h.append("<h3 style='margin-top:18px'>🎯 Propiedades nuevas con leads que encajan (").append(matchesProactivos.size()).append(")</h3><ul>");
                for (Map<String, Object> m : matchesProactivos) {
                    h.append("<li><strong>").append(m.get("propiedad")).append("</strong> → ")
                     .append(m.get("num_leads")).append(" leads compatibles. ¡Avísales!</li>");
                }
                h.append("</ul>");
            }
            bloque(h, "🔥 Leads calientes sin contactar", leadsCalientes, "nombre");
            bloque(h, "🚨 Leads en riesgo de fuga (>7 días sin actividad)", leadsEnRiesgo, "nombre");
            bloque(h, "♻️ Oportunidades dormidas (clientes de hace 3+ años)", oportunidadesDormidas, "numero");
            bloque(h, "📅 Visitas hoy / mañana", visitasProximas, "visitante_nombre");
            bloque(h, "📄 Contratos que vencen pronto", contratosPorVencer, "numero");
            bloque(h, "⚠️ Facturas impagadas", facturasImpagadas, "numero");
            bloque(h, "🏚️ Propiedades estancadas (>45 días)", propiedadesEstancadas, "name");
            // Sugerencias de precio (formato propio)
            if (!sugerenciasPrecio.isEmpty()) {
                h.append("<h3 style='margin-top:18px'>💸 Sugerencias de bajada de precio (").append(sugerenciasPrecio.size()).append(")</h3><ul>");
                int n = 0;
                for (Map<String, Object> s : sugerenciasPrecio) {
                    if (n++ >= 8) break;
                    h.append("<li>").append(s.get("direccion"))
                     .append(" — lleva ").append(s.get("dias")).append(" días → bajar ")
                     .append(s.get("pct")).append("% a <strong>").append(String.format("%,d", ((Number)s.get("precio_sugerido")).longValue())).append((" " + PaisActual.moneda() + "</strong></li>"));
                }
                h.append("</ul>");
            }
            bloque(h, "⏰ Tareas vencidas", tareasVencidas, "titulo");
            if (tareasCreadas > 0) {
                h.append("<p style='background:#ecfdf5;padding:10px;border-radius:8px'>✅ He creado automáticamente <strong>")
                 .append(tareasCreadas).append(" tareas</strong> de contacto para los leads calientes.</p>");
            }
            h.append("<p style='text-align:center;margin-top:20px'>")
             .append("<a href='").append(BASE_URL).append("/agente' style='display:inline-block;padding:12px 24px;background:#0f172a;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold'>Abrir panel del agente →</a></p>");
            h.append("<p style='font-size:11px;color:#94a3b8'>Generado por el Agente IA de InmobilIA Pro.</p>");
            h.append("</div>");
            return h.toString();
        }

        private void bloque(StringBuilder h, String titulo, List<Map<String, Object>> lista, String campo) {
            if (lista.isEmpty()) return;
            h.append("<h3 style='margin-top:18px'>").append(titulo).append(" (").append(lista.size()).append(")</h3><ul>");
            int n = 0;
            for (Map<String, Object> item : lista) {
                if (n++ >= 8) { h.append("<li>…y ").append(lista.size() - 8).append(" más</li>"); break; }
                h.append("<li>").append(item.getOrDefault(campo, "—")).append("</li>");
            }
            h.append("</ul>");
        }
    }
}
