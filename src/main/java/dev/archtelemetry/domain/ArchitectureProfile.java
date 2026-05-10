package dev.archtelemetry.domain;

import java.util.Set;

public record ArchitectureProfile(
        Set<ModuleMetrics> moduleMetrics,
        Set<DependencyCycle> cycles,
        Set<Violation> violations
) {
    public ArchitectureProfile {
        moduleMetrics = Set.copyOf(moduleMetrics);
        cycles = Set.copyOf(cycles);
        violations = Set.copyOf(violations);
    }
}
