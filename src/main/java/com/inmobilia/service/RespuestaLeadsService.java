package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Respuesta inmediata a los contactos (speed-to-lead), medida.
 *
 *  - Guarda cuándo se contestó por primera vez a cada lead (leads.primera_respuesta_at) desde
 *    cualquier sitio: respuesta automática, botón «Contactado», cambio de etapa, agente y
 *    seguimiento automático.
 *  - Calcula el tiempo de respuesta de los últimos 30 días y los contactos sin contestar.
 *  - Propone huecos de visita reales: los próximos días laborables sin otra visita a esa hora.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RespuestaLeadsService {

    /** Zona horaria del país de la inmobiliaria (la fija PaisInterceptor o PaisService.usar). */
    public static ZoneId zona() { return com.inmobilia.util.PaisActual.get().zonaId(); }
    /** Objetivo: contestar en 5 minutos o menos. */
    public static final int OBJETIVO_MIN = 5;
    /** Horas en las que se proponen visitas, de lunes a viernes y el sábado. */
    static final List<Integer> HORAS_LABORABLES = List.of(10, 12, 17);
    static final List<Integer> HORAS_SABADO = List.of(11);

    private final SupabaseService supabase;

    /** Apunta la primera respuesta a un lead (si aún no la tenía). Nunca falla. */
    public void marcarRespuesta(String org, String leadId, String via) {
        try {
            if (org == null || leadId == null || !leadId.matches("[0-9a-fA-F-]{36}")) return;
            List<Map<String, Object>> l = supabase.selectOwned("leads", leadId, org);
            if (l.isEmpty() || presente(l.get(0).get("primera_respuesta_at"))) return;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("primera_respuesta_at", OffsetDateTime.now().toString());
            d.put("primera_respuesta_via", via);
            supabase.update("leads", leadId, d, org);
        } catch (Exception e) {
            log.debug("No se pudo apuntar la respuesta al lead {}: {}", leadId, e.getMessage());
        }
    }

    /** Si la etapa sale de «Nuevo», cuenta como respondido. */
    public void alCambiarEtapa(String org, String leadId, Object etapaAntes, Object etapaNueva) {
        String antes = etapaAntes == null ? "" : String.valueOf(etapaAntes).trim();
        String nueva = etapaNueva == null ? "" : String.valueOf(etapaNueva).trim();
        if (!nueva.isEmpty() && !"Nuevo".equalsIgnoreCase(nueva) && !"Perdido".equalsIgnoreCase(nueva)
                && (antes.isEmpty() || "Nuevo".equalsIgnoreCase(antes)))
            marcarRespuesta(org, leadId, "etapa");
    }

    // ── Métricas ─────────────────────────────────────────────────────────

    public record PorOrigen(String origen, int recibidos, int respondidos, Long medianaMin) {}

    public record Metricas(int recibidos, int respondidos, Long medianaMin, int enObjetivo, int automaticas,
                           List<Map<String, Object>> sinResponder, List<PorOrigen> origenes) {
        public int pctRespondidos() { return recibidos == 0 ? 0 : Math.round(100f * respondidos / recibidos); }
        public int pctObjetivo() { return recibidos == 0 ? 0 : Math.round(100f * enObjetivo / recibidos); }
    }

    /** Tiempo de respuesta de los leads de los últimos 30 días. */
    public Metricas metricas(String org) {
        String desde = Instant.now().minus(Duration.ofDays(30)).toString();   // en UTC: sin «+» en la URL
        List<Map<String, Object>> leads = supabase.selectFilter("leads",
                "select=*&user_id=eq." + org + "&created_at=gte." + desde + "&order=created_at.asc&limit=5000");
        return calcular(leads, OffsetDateTime.now());
    }

    public static Metricas calcular(List<Map<String, Object>> leads, OffsetDateTime ahora) {
        List<Long> minutos = new ArrayList<>();
        int enObjetivo = 0, automaticas = 0;
        List<Map<String, Object>> sin = new ArrayList<>();
        Map<String, List<Map<String, Object>>> porOrigen = new TreeMap<>();
        for (Map<String, Object> l : leads) {
            OffsetDateTime creado = com.inmobilia.util.Fechas.leer(l.get("created_at"));
            OffsetDateTime resp = com.inmobilia.util.Fechas.leer(l.get("primera_respuesta_at"));
            String origen = presente(l.get("origen")) ? String.valueOf(l.get("origen")) : "sin origen";
            porOrigen.computeIfAbsent(origen, k -> new ArrayList<>()).add(l);
            if (creado != null && resp != null) {
                long m = Math.max(0, Duration.between(creado, resp).toMinutes());
                minutos.add(m);
                if (m <= OBJETIVO_MIN) enObjetivo++;
                if ("auto_email".equals(l.get("primera_respuesta_via"))) automaticas++;
            } else if (resp == null && !"Perdido".equalsIgnoreCase(String.valueOf(l.get("etapa")))) {
                Map<String, Object> s = new LinkedHashMap<>(l);
                s.put("esperando", creado == null ? "" : espera(Duration.between(creado, ahora)));
                sin.add(s);
            }
        }
        List<PorOrigen> origenes = new ArrayList<>();
        for (var e : porOrigen.entrySet()) {
            List<Long> m = new ArrayList<>();
            for (Map<String, Object> l : e.getValue()) {
                OffsetDateTime c = com.inmobilia.util.Fechas.leer(l.get("created_at")), r = com.inmobilia.util.Fechas.leer(l.get("primera_respuesta_at"));
                if (c != null && r != null) m.add(Math.max(0, Duration.between(c, r).toMinutes()));
            }
            origenes.add(new PorOrigen(e.getKey(), e.getValue().size(), m.size(), mediana(m)));
        }
        origenes.sort(Comparator.comparingInt(PorOrigen::recibidos).reversed());
        return new Metricas(leads.size(), minutos.size(), mediana(minutos), enObjetivo, automaticas,
                sin.size() > 30 ? sin.subList(0, 30) : sin, origenes);
    }

    static Long mediana(List<Long> v) {
        if (v.isEmpty()) return null;
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : Math.round((s.get(n / 2 - 1) + s.get(n / 2)) / 2.0);
    }

    /** «3 min», «2 h», «4 días». */
    public static String espera(Duration d) {
        long m = Math.max(0, d.toMinutes());
        if (m < 60) return m + " min";
        if (m < 48 * 60) return (m / 60) + " h";
        return (m / (60 * 24)) + " días";
    }

    /** Texto de una mediana en minutos para las pantallas. */
    public static String duracion(Long min) {
        return min == null ? "—" : espera(Duration.ofMinutes(min));
    }

    // ── Huecos de visita ─────────────────────────────────────────────────

    /** Los próximos huecos de visita (como mínimo 3 horas después de «desde») sin otra visita a menos de una hora. */
    public List<ZonedDateTime> huecosVisita(String org, ZonedDateTime desde, int cuantos) {
        List<ZonedDateTime> ocupadas = new ArrayList<>();
        try {
            // En UTC («Z»): un «+01:00» en la URL llegaría como espacio
            for (Map<String, Object> v : supabase.selectFilter("visitas", "select=fecha_inicio,estado&user_id=eq." + org
                    + "&fecha_inicio=gte." + desde.toInstant() + "&order=fecha_inicio.asc&limit=500")) {
                if ("cancelada".equals(v.get("estado"))) continue;
                OffsetDateTime f = com.inmobilia.util.Fechas.leer(v.get("fecha_inicio"));
                if (f != null && f.isBefore(desde.toOffsetDateTime().plusDays(15))) ocupadas.add(f.atZoneSameInstant(desde.getZone()));
            }
        } catch (Exception ignored) { }
        return huecos(desde, ocupadas, cuantos);
    }

    public static List<ZonedDateTime> huecos(ZonedDateTime desde, List<ZonedDateTime> ocupadas, int cuantos) {
        List<ZonedDateTime> out = new ArrayList<>();
        ZonedDateTime minimo = desde.plusHours(3);   // en la zona del país de la inmobiliaria
        LocalDate dia = minimo.toLocalDate();
        for (int i = 0; i < 14 && out.size() < cuantos; i++, dia = dia.plusDays(1)) {
            DayOfWeek d = dia.getDayOfWeek();
            List<Integer> horas = d == DayOfWeek.SUNDAY ? List.of() : d == DayOfWeek.SATURDAY ? HORAS_SABADO : HORAS_LABORABLES;
            for (int h : horas) {
                ZonedDateTime hueco = dia.atTime(h, 0).atZone(desde.getZone());
                if (hueco.isBefore(minimo)) continue;
                boolean libre = ocupadas.stream().noneMatch(o -> Math.abs(ChronoUnit.MINUTES.between(o, hueco)) < 60);
                if (libre) { out.add(hueco); break; }   // uno por día, para dar donde elegir
            }
        }
        return out;
    }

    /** «martes 10 de noviembre a las 10:00». */
    public static String textoHueco(ZonedDateTime h) {
        Locale es = Locale.forLanguageTag("es-ES");
        return h.getDayOfWeek().getDisplayName(TextStyle.FULL, es) + " " + h.getDayOfMonth() + " de "
                + h.getMonth().getDisplayName(TextStyle.FULL, es) + " a las " + String.format("%02d:%02d", h.getHour(), h.getMinute());
    }

    static boolean presente(Object o) {
        return o != null && !String.valueOf(o).isBlank() && !"null".equals(String.valueOf(o));
    }
}
