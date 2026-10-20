package com.inmobilia.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Cliente Supabase via PostgREST.
 * Equivalente a las funciones sb_insert / sb_query / sb_update / sb_delete del Python original.
 * Cachea las consultas GET durante 5 minutos (ttl=300 como el decorator @st.cache_data).
 */
@Service
@Slf4j
public class SupabaseService {

    private final WebClient supabaseClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final boolean enabled;
    private final CacheManager cacheManager;
    private volatile String lastError;

    public String lastError() { return lastError; }

    public SupabaseService(
            WebClient webClient,
            ObjectMapper mapper,
            CacheManager cacheManager,
            @Value("${supabase.url:}") String url,
            @Value("${supabase.key:}") String key) {
        this.mapper = mapper;
        this.apiKey = key;
        this.cacheManager = cacheManager;
        // Normalizar la URL: si pegaron la del DASHBOARD, convertirla a la de la API.
        url = normalizeSupabaseUrl(url);
        this.enabled = url != null && !url.isBlank() && key != null && !key.isBlank();
        if (enabled) {
            log.info("Supabase API base: {}/rest/v1", url);
            // IMPORTANTE: partir del bean `webClient` (que fuerza el resolvedor DNS del JDK
            // vía .mutate()); si usáramos WebClient.builder() nuevo, Reactor Netty usaría su
            // resolvedor async por defecto que FALLA en Railway ("Failed to resolve host").
            this.supabaseClient = webClient.mutate()
                    .baseUrl(url + "/rest/v1")
                    .defaultHeader("apikey", key)
                    .defaultHeader("Authorization", "Bearer " + key)
                    .defaultHeader("Content-Type", "application/json")
                    .defaultHeader("Prefer", "return=representation")
                    .build();
        } else {
            this.supabaseClient = null;
            log.warn("Supabase desactivado: SUPABASE_URL / SUPABASE_KEY no configurados.");
        }
    }

    /**
     * Auto-corrige errores típicos al configurar SUPABASE_URL:
     *  - URL del dashboard:  https://supabase.com/dashboard/project/XXXX  →  https://XXXX.supabase.co
     *  - URL con /rest/v1 al final ya incluido → se lo quita (lo añade el cliente)
     *  - Espacios, tabs (%09), barra final
     */
    static String normalizeSupabaseUrl(String url) {
        if (url == null) return null;
        String u = url.trim().replace("%09", "").replaceAll("\\s+", "");
        if (u.isEmpty()) return u;

        // Caso 1: pegaron la URL del panel/dashboard → extraer el project ref
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("supabase\\.com/dashboard/project/([a-zA-Z0-9]+)")
                .matcher(u);
        if (m.find()) {
            String ref = m.group(1);
            log.warn("SUPABASE_URL era la del dashboard. Corregida automáticamente a https://{}.supabase.co", ref);
            return "https://" + ref + ".supabase.co";
        }

        // Caso 2: quitar /rest/v1 final si lo incluyeron (el cliente ya lo añade)
        u = u.replaceAll("/rest/v1/?$", "");
        // Caso 3: quitar barra final
        u = u.replaceAll("/+$", "");
        return u;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Consulta cacheada: equivalente a sb_query. */
    @Cacheable(value = "sbQuery", key = "#table + '|' + (#userId != null ? #userId : '') + '|' + (#filters != null ? #filters.toString() : '')", unless = "#result == null")
    public List<Map<String, Object>> query(String table, String userId, Map<String, String> filters) {
        if (!enabled) return Collections.emptyList();
        try {
            StringBuilder q = new StringBuilder("/").append(table).append("?select=*");
            if (userId != null && !userId.isBlank()) {
                q.append("&user_id=eq.").append(userId);
            }
            if (filters != null) {
                filters.forEach((k, v) -> q.append("&").append(k).append("=eq.").append(v));
            }
            q.append("&order=created_at.desc");
            String response = supabaseClient.get()
                    .uri(q.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (response == null) return Collections.emptyList();
            return mapper.readValue(response, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Error consultando {}: {}", table, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> query(String table, String userId) {
        return query(table, userId, null);
    }

    /**
     * Invalida solo las entradas de caché de UNA tabla (las keys empiezan por "tabla|").
     * Evita el borrado global de toda la caché de lecturas en cada escritura, manteniendo
     * calientes el resto de tablas. Es correcto porque cada query cacheada es de una sola
     * tabla. Fallback a clear() total si la caché no expone un ConcurrentMap.
     */
    private void evictTable(String table) {
        if (cacheManager == null || table == null) return;
        evictPrefix(table);
        // Trigger V38 (tg_track_price): UPDATE propiedades → INSERT precio_historico.
        // Es el único trigger cruzado del esquema, así que invalidamos también su caché.
        if ("propiedades".equals(table)) evictPrefix("precio_historico");
    }

    private void evictPrefix(String table) {
        try {
            Cache c = cacheManager.getCache("sbQuery");
            if (c == null) return;
            Object nat = c.getNativeCache();
            if (nat instanceof ConcurrentMap<?, ?> map) {
                String prefix = table + "|";
                map.keySet().removeIf(k -> String.valueOf(k).startsWith(prefix));
            } else {
                c.clear();
            }
        } catch (Exception ignored) { }
    }

    /** Limpieza total de la caché de lecturas. Se usa en DELETE por si hay cascadas
     *  (ON DELETE CASCADE) que borran filas de otras tablas. */
    private void evictAllQueries() {
        if (cacheManager == null) return;
        try {
            Cache c = cacheManager.getCache("sbQuery");
            if (c != null) c.clear();
        } catch (Exception ignored) { }
    }

    /** Insercion con limpieza de valores NaN / Infinity (equivalente a clean_value). */
    public Map<String, Object> insert(String table, Map<String, Object> data) {
        if (!enabled) {
            log.warn("Insert abortado: Supabase deshabilitado (falta url/key).");
            return null;
        }
        try {
            Map<String, Object> clean = clean(data);
            // Solo los NOMBRES de los campos: el contenido puede llevar hashes de contraseña,
            // secretos de 2FA, tokens o datos personales, y los logs no deben guardarlos.
            log.info("POST /{}  campos={}", table, clean.keySet());
            String response = supabaseClient.post()
                    .uri("/" + table)
                    .bodyValue(clean)
                    .retrieve()
                    .onStatus(s -> s.isError(), resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new RuntimeException("HTTP " + resp.statusCode().value() + ": " + b)))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (response == null || response.isBlank()) {
                log.warn("Insert en {} devolvio respuesta vacia", table);
                lastError = "Supabase devolvio respuesta vacia (posible 201 sin representation)";
                return null;
            }
            List<Map<String, Object>> list = mapper.readValue(response, new TypeReference<>() {});
            lastError = null;
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("Error guardando en {}: {}", table, e.getMessage(), e);
            return null;
        } finally {
            evictTable(table);
        }
    }

    /** Actualizacion con proteccion IDOR (exige user_id). */
    public boolean update(String table, String id, Map<String, Object> data, String uid) {
        if (!enabled) return false;
        // La tabla users se identifica por id (no tiene user_id propio): la protección
        // equivalente es exigir que solo se actualice la propia fila (id == uid).
        boolean isUsers = "users".equals(table);
        if (isUsers && uid != null && !uid.isBlank() && !uid.equals(id)) {
            log.warn("Update users bloqueado: id {} != uid {}", id, uid);
            return false;
        }
        try {
            Map<String, Object> clean = clean(data);
            StringBuilder uri = new StringBuilder("/").append(table).append("?id=eq.").append(id);
            if (!isUsers && uid != null && !uid.isBlank()) uri.append("&user_id=eq.").append(uid);
            supabaseClient.patch()
                    .uri(uri.toString())
                    .bodyValue(clean)
                    .retrieve()
                    .onStatus(s -> s.isError(), resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new RuntimeException("HTTP " + resp.statusCode().value() + ": " + b)))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            lastError = null;
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("Error actualizando {}: {}", table, e.getMessage());
            return false;
        } finally {
            evictTable(table);
        }
    }

    /** Borrado con proteccion IDOR. */
    public boolean delete(String table, String id, String uid) {
        if (!enabled) return false;
        try {
            StringBuilder uri = new StringBuilder("/").append(table).append("?id=eq.").append(id);
            if (uid != null && !uid.isBlank()) uri.append("&user_id=eq.").append(uid);
            supabaseClient.delete()
                    .uri(uri.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            return true;
        } catch (Exception e) {
            log.error("Error eliminando {}: {}", table, e.getMessage());
            return false;
        } finally {
            evictAllQueries();   // conservador: un delete puede cascadear a otras tablas
        }
    }

    /** Busqueda por filtro libre (equivalente a .eq() chain). */
    public List<Map<String, Object>> findBy(String table, Map<String, String> filters) {
        return query(table, null, filters);
    }

    // ============================================================
    // MÉTODOS DE COMPATIBILIDAD (Sprints 47-110)
    // selectFilter / update(3 args) / delete(2 args)
    // ============================================================

    /** selectFilter compatibilidad: filter es un string PostgREST raw como "user_id=eq.XXX&estado=eq.activo". */
    public List<Map<String, Object>> selectFilter(String table, String filter) {
        if (!enabled) return Collections.emptyList();
        try {
            String uri = "/" + table + "?select=*" + (filter != null && !filter.isBlank() ? "&" + filter : "");
            String response = supabaseClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (response == null) return Collections.emptyList();
            return mapper.readValue(response, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("selectFilter {} err: {}", table, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Blindaje multi-tenant: devuelve la fila {table}/{id} SOLO si pertenece a uid
     * (columna user_id). Como la app usa la service_role key (que ignora el RLS de
     * Supabase), filtrar por user_id en cada consulta por-id es la única frontera
     * entre inmobiliarias. Lista vacía si no existe, no tiene user_id o no es del
     * usuario. Requiere que la tabla tenga columna user_id.
     */
    public List<Map<String, Object>> selectOwned(String table, String id, String uid) {
        if (uid == null || uid.isBlank()) return Collections.emptyList();
        return selectFilter(table, "id=eq." + id + "&user_id=eq." + uid);
    }

    /** update sin user_id (compat). La evicción por tabla la hace el método de 4 args. */
    public boolean update(String table, String id, Map<String, Object> data) {
        return update(table, id, data, null);
    }

    /** delete sin user_id (compat). La evicción por tabla la hace el método de 4 args. */
    public boolean delete(String table, String id) {
        return delete(table, id, null);
    }

    /** Limpia un map eliminando NaN / Infinity y serializando colecciones. */
    private Map<String, Object> clean(Map<String, Object> in) {
        return in.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> cleanValue(e.getValue()),
                        (a, b) -> b,
                        java.util.LinkedHashMap::new));
    }

    private Object cleanValue(Object v) {
        if (v instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return d;
        }
        if (v instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) return null;
            return f;
        }
        return v;
    }

    /** Limpieza total de la caché de lecturas, bajo demanda. */
    public void clearCache() {
        evictAllQueries();
    }
}
