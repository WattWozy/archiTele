package dev.archtelemetry.domain;

import java.time.Instant;
import java.util.List;

public record ScanRecord(
        String repoPath,
        String commitHash,
        Instant commitTime,
        String blueprintHash,
        List<Violation> violations,
        List<ModuleMetrics> moduleMetrics,
        List<Hotspot> hotspots
) {
    public ScanRecord {
        violations = List.copyOf(violations);
        moduleMetrics = List.copyOf(moduleMetrics);
        hotspots = List.copyOf(hotspots);
    }
}
