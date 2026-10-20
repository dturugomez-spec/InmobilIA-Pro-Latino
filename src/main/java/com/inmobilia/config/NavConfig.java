package com.inmobilia.config;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Catalogo de navegacion: categorias, iconos y herramientas.
 * Equivalente al dict NAV_CATS en InmobilIA.py.
 */
public final class NavConfig {

    public record Tool(String name, String icon, String desc) {}
    public record Category(String name, String icon, String color, List<Tool> tools) {}

    public static final Map<String, Category> CATEGORIES = buildCategories();

    private NavConfig() {}

    private static Map<String, Category> buildCategories() {
        Map<String, Category> m = new LinkedHashMap<>();

        m.put("Inicio", new Category("Inicio", "🏠", "#4361ee", List.of(
            new Tool("Dashboard", "🏠", "Panel de control y KPIs en tiempo real"),
            new Tool("Auditor IA de Cartera", "🏆", "Auditoria automatica cruzada con datos oficiales. Unico en el mercado."),
            new Tool("Subastas BOE Hunter", "🔨", "Caza inmuebles en subasta BOE con criterios de inversion."),
            new Tool("Benchmark Renta Ministerio", "🏛️", "Compara renta vs indice MITMA + zona tensionada Ley 12/2023."),
            new Tool("Stress Test de Cartera", "🔬", "Stress agregado: Euribor, vacancia, morosidad, OPEX."),
            new Tool("Comparador CCAA", "🗺️", "Fiscalidad + mercado por las 19 comunidades autonomas.")
        )));

        m.put("Cartera", new Category("Cartera", "🏢", "#7c3aed", List.of(
            new Tool("Cartera de Activos",    "🏗️", "Registro y seguimiento de propiedades"),
            new Tool("CRM de Inquilinos",     "👥", "Fichas y gestion completa de inquilinos"),
            new Tool("Gestion de Contratos",  "📋", "Contratos de arrendamiento"),
            new Tool("Visitas & Reservas",    "📅", "Agenda de visitas a activos"),
            new Tool("Control de Morosidad",  "⚠️",  "Seguimiento de impagos y deudas"),
            new Tool("Polizas & Seguros",     "🛡️", "Gestion de seguros y vencimientos"),
            new Tool("Archivo Documental",    "📁", "Repositorio digital de documentos"),
            new Tool("Comparador de Activos", "⚖️",  "Comparativa detallada entre propiedades"),
            new Tool("Pipeline CRM Leads",    "🎯",  "Captacion de leads desde origen hasta firma"),
            new Tool("Plantillas de Email",   "✉️",   "Emails con merge fields para automatizar"),
            new Tool("Centro de Alertas",     "🔔", "Alertas automaticas y vencimientos")
        )));

        m.put("Finanzas", new Category("Finanzas", "💰", "#05CD99", List.of(
            new Tool("Contabilidad & Finanzas",  "📊", "P&L, ingresos, gastos y cashflow"),
            new Tool("DCF & Flujos Descontados", "📈", "Modelo DCF con IRR, NPV y MOIC"),
            new Tool("Rentabilidad & Yield",     "💹", "Gross/net yield y cap rate"),
            new Tool("Estructuracion de Deuda",  "🏦", "Simulador hipotecario y amortizacion"),
            new Tool("Quick Check Inmobiliario", "⚡", "Rentabilidad en 30 segundos"),
            new Tool("Simulacion Monte Carlo",   "🎲", "Modelizacion probabilistica de retornos"),
            new Tool("Analisis de Sensibilidad", "🎛️", "Escenarios y variables criticas"),
            new Tool("Stress Test & DSCR",       "🔬", "Capacidad de pago en escenarios adversos"),
            new Tool("Optimizacion de Cartera",  "🎯", "Maximiza el retorno de tu cartera"),
            new Tool("Waterfall de Flujos",      "💧", "Estructura de distribucion de flujos"),
            new Tool("Modelos de Valoracion",    "🏷️", "Valoracion por multiples metodos"),
            new Tool("Planificacion Fiscal",     "📑", "Optimizacion fiscal de inversiones"),
            new Tool("Simulador Value-Add",      "🔨", "ROI de reformas y rehabilitaciones"),
            new Tool("Seguimiento de ROI",       "📉", "Control del retorno por activo"),
            new Tool("Flip & Trading",           "🔄", "Analisis de compraventa rapida"),
            new Tool("Analisis Comparativo CMA", "🔍", "Benchmarking con el mercado"),
            new Tool("Biblioteca de Analisis",   "📚", "Historial de todos tus analisis")
        )));

        m.put("IA", new Category("IA", "🤖", "#EE5D50", List.of(
            new Tool("Analizador de Operaciones IA", "🧠", "Analisis profundo de inversiones con GPT-4o"),
            new Tool("Lector IA de Contratos",       "📜", "Escanea contratos y detecta riesgos legales"),
            new Tool("Valoracion IA + Comparables",  "💎", "Tasacion con Catastro + INE + comparables"),
            new Tool("Asistente Inmobiliario IA",    "💬", "Asistente conversacional especializado"),
            new Tool("Scoring de Inquilinos",        "⭐", "Puntuacion de solvencia con IA"),
            new Tool("Buscador de Oportunidades",    "🔍", "Deteccion de oportunidades de inversion"),
            new Tool("Pipeline de Operaciones",      "🔄", "Gestion visual del pipeline de deals"),
            new Tool("Due Diligence Asistido",       "🔎", "Due diligence automatizado con IA"),
            new Tool("Informe de Inversion",         "📄", "Memorandos profesionales en minutos"),
            new Tool("Marketing & Captacion IA",     "📣", "Textos y estrategia de marketing"),
            new Tool("Analisis de Zona y Mercado",   "🗺️", "Inteligencia de mercado por zona"),
            new Tool("Predictor de Precios IA",      "🎯", "Prediccion de precios con IA"),
            new Tool("Generador de Informes IA",     "📝", "Informes automaticos en segundos"),
            new Tool("Business Intelligence",        "📊", "Dashboards de inteligencia de negocio")
        )));

        m.put("Herramientas", new Category("Herramientas", "🛠️", "#FFB547", List.of(
            new Tool("Health Score Cartera",     "💚", "Indice de salud de tu cartera 0-100"),
            new Tool("Future-Proof Score",       "⚡", "Power Readiness y Robotics Readiness"),
            new Tool("Score ESG",                "🌱", "Ambiental, Social y Gobernanza"),
            new Tool("Scanner IA Documentos",    "📷", "OCR inteligente de documentos"),
            new Tool("AutoPilot Comunicacion",   "✉️",  "Comunicacion automatizada con inquilinos"),
            new Tool("Radar de Mercado",         "📡", "Monitorizacion del mercado"),
            new Tool("Portal de Inquilino",      "🏠", "Autoservicio para inquilinos"),
            new Tool("Mantenimiento Predictivo", "🔧", "Prediccion de averias"),
            new Tool("Consulta Catastro",        "🗺️", "Acceso a datos catastrales"),
            new Tool("Datos Oficiales",          "🏛️", "Catastro, BCE, INE, BOE, validadores oficiales"),
            new Tool("Turistico vs Largo Plazo", "🏖️", "Comparativa de regimenes de alquiler"),
            new Tool("Cazador de Oportunidades", "🎯", "Sistema de alertas de oportunidades"),
            new Tool("Informe PDF Profesional",  "📄", "Informes profesionales para clientes"),
            new Tool("Euribor en Vivo",          "📊", "Tipos de referencia en tiempo real"),
            new Tool("Optimizador Holding",      "🏛️", "Estructura societaria optima"),
            new Tool("Comandos de Voz",          "🎙️", "Control por comandos de voz"),
            new Tool("Multi-Empresa",            "🏢", "Gestion de cartera multi-sociedad")
        )));

        m.put("Operaciones", new Category("Operaciones", "⚙️", "#4361ee", List.of(
            new Tool("Mantenimiento de Activos", "🔧", "Ordenes de trabajo y mantenimiento"),
            new Tool("Equipo y RRHH",            "👥", "Gestion del equipo y personal"),
            new Tool("Gestion de Tareas",        "✅", "Seguimiento de tareas y plazos"),
            new Tool("Agenda & Calendario",      "📅", "Eventos, visitas y recordatorios")
        )));

        m.put("Cuenta", new Category("Cuenta", "👤", "#A3AED0", List.of(
            new Tool("Configuracion", "⚙️", "API keys, email y preferencias")
        )));

        return m;
    }

    /** Devuelve la categoria que contiene la herramienta dada. */
    public static String pageToCat(String page) {
        if (page == null) return "Inicio";
        for (Map.Entry<String, Category> e : CATEGORIES.entrySet()) {
            for (Tool t : e.getValue().tools()) {
                if (t.name().equals(page)) return e.getKey();
            }
        }
        return "Inicio";
    }
}
