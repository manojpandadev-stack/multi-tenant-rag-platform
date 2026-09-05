package com.docmind.controller;

import com.docmind.service.UsageService;
import com.docmind.service.UsageService.UsageSummary;
import com.docmind.tenant.TenantAwareService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;
    private final TenantAwareService tenantAwareService;

    public UsageController(UsageService usageService, TenantAwareService tenantAwareService) {
        this.usageService = usageService;
        this.tenantAwareService = tenantAwareService;
    }

    /**
     * Current billing period usage for the caller's org.
     * Only ORG_ADMIN can view usage.
     */
    @GetMapping
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ResponseEntity<UsageSummary> getCurrentUsage(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        UsageSummary summary = usageService.getCurrentPeriod(orgId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Historical usage for the caller's org.
     * @param months number of past months to return (default 3)
     */
    @GetMapping("/history")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, Object>> getUsageHistory(
            @RequestParam(defaultValue = "3") int months,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        List<UsageSummary> history = usageService.getHistory(orgId, months);
        return ResponseEntity.ok(Map.of(
            "orgId", orgId.toString(),
            "periods", history,
            "totalPeriods", history.size()
        ));
    }
}
