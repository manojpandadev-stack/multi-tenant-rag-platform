package com.docmind.service;

import com.docmind.model.UsagePeriodSummary;
import com.docmind.repository.UsagePeriodSummaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads usage data and computes cost breakdowns per org per billing period.
 * Uses CostCalculator for provider-specific pricing.
 */
@Service
public class UsageService {

    private final UsagePeriodSummaryRepository usageRepository;
    private final UsageRecordingService usageRecordingService;
    private final CostCalculator costCalculator;

    public UsageService(
            UsagePeriodSummaryRepository usageRepository,
            UsageRecordingService usageRecordingService,
            CostCalculator costCalculator) {
        this.usageRepository = usageRepository;
        this.usageRecordingService = usageRecordingService;
        this.costCalculator = costCalculator;
    }

    /**
     * Current billing period summary for an org.
     */
    @Transactional(readOnly = true)
    public UsageSummary getCurrentPeriod(UUID orgId) {
        String currentPeriod = UsageRecordingService.currentBillingPeriod();
        UsagePeriodSummary period = usageRepository
            .findByOrgAndPeriodNative(orgId, currentPeriod)
            .orElseThrow(() -> new RuntimeException("No usage period found for org=" + orgId));
        return toSummary(period);
    }

    /**
     * Historical billing periods for an org.
     */
    @Transactional(readOnly = true)
    public List<UsageSummary> getHistory(UUID orgId, int months) {
        String currentPeriod = UsageRecordingService.currentBillingPeriod();
        List<UsagePeriodSummary> periods = usageRepository
            .findByOrganizationIdAndBillingPeriodOrderByBillingPeriodDesc(
                orgId, currentPeriod,
                org.springframework.data.domain.PageRequest.of(0, months));
        return periods.stream().map(this::toSummary).collect(Collectors.toList());
    }

    /**
     * Convert a UsagePeriodSummary entity to a UsageSummary DTO with cost breakdown.
     */
    private UsageSummary toSummary(UsagePeriodSummary period) {
        Map<String, Double> breakdown = costCalculator.costBreakdown(
            period.getEmbeddingTokens(),
            period.getLlmInputTokens(),
            period.getLlmOutputTokens(),
            period.getRerankCalls()
        );

        int totalQueries = period.getCacheHits() + period.getCacheMisses();
        double cacheHitRate = totalQueries > 0
            ? (double) period.getCacheHits() / totalQueries * 100.0
            : 0.0;

        double costPerQuery = costCalculator.costPerQuery(
            period.getEstimatedCostCents().intValue(), totalQueries);

        return new UsageSummary(
            period.getBillingPeriod(),
            period.getDocumentsUploaded(),
            period.getStorageBytes(),
            period.getQueriesTotal(),
            period.getQueriesVectorOnly(),
            period.getQueriesHybrid(),
            period.getQueriesHybridRerank(),
            period.getEmbeddingTokens(),
            period.getLlmInputTokens(),
            period.getLlmOutputTokens(),
            period.getCacheHits(),
            period.getCacheMisses(),
            Math.round(cacheHitRate * 10.0) / 10.0,
            period.getRerankCalls(),
            period.getEstimatedCostCents(),
            Math.round(costPerQuery * 1000.0) / 1000.0,
            breakdown
        );
    }

    /**
     * Usage summary DTO — the response from /api/usage.
     * Contains both raw counts and computed cost breakdown.
     */
    public record UsageSummary(
        String billingPeriod,
        int documentsUploaded,
        long storageBytes,
        int queriesTotal,
        int queriesVectorOnly,
        int queriesHybrid,
        int queriesHybridRerank,
        long embeddingTokens,
        long llmInputTokens,
        long llmOutputTokens,
        int cacheHits,
        int cacheMisses,
        double cacheHitRate,
        int rerankCalls,
        long estimatedCostCents,
        double costPerQueryCents,
        Map<String, Double> costBreakdown
    ) {}
}
