package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Llamadas a OpenAI con descuento de creditos del usuario.
 * Replica call_ai(), get_ai_credits(), deduct_ai_credit(), ensure_credits_column().
 * Usa HTTP directo en lugar de SDK para mantener la dependencia ligera.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIService {

    public static final String ENCODING_RULES = """
            REGLAS DE FORMATO:
            - Responde en español correcto, con sus tildes, eñes y signos de apertura (¿ ¡).
            - Nada de emojis ni símbolos decorativos.
            - NO empieces la respuesta con guiones ni separadores. Ve directo al contenido.
            - Usa una linea de guiones (----------) SOLO entre secciones cuando haya VARIAS; nunca al principio ni al final.
            - Para listas usa guiones simples: -
            - Para flechas usa: -->
            - Se directo, cuantitativo, profesional. Nada de relleno generico.
            """;

    private final SupabaseService supabase;
    private final ObjectMapper mapper;
    private final WebClient webClient;

    @Value("${openai.platform-key:}")
    private String platformKey;

    @Value("${openai.model:gpt-4o}")
    private String model;

    @Value("${openai.max-tokens:2500}")
    private int defaultMaxTokens;

    @Value("${openai.temperature:0.7}")
    private double temperature;

    @Value("${ai.credits.default:50}")
    private int defaultCredits;

    @Value("${ai.credits.cost:1}")
    private int creditCost;

    @Value("${openai.image-model:gpt-image-1}")
    private String imageModel;

    @Value("${openai.image-quality:medium}")
    private String imageQuality;

    public boolean hasPlatformKey() {
        return platformKey != null && !platformKey.isBlank();
    }

    @Cacheable(value = "aiCredits", key = "#uid", unless = "#result == null")
    public int getCredits(String uid) {
        try {
            var r = supabase.findBy("users", Map.of("id", uid));
            if (!r.isEmpty()) {
                Object v = r.get(0).get("ai_credits");
                if (v instanceof Number n) return n.intValue();
            }
        } catch (Exception e) {
            log.warn("Credit lookup failed: {}", e.getMessage());
        }
        return defaultCredits;
    }

    @CacheEvict(value = "aiCredits", key = "#uid")
    public boolean deductCredit(String uid) {
        try {
            int current = getCredits(uid);
            if (current < creditCost) return false;
            supabase.update("users", uid, Map.of("ai_credits", current - creditCost), uid);
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /** Suma créditos a la cuenta (tras comprar un pack). Evicta la caché de saldo. */
    @CacheEvict(value = "aiCredits", key = "#uid")
    public void addCredits(String uid, int amount) {
        if (uid == null || amount <= 0) return;
        try {
            int current = getCredits(uid);
            supabase.update("users", uid, Map.of("ai_credits", current + amount), uid);
            log.info("Sumados {} creditos a {} (nuevo saldo {})", amount, uid, current + amount);
            // Por separado: si la columna aún no existe en BD, la compra se suma igualmente
            int comprados = leerComprados(uid);
            supabase.update("users", uid, Map.of("ai_credits_comprados", comprados + amount), uid);
        } catch (Exception e) {
            log.warn("addCredits {}: {}", uid, e.getMessage());
        }
    }

    /** Saldo total y parte de él que viene de packs comprados. */
    public record Saldo(int total, int comprados) {}

    /**
     * Saldo tras la reposición mensual. Primero se gastan los créditos del plan y después los
     * comprados, así que de lo comprado queda min(comprados, actual). Lo no gastado del plan no
     * se acumula; lo comprado no caduca.
     */
    public static Saldo saldoTrasReponer(int actual, int comprados, int asignacion) {
        int compradosRestantes = Math.max(0, Math.min(comprados, actual));
        return new Saldo(asignacion + compradosRestantes, compradosRestantes);
    }

    /**
     * Repone los créditos del plan. Si marcarRenovacion, anota la fecha para no volver a
     * reponer hasta dentro de un mes. Devuelve el saldo resultante (o -1 si no se pudo).
     */
    @CacheEvict(value = "aiCredits", key = "#uid")
    public int reponerCreditos(String uid, int asignacion, boolean marcarRenovacion) {
        if (uid == null || asignacion <= 0) return -1;
        try {
            int actual = getCredits(uid);
            int comprados = leerComprados(uid);
            Saldo nuevo = saldoTrasReponer(actual, comprados, asignacion);
            supabase.update("users", uid, Map.of("ai_credits", nuevo.total()), uid);
            // Por separado: si las columnas aún no existen en BD, los créditos se reponen igualmente
            if (nuevo.comprados() != comprados) {
                supabase.update("users", uid, Map.of("ai_credits_comprados", nuevo.comprados()), uid);
            }
            if (marcarRenovacion) {
                supabase.update("users", uid, Map.of("ai_credits_renewed_at", com.inmobilia.util.Fechas.ahoraUtc()), uid);
            }
            log.info("Créditos repuestos para {}: {} → {} ({} comprados)", uid, actual, nuevo.total(), nuevo.comprados());
            return nuevo.total();
        } catch (Exception e) {
            log.warn("reponerCreditos {}: {}", uid, e.getMessage());
            return -1;
        }
    }

    /** Cobra varios créditos de golpe (p. ej. una imagen). False si no llega el saldo. */
    @CacheEvict(value = "aiCredits", key = "#uid")
    public boolean gastarCreditos(String uid, int n) {
        if (!hasPlatformKey()) return true;
        ensureCreditsInitialized(uid);
        int actual = getCredits(uid);
        if (actual < n) return false;
        return supabase.update("users", uid, Map.of("ai_credits", actual - n), uid);
    }

    /** Devuelve créditos cobrados por algo que al final falló (no cuenta como compra). */
    @CacheEvict(value = "aiCredits", key = "#uid")
    public void devolverCreditos(String uid, int n) {
        if (!hasPlatformKey() || n <= 0) return;
        sumarCreditosDelPlan(uid, n);
    }

    /** Suma créditos del plan (p. ej. al añadir un usuario a mitad de mes). No cuentan como comprados. */
    @CacheEvict(value = "aiCredits", key = "#uid")
    public void sumarCreditosDelPlan(String uid, int n) {
        if (uid == null || n <= 0) return;
        try {
            supabase.update("users", uid, Map.of("ai_credits", getCredits(uid) + n), uid);
        } catch (Exception e) {
            log.warn("sumarCreditosDelPlan {}: {}", uid, e.getMessage());
        }
    }

    /**
     * Edita una foto con el modelo de imagen de OpenAI y devuelve la imagen nueva (bytes).
     * Lanza IllegalStateException con un mensaje para el usuario si no se puede.
     * No cobra créditos: eso lo decide quien llama (gastarCreditos / devolverCreditos).
     */
    public byte[] editarImagen(byte[] foto, String tipoMime, String prompt) {
        if (!hasPlatformKey()) throw new IllegalStateException("La IA de imágenes no está configurada en el servidor");
        org.springframework.http.client.MultipartBodyBuilder mb = new org.springframework.http.client.MultipartBodyBuilder();
        mb.part("model", imageModel);
        mb.part("prompt", prompt);
        mb.part("size", "auto");
        mb.part("quality", imageQuality);
        mb.part("output_format", "jpeg");
        String ext = tipoMime != null && tipoMime.contains("png") ? "png" : tipoMime != null && tipoMime.contains("webp") ? "webp" : "jpg";
        mb.part("image", new org.springframework.core.io.ByteArrayResource(foto) {
            @Override public String getFilename() { return "foto." + ext; }
        }).contentType(org.springframework.http.MediaType.parseMediaType(tipoMime == null ? "image/jpeg" : tipoMime));
        String response;
        try {
            response = webClient.post()
                    .uri("https://api.openai.com/v1/images/edits")
                    .header("Authorization", "Bearer " + platformKey)
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(org.springframework.web.reactive.function.BodyInserters.fromMultipartData(mb.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(240))
                    .block();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String cuerpo = e.getResponseBodyAsString();
            log.warn("Imagen IA {}: {}", e.getStatusCode().value(), cuerpo);
            if (cuerpo.contains("verif")) throw new IllegalStateException("La cuenta de OpenAI del servidor necesita la verificación de organización para generar imágenes");
            if (cuerpo.contains("safety") || cuerpo.contains("moderation")) throw new IllegalStateException("OpenAI ha rechazado la foto por sus normas de contenido");
            throw new IllegalStateException("OpenAI no ha podido procesar la foto (error " + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            log.warn("Imagen IA: {}", e.getMessage());
            throw new IllegalStateException("La IA de imágenes no ha respondido a tiempo. Inténtalo de nuevo.");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(response, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
            Object b64 = data == null || data.isEmpty() ? null : data.get(0).get("b64_json");
            if (b64 == null) throw new IllegalStateException("La IA no ha devuelto ninguna imagen");
            return java.util.Base64.getDecoder().decode(b64.toString());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Respuesta de la IA de imágenes no válida");
        }
    }

    private int leerComprados(String uid) {
        try {
            var r = supabase.findBy("users", Map.of("id", uid));
            if (!r.isEmpty() && r.get(0).get("ai_credits_comprados") instanceof Number n) return Math.max(0, n.intValue());
        } catch (Exception ignored) { }
        return 0;
    }

    public void ensureCreditsInitialized(String uid) {
        try {
            var r = supabase.findBy("users", Map.of("id", uid));
            if (!r.isEmpty() && r.get(0).get("ai_credits") == null) {
                supabase.update("users", uid, Map.of("ai_credits", defaultCredits), uid);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Por qué call() devolvió null, en palabras para el usuario. Antes varias herramientas
     * hacían Map.of("texto", null), reventaban y la pantalla se quedaba en "Generando…".
     */
    public String motivoSinRespuesta(String uid) {
        if (!hasPlatformKey()) return "La IA no está disponible ahora mismo. Si el problema sigue, avisa a soporte.";
        if (uid != null && getCredits(uid) <= 0)
            return "Te has quedado sin créditos de IA este mes. Puedes comprar un pack en «Créditos de IA».";
        return "La IA no ha respondido. Inténtalo de nuevo en unos segundos.";
    }

    /** Respuesta JSON estándar de una herramienta de IA: {ok, clave: texto} o {ok:false, error}. */
    public Map<String, Object> respuesta(String clave, String texto, String uid) {
        if (texto == null || texto.isBlank()) return Map.of("ok", false, "error", motivoSinRespuesta(uid));
        return Map.of("ok", true, clave, texto);
    }

    public String call(String apiKey, String prompt) {
        return call(apiKey, prompt, "", defaultMaxTokens, null);
    }

    public String call(String apiKey, String prompt, String system, Integer maxTokens, String uid) {
        return pedir(apiKey, prompt, system, maxTokens, uid, null);
    }

    /**
     * Como call(), pero la IA ve además una foto: data URL (data:image/jpeg;base64,...) o https.
     * Gasta los mismos créditos que una consulta de texto.
     */
    public String callConImagen(String prompt, String system, String imagen, Integer maxTokens, String uid) {
        if (imagen == null || imagen.isBlank()) return call(null, prompt, system, maxTokens, uid);
        return pedir(null, prompt, system, maxTokens, uid, imagen);
    }

    private String pedir(String apiKey, String prompt, String system, Integer maxTokens, String uid, String imagen) {
        String effective = hasPlatformKey() ? platformKey : apiKey;
        if (effective == null || effective.isBlank()) {
            log.warn("No hay clave IA configurada");
            return null;
        }
        // Sistema de creditos: solo si se usa la clave de plataforma
        if (hasPlatformKey() && uid != null) {
            ensureCreditsInitialized(uid);
            int credits = getCredits(uid);
            if (credits <= 0) {
                log.warn("Usuario {} sin creditos", uid);
                return null;
            }
            deductCredit(uid);
        }

        try {
            String fullSys = (system == null || system.isBlank()) ? ENCODING_RULES : system + "\n" + ENCODING_RULES;
            Object contenido = imagen == null ? prompt : List.of(
                    Map.of("type", "text", "text", prompt),
                    Map.of("type", "image_url", "image_url", Map.of("url", imagen, "detail", "low")));
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", temperature,
                    "max_tokens", maxTokens == null ? defaultMaxTokens : maxTokens,
                    "messages", List.of(
                            Map.of("role", "system", "content", fullSys),
                            Map.of("role", "user",   "content", contenido)));

            String json = mapper.writeValueAsString(body);
            String response = webClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + effective)
                    .header("Content-Type", "application/json")
                    .bodyValue(json)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(120))
                    .block();

            if (response == null) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(response, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            String raw = String.valueOf(msg.get("content")).trim();
            // Quitar separadores de guiones iniciales (artefacto de formato del modelo)
            String out = limpiar(raw).replaceFirst("^(-{3,}\\s*)+", "").trim();
            return out;
        } catch (Exception e) {
            log.error("Error IA: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Deja el texto de la IA listo para pantalla, correo y PDF sin estropear el español.
     * Antes se pasaba a ASCII y los clientes recibían "senor", "tasacion", "ano"…
     * Se conservan tildes, eñes, ¿¡, $ y comillas (todo lo que cabe en las fuentes estándar
     * de PDF, Windows-1252); emojis y símbolos raros se quitan, y las flechas pasan a "->".
     */
    public static String limpiar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFC)
                .replaceAll("[→⇒➜➔⟶]", "->")
                .replace(' ', ' ');
        var cp1252 = java.nio.charset.Charset.forName("windows-1252").newEncoder();
        StringBuilder out = new StringBuilder(n.length());
        n.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t') out.appendCodePoint(c);
            else if (!Character.isISOControl(c) && Character.isBmpCodePoint(c) && cp1252.canEncode((char) c)) out.appendCodePoint(c);
        });
        return out.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
