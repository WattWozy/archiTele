package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.adapter.git.GitHistorySource;
import dev.archtelemetry.adapter.git.GitSnapshotSource;
import dev.archtelemetry.adapter.git.SnapshotConfig;
import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.application.AnalyzeHistory;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.ComputeGitStats;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.ReportHealth;
import dev.archtelemetry.application.port.DependencyResolver;
import dev.archtelemetry.application.port.HistorySource;
import dev.archtelemetry.application.port.SnapshotSource;
import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.CommitEntry;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Trend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) {
        Path repoPath = null;
        Path blueprintPath = null;
        int commitCount = 20;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repoPath = Path.of(args[++i]);
                case "--blueprint" -> blueprintPath = Path.of(args[++i]);
                case "--commits" -> commitCount = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                }
            }
        }

        if (repoPath == null || blueprintPath == null) {
            printUsage();
            return;
        }

        Blueprint blueprint = BlueprintLoader.load(blueprintPath);
        DependencyResolver resolver = new JavaDependencyResolver(blueprint.modules());
        SnapshotSource snapshotSource = new GitSnapshotSource(
                repoPath, resolver, new SnapshotConfig.LastN(commitCount));
        HistorySource historySource = new GitHistorySource(
                repoPath, new SnapshotConfig.LastN(commitCount));

        AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
        AnalyzeHistory analyzeHistory = new AnalyzeHistory(analyzeSnapshot);
        ComputeMetrics computeMetrics = new ComputeMetrics(analyzeSnapshot);
        ComputeGitStats computeGitStats = new ComputeGitStats();
        ReportHealth reportHealth = new ReportHealth();

        List<Snapshot> snapshots = snapshotSource.fetchSnapshots();
        List<CommitEntry> history = historySource.fetchHistory();
        Map<Module, ModuleGitStats> gitStats = computeGitStats.compute(blueprint, history);

        Trend trend = analyzeHistory.analyze(blueprint, snapshots);
        List<ArchitectureProfile> profiles = snapshots.stream()
                .map(s -> computeMetrics.compute(blueprint, s, gitStats))
                .toList();
        HealthReport report = reportHealth.report(trend, profiles);

        HealthReportPrinter.print(trend, report, snapshots);
    }

    private static void printUsage() {
        System.err.println("Usage: archtelemetry --repo <path> --blueprint <path> [--commits <n>]");
        System.exit(1);
    }
}
