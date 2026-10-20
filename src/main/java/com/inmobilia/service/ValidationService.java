package com.inmobilia.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Validadores oficiales espanoles: DNI, NIE, CIF, IBAN, Referencia Catastral.
 * Port directo de validar_dni, validar_nie, validar_cif, validar_iban, validar_ref_catastral.
 */
@Service
public class ValidationService {

    private static final String LETRAS_DNI = "TRWAGMYFPDXBNJZSQVHLCKE";
    private static final Pattern RE_DNI   = Pattern.compile("\\d{8}[A-Z]");
    private static final Pattern RE_NIE   = Pattern.compile("[XYZ]\\d{7}[A-Z]");
    private static final Pattern RE_CIF   = Pattern.compile("[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]");
    private static final Pattern RE_IBAN  = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{10,30}");
    private static final Pattern RE_RC    = Pattern.compile("[0-9A-Z]{20}");

    public boolean validarDni(String s) {
        if (s == null) return false;
        String v = s.trim().toUpperCase().replace("-", "");
        if (!RE_DNI.matcher(v).matches()) return false;
        int num = Integer.parseInt(v.substring(0, 8));
        return v.charAt(8) == LETRAS_DNI.charAt(num % 23);
    }

    public boolean validarNie(String s) {
        if (s == null) return false;
        String v = s.trim().toUpperCase().replace("-", "");
        if (!RE_NIE.matcher(v).matches()) return false;
        char prefix = switch (v.charAt(0)) {
            case 'X' -> '0';
            case 'Y' -> '1';
            default  -> '2';
        };
        int num = Integer.parseInt(prefix + v.substring(1, 8));
        return v.charAt(8) == LETRAS_DNI.charAt(num % 23);
    }

    public boolean validarCif(String s) {
        if (s == null) return false;
        String v = s.trim().toUpperCase().replace("-", "");
        if (!RE_CIF.matcher(v).matches()) return false;
        char letra = v.charAt(0);
        String numeros = v.substring(1, 8);
        char control = v.charAt(8);

        int sumaPar = 0;
        for (int i : new int[]{1, 3, 5}) sumaPar += numeros.charAt(i) - '0';
        int sumaImpar = 0;
        for (int i : new int[]{0, 2, 4, 6}) {
            int d = (numeros.charAt(i) - '0') * 2;
            sumaImpar += d / 10 + d % 10;
        }
        int total = sumaPar + sumaImpar;
        int digito = (10 - (total % 10)) % 10;
        String letraMap = "JABCDEFGHI";

        if ("KPQS".indexOf(letra) >= 0) return control == letraMap.charAt(digito);
        if ("ABEH".indexOf(letra) >= 0) return control == Character.forDigit(digito, 10);
        return control == Character.forDigit(digito, 10) || control == letraMap.charAt(digito);
    }

    public boolean validarIban(String iban) {
        if (iban == null) return false;
        String s = iban.replaceAll("\\s+", "").toUpperCase();
        if (!RE_IBAN.matcher(s).matches()) return false;
        String rearranged = s.substring(4) + s.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) numeric.append(c);
            else numeric.append(Integer.parseInt(String.valueOf(c), 36));
        }
        try {
            // IBAN puede ser muy largo, usamos BigInteger
            return new java.math.BigInteger(numeric.toString()).mod(java.math.BigInteger.valueOf(97)).intValue() == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validarRefCatastral(String s) {
        if (s == null) return false;
        String v = s.trim().toUpperCase().replace(" ", "");
        return RE_RC.matcher(v).matches();
    }
}
