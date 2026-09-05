package com.docmind.controller;

import com.docmind.dto.DocumentDtos.QueryRequest;
import com.docmind.service.QueryService;
import com.docmind.service.SemanticCacheService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;
    private final SemanticCacheService cacheService;

    public QueryController(QueryService queryService, SemanticCacheService cacheService) {
        this.queryService = queryService;
        this.cacheService = cacheService;
    }

    /**
     * Run a retrieval query against the org's documents.
     * Checks semantic cache first; on miss, runs retrieval and caches the result.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> query(
            @RequestBody QueryRequest request,
            Authentication authentication) {

        UUID userId = (UUID) authentication.getPrincipal();

        // Build document scope filter
        List<UUID> docScopeFilter = null;
        if (request.documentId() != null && !request.documentId().isBlank()) {
            docScopeFilter = List.of(UUID.fromString(request.documentId()));
        }

        var result = queryService.query(request.question(), userId, request.topK(), docScopeFilter);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", request.question());
        response.put("retrievalStrategy", result.retrievalStrategy());
        response.put("cacheHit", result.cacheHit());

        if (result.cacheHit()) {
            response.put("answer", result.cachedAnswer());
            response.put("sourceChunkIds", result.cachedSourceChunkIds());
            response.put("totalChunksFound", 0);
            response.put("chunks", List.of());
        } else {
            response.put("totalChunksFound", result.totalChunksFound());
            List<Map<String, Object>> chunks = result.chunks().stream()
                .map(sc -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("chunkId", sc.chunk().getId().toString());
                    map.put("content", sc.chunk().getContent());
                    map.put("documentId", sc.chunk().getDocument().getId().toString());
                    map.put("score", sc.score());
                    map.put("sources", sc.sources());
                    map.put("matchType", sc.matchType());
                    map.put("chunkIndex", sc.chunk().getChunkIndex());
                    return map;
                })
                .toList();
            response.put("chunks", chunks);
        }

        // Include cache metrics
        var metrics = cacheService.getMetrics();
        response.put("cacheMetrics", Map.of(
            "hits", metrics.hits(),
            "misses", metrics.misses(),
            "hitRate", String.format("%.1f%%", metrics.hitRate() * 100),
            "costSavedUsd", String.format("%.6f", metrics.costSavedUsd())
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Get cache metrics for the current org.
     */
    @GetMapping("/cache/metrics")
    public ResponseEntity<Map<String, Object>> cacheMetrics() {
        var metrics = cacheService.getMetrics();
        return ResponseEntity.ok(Map.of(
            "hits", metrics.hits(),
            "misses", metrics.misses(),
            "hitRate", metrics.hitRate(),
            "costSavedUsd", metrics.costSavedUsd()
        ));
    }
}
