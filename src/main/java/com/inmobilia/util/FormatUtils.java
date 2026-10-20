package com.inmobilia.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Helpers de formateo con la moneda, el formato de números y la hora del país de la inmobiliaria.
 */
public final class FormatUtils {
    private FormatUtils() {}

    /** Importe abreviado en la moneda de la inmobiliaria: "1,25M MXN", "850,0K COP", "900 PEN". */
    public static String dinero(Double v) {
        Pais pais = PaisActual.get();
        String mon = pais.moneda();
        if (v == null || v == 0) return "0 " + mon;
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(pais.locale());
        double abs = Math.abs(v);
        if (abs >= 1_000_000) return new DecimalFormat("#,##0.00'M " + mon + "'", sym).format(v / 1_000_000);
        if (abs >= 1_000)     return new DecimalFormat("#,##0.0'K " + mon + "'", sym).format(v / 1_000);
        return new DecimalFormat("#,##0' " + mon + "'", sym).format(v);
    }

    public static String pct(Double v) {
        if (v == null) return "—";
        return new DecimalFormat("#0.00'%'", DecimalFormatSymbols.getInstance(PaisActual.get().locale())).format(v);
    }

    public static String saludo() {
        int h = LocalDateTime.now(PaisActual.get().zonaId()).getHour();
        if (h < 12) return "Buenos días";
        if (h < 20) return "Buenas tardes";
        return "Buenas noches";
    }

    public static String fechaLarga() {
        Pais pais = PaisActual.get();
        return LocalDate.now(pais.zonaId()).format(
                DateTimeFormatter.ofPattern("EEEE, dd 'de' MMMM 'de' yyyy", pais.locale()));
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
