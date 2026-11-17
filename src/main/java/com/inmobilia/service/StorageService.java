package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sube archivos a Supabase Storage. Bucket público "uploads": cualquiera con el enlace puede
 * verlos, así que solo se suben fotos de inmuebles (esImagen). Nada de identificaciones, contratos ni recibos de sueldo.
 *
 * Configurar el bucket en Supabase:
 *   Dashboard → Storage → New bucket → name: "uploads" → Public bucket: ON → Save
 *
 * Política RLS (SQL):
 *   CREATE POLICY "Public read" ON storage.objects FOR SELECT USING (bucket_id = 'uploads');
 *   CREATE POLICY "Public upload" ON storage.objects FOR INSERT WITH CHECK (bucket_id = 'uploads');
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    @Value("${supabase.url:}")
    private String supabaseUrl;

    @Value("${supabase.key:}")
    private String supabaseKey;

    private final WebClient webClient;

    private static final String BUCKET = "uploads";

    public boolean isConfigured() {
        return supabaseUrl != null && !supabaseUrl.isBlank()
            && supabaseKey != null && !supabaseKey.isBlank();
    }

    /**
     * Sube un archivo y devuelve la URL pública.
     */
    public String upload(MultipartFile file, String folder) {
        if (!isConfigured()) throw new IllegalStateException("Supabase no configurado");
        if (file.isEmpty()) throw new IllegalArgumentException("Archivo vacío");

        String ext = inferExtension(file.getOriginalFilename(), file.getContentType());
        try {
            return upload(file.getBytes(), file.getContentType(), ext, folder);
        } catch (java.io.IOException e) {
            throw new RuntimeException("No se pudo leer el archivo");
        }
    }

    /** Sube bytes ya en memoria (p. ej. una imagen generada) y devuelve la URL pública. */
    public String upload(byte[] bytes, String contentType, String ext, String folder) {
        if (!isConfigured()) throw new IllegalStateException("Supabase no configurado");
        String safeFolder = folder == null ? "misc" : folder.replaceAll("[^a-zA-Z0-9_-]", "");
        String path = safeFolder + "/" + UUID.randomUUID() + ext;

        try {
            // La SUPABASE_URL puede venir con /rest/v1 al final; normalizamos a la base limpia
            // (https://xxx.supabase.co) antes de construir la ruta de Storage, o daría 404.
            String base = SupabaseService.normalizeSupabaseUrl(supabaseUrl);
            String url = base + "/storage/v1/object/" + BUCKET + "/" + path;

            webClient.post()
                    .uri(url)
                    .header("apikey", supabaseKey)
                    .headers(h -> { if (!SupabaseService.esClaveNueva(supabaseKey)) h.setBearerAuth(supabaseKey); })
                    .contentType(MediaType.parseMediaType(contentType != null ? contentType : "application/octet-stream"))
                    .bodyValue(bytes)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            return base + "/storage/v1/object/public/" + BUCKET + "/" + path;
        } catch (Exception e) {
            log.error("Error subiendo a Supabase Storage: {}", e.getMessage());
            throw new RuntimeException("No se pudo subir el archivo: " + e.getMessage());
        }
    }

    // ─── Almacenamiento privado (facturas, justificantes) ───────────────────────────────
    // Bucket sin acceso público: los archivos solo se leen a través de la app, que comprueba
    // antes que pertenecen a la inmobiliaria de quien los pide. Se crea solo si no existe.

    static final String BUCKET_PRIVADO = "privado";

    /** Sube un archivo privado y devuelve su ruta dentro del bucket (no una URL pública). */
    public String subirPrivado(byte[] bytes, String contentType, String nombreOriginal, String org) {
        if (!isConfigured()) throw new IllegalStateException("Supabase no configurado");
        String ext = inferExtension(nombreOriginal, contentType);
        String path = org.replaceAll("[^a-zA-Z0-9-]", "") + "/" + UUID.randomUUID() + ext;
        try {
            postPrivado(path, bytes, contentType);
        } catch (Exception e) {
            // Primer uso: el bucket todavía no existe → se crea (privado) y se reintenta
            crearBucketPrivado();
            try {
                postPrivado(path, bytes, contentType);
            } catch (Exception e2) {
                log.error("Error subiendo archivo privado: {}", e2.getMessage());
                throw new RuntimeException("No se pudo guardar el archivo: " + e2.getMessage());
            }
        }
        return path;
    }

    /** Descarga un archivo privado. La ruta debe empezar por la organización (lo comprueba quien llama). */
    public byte[] descargarPrivado(String path) {
        if (!isConfigured() || path == null || path.contains("..")) return null;
        try {
            return webClient.get()
                    .uri(SupabaseService.normalizeSupabaseUrl(supabaseUrl) + "/storage/v1/object/" + BUCKET_PRIVADO + "/" + path)
                    .header("apikey", supabaseKey)
                    .headers(h -> { if (!SupabaseService.esClaveNueva(supabaseKey)) h.setBearerAuth(supabaseKey); })
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            log.warn("No se pudo leer el archivo privado: {}", e.getMessage());
            return null;
        }
    }

    public void borrarPrivado(String path) {
        if (!isConfigured() || path == null || path.isBlank() || path.contains("..")) return;
        try {
            webClient.delete()
                    .uri(SupabaseService.normalizeSupabaseUrl(supabaseUrl) + "/storage/v1/object/" + BUCKET_PRIVADO + "/" + path)
                    .header("apikey", supabaseKey)
                    .headers(h -> { if (!SupabaseService.esClaveNueva(supabaseKey)) h.setBearerAuth(supabaseKey); })
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.warn("No se pudo borrar el archivo privado: {}", e.getMessage());
        }
    }

    private void postPrivado(String path, byte[] bytes, String contentType) {
        webClient.post()
                .uri(SupabaseService.normalizeSupabaseUrl(supabaseUrl) + "/storage/v1/object/" + BUCKET_PRIVADO + "/" + path)
                .header("apikey", supabaseKey)
                .headers(h -> { if (!SupabaseService.esClaveNueva(supabaseKey)) h.setBearerAuth(supabaseKey); })
                .contentType(MediaType.parseMediaType(contentType != null ? contentType : "application/octet-stream"))
                .bodyValue(bytes)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private void crearBucketPrivado() {
        try {
            webClient.post()
                    .uri(SupabaseService.normalizeSupabaseUrl(supabaseUrl) + "/storage/v1/bucket")
                    .header("apikey", supabaseKey)
                    .headers(h -> { if (!SupabaseService.esClaveNueva(supabaseKey)) h.setBearerAuth(supabaseKey); })
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("id", BUCKET_PRIVADO, "name", BUCKET_PRIVADO, "public", false,
                            "file_size_limit", 15 * 1024 * 1024))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Bucket privado '{}' creado en Supabase Storage", BUCKET_PRIVADO);
        } catch (Exception e) {
            log.warn("No se pudo crear el bucket privado (puede que ya exista): {}", e.getMessage());
        }
    }

    /** Factura o justificante: PDF o foto normal. */
    public static boolean esDocumento(MultipartFile file) {
        String tipo = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String nombre = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        return esImagen(file) || ("application/pdf".equals(tipo) && nombre.endsWith(".pdf"));
    }

    private static final java.util.Set<String> TIPOS_IMAGEN = java.util.Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    /** Foto normal (jpg, png, webp, gif). SVG no: puede llevar código. */
    public static boolean esImagen(MultipartFile file) {
        String tipo = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String nombre = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        return TIPOS_IMAGEN.contains(tipo) && nombre.matches(".*\\.(jpe?g|png|webp|gif)");
    }

    private static String inferExtension(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.')).toLowerCase();
            if (ext.matches("\\.(jpg|jpeg|png|gif|webp|pdf|doc|docx|xls|xlsx|csv|svg)")) return ext;
        }
        if (contentType != null) {
            return switch (contentType.toLowerCase()) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                case "application/pdf" -> ".pdf";
                default -> ".bin";
            };
        }
        return ".bin";
    }
}
