package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Negotiation Co-Pilot: prepara una negociación de compra, venta o alquiler.
 *
 *  1. Referencia: el valor (o la renta) que la cuenta tiene apuntado del activo; si no lo hay, la
 *     valoración orientativa de ValoracionService (no para naves: sin dato propio no se inventa).
 *     En alquiler, si no hay renta apuntada, el valor × una rentabilidad bruta típica del tipo.
 *  2. Rango: precio de apertura, objetivo y límite, según si pagas (compras/arriendas) o cobras.
 *  3. La IA escribe argumentos, respuestas a objeciones, concesiones y un guion con esos números.
 *     Sin IA (o sin créditos) sale una preparación básica con plantilla.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NegociacionService {

    private final SupabaseService supabase;
    private final ValoracionService valoracion;
    private final AIService ai;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Rentabilidad bruta anual típica por tipo de activo (para estimar una renta). */
    static final Map<String, Double> RENTABILIDAD = Map.of("vivienda", 0.05, "local", 0.065, "oficina", 0.06, "nave", 0.07);

    public record Rango(Double referencia, double apertura, double objetivo, double limite, String base, boolean pago) {}

    /** ¿Quien prepara la negociación es quien paga (compra o arrienda)? */
    static boolean paga(String rol) {
        return "compro".equals(rol) || "arriendo".equals(rol);
    }

    static String tipoDe(Map<String, Object> activo, String tipoForm) {
        String t = norm((tipoForm == null ? "" : tipoForm) + " " + str(activo.get("type")) + " " + str(activo.get("name")));
        if (t.matches(".*\\b(nave|industrial|logistic\\w*|almacen)\\b.*")) return "nave";
        if (t.matches(".*\\b(local|comercial|tienda)\\b.*")) return "local";
        if (t.matches(".*\\b(oficina|despacho)\\b.*")) return "oficina";
        return "vivienda";
    }

    /** Referencia de mercado: valor (compra/venta) o renta mensual (alquiler). Null si no hay base fiable. */
    Double referencia(String org, String operacion, Map<String, Object> activo, String tipo, double metros, String ciudad, StringBuilder base) {
        double valor = num(activo.get("current_value"));
        String origen = "el valor actual que tienes apuntado del activo";
        if (valor <= 0) { valor = num(activo.get("purchase_price")); origen = "el precio de compra que tienes apuntado"; }
        if (valor <= 0 && !"nave".equals(tipo) && metros > 0 && !ciudad.isBlank()) {
            Map<String, Object> v = valoracion.valorar(org, Map.of("metros", String.valueOf(metros), "provincia", ciudad,
                    "municipio", ciudad, "tipo", "vivienda".equals(tipo) ? "piso" : "local"));
            valor = num(v.get("precio_estimado"));
            origen = "una valoración orientativa por m² de " + ciudad + " (confianza " + v.get("confianza") + ")";
        }
        if ("alquiler".equals(operacion)) {
            double renta = num(activo.get("rental_income"));
            if (renta > 0) { base.append("la renta mensual que tienes apuntada del activo"); return renta; }
            if (valor > 0) {
                double r = RENTABILIDAD.get(tipo);
                base.append("una rentabilidad bruta típica del ").append(String.valueOf(r * 100).replace(".0", "").replace('.', ','))
                    .append(" % sobre ").append(origen);
                return valor * r / 12;
            }
            return null;
        }
        if (valor <= 0) return null;
        base.append(origen);
        return valor;
    }

    /** Apertura, objetivo y límite. Con referencia, alrededor de ella; sin ella, alrededor del precio dado. */
    static Rango rango(Double ref, double precio, String rol, boolean alquiler, String base) {
        boolean pago = paga(rol);
        double apertura, objetivo, limite;
        if (ref != null && ref > 0) {
            double pedido = precio > 0 ? precio : ref;
            if (pago) {
                apertura = Math.min(pedido, ref) * 0.90;
                objetivo = Math.min(pedido, ref * 0.97);
                limite = Math.min(pedido, ref * 1.03);
            } else {
                apertura = Math.max(pedido, ref * 1.08);
                objetivo = ref * 1.02;
                limite = ref * 0.95;
            }
        } else {
            double p = Math.max(precio, 0);
            if (pago) { apertura = p * 0.88; objetivo = p * 0.94; limite = p * 0.98; }
            else      { apertura = p;        objetivo = p * 0.96; limite = p * 0.92; }
            base = "el precio que has indicado (sin referencia de mercado: apunta el valor o la renta del activo para afinarlo)";
        }
        return new Rango(ref, redondear(apertura, alquiler), redondear(objetivo, alquiler), redondear(limite, alquiler), base, pago);
    }

    static double redondear(double v, boolean alquiler) {
        double paso = alquiler ? 10 : v >= 200_000 ? 1000 : 500;
        return Math.round(v / paso) * paso;
    }

    /** Prepara la negociación, la guarda y la devuelve para pintarla. */
    public Map<String, Object> preparar(String org, Map<String, String> in) {
        String operacion = List.of("compra", "venta", "alquiler").contains(in.get("operacion")) ? in.get("operacion") : "venta";
        // En compra y venta el papel sale de la operación; en alquiler, propietario o inquilino
        String rol = switch (operacion) {
            case "compra" -> "compro";
            case "venta" -> "vendo";
            default -> "arriendo".equals(in.get("rol")) ? "arriendo" : "alquilo";
        };
        Map<String, Object> activo = new HashMap<>();
        String propiedadId = in.getOrDefault("propiedad_id", "");
        if (!propiedadId.isBlank()) {
            List<Map<String, Object>> r = supabase.selectFilter("propiedades", "id=eq." + propiedadId + "&user_id=eq." + org + "&limit=1");
            if (!r.isEmpty()) activo = r.get(0); else propiedadId = "";
        }
        String tipo = tipoDe(activo, in.get("tipo"));
        double metros = num(in.get("metros")) > 0 ? num(in.get("metros")) : num(activo.get("area_m2"));
        String ciudad = !str(in.get("ciudad")).isBlank() ? str(in.get("ciudad")) : str(activo.get("city"));
        double precio = num(in.get("precio"));
        boolean alquiler = "alquiler".equals(operacion);

        StringBuilder base = new StringBuilder();
        Double ref = referencia(org, operacion, activo, tipo, metros, ciudad, base);
        Rango rango = rango(ref, precio, rol, alquiler, base.toString());

        String nombre = !str(activo.get("name")).isBlank() ? str(activo.get("name")) : (tipo + (ciudad.isBlank() ? "" : " en " + ciudad));
        Map<String, Object> plan = planIA(org, operacion, rol, nombre, tipo, metros, ciudad, precio, rango, in);
        boolean conIA = plan != null;
        if (plan == null) plan = plantilla(operacion, rol, tipo, metros, precio, rango);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("titulo", etiquetaRol(rol) + " · " + nombre);
        out.put("operacion", operacion);
        out.put("rol", rol);
        out.put("alquiler", alquiler);
        out.put("precio", precio);
        out.put("rango", rango);
        out.put("plan", plan);
        out.put("conIA", conIA);
        out.put("eurM2", metros > 0 && !alquiler && rango.objetivo() > 0 ? Math.round(rango.objetivo() / metros) : null);

        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("user_id", org);
        if (!propiedadId.isBlank()) fila.put("propiedad_id", propiedadId);
        fila.put("titulo", out.get("titulo"));
        fila.put("operacion", operacion);
        fila.put("rol", rol);
        if (precio > 0) fila.put("precio_pedido", precio);
        fila.put("apertura", rango.apertura());
        fila.put("objetivo", rango.objetivo());
        fila.put("limite", rango.limite());
        Map<String, Object> guardado = new LinkedHashMap<>(plan);
        guardado.put("base", rango.base());
        guardado.put("referencia", rango.referencia());
        guardado.put("con_ia", conIA);
        fila.put("plan", guardado);
        fila.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("negociaciones", fila);
        if (saved != null) out.put("id", saved.get("id"));
        return out;
    }

    private Map<String, Object> planIA(String org, String operacion, String rol, String nombre, String tipo, double metros,
                                       String ciudad, double precio, Rango r, Map<String, String> in) {
        String mon = " " + com.inmobilia.util.PaisActual.moneda();
        String unidad = "alquiler".equals(operacion) ? mon + "/mes" : mon;
        String prompt = "Prepara una negociación inmobiliaria en " + com.inmobilia.util.PaisActual.get().nombre() + ".\n"
                + "Operación: " + operacion + ". Yo: " + etiquetaRol(rol) + ".\n"
                + "Activo: " + nombre + " (" + tipo + (metros > 0 ? ", " + Math.round(metros) + " m²" : "") + (ciudad.isBlank() ? "" : ", " + ciudad) + ").\n"
                + (precio > 0 ? "Precio sobre la mesa: " + Math.round(precio) + unidad + ".\n" : "")
                + (r.referencia() != null ? "Referencia: " + Math.round(r.referencia()) + unidad + " (" + r.base() + ").\n" : "Sin referencia de mercado.\n")
                + "Rango calculado: apertura " + Math.round(r.apertura()) + unidad + ", objetivo " + Math.round(r.objetivo()) + unidad
                + ", límite " + Math.round(r.limite()) + unidad + ".\n"
                + (str(in.get("contraparte")).isBlank() ? "" : "Sobre la otra parte: " + str(in.get("contraparte")) + "\n")
                + (str(in.get("notas")).isBlank() ? "" : "Notas: " + str(in.get("notas")) + "\n")
                + "Devuelve SOLO un JSON: {\"resumen\":\"2 frases\",\"argumentos\":[5 frases],\"objeciones\":[{\"objecion\":\"...\",\"respuesta\":\"...\"} x4],"
                + "\"concesiones\":[3 cosas que puedo ceder que no sean precio],\"guion\":\"cómo abrir la conversación, 4-6 frases\"}. "
                + "Usa SOLO los números de arriba; no inventes datos de mercado, comparables ni leyes.";
        String raw = ai.call(null, prompt, "Eres un negociador inmobiliario experto, práctico y honesto. Español neutro de Latinoamérica.", 900, org);
        if (raw == null || raw.isBlank()) return null;
        try {
            String json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            @SuppressWarnings("unchecked") Map<String, Object> m = mapper.readValue(json, Map.class);
            return m.get("argumentos") instanceof List ? m : null;
        } catch (Exception e) {
            log.warn("Negotiation Co-Pilot: la IA no devolvió JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Preparación básica sin IA, con los números del rango. */
    static Map<String, Object> plantilla(String operacion, String rol, String tipo, double metros, double precio, Rango r) {
        boolean alquiler = "alquiler".equals(operacion);
        String mon = " " + com.inmobilia.util.PaisActual.moneda();
        String u = alquiler ? mon + "/mes" : mon;
        List<String> args = new ArrayList<>();
        if (r.referencia() != null && precio > 0) {
            double dif = (precio - r.referencia()) / r.referencia() * 100;
            args.add("El precio sobre la mesa (" + Math.round(precio) + u + ") está un " + Math.abs(Math.round(dif)) + " % "
                    + (dif >= 0 ? "por encima" : "por debajo") + " de la referencia (" + Math.round(r.referencia()) + u + ").");
        }
        if (metros > 0 && !alquiler && r.objetivo() > 0) args.add("A tu objetivo, el activo sale a " + Math.round(r.objetivo() / metros) + mon + "/m².");
        if (r.pago()) {
            args.add("Pide la documentación (nota simple, certificado energético, IBI y gastos) antes de concretar cifras.");
            args.add("Apóyate en lo que haya que invertir (reformas, adecuación, licencias) para justificar tu oferta.");
            args.add("Muestra que eres una operación segura: financiación o fondos acreditados y plazos claros.");
        } else {
            args.add("Destaca lo que diferencia al activo: ubicación, estado, accesos, potencia eléctrica o rentabilidad.");
            args.add("Ten preparada la documentación al día: transmite seriedad y quita excusas para rebajar.");
            args.add("Si hay más interesados, dilo sin exagerar: el tiempo juega a tu favor.");
        }
        List<Map<String, String>> obj = List.of(
                Map.of("objecion", "Es caro para lo que es.", "respuesta", r.pago() ? "Por eso mi oferta es " + Math.round(r.apertura()) + u + ": se ajusta a lo que vale hoy."
                        : "La referencia está en " + Math.round(r.objetivo()) + u + "; dime qué te preocupa y lo vemos."),
                Map.of("objecion", "Tengo otras opciones.", "respuesta", "Perfecto, compáralas con calma; esta propuesta tiene un plazo razonable."),
                Map.of("objecion", "Necesito pensarlo.", "respuesta", "Claro. ¿Qué dato te falta para decidir? Te lo envío hoy."),
                Map.of("objecion", "¿Podemos partir la diferencia?", "respuesta", "Puedo moverme un poco, pero no por debajo de " + Math.round(r.limite()) + u + " sin algo a cambio."));
        List<String> concesiones = alquiler
                ? List.of("Carencia de un mes por obras de adecuación", "Duración o preaviso más flexibles", "Asumir una reparación concreta")
                : List.of("Fecha de firma o entrega a su conveniencia", "Incluir mobiliario o instalaciones", "Repartir algún gasto de la operación");
        String guion = "Empieza preguntando qué es lo más importante para la otra parte y escucha. Resume lo que has entendido. "
                + "Presenta tu propuesta de " + Math.round(r.apertura()) + u + " con uno o dos argumentos, y calla. "
                + "Si contraofertan, cede despacio y siempre a cambio de algo. Tu objetivo es " + Math.round(r.objetivo()) + u
                + " y no pases de " + Math.round(r.limite()) + u + ".";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resumen", (r.pago() ? "Abre en " : "Sal en ") + Math.round(r.apertura()) + u + ", busca cerrar en " + Math.round(r.objetivo()) + u
                + " y no pases de " + Math.round(r.limite()) + u + ".");
        m.put("argumentos", args);
        m.put("objeciones", obj);
        m.put("concesiones", concesiones);
        m.put("guion", guion);
        return m;
    }

    public List<Map<String, Object>> historial(String org) {
        return supabase.selectFilter("negociaciones", "select=id,titulo,created_at,objetivo,operacion&user_id=eq." + org + "&order=created_at.desc&limit=15");
    }

    /** Una negociación guardada, en la misma forma que preparar(). */
    public Map<String, Object> ver(String org, String id) {
        List<Map<String, Object>> r = supabase.selectFilter("negociaciones", "id=eq." + id + "&user_id=eq." + org + "&limit=1");
        if (r.isEmpty()) return null;
        Map<String, Object> n = r.get(0);
        @SuppressWarnings("unchecked") Map<String, Object> plan = n.get("plan") instanceof Map ? (Map<String, Object>) n.get("plan") : Map.of();
        Double ref = plan.get("referencia") == null ? null : num(plan.get("referencia"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", n.get("id"));
        out.put("titulo", n.get("titulo"));
        out.put("operacion", n.get("operacion"));
        out.put("rol", n.get("rol"));
        out.put("alquiler", "alquiler".equals(str(n.get("operacion"))));
        out.put("precio", num(n.get("precio_pedido")));
        out.put("rango", new Rango(ref, num(n.get("apertura")), num(n.get("objetivo")), num(n.get("limite")), str(plan.get("base")), paga(str(n.get("rol")))));
        out.put("plan", plan);
        out.put("conIA", Boolean.TRUE.equals(plan.get("con_ia")));
        out.put("eurM2", null);
        return out;
    }

    static String etiquetaRol(String rol) {
        return switch (rol) {
            case "compro" -> "Compro";
            case "vendo" -> "Vendo";
            case "arriendo" -> "Alquilo (inquilino)";
            default -> "Alquilo (propietario)";
        };
    }

    private static double num(Object o) {
        Double d = com.inmobilia.util.Numeros.decimal(o);
        return d == null ? 0 : d;
    }

    private static String str(Object o) {
        return o == null || "null".equals(String.valueOf(o)) ? "" : String.valueOf(o).trim();
    }

    private static String norm(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
