package com.inmobilia.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Supabase (PostgREST) en memoria para probar la app entera sin base de datos: filtros
 * eq/neq/in/is/gt/lt/like/ilike/cs, not., or=(...), order, limit y offset; POST, PATCH y DELETE.
 * También guarda los ficheros que se suben a Storage. Lo que no vaya a {@link #dominio}
 * (OpenAI, etc.) responde 503 al momento: nada sale del ordenador.
 *
 * La usan las pruebas y la demo local ({@link DemoLocalConfig}).
 */
public class FakeSupabase implements ExchangeFunction {

    private final ObjectMapper mapper = new ObjectMapper();
    public final Map<String, List<Map<String, Object>>> tablas = new ConcurrentHashMap<>();
    /** Con esquema, se rechaza lo mismo que rechazaría Postgres. */
    public volatile EsquemaSql esquema;
    /** Rechazos (tabla: motivo), para el informe. */
    public final List<String> rechazos = Collections.synchronizedList(new ArrayList<>());
    /** Host al que se responde como si fuera Supabase (el de la petición termina en él). */
    public volatile String dominio = "supabase.co";

    public record Fichero(String tipo, byte[] datos) {}
    /** Storage: "bucket/carpeta/nombre.jpg" → fichero. */
    public final Map<String, Fichero> ficheros = new ConcurrentHashMap<>();

    public List<Map<String, Object>> tabla(String t) {
        return tablas.computeIfAbsent(t, k -> Collections.synchronizedList(new ArrayList<>()));
    }

    public void vaciar() { tablas.clear(); ficheros.clear(); }

    private static final String STORAGE = "/storage/v1/object/";

    @Override
    public Mono<ClientResponse> exchange(ClientRequest req) {
        String host = req.url().getHost() == null ? "" : req.url().getHost();
        String path = req.url().getRawPath();
        if (!host.endsWith(dominio)) return responder(503, "{\"error\":\"sin red en pruebas\"}");
        if (path.startsWith(STORAGE)) return storage(req, URLDecoder.decode(path.substring(STORAGE.length()), StandardCharsets.UTF_8));
        if (!path.startsWith("/rest/v1/")) return responder(503, "{\"error\":\"sin red en pruebas\"}");
        String tabla = path.substring("/rest/v1/".length());
        if (tabla.startsWith("rpc/")) return responder(200, "[]");
        List<String[]> params = params(req.url().getRawQuery());
        try {
            if (esquema != null && !esquema.tablas.containsKey(tabla)) {
                rechazos.add(req.method() + " " + tabla + ": la tabla no existe");
                return responder(404, "{\"message\":\"relation " + tabla + " does not exist\"}");
            }
            comprobarColumnas(tabla, params);
            if (req.method() == HttpMethod.GET) return responder(200, mapper.writeValueAsString(leer(tabla, params)));
            if (req.method() == HttpMethod.DELETE) {
                List<Map<String, Object>> t = tabla(tabla);
                Predicate<Map<String, Object>> p = filtro(params);
                List<Map<String, Object>> borradas = new ArrayList<>();
                synchronized (t) { t.removeIf(f -> { boolean b = p.test(f); if (b) borradas.add(f); return b; }); }
                return responder(200, mapper.writeValueAsString(borradas));
            }
            return cuerpo(req).flatMap(json -> {
                try {
                    if (req.method() == HttpMethod.POST) return responder(201, mapper.writeValueAsString(insertar(tabla, json)));
                    if (req.method() == HttpMethod.PATCH) {
                        Map<String, Object> cambios = mapper.readValue(json, new TypeReference<>() {});
                        if (esquema != null && esquema.tablas.containsKey(tabla)) {
                            Map<String, Object> sinNulos = new LinkedHashMap<>(cambios);
                            sinNulos.values().removeIf(Objects::isNull);
                            String motivo = esquema.rechazo(tabla, sinNulos);
                            if (motivo != null && !motivo.contains("not-null")) {
                                rechazos.add("PATCH " + tabla + ": " + motivo);
                                return responder(400, "{\"message\":\"" + motivo.replace("\"", "'") + "\"}");
                            }
                        }
                        if (esquema != null) cambios.replaceAll((k, v) -> esquema.comoPostgres(tabla, k, v));
                        List<Map<String, Object>> tocadas = new ArrayList<>();
                        Predicate<Map<String, Object>> p = filtro(params);
                        for (Map<String, Object> f : tabla(tabla)) if (p.test(f)) { f.putAll(cambios); tocadas.add(f); }
                        return responder(200, mapper.writeValueAsString(tocadas));
                    }
                    return responder(405, "{}");
                } catch (Exception e) {
                    return responder(400, "{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
                }
            });
        } catch (Exception e) {
            return responder(400, "{\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
        }
    }

    private List<Map<String, Object>> insertar(String tabla, String json) throws Exception {
        Object o = mapper.readValue(json, Object.class);
        List<Map<String, Object>> filas = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) filas.add(new LinkedHashMap<>(castMap(x)));
        else filas.add(new LinkedHashMap<>(castMap(o)));
        if (esquema != null) {
            for (Map<String, Object> f : filas) {
                String motivo = esquema.rechazo(tabla, f);
                if (motivo != null) {
                    rechazos.add("POST " + tabla + ": " + motivo);
                    throw new IllegalArgumentException(motivo);
                }
            }
        }
        for (Map<String, Object> f : filas) {
            if (esquema != null) f.replaceAll((k, v) -> esquema.comoPostgres(tabla, k, v));
            f.putIfAbsent("id", UUID.randomUUID().toString());
            f.putIfAbsent("created_at", OffsetDateTime.now().toString());
            tabla(tabla).add(f);
        }
        return filas;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    private List<Map<String, Object>> leer(String tabla, List<String[]> params) {
        List<Map<String, Object>> out;
        List<Map<String, Object>> t = tabla(tabla);
        synchronized (t) { out = new ArrayList<>(t.stream().filter(filtro(params)).toList()); }
        String order = valor(params, "order");
        if (order != null) {
            Comparator<Map<String, Object>> cmp = null;
            for (String parte : order.split(",")) {
                String[] p = parte.split("\\.");
                boolean desc = p.length > 1 && "desc".equals(p[1]);
                Comparator<Map<String, Object>> c = (a, b) -> comparar(a.get(p[0]), b.get(p[0]));
                if (desc) c = c.reversed();
                cmp = cmp == null ? c : cmp.thenComparing(c);
            }
            out.sort(cmp);
        }
        int offset = parseInt(valor(params, "offset"), 0);
        int limit = parseInt(valor(params, "limit"), Integer.MAX_VALUE);
        if (offset >= out.size()) return new ArrayList<>();
        out = out.subList(offset, (int) Math.min(out.size(), (long) offset + limit));
        // Copias (quien lee no debe poder modificar la "base de datos" sin un PATCH) y, como
        // PostgREST: select=* trae todas las columnas (null si están vacías); select=a,b solo esas
        String select = valor(params, "select");
        Map<String, EsquemaSql.Columna> cols = esquema == null ? null : esquema.tablas.get(tabla);
        List<String> pedidas = select == null || select.contains("*") ? null
                : Arrays.stream(select.split(",")).map(String::trim).filter(c -> !c.contains("(")).toList();
        return out.stream().map(f -> {
            Map<String, Object> copia = new LinkedHashMap<>();
            if (pedidas != null) { for (String c : pedidas) copia.put(c, f.get(c)); return copia; }
            if (cols != null) for (String c : cols.keySet()) copia.put(c, null);
            copia.putAll(f);
            return copia;
        }).toList();
    }

    private static final Set<String> NO_FILTRO = Set.of("select", "order", "limit", "offset", "on_conflict", "columns");

    /** Como PostgREST: filtrar u ordenar por una columna que no existe es un error 400. */
    private void comprobarColumnas(String tabla, List<String[]> params) {
        if (esquema == null || !esquema.tablas.containsKey(tabla)) return;
        Map<String, EsquemaSql.Columna> cols = esquema.tablas.get(tabla);
        List<String> usadas = new ArrayList<>();
        for (String[] kv : params) {
            switch (kv[0]) {
                case "select", "limit", "offset", "on_conflict", "columns", "and" -> { }
                case "order" -> { for (String o : kv[1].split(",")) usadas.add(o.split("\\.")[0]); }
                case "or" -> { for (String parte : trocear(kv[1].replaceAll("^\\(|\\)$", ""))) if (!parte.startsWith("and(")) usadas.add(parte.split("\\.")[0]); }
                default -> { if (!kv[0].contains("->")) usadas.add(kv[0]); }
            }
        }
        for (String c : usadas)
            if (!c.isBlank() && !cols.containsKey(c)) {
                String motivo = "column " + tabla + "." + c + " does not exist";
                rechazos.add("GET " + tabla + ": " + motivo);
                throw new IllegalArgumentException(motivo);
            }
    }

    private Predicate<Map<String, Object>> filtro(List<String[]> params) {
        Predicate<Map<String, Object>> p = f -> true;
        for (String[] kv : params) {
            if (NO_FILTRO.contains(kv[0])) continue;
            if (kv[0].equals("or")) { Predicate<Map<String, Object>> o = or(kv[1]); p = p.and(o); continue; }
            if (kv[0].equals("and") || kv[0].contains("->")) continue;
            String col = kv[0], expr = kv[1];
            p = p.and(f -> cumple(f.get(col), expr));
        }
        return p;
    }

    /** or=(a.eq.1,b.is.null) */
    private Predicate<Map<String, Object>> or(String expr) {
        String dentro = expr.replaceAll("^\\(|\\)$", "");
        List<String> partes = trocear(dentro);
        return f -> {
            for (String parte : partes) {
                if (parte.startsWith("and(") || parte.startsWith("or(")) return true;
                int i = parte.indexOf('.');
                if (i < 0) continue;
                if (cumple(f.get(parte.substring(0, i)), parte.substring(i + 1))) return true;
            }
            return false;
        };
    }

    private static List<String> trocear(String s) {
        List<String> out = new ArrayList<>();
        int nivel = 0, ini = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') nivel++;
            else if (c == ')') nivel--;
            else if (c == ',' && nivel == 0) { out.add(s.substring(ini, i)); ini = i + 1; }
        }
        out.add(s.substring(ini));
        return out;
    }

    static boolean cumple(Object v, String expr) {
        if (expr.startsWith("not.")) return !cumple(v, expr.substring(4));
        int i = expr.indexOf('.');
        if (i < 0) return true;
        String op = expr.substring(0, i), val = expr.substring(i + 1);
        String s = v == null ? null : (v instanceof Map || v instanceof List) ? v.toString() : String.valueOf(v);
        switch (op) {
            case "eq": return s != null && iguales(v, val);
            case "neq": return s == null || !iguales(v, val);
            case "is": return switch (val) {
                case "null" -> v == null;
                case "true" -> Boolean.TRUE.equals(v) || "true".equals(s);
                case "false" -> Boolean.FALSE.equals(v) || "false".equals(s);
                default -> true;
            };
            case "in": {
                String lista = val.replaceAll("^\\(|\\)$", "");
                for (String x : lista.split(",")) if (s != null && iguales(v, x.replace("\"", "").trim())) return true;
                return false;
            }
            case "gt": return s != null && comparar(v, val) > 0;
            case "gte": return s != null && comparar(v, val) >= 0;
            case "lt": return s != null && comparar(v, val) < 0;
            case "lte": return s != null && comparar(v, val) <= 0;
            case "like": case "ilike": {
                if (s == null) return false;
                String re = Pattern.quote(val).replace("*", "\\E.*\\Q").replace("%", "\\E.*\\Q");
                return Pattern.compile("^" + re + "$", op.equals("ilike") ? Pattern.CASE_INSENSITIVE | Pattern.DOTALL : Pattern.DOTALL).matcher(s).matches();
            }
            case "cs": {
                if (!(v instanceof List<?> l)) return s != null && s.contains(val.replaceAll("[{}\\[\\]\"]", ""));
                for (String x : val.replaceAll("[{}\\[\\]\"]", "").split(",")) if (!x.isBlank() && l.stream().noneMatch(e -> String.valueOf(e).equals(x.trim()))) return false;
                return true;
            }
            default: return true;
        }
    }

    private static boolean iguales(Object v, String val) {
        String s = String.valueOf(v);
        if (s.equals(val)) return true;
        try { return Double.parseDouble(s) == Double.parseDouble(val); } catch (Exception e) { return false; }
    }

    static int comparar(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        String sa = String.valueOf(a), sb = String.valueOf(b);
        try { return Double.compare(Double.parseDouble(sa), Double.parseDouble(sb)); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(sa).compareTo(OffsetDateTime.parse(sb)); } catch (Exception ignored) { }
        return sa.compareTo(sb);
    }

    private static List<String[]> params(String raw) {
        List<String[]> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String par : raw.split("&")) {
            int i = par.indexOf('=');
            if (i <= 0) continue;
            out.add(new String[]{dec(par.substring(0, i)), dec(par.substring(i + 1))});
        }
        return out;
    }

    private static String dec(String s) { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }

    private static String valor(List<String[]> params, String k) {
        for (String[] p : params) if (p[0].equals(k)) return p[1];
        return null;
    }

    private static int parseInt(String s, int def) { try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; } }

    /** Storage: subir (POST o PUT, con o sin upsert) y borrar un fichero. */
    private Mono<ClientResponse> storage(ClientRequest req, String ruta) {
        if (req.method() == HttpMethod.DELETE) {
            ficheros.remove(ruta);
            return responder(200, "{}");
        }
        if (req.method() != HttpMethod.POST && req.method() != HttpMethod.PUT) return responder(405, "{}");
        String tipo = req.headers().getContentType() == null ? "application/octet-stream" : req.headers().getContentType().toString();
        return bytes(req).flatMap(datos -> {
            ficheros.put(ruta, new Fichero(tipo, datos));
            return responder(200, "{\"Key\":\"" + ruta.replace("\"", "") + "\"}");
        });
    }

    private static Mono<String> cuerpo(ClientRequest req) {
        return bytes(req).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    private static Mono<byte[]> bytes(ClientRequest req) {
        // El cuerpo ya serializado: se captura escribiéndolo en un ClientHttpRequest falso
        CapturaCuerpo c = new CapturaCuerpo();
        return req.writeTo(c, org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults())
                .then(Mono.fromSupplier(c::bytes));
    }

    private static Mono<ClientResponse> responder(int status, String json) {
        return Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                .header("Content-Type", "application/json").body(json).build());
    }

    /** ClientHttpRequest mínimo para obtener el cuerpo (JSON o un fichero) que la app envía. */
    static final class CapturaCuerpo implements org.springframework.http.client.reactive.ClientHttpRequest {
        private final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        private final org.springframework.core.io.buffer.DefaultDataBufferFactory factory = new org.springframework.core.io.buffer.DefaultDataBufferFactory();
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] bytes() { return out.toByteArray(); }
        @Override public HttpMethod getMethod() { return HttpMethod.POST; }
        @Override public java.net.URI getURI() { return java.net.URI.create("http://x"); }
        @Override public org.springframework.util.MultiValueMap<String, org.springframework.http.HttpCookie> getCookies() { return new org.springframework.util.LinkedMultiValueMap<>(); }
        @Override public <T> T getNativeRequest() { return null; }
        public Map<String, Object> getAttributes() { return new HashMap<>(); }
        @Override public org.springframework.core.io.buffer.DataBufferFactory bufferFactory() { return factory; }
        @Override public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> action) { }
        @Override public boolean isCommitted() { return false; }
        @Override public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends org.springframework.core.io.buffer.DataBuffer> body) {
            return reactor.core.publisher.Flux.from(body).doOnNext(b -> {
                byte[] trozo = new byte[b.readableByteCount()];
                b.read(trozo);
                out.writeBytes(trozo);
                DataBufferUtils.release(b);
            }).then();
        }
        @Override public Mono<Void> writeAndFlushWith(org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends org.springframework.core.io.buffer.DataBuffer>> body) {
            return reactor.core.publisher.Flux.from(body).concatMap(this::writeWith).then();
        }
        @Override public Mono<Void> setComplete() { return Mono.empty(); }
        @Override public org.springframework.http.HttpHeaders getHeaders() { return headers; }
    }
}
