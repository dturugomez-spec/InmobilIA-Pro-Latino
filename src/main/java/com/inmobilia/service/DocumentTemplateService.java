package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motor de plantillas de documentos con variables.
 *
 * Sintaxis de variables:
 *   {lead.nombre}           → dato del lead
 *   {propiedad.direccion}   → dato de propiedad
 *   {contrato.duracion}     → del contrato
 *   {hoy}, {hoy.dia}, {hoy.mes_letra}, {hoy.anio}
 *   {empresa.nombre}, {empresa.telefono}
 *   {numero_en_letras:precio}  → conversión número → letras
 *   {%importe:1234.56}      → formato monetario español: 1.234,56 €
 *
 * Detecta variables automáticamente al guardar.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentTemplateService {

    private final SupabaseService supabase;

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{([^}]+)\\}");
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    /** Detecta variables en una plantilla. */
    public List<String> detectarVariables(String contenido) {
        if (contenido == null) return List.of();
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = VAR_PATTERN.matcher(contenido);
        while (m.find()) vars.add(m.group(1));
        return new ArrayList<>(vars);
    }

    /** Rellena una plantilla con datos del contexto. */
    public String rellenar(String plantillaHtml, Map<String, Map<String, Object>> contexto) {
        if (plantillaHtml == null) return "";
        Matcher m = VAR_PATTERN.matcher(plantillaHtml);
        StringBuilder sb = new StringBuilder();

        while (m.find()) {
            String expr = m.group(1).trim();
            // Los datos van como texto: un lead que entra por un formulario público con
            // <img onerror=...> en el nombre ejecutaba código en el documento y en la firma.
            String valor = com.inmobilia.util.HtmlSeguro.escapar(resolverExpresion(expr, contexto));
            m.appendReplacement(sb, Matcher.quoteReplacement(valor));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolverExpresion(String expr, Map<String, Map<String, Object>> ctx) {
        // Funciones especiales
        if (expr.startsWith("hoy")) {
            return resolverHoy(expr);
        }
        if (expr.startsWith("%importe:")) {
            return formatearImporte(expr.substring(9));
        }
        if (expr.startsWith("numero_en_letras:")) {
            try {
                double v = Double.parseDouble(expr.substring(17).trim());
                return numeroEnLetras(v);
            } catch (Exception e) { return ""; }
        }

        // Acceso a campos: entidad.campo
        if (expr.contains(".")) {
            String[] parts = expr.split("\\.", 2);
            String entidad = parts[0];
            String campo = parts[1];
            Map<String, Object> obj = ctx.get(entidad);
            if (obj == null) return "{" + expr + "}";
            Object val = obj.get(campo);
            if (val == null) return "—";

            // Si el campo es una fecha o número, formatearlo bonito
            String s = String.valueOf(val);
            if (campo.contains("precio") || campo.contains("importe") || campo.contains("renta")) {
                try { return formatearImporte(s); } catch (Exception e) { return s; }
            }
            return s;
        }

        return "{" + expr + "}";  // var desconocida
    }

    private String resolverHoy(String expr) {
        LocalDate hoy = LocalDate.now();
        switch (expr) {
            case "hoy": return hoy.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            case "hoy.dia": return String.valueOf(hoy.getDayOfMonth());
            case "hoy.mes": return String.valueOf(hoy.getMonthValue());
            case "hoy.mes_letra": return MESES[hoy.getMonthValue() - 1];
            case "hoy.anio": return String.valueOf(hoy.getYear());
            case "hoy.completa":
                return String.format("%d de %s de %d",
                        hoy.getDayOfMonth(), MESES[hoy.getMonthValue() - 1], hoy.getYear());
            default: return hoy.toString();
        }
    }

    private String formatearImporte(String s) {
        try {
            double v = Double.parseDouble(s.replace(",", ".").trim());
            return new java.text.DecimalFormat("#,##0.00", new java.text.DecimalFormatSymbols(new Locale("es", "ES")))
                    .format(v) + " €";
        } catch (Exception e) { return s; }
    }

    /** Conversión simple número → letras (para contratos). */
    public String numeroEnLetras(double v) {
        long entero = (long) v;
        int decimal = (int) Math.round((v - entero) * 100);
        String txt = numeroALetras(entero) + " EUROS";
        if (decimal > 0) txt += " CON " + numeroALetras(decimal) + " CÉNTIMOS";
        return txt.toUpperCase();
    }

    private String numeroALetras(long n) {
        if (n == 0) return "cero";
        if (n < 0) return "menos " + numeroALetras(-n);

        String[] unidades = {"", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
                "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
                "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"};
        String[] decenas = {"", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"};
        String[] centenas = {"", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
                "seiscientos", "setecientos", "ochocientos", "novecientos"};

        if (n < 30) return unidades[(int) n];
        if (n < 100) {
            int d = (int) (n / 10), u = (int) (n % 10);
            return decenas[d] + (u > 0 ? " y " + unidades[u] : "");
        }
        if (n == 100) return "cien";
        if (n < 1000) {
            int c = (int) (n / 100), r = (int) (n % 100);
            return centenas[c] + (r > 0 ? " " + numeroALetras(r) : "");
        }
        if (n < 1_000_000) {
            long miles = n / 1000, resto = n % 1000;
            String prefijo = miles == 1 ? "mil" : numeroALetras(miles) + " mil";
            return prefijo + (resto > 0 ? " " + numeroALetras(resto) : "");
        }
        long millones = n / 1_000_000, resto = n % 1_000_000;
        String prefijo = millones == 1 ? "un millón" : numeroALetras(millones) + " millones";
        return prefijo + (resto > 0 ? " " + numeroALetras(resto) : "");
    }

    /** Carga el contexto desde IDs de entidades. */
    public Map<String, Map<String, Object>> cargarContexto(String userId, Map<String, String> ids) {
        Map<String, Map<String, Object>> ctx = new LinkedHashMap<>();

        // Empresa (del usuario)
        List<Map<String, Object>> users = supabase.selectFilter("users", "id=eq." + userId);
        if (!users.isEmpty()) {
            Map<String, Object> empresa = new LinkedHashMap<>();
            empresa.put("nombre", users.get(0).getOrDefault("empresa", "Mi Inmobiliaria"));
            empresa.put("telefono", users.get(0).getOrDefault("telefono", ""));
            empresa.put("email", users.get(0).getOrDefault("email", ""));
            empresa.put("nif", users.get(0).getOrDefault("nif", ""));
            empresa.put("direccion", users.get(0).getOrDefault("direccion", ""));
            ctx.put("empresa", empresa);
        }

        // Cargar entidades referenciadas
        cargar(ctx, "lead", "leads", ids.get("lead_id"));
        cargar(ctx, "propiedad", "propiedades", ids.get("propiedad_id"));
        cargar(ctx, "contrato", "contratos", ids.get("contrato_id"));
        cargar(ctx, "inquilino", "inquilinos", ids.get("inquilino_id"));
        cargar(ctx, "propietario", "propietarios", ids.get("propietario_id"));
        cargar(ctx, "oferta", "ofertas", ids.get("oferta_id"));

        return ctx;
    }

    private void cargar(Map<String, Map<String, Object>> ctx, String alias, String tabla, String id) {
        if (id == null || id.isBlank()) return;
        try {
            List<Map<String, Object>> rows = supabase.selectFilter(tabla, "id=eq." + id);
            if (!rows.isEmpty()) ctx.put(alias, rows.get(0));
        } catch (Exception ignored) {}
    }

    /** Genera plantillas del sistema en español para usuario nuevo. */
    public void crearPlantillasDelSistema(String userId) {
        // Solo si no tiene aún
        List<Map<String, Object>> exist = supabase.selectFilter(
                "plantillas_documento", "user_id=eq." + userId + "&es_sistema=eq.true");
        if (!exist.isEmpty()) return;

        crearPlantilla(userId, "📜 Mandato de venta (exclusiva)", "mandato",
                "Autorización exclusiva del propietario a la inmobiliaria para vender",
                MANDATO_VENTA_TPL);

        crearPlantilla(userId, "📝 Reserva de propiedad", "reserva",
                "Documento de reserva con señal antes de las arras",
                RESERVA_TPL);

        crearPlantilla(userId, "💰 Contrato de arras", "arras",
                "Arras penitenciales con cláusulas estándar art. 1454 CC",
                ARRAS_TPL);

        crearPlantilla(userId, "🏠 Contrato de arrendamiento LAU", "contrato_alquiler",
                "Vivienda habitual según Ley de Arrendamientos Urbanos",
                ALQUILER_TPL);
    }

    private void crearPlantilla(String userId, String nombre, String cat, String desc, String html) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", userId);
        data.put("nombre", nombre);
        data.put("categoria", cat);
        data.put("descripcion", desc);
        data.put("contenido_html", html);
        data.put("variables_detectadas", detectarVariables(html));
        data.put("es_sistema", true);
        supabase.insert("plantillas_documento", data);
    }

    // === Plantillas predefinidas ===
    private static final String MANDATO_VENTA_TPL = """
            <h1>MANDATO DE VENTA EN EXCLUSIVA</h1>
            <p>En <strong>{empresa.direccion}</strong>, a <strong>{hoy.completa}</strong>.</p>
            <h2>REUNIDOS</h2>
            <p>De una parte, <strong>{propietario.nombre}</strong>, con NIF <strong>{propietario.dni}</strong>,
            domicilio en <strong>{propietario.direccion}</strong> (en adelante, "EL PROPIETARIO").</p>
            <p>De otra parte, <strong>{empresa.nombre}</strong>, con NIF <strong>{empresa.nif}</strong>
            (en adelante, "LA INMOBILIARIA").</p>
            <h2>OBJETO</h2>
            <p>EL PROPIETARIO encarga a LA INMOBILIARIA la venta en exclusiva del inmueble sito en
            <strong>{propiedad.direccion}</strong>, por un precio de venta de <strong>{%importe:propiedad.precio}</strong>
            (<em>{numero_en_letras:propiedad.precio}</em>).</p>
            <h2>COMISIÓN</h2>
            <p>La comisión pactada es del 3% + IVA sobre el precio final de venta.</p>
            <h2>DURACIÓN</h2>
            <p>El presente mandato tendrá una duración de 6 meses prorrogables.</p>
            <p>Firmado en {empresa.direccion} a {hoy.completa}.</p>
            """;

    private static final String RESERVA_TPL = """
            <h1>DOCUMENTO DE RESERVA</h1>
            <p>En a {hoy.completa}, D./Dña. <strong>{lead.nombre}</strong>, con NIF <strong>{lead.dni}</strong>,
            entrega a <strong>{empresa.nombre}</strong> la cantidad de <strong>3.000 €</strong> en concepto de RESERVA
            para la adquisición del inmueble:</p>
            <ul>
              <li><strong>Dirección:</strong> {propiedad.direccion}</li>
              <li><strong>Referencia catastral:</strong> {propiedad.referencia_catastral}</li>
              <li><strong>Precio de venta:</strong> {%importe:propiedad.precio}</li>
            </ul>
            <p>La presente reserva tendrá una validez de 15 días naturales para la firma del contrato de arras.</p>
            <p>Si transcurrido dicho plazo el comprador desiste, perderá la cantidad entregada.</p>
            """;

    private static final String ARRAS_TPL = """
            <h1>CONTRATO DE ARRAS PENITENCIALES</h1>
            <p>En {empresa.direccion}, a {hoy.completa}.</p>
            <h2>REUNIDOS</h2>
            <p>De una parte, <strong>{propietario.nombre}</strong>, NIF {propietario.dni} ("PARTE VENDEDORA").</p>
            <p>De otra, <strong>{lead.nombre}</strong>, NIF {lead.dni} ("PARTE COMPRADORA").</p>
            <h2>EXPONEN</h2>
            <p>I.- La PARTE VENDEDORA es propietaria de la finca sita en <strong>{propiedad.direccion}</strong>.</p>
            <p>II.- Ambas partes acuerdan la compraventa por <strong>{%importe:propiedad.precio}</strong>.</p>
            <h2>PACTAN</h2>
            <p><strong>PRIMERA.</strong> La PARTE COMPRADORA entrega <strong>{%importe:oferta.deposito_arras}</strong>
            en concepto de arras penitenciales (art. 1454 CC).</p>
            <p><strong>SEGUNDA.</strong> La escritura de compraventa se otorgará antes del
            <strong>{oferta.fecha_caducidad}</strong>.</p>
            <p><strong>TERCERA.</strong> En caso de desistimiento del COMPRADOR, perderá la cantidad entregada.
            Si desiste el VENDEDOR, devolverá el doble.</p>
            """;

    private static final String ALQUILER_TPL = """
            <h1>CONTRATO DE ARRENDAMIENTO DE VIVIENDA HABITUAL</h1>
            <p>En {empresa.direccion}, a {hoy.completa}.</p>
            <h2>REUNIDOS</h2>
            <p>De una parte, <strong>{propietario.nombre}</strong>, NIF {propietario.dni} ("ARRENDADOR").</p>
            <p>De otra, <strong>{inquilino.nombre}</strong>, NIF {inquilino.dni} ("ARRENDATARIO").</p>
            <h2>EXPONEN</h2>
            <p>Que el ARRENDADOR cede en arrendamiento al ARRENDATARIO la vivienda sita en
            <strong>{propiedad.direccion}</strong>, con referencia catastral {propiedad.referencia_catastral}.</p>
            <h2>CLÁUSULAS</h2>
            <p><strong>1ª RENTA.</strong> La renta mensual será de <strong>{%importe:contrato.renta_mensual}</strong>
            pagaderos en los 5 primeros días de cada mes.</p>
            <p><strong>2ª DURACIÓN.</strong> El plazo del arrendamiento es de 5 años, prorrogable según LAU.</p>
            <p><strong>3ª FIANZA.</strong> El ARRENDATARIO entrega una fianza de
            <strong>{%importe:contrato.fianza}</strong> (1 mensualidad).</p>
            <p><strong>4ª GASTOS.</strong> Los suministros (agua, luz, gas) serán por cuenta del ARRENDATARIO.</p>
            <p>Y en prueba de conformidad, firman el presente contrato por duplicado.</p>
            """;
}
