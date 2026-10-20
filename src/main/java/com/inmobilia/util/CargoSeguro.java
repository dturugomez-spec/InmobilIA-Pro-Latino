package com.inmobilia.util;

import java.time.LocalDate;

/**
 * Cargos de una póliza de seguro según su forma de pago (anual, semestral, trimestral, mensual):
 * cuántos hay al año, cuánto es cada uno y cuándo toca el siguiente.
 */
public final class CargoSeguro {
    private CargoSeguro() {}

    /** Meses entre dos cargos. Forma de pago desconocida o vacía → anual. */
    public static int mesesEntreCargos(String formaPago) {
        if (formaPago == null) return 12;
        return switch (formaPago.trim().toLowerCase()) {
            case "mensual" -> 1;
            case "trimestral" -> 3;
            case "semestral" -> 6;
            default -> 12;
        };
    }

    /** Importe de cada cargo (prima anual repartida), redondeado a céntimos. */
    public static Double importePorCargo(Object primaAnual, String formaPago) {
        try {
            double prima = Double.parseDouble(String.valueOf(primaAnual));
            int cargos = 12 / mesesEntreCargos(formaPago);
            return Math.round(prima / cargos * 100.0) / 100.0;
        } catch (Exception e) {
            return null;
        }
    }

    /** Primer cargo en {@code hoy} o después, contando desde la fecha de inicio. Sin inicio → null. */
    public static LocalDate proximoCargo(LocalDate inicio, String formaPago, LocalDate hoy) {
        if (inicio == null || hoy == null) return null;
        int meses = mesesEntreCargos(formaPago);
        LocalDate fecha = inicio;
        // Saltos de periodo en periodo (como mucho unos cientos de vueltas, incluso con pólizas muy antiguas)
        for (int i = 0; fecha.isBefore(hoy) && i < 2000; i++) {
            fecha = inicio.plusMonths((long) meses * (i + 1));
        }
        return fecha;
    }

    public static LocalDate leerFecha(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return null; }
    }
}
