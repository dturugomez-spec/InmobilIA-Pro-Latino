package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.EjemplosService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Dos empresas en la misma app: la A con todos los ejemplos (inquilinos, contratos, activos,
 * facturas, leads, equipo…) y la B vacía. Con su propia sesión, la B:
 *
 *  1. VER: abre todas las pantallas del menú, todas las fichas y documentos que la A tiene
 *     enlazados, y todas las rutas de la app que llevan un identificador, con los de la A.
 *     En nada de lo que recibe puede aparecer un nombre, email, dirección, referencia… ni un
 *     identificador de la A (salvo el que ella misma ha puesto en la dirección).
 *  2. TOCAR: envía los formularios que la A tiene en sus pantallas (con los ids de la A), llama a
 *     todas las acciones de la app con los ids de la A y envía sus propios formularios metiendo a
 *     mano el id de la A como dueño. Ningún dato de la A puede cambiar, borrarse ni aparecer nuevo.
 *
 * El informe queda en target/dos-empresas.txt.
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "ADMIN_EMAILS=nadie@inmobiliapro.example.com",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class DosEmpresasTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String A = "00000000-0000-4000-8000-0000000000a1";
    static final String B = "00000000-0000-4000-8000-0000000000b2";

    @TestConfiguration
    static class Config {
        @Bean(name = "httpClient")
        java.net.http.HttpClient httpClient() throws Exception {
            java.net.http.HttpClient c = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
            org.mockito.Mockito.when(c.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new java.io.IOException("sin red en pruebas"));
            org.mockito.Mockito.when(c.sendAsync(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("sin red en pruebas")));
            return c;
        }

        @Bean
        @Primary
        WebClient webClientDePrueba() {
            return WebClient.builder().exchangeFunction(BD)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired EjemplosService ejemplos;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapeo;

    private static void cuenta(String id, String email, String nombre) {
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", id, "email", email, "name", nombre, "role", "admin",
                "active", true, "plan", "agency", "plan_status", "active", "stripe_subscription_id", "sub_" + id.substring(34),
                "ai_credits", 100)));
    }

    private static MockHttpSession sesion(String org, String email) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, org, org, email, "Admin", "admin");
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private MockHttpSession deB() { return sesion(B, "admin@empresa-b.example.com"); }
    private MockHttpSession deA() { return sesion(A, "admin@empresa-a.example.com"); }

    record Respuesta(int status, String destino, String cuerpo, String tipo, int bytes) {
        boolean archivo() { return status == 200 && bytes > 0 && tipo != null && !tipo.contains("html") && !tipo.contains("json") && !tipo.contains("text") && !tipo.contains("xml"); }
    }

    private Respuesta pedir(MockHttpServletRequestBuilder req) {
        try {
            MvcResult r = mvc.perform(req).andReturn();
            String tipo = r.getResponse().getContentType();
            String cuerpo = tipo == null || tipo.contains("html") || tipo.contains("json") || tipo.contains("text") || tipo.contains("xml")
                    ? r.getResponse().getContentAsString(StandardCharsets.UTF_8) : "";
            return new Respuesta(r.getResponse().getStatus(), r.getResponse().getRedirectedUrl(), cuerpo, tipo,
                    r.getResponse().getContentAsByteArray().length);
        } catch (Exception e) {
            return new Respuesta(500, null, "", null, 0);
        }
    }

    // ─── Datos de la empresa A ──────────────────────────────────────────

    private static final Pattern UUID_RE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Campos que identifican a alguien o algo de la empresa: nombres, emails, direcciones, referencias… */
    private static final Set<String> CAMPOS_HUELLA = Set.of("nombre", "name", "email", "titulo", "title", "reference", "referencia",
            "numero", "full_number", "address", "direccion", "tenant_name", "property_name", "inquilino_nombre", "cliente",
            "client_name", "cliente_nombre", "asunto", "subject", "telefono", "phone", "dni", "nif", "cif", "id_number",
            "visitante_nombre", "lead_nombre", "propietario_nombre", "empresa", "legal_name", "contact_email", "descripcion");

    /** Copia de todas las filas de la A: tabla → id → fila. */
    private static Map<String, Map<String, Map<String, Object>>> foto() {
        Map<String, Map<String, Map<String, Object>>> f = new TreeMap<>();
        BD.tablas.forEach((t, filas) -> {
            Map<String, Map<String, Object>> m = new LinkedHashMap<>();
            synchronized (filas) {
                for (Map<String, Object> fila : filas) {
                    String id = String.valueOf(fila.get("id"));
                    if (!esDeB(t, fila)) m.put(id, new LinkedHashMap<>(fila));
                }
            }
            if (!m.isEmpty()) f.put(t, m);
        });
        return f;
    }

    private static final List<String> DUENO = List.of("user_id", "org_id", "organization_id", "agency_id", "owner_id", "id");

    private static boolean esDeB(String tabla, Map<String, Object> fila) {
        for (String k : DUENO) if (B.equals(String.valueOf(fila.get(k)))) return true;
        return false;
    }

    private static boolean esDeA(Map<String, Object> fila) {
        for (String k : DUENO) if (A.equals(String.valueOf(fila.get(k)))) return true;
        return false;
    }

    /** Diferencias entre la foto de la A y lo que hay ahora (cambiado, borrado o nuevo con dueño A). */
    private static List<String> cambios(Map<String, Map<String, Map<String, Object>>> antes) {
        List<String> out = new ArrayList<>();
        Map<String, Map<String, Map<String, Object>>> ahora = foto();
        for (var t : antes.entrySet()) {
            Map<String, Map<String, Object>> filas = ahora.getOrDefault(t.getKey(), Map.of());
            for (var e : t.getValue().entrySet()) {
                Map<String, Object> f = filas.get(e.getKey());
                if (f == null) { out.add("borra " + t.getKey() + " " + e.getKey()); continue; }
                for (var c : e.getValue().entrySet())
                    if (!Objects.equals(String.valueOf(c.getValue()), String.valueOf(f.get(c.getKey()))))
                        out.add("cambia " + t.getKey() + "." + c.getKey() + " de " + e.getKey());
                for (String k : f.keySet()) if (!e.getValue().containsKey(k)) out.add("añade " + t.getKey() + "." + k + " a " + e.getKey());
            }
        }
        for (var t : ahora.entrySet())
            for (var e : t.getValue().entrySet())
                if (!antes.getOrDefault(t.getKey(), Map.of()).containsKey(e.getKey()) && esDeA(e.getValue()))
                    out.add("crea en la A " + t.getKey() + " " + e.getKey());
        return out;
    }

    /** Deja los datos de la A como en la foto (para seguir probando tras un fallo). */
    private static void restaurar(Map<String, Map<String, Map<String, Object>>> antes) {
        BD.tablas.forEach((t, filas) -> {
            synchronized (filas) {
                filas.removeIf(f -> !esDeB(t, f) && (antes.getOrDefault(t, Map.of()).containsKey(String.valueOf(f.get("id"))) || esDeA(f)));
            }
        });
        antes.forEach((t, m) -> m.values().forEach(f -> BD.tabla(t).add(new LinkedHashMap<>(f))));
    }

    // ─── Formularios ────────────────────────────────────────────────────

    record Formulario(String accion, Map<String, String> campos) {}

    private static final Pattern FORM = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern ATR = Pattern.compile("(?is)([a-z:-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern CAMPO = Pattern.compile("(?is)<(input|textarea|select)\\b([^>]*)>(?:(.*?)</\\1>)?");
    private static final Pattern OPCION = Pattern.compile("(?is)<option\\b([^>]*)>");

    static List<Formulario> formularios(String html) {
        List<Formulario> out = new ArrayList<>();
        Matcher m = FORM.matcher(html);
        while (m.find()) {
            Map<String, String> atr = atributos(m.group(1));
            if (!"post".equalsIgnoreCase(atr.getOrDefault("method", ""))) continue;
            String accion = atr.getOrDefault("action", "").replace("&amp;", "&");
            if (!accion.startsWith("/") || accion.startsWith("/logout")) continue;
            Map<String, String> campos = new LinkedHashMap<>();
            Matcher c = CAMPO.matcher(m.group(2));
            while (c.find()) {
                Map<String, String> a = atributos(c.group(2));
                String nombre = a.get("name");
                if (nombre == null || nombre.isBlank()) continue;
                String valor = switch (c.group(1).toLowerCase()) {
                    case "textarea" -> c.group(3) == null ? "" : c.group(3).trim();
                    case "select" -> primeraOpcion(c.group(3));
                    default -> {
                        String tipo = a.getOrDefault("type", "text").toLowerCase();
                        if ((tipo.equals("checkbox") || tipo.equals("radio")) && !a.containsKey("checked")) yield null;
                        if (tipo.equals("file")) yield null;
                        yield a.getOrDefault("value", tipo.equals("number") ? "1" : tipo.equals("email") ? "prueba@empresa-b.example.com" : "Prueba B");
                    }
                };
                if (valor != null) campos.putIfAbsent(nombre, valor.replace("&amp;", "&"));
            }
            out.add(new Formulario(accion, campos));
        }
        return out;
    }

    private static String primeraOpcion(String html) {
        if (html == null) return "";
        Matcher o = OPCION.matcher(html);
        String primera = null;
        while (o.find()) {
            Map<String, String> a = atributos(o.group(1));
            if (primera == null) primera = a.getOrDefault("value", "");
            if (a.containsKey("selected")) return a.getOrDefault("value", "");
        }
        return primera == null ? "" : primera;
    }

    private static Map<String, String> atributos(String s) {
        Map<String, String> a = new HashMap<>();
        Matcher m = ATR.matcher(s);
        while (m.find()) a.put(m.group(1).toLowerCase(), m.group(2));
        for (String suelto : List.of("checked", "selected"))
            if (Pattern.compile("(?i)(^|\\s)" + suelto + "(\\s|$|=)").matcher(s).find()) a.putIfAbsent(suelto, "");
        return a;
    }

    // ─── La prueba ──────────────────────────────────────────────────────

    @Test
    void ningunaEmpresaVeNiTocaLoDeLaOtra() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        cuenta(A, "admin@empresa-a.example.com", "Admin A");
        cuenta(B, "admin@empresa-b.example.com", "Admin B");
        StringBuilder informe = new StringBuilder();
        List<String> fugas = new ArrayList<>();

        // La B antes de que la A tenga nada: lo que es de la propia app (textos, plantillas…)
        List<String> rutas = PantallasConEjemplosTest.rutasDelMenu();
        Map<String, String> antesB = new LinkedHashMap<>();
        for (String r : rutas) antesB.put(r, pedir(get(r).session(deB())).cuerpo());
        String todoAntes = String.join("\n", antesB.values()).toLowerCase();

        Map<String, Object> carga = ejemplos.cargarAhora(A);
        assertTrue(String.valueOf(carga.get("mensaje")).startsWith("Ejemplos cargados"), String.valueOf(carga));

        // Huellas de la A: lo que no puede aparecer nunca en lo que recibe la B
        Map<String, String> huellas = new LinkedHashMap<>();
        Set<String> idsA = new HashSet<>();
        Map<String, List<String>> idsPorTabla = new TreeMap<>();
        BD.tablas.forEach((t, filas) -> filas.forEach(f -> {
            if (esDeB(t, f)) return;
            String id = String.valueOf(f.get("id"));
            if (UUID_RE.matcher(id).matches()) { idsA.add(id); idsPorTabla.computeIfAbsent(t, k -> new ArrayList<>()).add(id); }
            for (String k : CAMPOS_HUELLA) {
                Object v = f.get(k);
                if (!(v instanceof String s)) continue;
                s = s.trim();
                if (s.length() < 7 || !s.matches(".*[a-zA-Zá-úÁ-Ú].*") || s.length() > 120) continue;
                if (todoAntes.contains(s.toLowerCase())) continue;   // texto de la propia app, no de la A
                huellas.putIfAbsent(s, t + "." + k);
            }
        }));
        informe.append("Huellas de la A: ").append(huellas.size()).append(" · ids de la A: ").append(idsA.size()).append("\n\n");

        // La A recorre su app: fichas, documentos y formularios que tiene enlazados
        Set<String> detalles = new TreeSet<>();
        List<Formulario> formulariosA = new ArrayList<>();
        for (String r : rutas) {
            Respuesta a = pedir(get(r).session(deA()));
            detalles.addAll(PantallasConEjemplosTest.enlacesDeDetalle(a.cuerpo()));
            formulariosA.addAll(formularios(a.cuerpo()));
        }
        for (String d : new ArrayList<>(detalles)) {   // un nivel más: lo enlazado desde las fichas
            Respuesta a = pedir(get(d).session(deA()));
            formulariosA.addAll(formularios(a.cuerpo()));
        }
        Map<String, Map<String, Map<String, Object>>> fotoA = foto();   // los datos de la A tal como están: nada de lo que haga la B puede cambiarlos

        // ── 1. VER ──
        informe.append("== Pantallas del menú (B) ==\n");
        for (String r : rutas) {
            revisar("VE  " + r, pedir(get(r).session(deB())), huellas, idsA, Set.of(), fugas, informe);
            comprobarCambios("TOCA al abrir " + r, null, fotoA, fugas, informe);
        }

        informe.append("\n== Fichas y documentos de la A abiertos por la B: ").append(detalles.size()).append(" ==\n");
        for (String d : detalles) {
            Respuesta b = pedir(get(d).session(deB()));
            comprobarCambios("TOCA al abrir " + d, b, fotoA, fugas, informe);
            if (b.archivo() && !mismoArchivoQueConIdInventado(d, b)) { fuga("VE  " + d + " → descarga un archivo de la A (" + b.tipo() + ")", fugas, informe); continue; }
            Respuesta anonimo = pedir(get(d));
            if (contieneHuella(anonimo.cuerpo(), huellas, idsDe(d)) != null && b.status() == 200) {
                informe.append("PÚBLICA (la ve cualquiera, sin cuenta) ").append(d).append("\n");
                continue;
            }
            revisar("VE  " + d, b, huellas, idsA, idsDe(d), fugas, informe);
        }

        // Todas las rutas de la app con identificador, con ids de la A de la tabla que toca
        List<String[]> plantillas = rutasConId();
        informe.append("\n== Rutas con identificador: ").append(plantillas.size()).append(" ==\n");
        int pedidas = 0;
        for (String[] p : plantillas) {
            if (!p[0].equals("GET")) continue;
            for (String id : idsCandidatos(p[1], idsPorTabla)) {
                String url = p[1].replaceAll("\\{[^}]+}", id);
                Respuesta b = pedir(get(url).session(deB()));
                pedidas++;
                comprobarCambios("TOCA al abrir " + url, b, fotoA, fugas, informe);
                if (b.archivo() && !mismoArchivoQueConIdInventado(url, b)) { fuga("VE  " + url + " → descarga un archivo de la A (" + b.tipo() + ")", fugas, informe); continue; }
                if (contieneHuella(b.cuerpo(), huellas, Set.of(id)) == null) continue;
                Respuesta anonimo = pedir(get(url));
                if (contieneHuella(anonimo.cuerpo(), huellas, Set.of(id)) != null) {
                    informe.append("PÚBLICA (la ve cualquiera, sin cuenta) ").append(url).append("\n");
                    continue;
                }
                revisar("VE  " + url, b, huellas, idsA, Set.of(id), fugas, informe);
            }
        }
        informe.append("Peticiones GET con ids de la A: ").append(pedidas).append("\n");

        // Un inmueble que la A no ha publicado no se ve en su ficha pública ni sale en el mapa para Google
        Map<String, Object> privado = BD.tabla("propiedades").stream().filter(DosEmpresasTest::esDeA).findFirst().orElseThrow();
        Map<String, Object> publico = BD.tabla("propiedades").stream().filter(DosEmpresasTest::esDeA).skip(1).findFirst().orElseThrow();
        privado.put("publicada", false);
        publico.put("publicada", true);
        String idPrivado = String.valueOf(privado.get("id"));
        String mapa = pedir(get("/sitemap.xml")).cuerpo();
        if (mapa.contains(idPrivado)) fuga("VE  /sitemap.xml → lista un inmueble no publicado de la A", fugas, informe);
        if (!mapa.contains(String.valueOf(publico.get("id")))) fuga("El sitemap ya no lista los inmuebles publicados (comprobación)", fugas, informe);
        if (pedir(get("/propiedad/" + idPrivado)).cuerpo().contains(String.valueOf(privado.get("name"))))
            fuga("VE  /propiedad/" + idPrivado + " → enseña sin cuenta un inmueble no publicado", fugas, informe);
        if (pedir(get("/reservas-online/p/" + idPrivado)).cuerpo().contains(String.valueOf(privado.get("name"))))
            fuga("VE  /reservas-online/p/" + idPrivado + " → enseña sin cuenta un inmueble no publicado", fugas, informe);
        fotoA = foto();

        // ── 2. TOCAR ── (y lo que devuelve cada acción tampoco puede llevar datos de la A)
        informe.append("\n== Formularios de la A enviados por la B ==\n");
        Set<String> enviados = new HashSet<>();
        for (Formulario f : formulariosA) {
            if (!enviados.add(f.accion() + f.campos().keySet())) continue;
            boolean conIdA = idsA.stream().anyMatch(id -> f.accion().contains(id) || f.campos().containsValue(id));
            if (!conIdA) continue;
            Set<String> enviado = new HashSet<>(f.campos().values());
            enviado.addAll(idsDe(f.accion()));
            tocar("TOCA " + f.accion() + " (formulario de la A)", ses -> {
                MockHttpServletRequestBuilder req = conSesion(post(f.accion()), ses);
                f.campos().forEach(req::param);
                return req;
            }, enviado, fotoA, huellas, idsA, fugas, informe);
        }
        int acciones = 0;
        for (String[] p : plantillas) {
            if (p[0].equals("GET")) continue;
            for (String id : idsCandidatos(p[1], idsPorTabla)) {
                String url = p[1].replaceAll("\\{[^}]+}", id);
                acciones++;
                tocar("TOCA " + p[0] + " " + url, ses -> conSesion(post(url), ses).param("id", id), Set.of(id), fotoA, huellas, idsA, fugas, informe);
            }
        }
        // CRUD genérico: editar y borrar cada fila de la A con su id
        for (var t : idsPorTabla.entrySet()) {
            for (String id : t.getValue()) {
                acciones += 2;
                tocar("TOCA /crud/" + t.getKey() + " id=" + id, ses -> conSesion(post("/crud/" + t.getKey()), ses)
                        .param("id", id).param("name", "Cambiado por B").param("nombre", "Cambiado por B"),
                        Set.of(id, "Cambiado por B"), fotoA, huellas, idsA, fugas, informe);
                tocar("TOCA /crud/" + t.getKey() + "/" + id + "/delete", ses -> conSesion(post("/crud/" + t.getKey() + "/" + id + "/delete"), ses),
                        Set.of(id), fotoA, huellas, idsA, fugas, informe);
            }
        }
        informe.append("Acciones con ids de la A: ").append(acciones).append("\n");

        // Formularios propios de la B con la A metida a mano como dueña
        informe.append("\n== Formularios de la B con user_id/org_id de la A ==\n");
        Set<String> propios = new HashSet<>();
        for (String r : rutas) {
            for (Formulario f : formularios(antesB.get(r))) {
                if (!propios.add(f.accion() + f.campos().keySet())) continue;
                if (f.accion().matches(".*/(delete|borrar|eliminar|quitar|cancelar|baja|logout).*") || f.accion().startsWith("/account")) continue;
                Set<String> enviado = new HashSet<>(f.campos().values());
                enviado.add(A);
                tocar("TOCA " + f.accion() + " (formulario de la B con dueño A)", ses -> {
                    MockHttpServletRequestBuilder req = conSesion(post(f.accion()), ses);
                    f.campos().forEach(req::param);
                    for (String k : List.of("user_id", "org_id", "organization_id", "agency_id", "owner_id", "uid", "orgId", "userId"))
                        req.param(k, A);
                    return req;
                }, enviado, fotoA, huellas, idsA, fugas, informe);
            }
        }

        Path salida = Path.of("target", "dos-empresas.txt");
        Files.createDirectories(salida.getParent());
        Files.writeString(salida, informe + "\nFUGAS (" + fugas.size() + "):\n" + String.join("\n", fugas) + "\n");
        System.out.println(informe);
        assertTrue(fugas.isEmpty(), "La empresa B ve o toca datos de la A (" + fugas.size() + "), ver target/dos-empresas.txt:\n"
                + String.join("\n", fugas.subList(0, Math.min(40, fugas.size()))));
    }

    private void revisar(String que, Respuesta r, Map<String, String> huellas, Set<String> idsA, Set<String> permitidos,
                         List<String> fugas, StringBuilder informe) {
        String h = contieneHuella(r.cuerpo(), huellas, permitidos);
        if (h == null) {
            for (String id : idsA) if (!permitidos.contains(id) && r.cuerpo().contains(id)) { h = "id de la A " + id; break; }
        }
        if (h != null) {
            String linea = que + " → " + r.status() + " muestra " + h;
            fugas.add(linea);
            informe.append("FUGA ").append(linea).append("\n");
        }
    }

    private void fuga(String linea, List<String> fugas, StringBuilder informe) {
        fugas.add(linea);
        informe.append("FUGA ").append(linea).append("\n");
    }

    /** ¿Con un id que no existe sale el mismo archivo? Entonces no es un documento de la A (es genérico). */
    private boolean mismoArchivoQueConIdInventado(String url, Respuesta conIdA) {
        String inventada = UUID_RE.matcher(url).replaceAll("11111111-2222-4333-8444-555555555555");
        Respuesta otra = pedir(get(inventada).session(deB()));
        return otra.archivo() && Math.abs(otra.bytes() - conIdA.bytes()) < 64;
    }

    /** Primera huella de la A en el cuerpo, salvo las que vienen en lo que la propia B ha mandado. */
    private static String contieneHuella(String cuerpo, Map<String, String> huellas, Set<String> permitidos) {
        if (cuerpo == null || cuerpo.isEmpty()) return null;
        String bajo = cuerpo.toLowerCase();
        for (var e : huellas.entrySet()) {
            String h = e.getKey().toLowerCase();
            if (!bajo.contains(h)) continue;
            if (permitidos.stream().anyMatch(x -> x != null && x.toLowerCase().contains(h))) continue;
            return "«" + e.getKey() + "» (" + e.getValue() + ")";
        }
        return null;
    }

    private void comprobarCambios(String que, Respuesta r, Map<String, Map<String, Map<String, Object>>> foto,
                                  List<String> fugas, StringBuilder informe) {
        List<String> c = cambios(foto);
        if (c.isEmpty()) return;
        String linea = que + (r == null ? "" : " → " + r.status()) + ": " + String.join("; ", c.subList(0, Math.min(5, c.size())));
        fugas.add(linea);
        informe.append("FUGA ").append(linea).append("\n");
        restaurar(foto);
    }

    private static MockHttpServletRequestBuilder conSesion(MockHttpServletRequestBuilder req, MockHttpSession ses) {
        return ses == null ? req : req.session(ses);
    }

    /**
     * La B hace la petición. Fuga si la respuesta lleva datos de la A (que no haya mandado ella) o si
     * cambia algo de la A, salvo que lo mismo pase sin cuenta: entonces es un formulario público
     * (p. ej. «contactar» en la ficha pública de un inmueble) y se apunta aparte.
     */
    private void tocar(String que, java.util.function.Function<MockHttpSession, MockHttpServletRequestBuilder> peticion,
                       Set<String> enviado, Map<String, Map<String, Map<String, Object>>> foto, Map<String, String> huellas,
                       Set<String> idsA, List<String> fugas, StringBuilder informe) {
        Respuesta r = pedir(peticion.apply(deB()));
        String h = contieneHuella(r.cuerpo(), huellas, enviado);
        if (h == null) for (String id : idsA) if (!enviado.contains(id) && r.cuerpo().contains(id)) { h = "id de la A " + id; break; }
        if (h != null) {
            Respuesta anonimo = pedir(peticion.apply(null));   // ¿lo devuelve igual sin cuenta? entonces es una página pública
            if (contieneHuella(anonimo.cuerpo(), huellas, enviado) != null) informe.append("PÚBLICA (la ve cualquiera, sin cuenta) ").append(que).append("\n");
            else fuga(que + " → " + r.status() + " devuelve " + h, fugas, informe);
        }
        List<String> c = cambios(foto);
        if (c.isEmpty()) return;
        restaurar(foto);
        pedir(peticion.apply(null));   // lo mismo, sin cuenta
        boolean publica = !cambios(foto).isEmpty();
        restaurar(foto);
        if (publica) { informe.append("PÚBLICA (la usa cualquiera, sin cuenta) ").append(que).append("\n"); return; }
        fuga(que + " → " + r.status() + ": " + String.join("; ", c.subList(0, Math.min(5, c.size()))), fugas, informe);
    }

    private static Set<String> idsDe(String url) {
        Set<String> s = new HashSet<>();
        Matcher m = UUID_RE.matcher(url);
        while (m.find()) s.add(m.group());
        return s;
    }

    /** Rutas de la app (método, patrón) con algún identificador en el camino; sin las públicas por token. */
    private List<String[]> rutasConId() {
        List<String[]> out = new ArrayList<>();
        for (RequestMappingInfo info : mapeo.getHandlerMethods().keySet()) {
            Set<RequestMethod> metodos = info.getMethodsCondition().getMethods();
            for (String p : info.getPatternValues()) {
                if (!p.contains("{")) continue;
                String vars = String.join(" ", Pattern.compile("\\{[^}]+}").matcher(p).results().map(x -> x.group().toLowerCase()).toList());
                if (vars.matches(".*(token|codigo|code|slug|hash|clave|key|ref|tabla|table|pack|plan|tipo|cat|nombre|name|provincia|anio|year|mes|formato|archivo|file|path|\\*).*"))
                    continue;
                if (metodos.isEmpty() || metodos.contains(RequestMethod.GET)) out.add(new String[]{"GET", p});
                if (metodos.contains(RequestMethod.POST) || metodos.contains(RequestMethod.PUT)
                        || metodos.contains(RequestMethod.PATCH) || metodos.contains(RequestMethod.DELETE)) out.add(new String[]{"POST", p});
            }
        }
        out.sort(Comparator.comparing((String[] x) -> x[1]));
        return out;
    }

    private static final List<String> TABLAS_BASE = List.of("propiedades", "leads", "inquilinos", "contratos", "invoices",
            "receipts", "tareas", "visitas", "documentos", "transacciones", "propietarios", "oportunidades");

    /** Ids de la A para probar en una ruta: de las tablas cuyo nombre se parece a la ruta, o de las principales. */
    private static List<String> idsCandidatos(String patron, Map<String, List<String>> idsPorTabla) {
        List<String> trozos = Arrays.stream(patron.toLowerCase().split("[/{}_-]+")).filter(s -> s.length() >= 4).toList();
        List<String> tablas = new ArrayList<>();
        for (String t : idsPorTabla.keySet()) {
            String raiz = t.length() > 5 ? t.substring(0, t.length() - 2) : t;
            if (trozos.stream().anyMatch(s -> t.contains(s.length() > 5 ? s.substring(0, s.length() - 1) : s) || s.contains(raiz))) tablas.add(t);
        }
        if (tablas.isEmpty()) tablas.addAll(TABLAS_BASE);
        List<String> ids = new ArrayList<>();
        for (String t : tablas) {
            List<String> l = idsPorTabla.getOrDefault(t, List.of());
            ids.addAll(l.subList(0, Math.min(2, l.size())));
        }
        return ids.stream().distinct().limit(16).toList();
    }
}
