package com.inmobilia.service;

import com.inmobilia.util.CargoSeguro;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Cuentas bancarias (normalizar, enmascarar) y cargos de seguros (importe, próximo cargo). */
class CuentasYCargosTest {

    @Test
    void iban_seNormalizaYSeEnmascara() {
        assertEquals("ES9121000418450200051332", AvisoCuentaBancariaService.normalizar("es91 2100 0418 4502 0005 1332"));
        assertEquals("ES91 •••• 1332", AvisoCuentaBancariaService.enmascarar("ES91 2100 0418 4502 0005 1332"));
        assertEquals("(vacía)", AvisoCuentaBancariaService.enmascarar(null));
        assertEquals("", AvisoCuentaBancariaService.normalizar("null"));
        assertEquals("ES91 •••• 1332", AvisoCuentaBancariaService.textoCuenta("ES9121000418450200051332"));
        assertEquals("Tarjeta de empresa", AvisoCuentaBancariaService.textoCuenta("Tarjeta de empresa"));
        assertNull(AvisoCuentaBancariaService.textoCuenta(" "));
    }

    @Test
    void cuentas_clabeCbuIbanYLocales() {
        assertTrue(AvisoCuentaBancariaService.cuentaValida("002010077777777771"), "CLABE mexicana válida");
        assertFalse(AvisoCuentaBancariaService.cuentaValida("002010077777777772"), "CLABE con dígito de control mal");
        assertTrue(AvisoCuentaBancariaService.cuentaValida("2850590940090418135201"), "CBU argentino válido");
        assertFalse(AvisoCuentaBancariaService.cuentaValida("2850590940090418135202"));
        assertTrue(AvisoCuentaBancariaService.cuentaValida("ES9121000418450200051332"), "IBAN válido");
        assertFalse(AvisoCuentaBancariaService.cuentaValida("ES9121000418450200051333"));
        assertTrue(AvisoCuentaBancariaService.cuentaValida(AvisoCuentaBancariaService.normalizar("0012-3456-78")),
                "cuenta local (Colombia, Chile, Perú…)");
        assertFalse(AvisoCuentaBancariaService.cuentaValida("12-3"));
    }

    @Test
    void emailDeAviso_noEnseñaElIbanCompletoYEscapaElNombre() {
        String html = AvisoCuentaBancariaService.html("<b>Eva</b>", "eva@x.es", "Mi negocio",
                "ES9121000418450200051332", "ES7620770024003102575766");
        assertFalse(html.contains("ES9121000418450200051332"));
        assertFalse(html.contains("ES7620770024003102575766"));
        assertTrue(html.contains("ES91 •••• 1332") && html.contains("ES76 •••• 5766"));
        assertFalse(html.contains("<b>Eva</b>"), "el nombre va escapado");
    }

    @Test
    void importePorCargo() {
        assertEquals(600.0, CargoSeguro.importePorCargo(600, "anual"));
        assertEquals(50.0, CargoSeguro.importePorCargo("600", "mensual"));
        assertEquals(150.0, CargoSeguro.importePorCargo(600.0, "trimestral"));
        assertEquals(300.0, CargoSeguro.importePorCargo(600, "semestral"));
        assertEquals(33.33, CargoSeguro.importePorCargo(400, "mensual"));
        assertNull(CargoSeguro.importePorCargo(null, "anual"));
    }

    @Test
    void proximoCargo_desdeLaFechaDeInicio() {
        LocalDate hoy = LocalDate.of(2026, 10, 20);
        assertEquals(LocalDate.of(2027, 3, 1), CargoSeguro.proximoCargo(LocalDate.of(2025, 3, 1), "anual", hoy));
        assertEquals(LocalDate.of(2026, 11, 15), CargoSeguro.proximoCargo(LocalDate.of(2026, 5, 15), "mensual", hoy));
        assertEquals(LocalDate.of(2026, 10, 20), CargoSeguro.proximoCargo(LocalDate.of(2026, 7, 20), "trimestral", hoy), "hoy cuenta");
        assertEquals(LocalDate.of(2027, 1, 1), CargoSeguro.proximoCargo(LocalDate.of(2027, 1, 1), "anual", hoy), "póliza futura");
        // Fin de mes: sin ir perdiendo días (31 ene → 28 feb → 31 mar…)
        assertEquals(LocalDate.of(2026, 10, 31), CargoSeguro.proximoCargo(LocalDate.of(2026, 1, 31), "mensual", hoy));
        assertNull(CargoSeguro.proximoCargo(null, "anual", hoy));
    }
}
