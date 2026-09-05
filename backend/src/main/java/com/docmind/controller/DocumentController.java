package com.docmind.controller;

import com.docmind.dto.DocumentDtos.DocumentResponse;
import com.docmind.dto.DocumentDtos.PaginatedResponse;
import com.docmind.dto.DocumentDtos.UploadResponse;
import com.docmind.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Upload a document for processing.
     * Returns immediately with document ID; processing runs async.
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        UUID userId = (UUID) authentication.getPrincipal();
        UploadResponse response = documentService.uploadDocument(file, userId);
        return ResponseEntity.accepted().body(response);
    }

    /**
     * List documents for the current org (paginated, with optional status filter).
     */
    @GetMapping
    public ResponseEntity<PaginatedResponse<DocumentResponse>> listDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        PaginatedResponse<DocumentResponse> docs =
            documentService.listDocuments(userId, page, size, status);
        return ResponseEntity.ok(docs);
    }

    /**
     * Get document details by ID (scoped to current org).
     */
    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> getDocument(
            @PathVariable UUID documentId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        DocumentResponse doc = documentService.getDocument(documentId, userId);
        return ResponseEntity.ok(doc);
    }

    /**
     * Retry processing of a failed document.
     */
    @PostMapping("/{documentId}/retry")
    public ResponseEntity<DocumentResponse> retryDocument(
            @PathVariable UUID documentId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        DocumentResponse doc = documentService.retryDocument(documentId, userId);
        return ResponseEntity.accepted().body(doc);
    }

    /**
     * Delete a document and all its chunks (scoped to current org).
     */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Map<String, String>> deleteDocument(
            @PathVariable UUID documentId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        documentService.deleteDocument(documentId, userId);
        return ResponseEntity.ok(Map.of("message", "Document deleted"));
    }
}
