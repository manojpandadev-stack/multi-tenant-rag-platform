package com.docmind.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text from uploaded documents.
 *
 * Supports: PDF (PDFBox 3.0), DOCX (Apache POI 5.2), TXT (plain read).
 * Each method returns a list of page objects for metadata preservation.
 * Failures are reported as ExtractionResult with success=false and a clear error message.
 */
@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    public record ExtractedPage(int pageNumber, String text) {}

    public record ExtractionResult(boolean success, List<ExtractedPage> pages, String errorMessage) {
        public static ExtractionResult ok(List<ExtractedPage> pages) {
            return new ExtractionResult(true, pages, null);
        }
        public static ExtractionResult failed(String errorMessage) {
            return new ExtractionResult(false, List.of(), errorMessage);
        }
    }

    /**
     * Extract text from a file based on its type.
     */
    public ExtractionResult extract(Path filePath, String fileType) {
        return switch (fileType.toUpperCase()) {
            case "PDF" -> extractPdf(filePath);
            case "DOCX" -> extractDocx(filePath);
            case "TXT" -> extractTxt(filePath);
            default -> ExtractionResult.failed("Unsupported file type: " + fileType);
        };
    }

    private ExtractionResult extractPdf(Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            List<ExtractedPage> pages = new ArrayList<>();

            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document).trim();
                if (!text.isEmpty()) {
                    pages.add(new ExtractedPage(i, text));
                }
            }

            log.debug("Extracted {} pages from PDF: {}", pages.size(), filePath.getFileName());
            return ExtractionResult.ok(pages);
        } catch (IOException e) {
            String msg = "Failed to extract PDF: " + e.getMessage();
            if (e.getMessage() != null && e.getMessage().contains("encrypted")) {
                msg = "PDF is password-protected and cannot be processed";
            }
            log.warn("{} for file {}", msg, filePath.getFileName(), e);
            return ExtractionResult.failed(msg);
        }
    }

    private ExtractionResult extractDocx(Path filePath) {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(filePath));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {

            String text = extractor.getText();
            List<ExtractedPage> pages = new ArrayList<>();
            if (text != null && !text.trim().isEmpty()) {
                // DOCX doesn't have true pages; treat the whole document as one "page"
                pages.add(new ExtractedPage(1, text.trim()));
            }

            log.debug("Extracted DOCX: {} chars from {}", text != null ? text.length() : 0, filePath.getFileName());
            return ExtractionResult.ok(pages);
        } catch (IOException e) {
            String msg = "Failed to extract DOCX: " + e.getMessage();
            log.warn("{} for file {}", msg, filePath.getFileName(), e);
            return ExtractionResult.failed(msg);
        }
    }

    private ExtractionResult extractTxt(Path filePath) {
        try {
            String text = Files.readString(filePath, StandardCharsets.UTF_8);
            List<ExtractedPage> pages = new ArrayList<>();
            if (!text.trim().isEmpty()) {
                pages.add(new ExtractedPage(1, text.trim()));
            }

            log.debug("Extracted TXT: {} chars from {}", text.length(), filePath.getFileName());
            return ExtractionResult.ok(pages);
        } catch (IOException e) {
            String msg = "Failed to read TXT file: " + e.getMessage();
            log.warn("{} for file {}", msg, filePath.getFileName(), e);
            return ExtractionResult.failed(msg);
        }
    }
}
