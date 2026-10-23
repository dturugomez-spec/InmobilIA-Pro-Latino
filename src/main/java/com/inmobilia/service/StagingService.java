package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staging digital de verdad: la IA amuebla la foto de una estancia vacía.
 * Antes el proyecto se quedaba para siempre en "pendiente": nadie lo procesaba.
 *
 * Tarda 30–90 s, así que va en segundo plano: el proyecto pasa por procesando → completado
 * (o error, devolviendo los créditos) y se avisa con una notificación.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StagingService {

    /** Una imagen cuesta bastante más que una consulta de texto. */
    public static final int CREDITOS = 5;

    static final Map<String, String> ESTILOS = Map.of(
            "moderno", "modern contemporary",
            "nordico", "Scandinavian, light wood and white tones",
            "industrial", "industrial loft, metal and dark wood",
            "clasico", "classic elegant",
            "minimalista", "minimalist, few pieces, neutral colours",
            "boho", "bohemian, natural fibres and plants");

    static final Map<String, String> ESTANCIAS = Map.of(
            "salon", "living room", "dormitorio", "bedroom", "cocina", "kitchen",
            "bano", "bathroom", "terraza", "terrace", "oficina", "home office");

    private final SupabaseService supabase;
    private final StorageService storage;
    private final AIService ai;
    private final NotificationService notif;

    static String prompt(String estilo, String estancia) {
        return "Virtually stage this " + ESTANCIAS.getOrDefault(estancia, "room")
                + " for a real estate listing in a " + ESTILOS.getOrDefault(estilo, "modern") + " style. "
                + "Keep the architecture exactly as it is: same walls, windows, doors, floor, ceiling, "
                + "radiators, sockets, camera angle, perspective and natural light. Do not add or remove "
                + "windows or change the size of the room. Only add furniture, rugs, lamps and decor at a "
                + "realistic scale. Photorealistic, professional real estate photography.";
    }

    @Async
    public void procesar(String id, String uid, String nombre, byte[] foto, String tipoMime, String estilo, String estancia) {
        Map<String, Object> cambios = new LinkedHashMap<>();
        try {
            supabase.update("staging_proyectos", id, Map.of("estado", "procesando"), uid);
            byte[] resultado = ai.editarImagen(foto, tipoMime, prompt(estilo, estancia));
            String url = storage.upload(resultado, tipo(resultado), extension(resultado), "staging");
            cambios.put("estado", "completado");
            cambios.put("imagen_staged_url", url);
            cambios.put("coste", CREDITOS);
            cambios.put("completed_at", OffsetDateTime.now().toString());
            supabase.update("staging_proyectos", id, cambios, uid);
            notif.create(uid, "Staging listo: " + nombre, "La foto amueblada ya está disponible.", "/staging-digital", "success");
        } catch (Exception e) {
            String motivo = e instanceof IllegalStateException ? e.getMessage() : "No se pudo generar la imagen";
            log.warn("Staging {} falló: {}", id, e.getMessage());
            ai.devolverCreditos(uid, CREDITOS);
            cambios.clear();
            cambios.put("estado", "error");
            cambios.put("metadata", Map.of("error", motivo));
            supabase.update("staging_proyectos", id, cambios, uid);
            notif.create(uid, "Staging sin terminar: " + nombre, motivo + ". Te hemos devuelto los créditos.", "/staging-digital", "warning");
        }
    }

    /**
     * Si el servidor se reinicia a mitad (deploy), la tarea en segundo plano se pierde y el
     * proyecto se quedaría "generando" para siempre. Pasados 15 min se marca como error y se
     * devuelven los créditos cobrados (metadata.creditos; los proyectos antiguos no cobraron).
     */
    public void cerrarAtascados(java.util.List<Map<String, Object>> proyectos, String uid) {
        OffsetDateTime limite = OffsetDateTime.now().minusMinutes(15);
        for (Map<String, Object> p : proyectos) {
            Object estado = p.get("estado");
            if (!"pendiente".equals(estado) && !"procesando".equals(estado)) continue;
            try {
                if (OffsetDateTime.parse(String.valueOf(p.get("created_at"))).isAfter(limite)) continue;
            } catch (Exception e) {
                continue;
            }
            if (p.get("metadata") instanceof Map<?, ?> m && m.get("creditos") instanceof Number n) {
                ai.devolverCreditos(uid, n.intValue());
            }
            Map<String, Object> error = Map.of("error", "Se interrumpió el proceso. Vuelve a subir la foto");
            supabase.update("staging_proyectos", String.valueOf(p.get("id")), Map.of("estado", "error", "metadata", error), uid);
            p.put("estado", "error");
            p.put("metadata", error);
        }
    }

    static String tipo(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0x89 && b[1] == 'P') return "image/png";
        if (b.length > 11 && b[0] == 'R' && b[8] == 'W') return "image/webp";
        return "image/jpeg";
    }

    static String extension(byte[] b) {
        return switch (tipo(b)) { case "image/png" -> ".png"; case "image/webp" -> ".webp"; default -> ".jpg"; };
    }
}
