package com.inmobilia.service;

import com.inmobilia.util.Pais;
import com.inmobilia.util.PaisActual;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * País de cada inmobiliaria (users.pais de la cuenta administradora = organization_id).
 * Caché de 5 minutos para no consultar la BD en cada petición.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaisService {

    private static final long TTL_MS = 5 * 60_000;

    private record Entrada(Pais pais, long at) {}

    private final SupabaseService supabase;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    /** País de la inmobiliaria; el de por defecto si no lo ha elegido o no se puede leer. */
    public Pais deOrganizacion(String orgUid) {
        if (orgUid == null || orgUid.isBlank()) return Pais.de(null);
        Entrada e = cache.get(orgUid);
        if (e != null && System.currentTimeMillis() - e.at() < TTL_MS) return e.pais();
        Pais p = Pais.de(null);
        try {
            List<Map<String, Object>> us = supabase.selectFilter("users",
                    "id=eq." + orgUid + "&select=pais,moneda&limit=1");
            if (!us.isEmpty()) {
                Object mon = us.get(0).get("moneda");
                p = Pais.de(us.get(0).get("pais")).conMoneda(mon == null ? null : String.valueOf(mon));
            }
        } catch (Exception ex) {
            log.debug("No se pudo leer el país de {}: {}", orgUid, ex.getMessage());
        }
        cache.put(orgUid, new Entrada(p, System.currentTimeMillis()));
        return p;
    }

    /** Para páginas públicas de una inmobiliaria (ficha, microsite, valoración…): usar su país. */
    public Pais usar(String orgUid) {
        Pais p = deOrganizacion(orgUid);
        PaisActual.set(p);
        return p;
    }

    /** Guarda país y moneda de trabajo (la local del país o USD). */
    public boolean guardar(String orgUid, String codigo, String moneda) {
        if (!Pais.existe(codigo)) return false;
        Pais p = Pais.de(codigo).conMoneda(moneda);
        boolean ok = supabase.update("users", orgUid, Map.of("pais", p.codigo(), "moneda", p.moneda()));
        cache.remove(orgUid);
        return ok;
    }
}
