package com.docmind.service;

import com.docmind.dto.DocumentDtos.DocumentResponse;
import com.docmind.dto.DocumentDtos.PaginatedResponse;
import com.docmind.dto.DocumentDtos.UploadResponse;
import com.docmind.event.DocumentUploadedEventPublisher;
import com.docmind.exception.ResourceNotFoundException;
import com.docmind.model.Document;
import com.docmind.model.Organization;
import com.docmind.repository.DocumentChunkRepository;
import com.docmind.repository.DocumentRepository;
import com.docmind.repository.OrganizationRepository;
import com.docmind.tenant.TenantAwareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Document service: upload, status, list, retry, delete.
 * All operations are tenant-scoped via TenantAwareService.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024; // 25MB
    private static final Set<String> ALLOWED_TYPES = Set.of("PDF", "DOCX", "TXT");

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final OrganizationRepository orgRepository;
    private final TenantAwareService tenantAwareService;
    private final DocumentProcessingPipeline pipeline;
    private final SemanticCacheService cacheService;
    private final UsageRecordingService usageRecording;
    private final DocumentUploadedEventPublisher eventPublisher;
    private final String processingMode;

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository,
            OrganizationRepository orgRepository,
            TenantAwareService tenantAwareService,
            DocumentProcessingPipeline pipeline,
            SemanticCacheService cacheService,
            UsageRecordingService usageRecording,
            DocumentUploadedEventPublisher eventPublisher,
            @Value("${docmind.processing.mode:kafka}") String processingMode) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.orgRepository = orgRepository;
        this.tenantAwareService = tenantAwareService;
        this.pipeline = pipeline;
        this.cacheService = cacheService;
        this.usageRecording = usageRecording;
        this.eventPublisher = eventPublisher;
        this.processingMode = processingMode;
    }

    /**
     * Upload a document: validate, store on disk, create DB record, trigger async processing.
     */
    @Transactional
    public UploadResponse uploadDocument(MultipartFile file, UUID userId) throws IOException {
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File exceeds maximum size of 25MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Filename is required");
        }

        String extension = getFileExtension(originalFilename).toUpperCase();
        if (!ALLOWED_TYPES.contains(extension)) {
            throw new IllegalArgumentException(
                "Unsupported file type: " + extension + ". Allowed: PDF, DOCX, TXT");
        }

        // Store file on disk
        Path storageDir = Paths.get("storage", orgId.toString());
        Files.createDirectories(storageDir);
        String storedFilename = UUID.randomUUID() + "." + extension.toLowerCase();
        Path filePath = storageDir.resolve(storedFilename);
        file.transferTo(filePath.toFile());

        // Create DB record
        Organization org = orgRepository.findById(orgId).orElseThrow();
        Document doc = Document.builder()
            .organization(org)
            .filename(storedFilename)
            .originalFilename(originalFilename)
            .fileType(extension)
            .fileSizeBytes(file.getSize())
            .storagePath(filePath.toString())
            .status(Document.ProcessingStatus.PENDING)
            .build();

        doc = documentRepository.save(doc);

        log.info("Document uploaded: {} (org={}, size={} bytes, type={})",
                originalFilename, orgId, file.getSize(), extension);

        // Record usage
        usageRecording.recordDocumentUpload(orgId, file.getSize());

        // Trigger processing:
        //  - kafka (default): publish the trigger event; the consumer runs the pipeline.
        //  - async (fallback): direct @Async bounded-thread-pool invocation (Stage 2).
        if ("kafka".equalsIgnoreCase(processingMode)) {
            // Ordered afterCommit -> consumer can never beat the DB commit
            eventPublisher.publishUploaded(doc.getId(), orgId);
        } else {
            if (!"async".equalsIgnoreCase(processingMode)) {
                log.warn("Unknown docmind.processing.mode='{}' — falling back to @Async", processingMode);
            }
            pipeline.processDocument(doc.getId());
        }

        return new UploadResponse(
            doc.getId().toString(),
            originalFilename,
            "PENDING",
            "Document uploaded and queued for processing"
        );
    }

    /**
     * Get document status, scoped to the current org.
     */
    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID documentId, UUID userId) {
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        Document doc = documentRepository.findByIdAndOrgId(documentId, orgId);
        if (doc == null) {
            throw new ResourceNotFoundException("Document not found");
        }
        return toResponse(doc);
    }

    /**
     * Paginated document list for the current org, with optional status filter.
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<DocumentResponse> listDocuments(
            UUID userId, int page, int size, String statusFilter) {
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        Pageable pageable = PageRequest.of(page, size);

        Page<Document> docPage;
        if (statusFilter != null && !statusFilter.isBlank()) {
            Document.ProcessingStatus status = Document.ProcessingStatus.valueOf(statusFilter.toUpperCase());
            docPage = documentRepository.findByOrgIdAndStatusPaged(orgId, status, pageable);
        } else {
            docPage = documentRepository.findByOrgIdPaged(orgId, pageable);
        }

        return new PaginatedResponse<>(
            docPage.getContent().stream().map(this::toResponse).toList(),
            docPage.getNumber(),
            docPage.getSize(),
            docPage.getTotalElements(),
            docPage.getTotalPages()
        );
    }

    /**
     * Retry processing of a failed document.
     */
    @Transactional
    public DocumentResponse retryDocument(UUID documentId, UUID userId) {
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        Document doc = documentRepository.findByIdAndOrgId(documentId, orgId);
        if (doc == null) {
            throw new ResourceNotFoundException("Document not found");
        }
        if (doc.getStatus() != Document.ProcessingStatus.FAILED) {
            throw new IllegalArgumentException(
                "Can only retry documents in FAILED status, current: " + doc.getStatus());
        }

        pipeline.retryDocument(documentId);
        return toResponse(doc);
    }

    /**
     * Delete document and its chunks, scoped to current org.
     */
    @Transactional
    public void deleteDocument(UUID documentId, UUID userId) {
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        Document doc = documentRepository.findByIdAndOrgId(documentId, orgId);
        if (doc == null) {
            throw new ResourceNotFoundException("Document not found");
        }

        var chunks = chunkRepository.findByDocumentIdAndOrgId(documentId, orgId);
        chunkRepository.deleteAll(chunks);
        documentRepository.delete(doc);

        // Invalidate cache entries referencing this document
        cacheService.invalidateByDocument(documentId);

        // Delete file from disk
        if (doc.getStoragePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getStoragePath()));
            } catch (IOException e) {
                log.warn("Failed to delete file from disk: {}", doc.getStoragePath(), e);
            }
        }
    }

    /**
     * Get usage stats for the current org.
     */
    @Transactional(readOnly = true)
    public UsageStats getUsageStats(UUID userId) {
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        long docCount = documentRepository.countByOrgId(orgId);
        long storageBytes = documentRepository.sumFileSizeByOrgId(orgId);
        long chunkCount = chunkRepository.countByOrgId(orgId);
        return new UsageStats(docCount, storageBytes / (1024 * 1024), chunkCount);
    }

    public record UsageStats(long documentCount, long storageMB, long chunkCount) {}

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            throw new IllegalArgumentException("File must have an extension (PDF, DOCX, or TXT)");
        }
        return filename.substring(lastDot + 1);
    }

    private DocumentResponse toResponse(Document doc) {
        return new DocumentResponse(
            doc.getId().toString(),
            doc.getOriginalFilename() != null ? doc.getOriginalFilename() : doc.getFilename(),
            doc.getFileType(),
            doc.getFileSizeBytes(),
            doc.getStatus().name(),
            doc.getChunkCount(),
            doc.getErrorMessage(),
            doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null
        );
    }
}
