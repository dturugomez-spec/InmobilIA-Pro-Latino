package com.inmobilia.util;

import java.util.Map;

/**
 * Resolucion de nombre-de-herramienta a URL del controlador.
 * Equivalente a router dispatching del InmobilIA.py (PAGES dict).
 */
public final class ToolRoutes {
    public ToolRoutes() {}

    /** Version instancia para usar desde Thymeleaf: ${toolRoutes.route(name)}. */
    public String route(String name) {
        return routeFor(name);
    }

    private static final Map<String, String> ROUTES = Map.ofEntries(
            // Inicio
            Map.entry("Dashboard", "/dashboard"),
            Map.entry("Auditor IA de Cartera", "/herramientas/auditoria"),
            Map.entry("Subastas BOE Hunter", "/herramientas/subastas-boe"),
            Map.entry("Benchmark Renta Ministerio", "/herramientas/benchmark-renta"),
            Map.entry("Stress Test de Cartera", "/herramientas/stress-cartera"),
            Map.entry("Comparador CCAA", "/herramientas/comparador-ccaa"),
            // Cartera
            Map.entry("Cartera de Activos", "/crud/propiedades"),
            Map.entry("CRM de Inquilinos", "/crud/inquilinos"),
            Map.entry("Gestion de Contratos", "/crud/contratos"),
            Map.entry("Visitas & Reservas", "/crud/reservas"),
            Map.entry("Control de Morosidad", "/crud/inquilinos"),
            Map.entry("Polizas & Seguros", "/crud/seguros"),
            Map.entry("Archivo Documental", "/crud/documentos"),
            Map.entry("Comparador de Activos", "/crud/propiedades"),
            Map.entry("Pipeline CRM Leads", "/crud/leads"),
            Map.entry("Plantillas de Email", "/crud/email_templates"),
            Map.entry("Centro de Alertas", "/dashboard"),
            // Finanzas
            Map.entry("Contabilidad & Finanzas", "/crud/transacciones"),
            Map.entry("DCF & Flujos Descontados", "/finanzas/dcf"),
            Map.entry("Rentabilidad & Yield", "/finanzas/yield"),
            Map.entry("Estructuracion de Deuda", "/finanzas/hipoteca"),
            Map.entry("Quick Check Inmobiliario", "/finanzas/quickcheck"),
            Map.entry("Simulacion Monte Carlo", "/finanzas/montecarlo"),
            Map.entry("Analisis de Sensibilidad", "/finanzas/sensibilidad"),
            Map.entry("Stress Test & DSCR", "/finanzas/stress"),
            Map.entry("Optimizacion de Cartera", "/finanzas/portfolio"),
            Map.entry("Waterfall de Flujos", "/finanzas/waterfall"),
            Map.entry("Modelos de Valoracion", "/finanzas/valoracion"),
            Map.entry("Planificacion Fiscal", "/herramientas/fiscal"),
            Map.entry("Simulador Value-Add", "/finanzas/reformas"),
            Map.entry("Seguimiento de ROI", "/finanzas/portfolio"),
            Map.entry("Flip & Trading", "/finanzas/quickcheck"),
            Map.entry("Analisis Comparativo CMA", "/finanzas/valoracion"),
            Map.entry("Biblioteca de Analisis", "/finanzas/biblioteca"),
            // IA
            Map.entry("Analizador de Operaciones IA", "/ia/analyzer"),
            Map.entry("Lector IA de Contratos", "/ia/contratos"),
            Map.entry("Valoracion IA + Comparables", "/finanzas/valoracion"),
            Map.entry("Asistente Inmobiliario IA", "/ia/asistente"),
            Map.entry("Scoring de Inquilinos", "/ia/scoring"),
            Map.entry("Buscador de Oportunidades", "/ia/dealflow"),
            Map.entry("Pipeline de Operaciones", "/ia/dealflow"),
            Map.entry("Due Diligence Asistido", "/ia/due-diligence"),
            Map.entry("Informe de Inversion", "/ia/memo"),
            Map.entry("Marketing & Captacion IA", "/ia/marketing"),
            Map.entry("Analisis de Zona y Mercado", "/ia/zona"),
            Map.entry("Predictor de Precios IA", "/ia/analyzer"),
            Map.entry("Generador de Informes IA", "/ia/memo"),
            Map.entry("Business Intelligence", "/dashboard"),
            // Herramientas
            Map.entry("Health Score Cartera", "/herramientas/health-score"),
            Map.entry("Future-Proof Score", "/herramientas/future-proof"),
            Map.entry("Score ESG", "/herramientas/esg"),
            Map.entry("Scanner IA Documentos", "/ia/contratos"),
            Map.entry("AutoPilot Comunicacion", "/config"),
            Map.entry("Radar de Mercado", "/herramientas/radar"),
            Map.entry("Portal de Inquilino", "/crud/inquilinos"),
            Map.entry("Mantenimiento Predictivo", "/herramientas/mantenimiento-predictivo"),
            Map.entry("Consulta Catastro", "/herramientas/datos-oficiales"),
            Map.entry("Datos Oficiales", "/herramientas/datos-oficiales"),
            Map.entry("Turistico vs Largo Plazo", "/herramientas/turistico"),
            Map.entry("Cazador de Oportunidades", "/ia/dealflow"),
            Map.entry("Informe PDF Profesional", "/ia/memo"),
            Map.entry("Euribor en Vivo", "/herramientas/euribor"),
            Map.entry("Optimizador Holding", "/herramientas/holding"),
            Map.entry("Comandos de Voz", "/config"),
            Map.entry("Multi-Empresa", "/config"),
            // Operaciones
            Map.entry("Mantenimiento de Activos", "/crud/mantenimiento"),
            Map.entry("Equipo y RRHH", "/crud/personal"),
            Map.entry("Gestion de Tareas", "/crud/tareas"),
            Map.entry("Agenda & Calendario", "/crud/eventos"),
            // Cuenta
            Map.entry("Configuracion", "/config")
    );

    public static String routeFor(String toolName) {
        return ROUTES.getOrDefault(toolName, "/dashboard");
    }
}
