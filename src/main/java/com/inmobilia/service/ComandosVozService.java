package com.inmobilia.service;

import com.inmobilia.util.Fechas;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comandos de voz: el navegador pasa la voz a texto (Web Speech API) y aquí se entiende la orden.
 * Lo habitual se resuelve sin IA y al momento (crear una tarea, preguntar por activos, contratos
 * que vencen, recibos sin pagar, incidencias, tareas de hoy o renta del mes); lo demás va al
 * agente de IA (AgentActionService), que gasta créditos.
 */
@Service
@RequiredArgsConstructor
public class ComandosVozService {

    private final SupabaseService supabase;
    private final PlanService planes;
    private final AgentActionService agente;

    public record Respuesta(String texto, String enlace, String accion) {}

    public Respuesta ejecutar(String org, String yo, String frase) {
        String t = norm(frase);
        if (t.isBlank()) return new Respuesta("No te he entendido. Prueba otra vez.", null, "nada");
        Matcher tarea = TAREA.matcher(t);
        if (tarea.find()) return crearTarea(org, yo, frase, tarea.end());
        if (t.matches(".*\\b(activos?|inmuebles?|naves?|locales?|oficinas?|propiedades|cartera)\\b.*")
                && t.matches(".*\\b(cuant[oa]s?|cuantos tengo|numero|libres?|quedan)\\b.*")) return activos(org);
        if (t.matches(".*\\bcontratos?\\b.*") && t.matches(".*\\b(venc\\w*|caduc\\w*|termin\\w*|acaba\\w*|renov\\w*)\\b.*")) return contratosQueVencen(org);
        if (t.matches(".*\\b(recibos?|cobros?|pagos?|impag\\w*|moros\\w*|debe\\w*|deuda)\\b.*")) return recibosPendientes(org);
        if (t.matches(".*\\b(incidencias?|averias?|avisos? de inquilin\\w*)\\b.*")) return incidencias(org);
        if (t.matches(".*\\btareas?\\b.*") || t.matches(".*\\bque tengo (hoy|manana|pendiente)\\b.*")) return tareas(org);
        if (t.matches(".*\\b(renta|rentas|alquileres|ingresos?)\\b.*") && t.matches(".*\\b(cuant\\w*|total|mes|mensual\\w*)\\b.*")) return renta(org);
        // Lo demás: el agente de IA
        Map<String, Object> r = agente.procesar(org, frase, "El usuario habla por voz desde el móvil: responde muy breve.");
        return new Respuesta(String.valueOf(r.getOrDefault("respuesta", "No te he entendido.")), null, String.valueOf(r.getOrDefault("accion", "responder")));
    }

    // ─── Tareas ─────────────────────────────────────────────────────────

    private static final Pattern TAREA = Pattern.compile(
            "^(recuerdame( que)?|recuerda(me)?( que)?|crea(r)? (una )?tarea( para)?|nueva tarea|apunta(me)?( que)?|anota(me)?( que)?|tarea:?)\\s+");

    private Respuesta crearTarea(String org, String yo, String fraseOriginal, int desde) {
        String resto = fraseOriginal.trim();
        // Quitar el disparador respetando mayúsculas y tildes del original
        String[] palabrasNorm = norm(fraseOriginal).substring(0, desde).trim().split("\\s+");
        for (int i = 0; i < palabrasNorm.length && resto.contains(" "); i++) resto = resto.substring(resto.indexOf(' ') + 1).trim();
        LocalDate hoy = LocalDate.now();
        Cuando c = cuando(resto, hoy);
        String titulo = c.resto().isBlank() ? resto : c.resto();
        titulo = Character.toUpperCase(titulo.charAt(0)) + titulo.substring(1);
        if (titulo.length() > 200) titulo = titulo.substring(0, 200);

        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("user_id", org);
        if (yo != null && !yo.equals(org)) fila.put("asignado_a", yo);
        fila.put("titulo", titulo);
        fila.put("prioridad", "media");
        fila.put("estado", "pendiente");
        fila.put("fecha_limite", c.fecha().toString());
        fila.put("descripcion", "Creada por voz: «" + fraseOriginal.trim() + "»");
        fila.put("created_at", OffsetDateTime.now().toString());
        if (supabase.insert("tareas", fila) == null) return new Respuesta("No he podido crear la tarea. Inténtalo de nuevo.", null, "error");
        return new Respuesta("Tarea creada para " + diaHablado(c.fecha(), hoy) + ": " + titulo + ".", "/crud/tareas", "tarea_creada");
    }

    record Cuando(LocalDate fecha, String resto) {}

    private static final String[] DIAS = {"lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo"};

    /** Fecha dicha en la frase ("mañana", "el viernes", "en 3 días"…) y la frase sin ella. Por defecto, mañana. */
    static Cuando cuando(String frase, LocalDate hoy) {
        String n = sinTildes(frase);
        LocalDate fecha = null;
        String quitar = null;
        Matcher m;
        if ((m = Pattern.compile("\\bpasado manana\\b").matcher(n)).find()) { fecha = hoy.plusDays(2); quitar = m.group(); }
        else if ((m = Pattern.compile("\\b(para |de )?manana\\b").matcher(n)).find()) { fecha = hoy.plusDays(1); quitar = m.group(); }
        else if ((m = Pattern.compile("\\b(para |de )?hoy\\b").matcher(n)).find()) { fecha = hoy; quitar = m.group(); }
        else if ((m = Pattern.compile("\\ben (\\d+|un|una|dos|tres|cuatro|cinco|seis|siete) (dias?|semanas?)\\b").matcher(n)).find()) {
            int num = numero(m.group(1));
            fecha = m.group(2).startsWith("semana") ? hoy.plusWeeks(num) : hoy.plusDays(num);
            quitar = m.group();
        } else if ((m = Pattern.compile("\\b(la )?semana que viene\\b|\\bla proxima semana\\b").matcher(n)).find()) {
            fecha = hoy.with(TemporalAdjusters.next(DayOfWeek.MONDAY)); quitar = m.group();
        } else {
            for (int i = 0; i < DIAS.length; i++) {
                if ((m = Pattern.compile("\\b(el |este |para el )?" + DIAS[i] + "\\b").matcher(n)).find()) {
                    fecha = hoy.with(TemporalAdjusters.next(DayOfWeek.of(i + 1)));
                    quitar = m.group();
                    break;
                }
            }
        }
        String resto = frase.trim();
        if (quitar != null) resto = quitarSinTildes(resto, quitar);
        resto = resto.replaceAll("^(que|de|para)\\s+", "").replaceAll("\\s{2,}", " ").replaceAll("[\\s,.]+$", "").trim();
        return new Cuando(fecha == null ? hoy.plusDays(1) : fecha, resto);
    }

    /** Quita del texto original el trozo que, sin tildes ni mayúsculas, es igual a 'trozoNorm'. */
    private static String quitarSinTildes(String original, String trozoNorm) {
        String n = sinTildes(original);
        int i = n.indexOf(trozoNorm);
        if (i < 0 || n.length() != original.length()) return original;
        return (original.substring(0, i) + original.substring(i + trozoNorm.length())).trim();
    }

    private static int numero(String s) {
        return switch (s) {
            case "un", "una" -> 1; case "dos" -> 2; case "tres" -> 3; case "cuatro" -> 4;
            case "cinco" -> 5; case "seis" -> 6; case "siete" -> 7;
            default -> { try { yield Integer.parseInt(s); } catch (Exception e) { yield 1; } }
        };
    }

    private static String diaHablado(LocalDate f, LocalDate hoy) {
        long d = ChronoUnit.DAYS.between(hoy, f);
        if (d == 0) return "hoy";
        if (d == 1) return "mañana";
        String[] dias = {"el lunes", "el martes", "el miércoles", "el jueves", "el viernes", "el sábado", "el domingo"};
        return d < 7 ? dias[f.getDayOfWeek().getValue() - 1] : "el " + f.getDayOfMonth() + "/" + f.getMonthValue();
    }

    // ─── Consultas ──────────────────────────────────────────────────────

    private Respuesta activos(String org) {
        int usados = planes.activosUsados(org);
        int libres = planes.activosLibres(org);
        String txt = "Tienes " + usados + (usados == 1 ? " activo" : " activos")
                + (libres == Integer.MAX_VALUE ? "." : " y te quedan " + libres + " libres en tu plan.");
        return new Respuesta(txt, "/crud/propiedades", "consulta");
    }

    private Respuesta contratosQueVencen(String org) {
        LocalDate hoy = LocalDate.now();
        List<String> lista = new ArrayList<>();
        for (Map<String, Object> c : supabase.selectFilter("contratos", "user_id=eq." + org + "&limit=2000")) {
            LocalDate fin = fecha(c.get("fecha_fin") != null ? c.get("fecha_fin") : c.get("end_date"));
            if (fin == null || fin.isBefore(hoy) || ChronoUnit.DAYS.between(hoy, fin) > 90) continue;
            lista.add(texto(c.get("tenant_name"), "un inquilino") + " el " + fin.getDayOfMonth() + "/" + fin.getMonthValue());
        }
        if (lista.isEmpty()) return new Respuesta("No vence ningún contrato en los próximos 90 días.", "/crud/contratos", "consulta");
        return new Respuesta("En los próximos 90 días vence" + (lista.size() == 1 ? " 1 contrato: " : "n " + lista.size() + " contratos: ")
                + String.join("; ", lista.subList(0, Math.min(5, lista.size()))) + ".", "/crud/contratos", "consulta");
    }

    private Respuesta recibosPendientes(String org) {
        List<String> lista = new ArrayList<>();
        double total = 0;
        for (Map<String, Object> r : supabase.selectFilter("receipts", "user_id=eq." + org + "&limit=500")) {
            String s = texto(r.get("status"), "").toLowerCase();
            if (s.contains("pagad") || s.contains("cobrad") || s.contains("anulad") || !texto(r.get("fecha_pago"), "").isBlank()) continue;
            Double imp = com.inmobilia.util.Numeros.decimal(r.get("importe"));
            total += imp == null ? 0 : imp;
            lista.add(texto(r.get("inquilino_nombre"), "un inquilino"));
        }
        if (lista.isEmpty()) return new Respuesta("No tienes recibos pendientes de cobro.", "/receipts", "consulta");
        return new Respuesta("Tienes " + lista.size() + (lista.size() == 1 ? " recibo pendiente" : " recibos pendientes")
                + " por " + Math.round(total) + " " + com.inmobilia.util.PaisActual.moneda() + ": " + String.join(", ", new LinkedHashSet<>(lista)) + ".", "/receipts", "consulta");
    }

    private Respuesta incidencias(String org) {
        List<String> abiertas = new ArrayList<>();
        for (Map<String, Object> t : supabase.selectFilter("tenant_requests", "org_id=eq." + org + "&order=created_at.desc&limit=200")) {
            String s = texto(t.get("status"), "");
            if (!"Resuelta".equalsIgnoreCase(s) && !"Cerrada".equalsIgnoreCase(s)) abiertas.add(texto(t.get("title"), "sin título"));
        }
        if (abiertas.isEmpty()) return new Respuesta("No hay incidencias abiertas.", AutopilotComunicacionService.RUTA, "consulta");
        return new Respuesta("Hay " + abiertas.size() + (abiertas.size() == 1 ? " incidencia abierta: " : " incidencias abiertas: ")
                + String.join("; ", abiertas.subList(0, Math.min(4, abiertas.size()))) + ".", AutopilotComunicacionService.RUTA, "consulta");
    }

    private Respuesta tareas(String org) {
        String hoy = LocalDate.now().toString();
        List<Map<String, Object>> t = supabase.selectFilter("tareas",
                "user_id=eq." + org + "&estado=eq.pendiente&fecha_limite=lte." + hoy + "&order=fecha_limite.asc&limit=20");
        if (t.isEmpty()) return new Respuesta("No tienes tareas pendientes para hoy.", "/crud/tareas", "consulta");
        List<String> titulos = t.stream().map(x -> texto(x.get("titulo"), "tarea")).limit(5).toList();
        return new Respuesta("Para hoy tienes " + t.size() + (t.size() == 1 ? " tarea: " : " tareas: ") + String.join("; ", titulos) + ".",
                "/crud/tareas", "consulta");
    }

    private Respuesta renta(String org) {
        double total = 0;
        int n = 0;
        for (Map<String, Object> c : supabase.selectFilter("contratos", "user_id=eq." + org + "&limit=2000")) {
            String e = (texto(c.get("estado"), "") + " " + texto(c.get("status"), "")).toLowerCase();
            if (e.contains("finaliz") || e.contains("rescind") || e.contains("cancel") || e.contains("inactiv")) continue;
            Double r = com.inmobilia.util.Numeros.decimal(c.get("renta_mensual") != null ? c.get("renta_mensual") : c.get("rent"));
            if (r != null && r > 0) { total += r; n++; }
        }
        if (n == 0) return new Respuesta("No tengo contratos activos con renta para calcularlo.", "/crud/contratos", "consulta");
        return new Respuesta("Tus " + n + " contratos activos suman " + Math.round(total) + " " + com.inmobilia.util.PaisActual.moneda() + " de renta al mes.", "/crud/contratos", "consulta");
    }

    // ─── Utilidades ─────────────────────────────────────────────────────

    private static LocalDate fecha(Object v) {
        String s = v == null ? null : String.valueOf(v);
        OffsetDateTime f = Fechas.leer(s != null && s.length() == 10 ? s + "T00:00:00" : s);
        return f == null ? null : f.toLocalDate();
    }

    private static String texto(Object o, String porDefecto) {
        String s = o == null || "null".equals(String.valueOf(o)) ? "" : String.valueOf(o).trim();
        return s.isBlank() ? porDefecto : s;
    }

    /** Minúsculas y sin tildes, con la misma longitud que el original (para recortar sobre él). */
    static String sinTildes(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[¿?¡!]", "").trim();
    }
}
