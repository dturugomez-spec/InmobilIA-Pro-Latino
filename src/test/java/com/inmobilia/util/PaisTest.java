package com.inmobilia.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** País de la inmobiliaria: moneda, impuesto, documentos y formato de importes. */
class PaisTest {

    @AfterEach
    void limpiar() { PaisActual.limpiar(); }

    @Test
    void codigoDesconocidoOVacio_usaElPaisPorDefecto() {
        assertEquals(Pais.POR_DEFECTO, Pais.de(null).codigo());
        assertEquals(Pais.POR_DEFECTO, Pais.de("ES").codigo());
        assertEquals("CO", Pais.de(" co ").codigo());
    }

    @Test
    void cadaPaisTraeSusDatosFiscales() {
        Pais mx = Pais.de("MX");
        assertEquals("MXN", mx.moneda());
        assertEquals("IVA 16 %", mx.impuestoConTipo());
        assertEquals("RFC", mx.etiquetaIdFiscal());
        Pais pe = Pais.de("PE");
        assertEquals("IGV", pe.impuesto());
        assertEquals("RUC", pe.etiquetaIdFiscal());
        assertEquals("Cédula jurídica", Pais.de("CR").etiquetaIdFiscal());
        for (Pais p : Pais.todos()) assertNotNull(p.zonaId(), p.codigo());
    }

    @Test
    void monedaDeTrabajo_soloLaLocalOUsd() {
        Pais ar = Pais.de("AR");
        assertEquals("USD", ar.conMoneda("usd").moneda());
        assertEquals("ARS", ar.conMoneda("USD").monedaLocal());
        assertEquals("ARS", ar.conMoneda("EUR").moneda(), "otra moneda distinta se ignora");
        assertEquals("ARS", ar.conMoneda(null).moneda());
    }

    @Test
    void importesConLaMonedaYLosSeparadoresDelPais() {
        PaisActual.set(Pais.de("CO"));
        assertEquals("1.500.000 COP", new java.text.DecimalFormat("#,##0' COP'",
                java.text.DecimalFormatSymbols.getInstance(Pais.de("CO").locale())).format(1_500_000));
        assertEquals("1,50M COP", FormatUtils.dinero(1_500_000.0));
        assertEquals("900 COP", FormatUtils.dinero(900.0));
        PaisActual.set(Pais.de("MX"));
        assertEquals("1.50M MXN", FormatUtils.dinero(1_500_000.0));
        assertEquals("0 MXN", FormatUtils.dinero(null));
    }

    @Test
    void tareaEnOtroHilo_llevaElPaisDeQuienLaLanzaYNoLoDeja() throws Exception {
        PaisActual.set(Pais.de("CL"));
        String[] visto = new String[2];
        Runnable tarea = PaisActual.propagar(() -> visto[0] = PaisActual.moneda());
        Thread t = new Thread(() -> { tarea.run(); visto[1] = PaisActual.get().codigo(); });
        t.start();
        t.join();
        assertEquals("CLP", visto[0]);
        assertEquals(Pais.POR_DEFECTO, visto[1], "al acabar, el hilo no se queda con el país de otra inmobiliaria");
        PaisActual.set(Pais.de("PE"));
        PaisActual.propagar(() -> assertEquals("PEN", PaisActual.moneda())).run();
        assertEquals("PE", PaisActual.get().codigo(), "en el mismo hilo (CallerRunsPolicy) se restaura el país");
    }

    @Test
    void divisas_sinRedUsaLosTiposDeRespaldo() {
        com.inmobilia.service.CurrencyService c = new com.inmobilia.service.CurrencyService();
        assertEquals(100.0, c.convertir(100, "MXN", "MXN"));
        double mxn = c.convertir(1, "USD", "MXN");
        assertTrue(mxn > 5 && mxn < 50, "conversión inmediata sin esperar a la red: " + mxn);
    }

    @Test
    void preciosDeReferencia_porCiudadYPais() {
        Pais mx = Pais.de("MX");
        assertTrue(PreciosReferencia.conocida(mx, "Ciudad de México"));
        assertTrue(PreciosReferencia.conocida(mx, "  QUERÉTARO "));
        assertFalse(PreciosReferencia.conocida(mx, "Pueblo inventado"));
        assertTrue(PreciosReferencia.usdPorM2(mx, "cdmx") > PreciosReferencia.usdPorM2(mx, "Pueblo inventado"));
        assertEquals("Bogotá", PreciosReferencia.ciudadPrincipal(Pais.de("CO")));
    }
}
