package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.*;

/**
 * Páginas públicas detalladas de cada funcionalidad.
 * Las cards de la landing enlazan aquí: /feature/{slug}
 */
@Controller
public class FeatureController {

    @GetMapping("/feature/{slug}")
    public String detail(@PathVariable String slug, Model model) {
        Map<String, Object> data = FEATURES.get(slug);
        if (data == null) return "redirect:/#producto";
        model.addAttribute("f", data);
        model.addAttribute("relacionados", relacionadosOf(slug));
        return "feature";
    }

    @GetMapping("/features")
    public String index(Model model) {
        model.addAttribute("features", FEATURES);
        return "features_index";
    }

    private List<Map.Entry<String, Map<String, Object>>> relacionadosOf(String currentSlug) {
        List<Map.Entry<String, Map<String, Object>>> all = new ArrayList<>(FEATURES.entrySet());
        all.removeIf(e -> e.getKey().equals(currentSlug));
        Collections.shuffle(all);
        return all.subList(0, Math.min(3, all.size()));
    }

    // ════════════════════════════════════════════════════════════════
    //  DATOS DE LAS 9 FEATURES
    // ════════════════════════════════════════════════════════════════
    private static final Map<String, Map<String, Object>> FEATURES = new LinkedHashMap<>();
    static {
        // ─── IA ───
        FEATURES.put("ia", f(
            "ia", "🧠", "Inteligencia artificial", "blue",
            "IA que entiende tu negocio inmobiliario",
            "Más allá de un chatbot genérico: nuestra IA está entrenada con casuística del sector inmobiliario de Latinoamérica. Prioriza leads, redacta descripciones, transcribe llamadas y resume conversaciones para que tu equipo se centre en cerrar operaciones.",
            new String[][]{
                {"Lead Scoring automático", "Cada lead recibe una puntuación de 0 a 100 y una temperatura (HOT/Caliente/Tibio/Frío) calculada en tiempo real según comportamiento, datos del perfil y probabilidad estadística de conversión."},
                {"Descripciones SEO en 5 segundos", "Le das los datos de la propiedad y la IA redacta una descripción optimizada para portales y motores de búsqueda. Multilingüe (ES/EN/CA)."},
                {"Chatbot embebible en tu web", "Widget conectado a tu inventario real que responde dudas, agenda visitas y captura leads sin que tú intervengas. Funciona 24/7."},
                {"OCR de identificaciones con un click", "Foto de la INE, cédula, DNI o RUN del cliente y se rellenan automáticamente todos los campos del expediente. Validación de letras incluida."},
                {"Notas de voz transcritas", "Graba notas tras una visita y se transcriben + resumen automáticamente. Quedan asociadas al cliente y la propiedad."},
                {"Reconocimiento de habitaciones por foto", "Sube fotos del inmueble y la IA detecta tipo de habitación, estado de reforma y características destacables para el anuncio."}
            },
            new String[][]{
                {"Reduce tiempo de cualificación", "De horas a segundos. La IA prioriza qué leads merecen tu atención inmediata."},
                {"Más conversión", "Llegas antes a los leads calientes, cuando aún están comparando opciones."},
                {"Más tiempo para vender", "Tu equipo deja de copiar/pegar datos y se dedica a lo importante."}
            }
        ));

        // ─── CUMPLIMIENTO ───
        FEATURES.put("cumplimiento", f(
            "cumplimiento", "🌎", "Hecho para Latinoamérica", "emerald",
            "Tu país, tu moneda, tus reglas",
            "Cada inmobiliaria elige su país y la plataforma se adapta: moneda local o dólares, impuesto al consumo, identificaciones fiscales, prefijo telefónico, zona horaria y textos en español de la región. Funciona en 17 países, de México a Argentina.",
            new String[][]{
                {"17 países", "México, Colombia, Argentina, Chile, Perú, Ecuador, Uruguay, Paraguay, Bolivia, Venezuela, Centroamérica y República Dominicana."},
                {"Moneda local o USD", "Importes en MXN, COP, ARS, CLP, PEN… o en dólares si tu mercado cotiza así la compraventa."},
                {"Impuestos e identificaciones", "IVA, IGV, ITBIS, ITBMS o ISV con su tipo general, y RFC, NIT, CUIT, RUT, RUC o cédula en cada formulario."},
                {"Protección de datos", "Registro de accesos y cambios, derechos ARCO, exportación y borrado de datos, con la ley de datos de tu país en los documentos."},
                {"Firma electrónica", "Contratos firmados desde el celular con verificación de identidad y registro de evidencias (email, IP, fecha y hora)."},
                {"Contratos orientativos", "Plantillas de arrendamiento y encargo con depósito en garantía, ajuste por inflación y datos de tu país, listas para revisar con tu abogado."}
            },
            new String[][]{
                {"Sin adaptar nada a mano", "Eliges el país una vez y toda la app habla tu idioma comercial."},
                {"Listo para tu contador", "Resumen de impuestos y rentas por periodo, exportable. La factura electrónica oficial se timbra en el sistema de tu país."},
                {"Todo trazable", "Cada acción queda registrada: quién, qué y cuándo."}
            }
        ));

        // ─── AUTOMATIZACIÓN ───
        FEATURES.put("automatizacion", f(
            "automatizacion", "⚡", "Automatización", "amber",
            "Workflows visuales sin programar",
            "Cuando ocurre X → haz Y. Configura reglas de negocio en minutos con un editor visual tipo Zapier integrado en tu CRM. Sin código, sin integraciones externas, sin coste por ejecución.",
            new String[][]{
                {"13 triggers de negocio", "Lead creado, visita agendada, factura impagada, contrato firmado, propiedad publicada... Cada evento puede disparar acciones."},
                {"7 acciones encadenables", "Enviar email, asignar agente, crear tarea, mover en kanban, notificar por WhatsApp, etiquetar, cambiar de estado."},
                {"Editor visual", "Diseña los workflows arrastrando bloques. Lo entiende cualquiera del equipo, no solo el técnico."},
                {"Filtros condicionales", ("Aplica reglas solo si se cumplen condiciones: 'solo si presupuesto > 200.000 " + PaisActual.moneda() + "', 'solo si la zona es Polanco'...")},
                {"Programación temporal", "Ejecuta acciones en delay: '24h después de una visita, enviar encuesta NPS'. Sin atajos manuales."},
                {"Histórico de ejecuciones", "Cada workflow lleva un log de todas sus ejecuciones, errores y resultados. Debugging fácil."}
            },
            new String[][]{
                {"Recupera horas a la semana", "Los procesos repetitivos del CRM se ejecutan solos mientras tú duermes."},
                {"Cero leads perdidos", "Cada lead tiene asignado un seguimiento automático. Nada cae por el olvido humano."},
                {"Escalable", "Tus reglas se aplican a 5 o a 5.000 leads sin perder calidad."}
            }
        ));

        // ─── GESTIÓN ───
        FEATURES.put("gestion", f(
            "gestion", "📊", "Gestión completa", "purple",
            "Toda tu cartera en un único panel",
            "Olvídate de las hojas de Excel, los carpetas de Drive y los archivos sueltos. Toda la operativa de tu inmobiliaria en un único sistema diseñado para el sector.",
            new String[][]{
                {"Propiedades", "Ficha completa con fotos, planos, vídeos, tour virtual, clave catastral, historial de precios y visitas."},
                {"Leads y oportunidades", "Pipeline kanban configurable con scoring, etapas personalizadas, drag&drop entre fases."},
                {"Inquilinos y propietarios", "Carteras separadas con histórico de operaciones, documentos asociados y datos legales completos."},
                {"Contratos", "Plantillas de arrendamiento/arrendamiento temporal/compraventa/promesa con variables auto-rellenadas. Firma electrónica integrada."},
                {"Visitas con agenda", "Calendario sincronizado con Google Calendar, recordatorios automáticos al cliente, feedback post-visita."},
                {"Comisiones", "Cálculo automático por agente, propiedad y operación. Liquidaciones mensuales sin fricción."},
                {"Seguros y siniestros", "Pólizas de hogar, impago de alquiler y RC. Gestión de siniestros con seguimiento."},
                {"Obra nueva / promociones", "Páginas dedicadas con plantas, fases comerciales, reserva online con depósito Stripe."}
            },
            new String[][]{
                {"Un solo sistema, no ocho", "Reemplaza CRM, ERP, drive de docs, calendario, hojas Excel."},
                {"Búsqueda global", "Encuentra cualquier registro escribiendo 2 letras. Indexación full-text."},
                {"Acceso desde cualquier sitio", "Web, celular, tablet. Sincronización en tiempo real entre dispositivos."}
            }
        ));

        // ─── COMUNICACIÓN ───
        FEATURES.put("comunicacion", f(
            "comunicacion", "💬", "Comunicación unificada", "green",
            "Habla con tus clientes desde el CRM",
            "WhatsApp, SMS, email y newsletter desde un único panel, con tracking completo de aperturas, clicks y conversiones. Adiós a saltar entre apps.",
            new String[][]{
                {"WhatsApp Business Cloud API", "API oficial de Meta integrada. Plantillas pre-aprobadas, envíos masivos personalizados, conversaciones 1:1 sin pasar por el celular."},
                {"SMS marketing", "Pasarela conectada para recordatorios de visitas, confirmaciones y campañas masivas con tasa de apertura del 98%."},
                {"Email marketing avanzado", "Editor visual de plantillas, A/B testing de asuntos, segmentación dinámica, tracking de aperturas y clicks."},
                {"Newsletter público", "Captura suscriptores desde tu microsite con doble opt-in y consentimiento registrado. Envíos mensuales programados."},
                {"Bandeja unificada IMAP", "Tus emails de Gmail/Outlook integrados con la ficha del cliente. Adjuntos archivados automáticamente."},
                {"Chat interno del equipo", "Canales por proyecto, mensajes directos, menciones y compartir archivos con tu equipo sin Slack externo."}
            },
            new String[][]{
                {"Conversión un 30% mayor", "Responder por WhatsApp en menos de 5 minutos multiplica las conversiones."},
                {"Conversaciones trazables", "Cada mensaje queda asociado al lead/cliente/propiedad. Sin pérdidas de contexto."},
                {"Ahorro en herramientas", "Sustituye Mailchimp + Twilio + Intercom + Slack."}
            }
        ));

        // ─── PRESENCIA ONLINE ───
        FEATURES.put("presencia-online", f(
            "presencia-online", "🌐", "Presencia online", "pink",
            "Tu agencia en internet, sin agencia de marketing",
            "Tu propio microsite con SEO optimizado, sindicación automática a los principales portales inmobiliarios y herramientas para destacar cada propiedad. Sin contratar a una agencia externa.",
            new String[][]{
                {"Microsite SEO incluido", "Tu propia web inmobiliaria con dominio personalizable, blog integrado, SEO técnico optimizado y velocidad de carga premium."},
                {"Página pública por propiedad", "Cada inmueble con URL pública SEO-friendly, fotos en alta resolución, plano, mapa, tour virtual y formulario de contacto."},
                {"Sindicación a portales", "Feed XML automático para los portales de tu país (Inmuebles24, Finca Raíz, Zonaprop, Portal Inmobiliario, Urbania...). Subes una vez, sale en todos sin formato manual."},
                {"Tours virtuales 360º", "Sube tours de Matterport, Kuula o equivalentes y embébelos directamente en la ficha. Aumentan engagement un 40%."},
                {"Staging digital con IA", "Decoración virtual de pisos vacíos para que vendan mejor. Tu cliente ve cómo quedaría amueblado."},
                {"Blog SEO integrado", "Editor markdown, categorías, etiquetas, multi-idioma. Generación de artículos asistida por IA."}
            },
            new String[][]{
                {"Más visitas orgánicas", "SEO técnico bien hecho desde el día 1. Sin esperar a posicionar."},
                {"Sin pagar a una web externa", "Microsite incluido. Cambias contenido tú mismo desde el CRM."},
                {"Visibilidad multiportal", "Mismo inmueble en 4 portales sin esfuerzo extra."}
            }
        ));

        // ─── COBROS ───
        FEATURES.put("cobros", f(
            "cobros", "💳", "Cobros automáticos", "cyan",
            "Cobra alquileres sin perseguir a nadie",
            "Control de cobros de rentas, conciliación bancaria automática, multimoneda y reconciliación contable. La parte aburrida de gestionar alquileres, automatizada.",
            new String[][]{
                {"Recordatorios de pago", "Avisos automáticos por email y WhatsApp antes y después del vencimiento de cada renta."},
                {"Conciliación bancaria", "Importa el extracto bancario y la plataforma asocia cada transacción a su factura automáticamente."},
                {"Multimoneda", "Tipos de cambio actualizados a diario. Trabaja en moneda local o en USD sin conversiones manuales."},
                {"Caja chica controlada", "Movimientos en efectivo trazables con justificantes adjuntos. Cierre de caja mensual con un click."},
                {"Gastos del agente", "Kilometraje, dietas y reembolsos asociados a cada operación. Aprobación con un click."},
                {"Extractos propietarios", "Liquidaciones mensuales automáticas a los propietarios con desglose de rentas, gastos y comisiones."}
            },
            new String[][]{
                {"Cero impagos olvidados", "El sistema te avisa antes de que pase. Recordatorios al inquilino automáticos."},
                {"Conciliación en minutos", "De pasar 2 días al mes a 10 minutos. El resto lo hace la máquina."},
                {"Sin Excel para liquidar", "Los extractos a propietarios se generan solos."}
            }
        ));

        // ─── RED COLABORATIVA ───
        FEATURES.put("red-colaborativa", f(
            "red-colaborativa", "🤝", "Red colaborativa", "indigo",
            "MLS interno: comparte y crece",
            "Comparte tu cartera con agencias asociadas y benefíciate de la suya. Co-broking con reparto automático de comisiones, sin acuerdos manuales por cada operación.",
            new String[][]{
                {"Cartera compartida", "Decide qué propiedades quieres compartir con otras agencias y bajo qué condiciones de comisión."},
                {"Reparto automático", "Cuando una agencia colaboradora vende una propiedad tuya, el sistema reparte la comisión según el acuerdo configurado."},
                {"Búsqueda inversa", "Da de alta los criterios de tus clientes; el sistema te avisa automáticamente cuando otra agencia publica una propiedad que encaja."},
                {"Open House compartido", "Organiza jornadas de puertas abiertas donde varias agencias pueden traer clientes y compartir comisiones."},
                {"Histórico de operaciones", "Reporting completo de qué agencias generan más operaciones y mejores ratios."},
                {"Acuerdos a medida", "Configura porcentajes distintos por agencia, por zona o por tipo de operación."}
            },
            new String[][]{
                {"Más cartera sin captar", "Accedes a propiedades que no tienes en exclusiva."},
                {"Más operaciones cerradas", "Si tú no lo tienes, otra agencia sí. Y al revés."},
                {"Únicos en el mercado", "Funcionalidad que solo existe aquí. Tus competidores no la tienen."}
            }
        ));

        // ─── MÓVIL ───
        FEATURES.put("movil", f(
            "movil", "📱", "Celular y multidispositivo", "slate",
            "Trabaja desde donde estés",
            "Funciona en el móvil y se instala como una app desde el navegador, en Android e iOS. Tu equipo trabaja desde la oficina, el coche o una cafetería.",
            new String[][]{
                {"Se instala como app", "Desde Chrome o Safari: \"Añadir a pantalla de inicio\". Se abre a pantalla completa, con su icono, sin pasar por App Store ni Google Play."},
                {"Todo en la nube", "Lo que cambias en el celular lo ve tu equipo en la computadora al recargar. Sin instalar nada ni sincronizar a mano."},
                {"Pensado para pantallas pequeñas", "Menús, fichas y formularios se adaptan al celular para trabajar con una mano en plena visita."},
                {"Tu marca en lo que ve el cliente", "Logo, color y datos de contacto de tu agencia en PDFs, correos, fichas de inmuebles y portal del propietario."},
                {"Notas de voz y fotos", "Dicta la nota tras la visita: se pasa a texto y la IA saca el resumen; sube fotos desde la cámara a la ficha del inmueble."}
            },
            new String[][]{
                {"Tu equipo siempre conectado", "Ya no dependes de estar en la oficina para gestionar."},
                {"Imagen profesional", "El cliente recibe documentos y enlaces con la marca de tu agencia."},
                {"Reduce fricción comercial", "Enseñas la ficha o la valoración en el móvil, en el momento."}
            }
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> f(String slug, String icon, String titulo, String color,
                                         String h1, String subtitulo,
                                         String[][] funciones, String[][] beneficios) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", slug);
        m.put("icon", icon);
        m.put("titulo", titulo);
        m.put("color", color);
        m.put("h1", h1);
        m.put("subtitulo", subtitulo);
        // funciones
        List<Map<String, String>> funcs = new ArrayList<>();
        for (String[] x : funciones) funcs.add(Map.of("titulo", x[0], "descripcion", x[1]));
        m.put("funciones", funcs);
        // beneficios
        List<Map<String, String>> bens = new ArrayList<>();
        for (String[] x : beneficios) bens.add(Map.of("titulo", x[0], "descripcion", x[1]));
        m.put("beneficios", bens);
        return m;
    }
}
