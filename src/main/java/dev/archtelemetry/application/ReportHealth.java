package dev.archtelemetry.application;

import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.Violation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReportHealth {

    public HealthReport report(Trend trend) {
        List<Trend.SnapshotEntry> entries = trend.entries();

        if (entries.isEmpty()) {
            return new HealthReport(0, Set.of(), Set.of(), DriftDirection.STABLE);
        }

        Trend.SnapshotEntry latest = entries.get(entries.size() - 1);
        Set<Violation> latestViolations = latest.violations();

        if (entries.size() < 2) {
            return new HealthReport(latestViolations.size(), Set.copyOf(latestViolations), Set.of(), DriftDirection.STABLE);
        }

        Trend.SnapshotEntry previous = entries.get(entries.size() - 2);
        Set<Violation> previousViolations = previous.violations();

        Set<Violation> newViolations = new HashSet<>(latestViolations);
        newViolations.removeAll(previousViolations);

        Set<Violation> resolvedViolations = new HashSet<>(previousViolations);
        resolvedViolations.removeAll(latestViolations);

        return new HealthReport(
                latestViolations.size(),
                Set.copyOf(newViolations),
                Set.copyOf(resolvedViolations),
                trend.direction()
        );
    }
}
