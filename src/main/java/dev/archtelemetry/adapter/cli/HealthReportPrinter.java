package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.domain.DriftDirection;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.Violation;

import java.util.List;
import java.util.Set;

public final class HealthReportPrinter {

    public static void print(Trend trend, HealthReport report, List<Snapshot> snapshots) {
        System.out.println("ArchTelemetry Health Report");
        System.out.println("===========================");
        System.out.println();
        System.out.printf("Snapshots analyzed : %d%n", snapshots.size());
        System.out.printf("Trend              : %s%n", formatDirection(report.driftDirection()));
        System.out.printf("Current violations : %d%n", report.totalViolations());
        System.out.println();

        List<Trend.SnapshotEntry> entries = trend.entries();
        System.out.println("--- Snapshot History (oldest -> newest) ---");
        for (int i = 0; i < entries.size(); i++) {
            Trend.SnapshotEntry entry = entries.get(i);
            Snapshot snapshot = snapshots.get(i);
            int count = entry.violationCount();
            System.out.printf("  %.8s  %s  %d %s%n",
                    entry.commitId(),
                    snapshot.timestamp(),
                    count,
                    count == 1 ? "violation" : "violations");
        }
        System.out.println();

        System.out.printf("--- Current Violations (%d) ---%n", report.totalViolations());
        if (entries.isEmpty()) {
            System.out.println("  (none)");
        } else {
            Set<Violation> current = entries.get(entries.size() - 1).violations();
            if (current.isEmpty()) {
                System.out.println("  (none)");
            } else {
                current.stream()
                        .map(v -> "  " + v.dependency().source().name() + " -> " + v.dependency().target().name())
                        .sorted()
                        .forEach(System.out::println);
            }
        }
        System.out.println();

        System.out.println("--- New Since Previous Snapshot ---");
        if (report.newViolations().isEmpty()) {
            System.out.println("  (none)");
        } else {
            report.newViolations().stream()
                    .map(v -> "  " + v.dependency().source().name() + " -> " + v.dependency().target().name())
                    .sorted()
                    .forEach(System.out::println);
        }
        System.out.println();

        System.out.println("--- Resolved Since Previous Snapshot ---");
        if (report.resolvedViolations().isEmpty()) {
            System.out.println("  (none)");
        } else {
            report.resolvedViolations().stream()
                    .map(v -> "  " + v.dependency().source().name() + " -> " + v.dependency().target().name())
                    .sorted()
                    .forEach(System.out::println);
        }
    }

    private static String formatDirection(DriftDirection direction) {
        return switch (direction) {
            case IMPROVING -> "IMPROVING (improving)";
            case STABLE -> "STABLE";
            case DEGRADING -> "DEGRADING (worsening)";
        };
    }
}
