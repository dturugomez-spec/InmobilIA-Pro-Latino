package com.inmobilia.util;

import java.text.DecimalFormatSymbols;

/**
 * Números escritos en formularios, sin romper la página: vacío o texto raro → null (antes
 * daba error 500). Entiende el formato del país de la inmobiliaria:
 *  - coma decimal (Colombia, Argentina, Chile, Uruguay…): "1.250,50", "250.000", "1250,5"
 *  - punto decimal (México, Perú, Centroamérica…): "1,250.50", "250,000", "1250.5"
 * y quita símbolos de moneda y espacios ("$ 1 250", "S/ 300", "1.500 COP").
 */
public final class Numeros {
    private Numeros() {}

    /** Con el separador decimal del país de la inmobiliaria que atiende la petición. */
    public static Double decimal(Object valor) {
        return decimal(valor, DecimalFormatSymbols.getInstance(PaisActual.get().locale()).getDecimalSeparator());
    }

    /** @param sepDecimal ',' o '.' según el país; solo decide cuando el número trae un único tipo de separador. */
    public static Double decimal(Object valor, char sepDecimal) {
        if (valor == null) return null;
        String s = String.valueOf(valor)
                .replaceAll("(?i)\\b[A-Z]{3}\\b|S/|Bs\\.?|[$€%₡₲\\s ]", "");
        if (s.isEmpty()) return null;
        int coma = s.lastIndexOf(','), punto = s.lastIndexOf('.');
        if (coma >= 0 && punto >= 0) {
            // El último separador es el decimal; el otro, de miles
            s = coma > punto ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (coma >= 0) {
            // Solo comas: de miles si el país usa punto decimal y tienen forma de miles ("250,000")
            s = sepDecimal == '.' && s.matches("-?\\d{1,3}(,\\d{3})+") ? s.replace(",", "") : s.replace(',', '.');
        } else if (sepDecimal == ',' && s.matches("-?\\d{1,3}(\\.\\d{3})+")) {
            s = s.replace(".", ""); // "250.000" son doscientos cincuenta mil, no 250
        }
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer entero(Object valor) {
        Double d = decimal(valor);
        return d == null || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE ? null : d.intValue();
    }
}
