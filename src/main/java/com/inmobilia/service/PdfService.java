package com.inmobilia.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Extraccion de texto de PDF (equivalente a pdfplumber).
 */
@Service
@Slf4j
public class PdfService {

    public String extractText(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) return "";
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (Exception e) {
            log.warn("Error leyendo PDF: {}", e.getMessage());
            return "";
        }
    }

    public String extractText(InputStream stream) {
        try (PDDocument doc = Loader.loadPDF(stream.readAllBytes())) {
            return new PDFTextStripper().getText(doc);
        } catch (Exception e) {
            log.warn("Error leyendo PDF: {}", e.getMessage());
            return "";
        }
    }
}
