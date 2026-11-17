package com.inmobilia.service;

import com.inmobilia.util.PaisActual;
import com.inmobilia.util.PreciosPlataforma;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Promociones del cobro con Stripe (las mismas que en la versión española, decididas el 17-nov-2026):
 *
 * <ul>
 *   <li><b>Oferta de lanzamiento:</b> quien se suscribe antes de {@code app.oferta.primer-cobro} (por defecto el
 *   1 de febrero de 2027, a las 0:00 en la hora del país de la inmobiliaria) no paga nada hasta ese día. En Stripe
 *   es un periodo de prueba que acaba esa fecha. Solo para quien nunca ha tenido una suscripción. Se apaga dejando
 *   OFERTA_PRIMER_COBRO vacía.</li>
 *   <li><b>Socio fundador:</b> las {@value #FUNDADOR_PLAZAS} primeras inmobiliarias que marque el dueño de la
 *   plataforma tienen un {@value #FUNDADOR_PCT} % de descuento en sus {@value #FUNDADOR_COBROS} primeros cobros.
 *   En Stripe es un cupón de porcentaje (vale para todas las monedas) con duración en meses que la app crea sola.</li>
 * </ul>
 */
@Component
public class Promociones {

    public static final int FUNDADOR_PLAZAS = 10;
    public static final int FUNDADOR_PCT = 20;
    public static final int FUNDADOR_COBROS = 4;
    /** Stripe no acepta un fin de prueba a menos de 48 horas. */
    static final Duration MINIMO_STRIPE = Duration.ofHours(49);

    private static final DateTimeFormatter LARGA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-419"));

    private final LocalDate primerCobro;

    public Promociones(@Value("${app.oferta.primer-cobro:2027-02-01}") String primerCobro) {
        this.primerCobro = fecha(primerCobro);
    }

    static LocalDate fecha(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Zona del país de la inmobiliaria de esta petición (fuera de una petición, la del país por defecto). */
    static ZoneId zona() { return PaisActual.get().zonaId(); }

    // ───────────── Oferta de lanzamiento ─────────────

    /** Día del primer cobro de la oferta, o null si no hay oferta. */
    public LocalDate primerCobro() { return primerCobro; }

    /** ¿Se puede todavía ofrecer? (hasta 2 días antes: es lo mínimo que acepta Stripe). */
    public boolean activa() { return activa(primerCobro, Instant.now(), zona()); }

    static boolean activa(LocalDate primerCobro, Instant ahora, ZoneId zona) {
        return primerCobro != null && inicio(primerCobro, zona).isAfter(ahora.plus(MINIMO_STRIPE));
    }

    /** «1 de febrero de 2027». */
    public String primerCobroTexto() { return primerCobro == null ? "" : primerCobro.format(LARGA); }

    static Instant inicio(LocalDate d, ZoneId zona) { return d.atStartOfDay(zona).toInstant(); }

    /** Fin del periodo sin cobro para Stripe con la hora del país de esta petición. */
    public static Long finSinCobro(Map<String, Object> user, LocalDate primerCobro, Instant ahora) {
        return finSinCobro(user, primerCobro, ahora, zona());
    }

    /**
     * Fin del periodo sin cobro para Stripe (segundos epoch), o null si se cobra al momento: el más tardío entre
     * lo que queda de la prueba de la cuenta y la fecha de la oferta. Nada para quien ya tuvo una suscripción.
     */
    public static Long finSinCobro(Map<String, Object> user, LocalDate primerCobro, Instant ahora, ZoneId zona) {
        if (PlanService.haSuscritoAlgunaVez(user)) return null;
        Instant limite = ahora.plus(MINIMO_STRIPE);
        Instant fin = null;
        OffsetDateTime prueba = com.inmobilia.util.Fechas.leer(user == null ? null : user.get("trial_ends_at"));
        if (prueba != null && prueba.toInstant().isAfter(limite)) fin = prueba.toInstant();
        if (activa(primerCobro, ahora, zona) && (fin == null || inicio(primerCobro, zona).isAfter(fin))) fin = inicio(primerCobro, zona);
        return fin == null ? null : fin.getEpochSecond();
    }

    // ───────────── Socio fundador ─────────────

    public int fundadorPlazas() { return FUNDADOR_PLAZAS; }
    public int fundadorPct() { return FUNDADOR_PCT; }
    public int fundadorCobros() { return FUNDADOR_COBROS; }

    public static boolean esFundador(Map<String, Object> user) {
        Object v = user == null ? null : user.get("socio_fundador");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    /**
     * Precio mensual con el descuento de fundador en la moneda de esta petición, ya formateado:
     * plan_pro en México → «800 MXN»; en dólares → «43,20 USD».
     */
    public String precioFundador(String concepto) {
        String moneda = PreciosPlataforma.moneda();
        int decimales = PreciosPlataforma.SIN_DECIMALES.contains(moneda) ? 0 : 2;
        BigDecimal v = PreciosPlataforma.importe(moneda, concepto)
                .multiply(BigDecimal.valueOf(100 - FUNDADOR_PCT))
                .divide(BigDecimal.valueOf(100), decimales, RoundingMode.HALF_UP);
        return PreciosPlataforma.formato(v, moneda, PaisActual.get().locale());
    }

    /**
     * Meses que debe durar el cupón para que el descuento cubra exactamente los {@value #FUNDADOR_COBROS} primeros
     * cobros. En Stripe la duración cuenta desde la suscripción, también durante el periodo sin cobro: si el primer
     * cobro es dentro de k meses, el cupón dura k + 3 (cubre ese cobro y los 3 siguientes, y acaba antes del quinto).
     */
    public static int mesesCupon(Instant ahora, Long finSinCobro) {
        if (finSinCobro == null || finSinCobro <= ahora.getEpochSecond()) return FUNDADOR_COBROS;
        ZonedDateTime desde = ahora.atZone(ZoneOffset.UTC);
        Instant primero = Instant.ofEpochSecond(finSinCobro);
        int k = 1;
        while (!desde.plusMonths(k).toInstant().isAfter(primero)) k++;
        return k + FUNDADOR_COBROS - 1;
    }

    /** Id del cupón de Stripe para una duración: inmobilia_fundador_20_6m (el mismo que en España). */
    public static String cuponId(int meses) { return "inmobilia_fundador_" + FUNDADOR_PCT + "_" + meses + "m"; }

    /** Las cuentas marcadas como socio fundador (ids). Vacío si aún no existe la columna (V120). */
    public static List<String> fundadores(SupabaseService supabase) {
        return supabase.selectFilter("users", "select=id&socio_fundador=is.true&organization_id=is.null&limit=100")
                .stream().map(u -> String.valueOf(u.get("id"))).toList();
    }
}
