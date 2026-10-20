package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Generador de adeudos directos SEPA (XML pain.008.001.02).
 * Estándar oficial Iberpay/SCT-CORE para Core / B2B.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SepaService {

    private final SupabaseService supabase;

    /** Genera el XML SEPA pain.008.001.02 para una remesa. */
    public String generarXmlRemesa(String remesaId) {
        List<Map<String, Object>> remRows = supabase.selectFilter("sepa_remesas", "id=eq." + remesaId);
        if (remRows.isEmpty()) return null;
        Map<String, Object> rem = remRows.get(0);
        String userId = String.valueOf(rem.get("user_id"));

        // Acreedor
        List<Map<String, Object>> credRows = supabase.selectFilter("sepa_creditor", "user_id=eq." + userId);
        if (credRows.isEmpty()) return null;
        Map<String, Object> cred = credRows.get(0);

        // Adeudos
        List<Map<String, Object>> adeudos = supabase.selectFilter("sepa_adeudos", "remesa_id=eq." + remesaId);
        if (adeudos.isEmpty()) return null;

        double total = adeudos.stream().mapToDouble(a -> nd(a.get("importe"))).sum();
        String msgId = "REM" + System.currentTimeMillis();
        String fechaCobro = String.valueOf(rem.get("fecha_cobro"));
        String secuencia = String.valueOf(rem.getOrDefault("secuencia", "RCUR"));

        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.008.001.02\">\n");
        x.append("  <CstmrDrctDbtInitn>\n");

        // Group Header
        x.append("    <GrpHdr>\n");
        x.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        x.append("      <CreDtTm>").append(OffsetDateTime.now()).append("</CreDtTm>\n");
        x.append("      <NbOfTxs>").append(adeudos.size()).append("</NbOfTxs>\n");
        x.append("      <CtrlSum>").append(String.format(Locale.US, "%.2f", total)).append("</CtrlSum>\n");
        x.append("      <InitgPty>\n");
        x.append("        <Nm>").append(esc(cred.get("nombre"))).append("</Nm>\n");
        x.append("        <Id><OrgId><Othr><Id>").append(esc(cred.get("identificador_acreedor"))).append("</Id></Othr></OrgId></Id>\n");
        x.append("      </InitgPty>\n");
        x.append("    </GrpHdr>\n");

        // Payment Info
        x.append("    <PmtInf>\n");
        x.append("      <PmtInfId>").append(msgId).append("-PMT</PmtInfId>\n");
        x.append("      <PmtMtd>DD</PmtMtd>\n");
        x.append("      <NbOfTxs>").append(adeudos.size()).append("</NbOfTxs>\n");
        x.append("      <CtrlSum>").append(String.format(Locale.US, "%.2f", total)).append("</CtrlSum>\n");
        x.append("      <PmtTpInf>\n");
        x.append("        <SvcLvl><Cd>SEPA</Cd></SvcLvl>\n");
        x.append("        <LclInstrm><Cd>CORE</Cd></LclInstrm>\n");
        x.append("        <SeqTp>").append(secuencia).append("</SeqTp>\n");
        x.append("      </PmtTpInf>\n");
        x.append("      <ReqdColltnDt>").append(fechaCobro).append("</ReqdColltnDt>\n");
        x.append("      <Cdtr><Nm>").append(esc(cred.get("nombre"))).append("</Nm></Cdtr>\n");
        x.append("      <CdtrAcct><Id><IBAN>").append(s(cred.get("iban")).replaceAll("\\s", "")).append("</IBAN></Id></CdtrAcct>\n");
        x.append("      <CdtrAgt><FinInstnId>");
        if (cred.get("bic") != null) x.append("<BIC>").append(esc(cred.get("bic"))).append("</BIC>");
        else x.append("<Othr><Id>NOTPROVIDED</Id></Othr>");
        x.append("</FinInstnId></CdtrAgt>\n");
        x.append("      <ChrgBr>SLEV</ChrgBr>\n");
        x.append("      <CdtrSchmeId><Id><PrvtId><Othr>\n");
        x.append("        <Id>").append(esc(cred.get("identificador_acreedor"))).append("</Id>\n");
        x.append("        <SchmeNm><Prtry>SEPA</Prtry></SchmeNm>\n");
        x.append("      </Othr></PrvtId></Id></CdtrSchmeId>\n");

        // Adeudos individuales
        for (Map<String, Object> a : adeudos) {
            Map<String, Object> mandato = null;
            if (a.get("mandato_id") != null) {
                List<Map<String, Object>> mr = supabase.selectFilter("sepa_mandatos", "id=eq." + a.get("mandato_id"));
                if (!mr.isEmpty()) mandato = mr.get(0);
            }

            x.append("      <DrctDbtTxInf>\n");
            x.append("        <PmtId><EndToEndId>").append(esc(a.get("end_to_end_id"))).append("</EndToEndId></PmtId>\n");
            x.append("        <InstdAmt Ccy=\"EUR\">").append(String.format(Locale.US, "%.2f", nd(a.get("importe")))).append("</InstdAmt>\n");
            x.append("        <DrctDbtTx><MndtRltdInf>\n");
            x.append("          <MndtId>").append(esc(mandato != null ? mandato.get("referencia_mandato") : "MNDT")).append("</MndtId>\n");
            x.append("          <DtOfSgntr>").append(mandato != null ? mandato.get("fecha_firma") : LocalDate.now()).append("</DtOfSgntr>\n");
            x.append("        </MndtRltdInf></DrctDbtTx>\n");
            x.append("        <DbtrAgt><FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId></DbtrAgt>\n");
            x.append("        <Dbtr><Nm>").append(esc(a.get("deudor_nombre"))).append("</Nm></Dbtr>\n");
            x.append("        <DbtrAcct><Id><IBAN>").append(s(a.get("deudor_iban")).replaceAll("\\s", "")).append("</IBAN></Id></DbtrAcct>\n");
            x.append("        <RmtInf><Ustrd>").append(esc(a.get("concepto"))).append("</Ustrd></RmtInf>\n");
            x.append("      </DrctDbtTxInf>\n");
        }

        x.append("    </PmtInf>\n");
        x.append("  </CstmrDrctDbtInitn>\n");
        x.append("</Document>\n");

        // Marcar remesa como generada
        supabase.update("sepa_remesas", remesaId, Map.of(
                "estado", "generada",
                "msg_id", msgId,
                "total", total,
                "num_adeudos", adeudos.size()
        ));

        return x.toString();
    }

    /** Genera referencia única mandato. */
    public String generarReferenciaMandato(String userId) {
        return ("MND" + userId.substring(0, 8) + System.currentTimeMillis()).substring(0, 35);
    }

    /** Valida IBAN español. */
    public boolean validarIBAN(String iban) {
        if (iban == null) return false;
        String clean = iban.replaceAll("\\s", "").toUpperCase();
        if (!clean.matches("^ES\\d{22}$")) return false;
        // Algoritmo MOD 97
        String rearranged = clean.substring(4) + clean.substring(0, 4);
        StringBuilder n = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) n.append(c);
            else n.append((int) c - 55);
        }
        try {
            java.math.BigInteger bi = new java.math.BigInteger(n.toString());
            return bi.mod(java.math.BigInteger.valueOf(97)).intValue() == 1;
        } catch (Exception e) { return false; }
    }

    private static String esc(Object o) {
        if (o == null) return "";
        return String.valueOf(o)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
