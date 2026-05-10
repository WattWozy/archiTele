package dev.archtelemetry.application;

import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.Violation;

import java.util.Set;

public record HealthReport(
        int totalViolations,
        Set<Violation> newViolations,
        Set<Violation> resolvedViolations,
        DriftDirection driftDirection
) {}
