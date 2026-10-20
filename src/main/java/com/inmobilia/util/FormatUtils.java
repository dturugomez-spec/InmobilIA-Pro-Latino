package com.inmobilia.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Helpers de formateo. Port de fmt_eur, saludo horario, etc.
 */
public final class FormatUtils {
    private FormatUtils() {}

    private static final DecimalFormatSymbols SYM = new DecimalFormatSymbols(new Locale("es", "ES"));
    private static final DecimalFormat FMT_MILLION = new DecimalFormat("#,##0.00'M EUR'", SYM);
    private static final DecimalFormat FMT_THOUSAND = new DecimalFormat("#,##0.0'K EUR'", SYM);
    private static final DecimalFormat FMT_ENTERO   = new DecimalFormat("#,##0' EUR'", SYM);
    private static final DecimalFormat FMT_PCT_2    = new DecimalFormat("#0.00'%'", SYM);

    public static String eur(Double v) {
        if (v == null || v == 0) return "0 EUR";
        double abs = Math.abs(v);
        if (abs >= 1_000_000) return FMT_MILLION.format(v / 1_000_000);
        if (abs >= 1_000)     return FMT_THOUSAND.format(v / 1_000);
        return FMT_ENTERO.format(v);
    }

    public static String pct(Double v) {
        if (v == null) return "—";
        return FMT_PCT_2.format(v);
    }

    public static String saludo() {
        int h = LocalDateTime.now().getHour();
        if (h < 13) return "Buenos dias";
        if (h < 20) return "Buenas tardes";
        return "Buenas noches";
    }

    private static final DateTimeFormatter FECHA_ES = DateTimeFormatter.ofPattern(
            "EEEE, dd 'de' MMMM 'de' yyyy", new Locale("es", "ES"));

    public static String fechaLarga() {
        return LocalDate.now().format(FECHA_ES);
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
