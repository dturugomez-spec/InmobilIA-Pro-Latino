package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 🎭 Entrenador IA de objeciones (role-play coach)
 *
 * Un cliente simulado por IA plantea objeciones reales de captación/venta y se
 * mantiene en personaje; al terminar, evalúa el desempeño del agente con una
 * puntuación y feedback accionable. Reutiliza {@link AIService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoachingService {

    private final AIService aiService;
    private final ObjectMapper objectMapper;

    public record Escenario(String clave, String titulo, String rol, String descripcion, String persona) {}

    private static final Map<String, Escenario> ESCENARIOS = new LinkedHashMap<>();
    static {
        add("comision", "«Otra agencia me cobra menos comisión»", "propietario",
            "El propietario compara tu comisión con la de la competencia.",
            "Eres un propietario que quiere vender su departamento pero te resistes a pagar la comisión: insistes en que otra agencia te cobra menos. Defiende tu postura con educación pero firmeza.");
        add("particular", "«Quiero venderlo yo solo (particular)»", "propietario",
            "El propietario cree que puede vender sin agencia y ahorrarse la comisión.",
            "Eres un propietario convencido de que puedes vender tu vivienda por tu cuenta en los portales inmobiliarios sin pagar a nadie. Eres escéptico con las agencias.");
        add("valoracion_baja", "«Tu valoración es muy baja, vale más»", "propietario",
            "El propietario considera que su vivienda vale más que tu valoración.",
            "Eres un propietario con apego emocional a tu casa: crees que vale mucho más que la valoración del agente y te ofende un poco la cifra.");
        add("exclusiva", "«No quiero exclusiva, prefiero multiagencia»", "propietario",
            "El propietario rechaza la exclusiva y quiere darlo a varias agencias.",
            "Eres un propietario que piensa que dar el departamento a muchas agencias lo venderá antes. Te niegas a firmar una exclusiva.");
        add("presupuesto", "«Está por encima de mi presupuesto»", "comprador",
            "El comprador dice que el inmueble se sale de su presupuesto.",
            "Eres un comprador interesado pero que dice no llegar al precio. Intentas negociar a la baja y dudas si seguir.");
        add("pensarlo", "«Me lo tengo que pensar»", "comprador",
            "El comprador da largas tras la visita sin comprometerse.",
            "Eres un comprador que tras una visita que te gustó das largas: 'me lo tengo que pensar', 'tengo que hablarlo'. Evitas comprometerte.");
        add("mas_barato", "«He visto uno parecido más barato»", "comprador",
            "El comprador compara con otro inmueble más económico.",
            "Eres un comprador que ha visto otro inmueble parecido más barato y lo usas como palanca para que bajen el precio.");
    }
    private static void add(String k, String t, String rol, String desc, String persona) {
        ESCENARIOS.put(k, new Escenario(k, t, rol, desc, persona));
    }

    public Collection<Escenario> escenarios() { return ESCENARIOS.values(); }
    public Escenario escenario(String clave) { return ESCENARIOS.get(clave); }

    /** Genera la siguiente réplica del cliente, manteniéndose en personaje. */
    public String replicaCliente(String clave, String dificultad, List<Map<String, Object>> historial, String uid) {
        Escenario e = ESCENARIOS.get(clave);
        if (e == null) return "(escenario no válido)";

        String sistema = e.persona() + " " + dificultadHint(dificultad) +
                " Eres el CLIENTE, no el agente. Responde SIEMPRE en primera persona como el cliente, " +
                "en 1-3 frases, en español de Latinoamérica (" + com.inmobilia.util.PaisActual.get().nombre() + "), sin vosotros, con objeciones realistas. No te rompas el personaje, " +
                "no des consejos al agente y no narres. Si el agente te convence de verdad, puedes ceder poco a poco.";

        StringBuilder conv = new StringBuilder("Conversación hasta ahora:\n");
        if (historial == null || historial.isEmpty()) {
            conv.append("(inicio) Plantea tu objeción inicial.\n");
        } else {
            for (Map<String, Object> m : historial) {
                String rol = "agente".equals(String.valueOf(m.get("rol"))) ? "AGENTE" : "CLIENTE (tú)";
                conv.append(rol).append(": ").append(m.get("texto")).append("\n");
            }
        }
        conv.append("\nResponde como el CLIENTE:");

        try {
            String r = aiService.call(null, conv.toString(), sistema, 220, uid);
            return r == null || r.isBlank() ? "Mmm, no lo tengo claro todavía." : r.trim();
        } catch (Exception ex) {
            log.warn("coach replica: {}", ex.getMessage());
            return "Disculpa, ¿me lo puedes repetir?";
        }
    }

    /** Evalúa el desempeño del agente y devuelve puntuación + feedback. */
    public Map<String, Object> evaluar(String clave, List<Map<String, Object>> historial, String uid) {
        Escenario e = ESCENARIOS.get(clave);
        StringBuilder conv = new StringBuilder();
        if (historial != null) for (Map<String, Object> m : historial) {
            conv.append("agente".equals(String.valueOf(m.get("rol"))) ? "AGENTE: " : "CLIENTE: ").append(m.get("texto")).append("\n");
        }
        String prompt = "Escenario de objeción: " + (e != null ? e.titulo() : clave) + "\n\n" +
                "Transcripción del role-play:\n" + conv + "\n\n" +
                "Evalúa el desempeño del AGENTE inmobiliario manejando esta objeción. " +
                "Devuelve SOLO un JSON válido con esta forma exacta:\n" +
                "{\"puntuacion\": <0-100>, \"fortalezas\": [\"...\"], \"mejoras\": [\"...\"], \"respuesta_modelo\": \"<una respuesta modelo breve>\"}\n" +
                "Sé concreto y útil, en español de Latinoamérica (" + com.inmobilia.util.PaisActual.get().nombre() + "), sin vosotros. Sin texto fuera del JSON.";
        try {
            String raw = aiService.call(null, prompt,
                    "Eres un formador experto en ventas inmobiliarias. Respondes solo JSON.", 500, uid);
            return parseEval(raw);
        } catch (Exception ex) {
            log.warn("coach evaluar: {}", ex.getMessage());
            return Map.of("puntuacion", 0, "fortalezas", List.of(), "mejoras", List.of("No se pudo evaluar, inténtalo de nuevo."), "respuesta_modelo", "");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEval(String raw) {
        try {
            if (raw == null) throw new IllegalStateException("vacío");
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a >= 0 && b > a) raw = raw.substring(a, b + 1);
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of("puntuacion", 0, "fortalezas", List.of(), "mejoras", List.of("Respuesta no interpretable."), "respuesta_modelo", "");
        }
    }

    public String comparablesJson(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }

    private static String dificultadHint(String d) {
        return switch (d == null ? "media" : d) {
            case "facil" -> "Eres un cliente relativamente accesible: pones alguna pega pero cedes con buenos argumentos.";
            case "dificil" -> "Eres un cliente muy difícil: insistente, escéptico y poco dispuesto a ceder.";
            default -> "Eres un cliente de dificultad media: pones pegas firmes pero razonables.";
        };
    }
}
