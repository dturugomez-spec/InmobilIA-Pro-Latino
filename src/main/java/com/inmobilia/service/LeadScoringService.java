package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Calcula el lead score 0-100 según múltiples factores ponderados:
 *
 *  📋 Completitud del perfil:    20 pts (datos, presupuesto, zona definida)
 *  💰 Calidad económica:          20 pts (presupuesto alto + financiación clara)
 *  ⏰ Urgencia:                   15 pts (plazo corto, ya buscando activamente)
 *  📞 Engagement (interacciones): 25 pts (visitas web, emails abiertos, llamadas)
 *  🤝 Confianza:                  10 pts (identificación, contrato firma, depósito)
 *  ⏱️ Antigüedad/recency:         10 pts (más reciente = más cálido)
 *
 * Temperatura:
 *  - hot (80+):     contactar inmediato
 *  - caliente (60+): este mismo día
 *  - tibio (40+):    esta semana
 *  - frio (<40):     campaña drip / no prioridad
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadScoringService {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final NotificationService notif;

    /** Recalcula el score de un lead. */
    public Map<String, Object> calcularScore(String leadId) {
        List<Map<String, Object>> leads = supabase.selectFilter("leads", "id=eq." + leadId);
        if (leads.isEmpty()) return Map.of("score", 0);
        Map<String, Object> lead = leads.get(0);

        int score = 0;
        Map<String, Integer> desglose = new LinkedHashMap<>();

        // 1) Completitud (20 pts)
        int completitud = 0;
        if (notBlank(lead.get("nombre"))) completitud += 3;
        if (notBlank(lead.get("email"))) completitud += 3;
        if (notBlank(lead.get("telefono"))) completitud += 3;
        if (notBlank(lead.get("zona"))) completitud += 2;
        if (lead.get("presupuesto") != null) completitud += 4;
        if (notBlank(lead.get("interes")) || notBlank(lead.get("tipo_activo"))) completitud += 2;
        if (notBlank(lead.get("tipo_activo"))) completitud += 1;
        if (notBlank(lead.get("notas"))) completitud += 2;
        completitud = Math.min(20, completitud);
        desglose.put("completitud", completitud);
        score += completitud;

        // 2) Calidad económica (20 pts)
        int economico = 0;
        double presup = nd(lead.get("presupuesto"));
        if (presup >= 500000) economico += 15;
        else if (presup >= 300000) economico += 12;
        else if (presup >= 200000) economico += 10;
        else if (presup >= 100000) economico += 6;
        else if (presup > 0) economico += 3;

        // Financiación: si dice "al contado" o "ya tengo hipoteca aprobada"
        String notas = String.valueOf(lead.getOrDefault("notas", "")).toLowerCase();
        if (notas.contains("contado") || notas.contains("efectivo")) economico += 5;
        else if (notas.contains("hipoteca aprobada") || notas.contains("preconcedida")) economico += 3;
        economico = Math.min(20, economico);
        desglose.put("economico", economico);
        score += economico;

        // 3) Urgencia (15 pts)
        int urgencia = 0;
        String plazo = String.valueOf(lead.getOrDefault("plazo_decision", "")).toLowerCase();
        if (plazo.contains("inmediato") || plazo.contains("urgente") || plazo.contains("1 mes")) urgencia += 15;
        else if (plazo.contains("3 mes") || plazo.contains("trimestre")) urgencia += 10;
        else if (plazo.contains("6 mes")) urgencia += 5;
        else if (notas.contains("urgente") || notas.contains("ya")) urgencia += 8;
        desglose.put("urgencia", urgencia);
        score += urgencia;

        // 4) Engagement (25 pts) — basado en interacciones
        List<Map<String, Object>> ints = supabase.selectFilter(
                "lead_interacciones",
                "lead_id=eq." + leadId + "&order=created_at.desc&limit=50");
        int engagement = 0;
        for (Map<String, Object> i : ints) {
            String tipo = String.valueOf(i.get("tipo"));
            switch (tipo) {
                case "visita_realizada": engagement += 8; break;
                case "llamada":          engagement += 5; break;
                case "whatsapp":         engagement += 3; break;
                case "email_click":      engagement += 2; break;
                case "email_abierto":    engagement += 1; break;
                case "web_visita":       engagement += 1; break;
                case "formulario_extra": engagement += 4; break;
            }
        }
        // Bonus si hay actividad muy reciente (últimas 24h)
        if (!ints.isEmpty()) {
            try {
                OffsetDateTime ultima = OffsetDateTime.parse(String.valueOf(ints.get(0).get("created_at")));
                long horas = ChronoUnit.HOURS.between(ultima, OffsetDateTime.now());
                if (horas < 24) engagement += 3;
                else if (horas < 72) engagement += 1;
            } catch (Exception ignored) {}
        }
        engagement = Math.min(25, engagement);
        desglose.put("engagement", engagement);
        score += engagement;

        // 5) Confianza (10 pts)
        int confianza = 0;
        if (notBlank(lead.get("dni"))) confianza += 5;
        if (Boolean.TRUE.equals(lead.get("verificado"))) confianza += 3;
        if (notBlank(lead.get("ocupacion"))) confianza += 2;
        confianza = Math.min(10, confianza);
        desglose.put("confianza", confianza);
        score += confianza;

        // 6) Recency (10 pts)
        int recency = 0;
        try {
            String created = String.valueOf(lead.get("created_at"));
            OffsetDateTime c = OffsetDateTime.parse(created);
            long dias = ChronoUnit.DAYS.between(c, OffsetDateTime.now());
            if (dias <= 1) recency = 10;
            else if (dias <= 7) recency = 7;
            else if (dias <= 30) recency = 4;
            else if (dias <= 90) recency = 2;
            else recency = 0;
        } catch (Exception ignored) {}
        desglose.put("recency", recency);
        score += recency;

        // Cap final
        score = Math.min(100, Math.max(0, score));

        // Temperatura
        String temperatura;
        if (score >= 80) temperatura = "hot";
        else if (score >= 60) temperatura = "caliente";
        else if (score >= 40) temperatura = "tibio";
        else temperatura = "frio";

        // Probabilidad conversión (heurística simple)
        double probabilidad = score * 0.6; // aprox % de probabilidad ajustable

        // Siguiente acción sugerida
        String sigAccion = sugerirAccion(lead, score, ints);

        // Actualizar lead
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("score", score);
        upd.put("score_actualizado_at", OffsetDateTime.now().toString());
        upd.put("probabilidad_conversion", probabilidad);
        upd.put("temperatura", temperatura);
        upd.put("siguiente_accion", sigAccion);
        upd.put("score_desglose", desglose);
        supabase.update("leads", leadId, upd);

        // Guardar histórico
        try {
            Map<String, Object> hist = new LinkedHashMap<>();
            hist.put("lead_id", leadId);
            hist.put("score", score);
            hist.put("temperatura", temperatura);
            hist.put("desglose", desglose);
            hist.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("lead_score_historico", hist);
        } catch (Exception ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score);
        result.put("temperatura", temperatura);
        result.put("desglose", desglose);
        result.put("probabilidad", probabilidad);
        result.put("siguienteAccion", sigAccion);
        return result;
    }

    /** Registra una interacción y recalcula score. */
    @Async
    public void registrarInteraccion(String leadId, String tipo, String detalle, String userId) {
        try {
            int valor = valorScore(tipo);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lead_id", leadId);
            data.put("user_id", userId);
            data.put("tipo", tipo);
            data.put("detalle", detalle);
            data.put("valor_score", valor);
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("lead_interacciones", data);

            // Recalcular
            calcularScore(leadId);
        } catch (Exception e) {
            log.warn("Err registrar interacción: {}", e.getMessage());
        }
    }

    private int valorScore(String tipo) {
        return switch (tipo) {
            case "visita_realizada" -> 8;
            case "llamada" -> 5;
            case "formulario_extra" -> 4;
            case "whatsapp" -> 3;
            case "email_click" -> 2;
            default -> 1;
        };
    }

    /** Sugiere siguiente acción según score, perfil y últimas interacciones. */
    private String sugerirAccion(Map<String, Object> lead, int score, List<Map<String, Object>> interacciones) {
        // Reglas rápidas
        if (score >= 80) {
            // ¿ya tuvo visita?
            boolean tuvoVisita = interacciones.stream().anyMatch(i -> "visita_realizada".equals(i.get("tipo")));
            if (tuvoVisita) return "🔥 Lead HOT con visita. Cerrar oferta o segunda visita YA.";
            return "🔥 Lead HOT sin visita. LLAMAR AHORA para agendar visita.";
        } else if (score >= 60) {
            if (lead.get("telefono") == null || String.valueOf(lead.get("telefono")).isBlank()) {
                return "📧 Lead caliente sin teléfono. Email + pedirlo.";
            }
            return "🟠 Lead caliente. Llamada HOY o WhatsApp.";
        } else if (score >= 40) {
            return "🟡 Lead tibio. Enviar 2-3 propiedades que encajen + seguimiento en 48h.";
        } else {
            return "🔵 Lead frío. Drip campaign automática. No prioritario.";
        }
    }

    /** Recalcula scores de todos los leads cada noche. */
    @Scheduled(cron = "0 0 3 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void cronRecalcular() {
        log.info("[CRON] Recalculando scores de leads...");
        List<Map<String, Object>> leads = supabase.selectFilter(
                "leads", "etapa=not.in.(Perdido,Firmado,Cerrado)&limit=2000");
        int actualizados = 0;
        for (Map<String, Object> l : leads) {
            try {
                calcularScore(String.valueOf(l.get("id")));
                actualizados++;
            } catch (Exception e) {
                log.warn("Err recalc lead {}: {}", l.get("id"), e.getMessage());
            }
        }
        log.info("[CRON] {} leads recalculados", actualizados);
    }

    /** Pide a la IA un análisis profundo + sugerencias accionables. */
    public String analisisAI(String leadId, String userId) {
        List<Map<String, Object>> leads = supabase.selectFilter("leads", "id=eq." + leadId);
        if (leads.isEmpty()) return "Lead no encontrado";
        Map<String, Object> lead = leads.get(0);

        List<Map<String, Object>> interacciones = supabase.selectFilter(
                "lead_interacciones", "lead_id=eq." + leadId + "&order=created_at.desc&limit=20");

        StringBuilder ctx = new StringBuilder();
        ctx.append("LEAD:\n");
        ctx.append("- Nombre: ").append(s(lead.get("nombre"))).append("\n");
        ctx.append("- Interés: ").append(s(lead.get("interes"))).append("\n");
        ctx.append("- Presupuesto: ").append(s(lead.get("presupuesto"))).append("\n");
        ctx.append("- Zona: ").append(s(lead.get("zona"))).append("\n");
        ctx.append("- Plazo: ").append(s(lead.get("plazo_decision"))).append("\n");
        ctx.append("- Score actual: ").append(s(lead.get("score"))).append("\n");
        ctx.append("- Notas: ").append(s(lead.get("notas"))).append("\n\n");

        ctx.append("INTERACCIONES (últimas):\n");
        for (Map<String, Object> i : interacciones) {
            ctx.append("- ").append(s(i.get("tipo"))).append(": ").append(s(i.get("detalle")))
               .append(" (").append(s(i.get("created_at"))).append(")\n");
        }

        String prompt = ctx + "\nAnaliza este lead y devuelve en español, máx. 200 palabras:\n" +
                "1. Análisis de perfil (1 frase)\n" +
                "2. Probabilidad de conversión y por qué\n" +
                "3. Próximos 3 pasos concretos a ejecutar HOY";

        try {
            return aiService.call(null, prompt,
                    "Eres un asistente experto en ventas inmobiliarias. Conciso y accionable.",
                    400, userId);
        } catch (Exception e) {
            return "No se pudo generar análisis IA: " + e.getMessage();
        }
    }

    private static boolean notBlank(Object o) { return o != null && !String.valueOf(o).isBlank() && !"null".equals(String.valueOf(o)); }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
