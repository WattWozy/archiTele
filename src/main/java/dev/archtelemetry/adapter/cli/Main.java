package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.adapter.git.GitHistorySource;
import dev.archtelemetry.adapter.git.GitSnapshotSource;
import dev.archtelemetry.adapter.git.SnapshotConfig;
import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.application.AnalyzeHistory;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.BlueprintValidator;
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
import dev.archtelemetry.domain.StaleModuleWarning;
import dev.archtelemetry.domain.Trend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) {
        Path repoPath = null;
        Path blueprintPath = null;
        Path outPath = null;
        int commitCount = 20;
        String format = "console";
        List<String> failOnConditions = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repoPath = Path.of(args[++i]);
                case "--blueprint" -> blueprintPath = Path.of(args[++i]);
                case "--commits" -> commitCount = Integer.parseInt(args[++i]);
                case "--format" -> format = args[++i];
                case "--out" -> outPath = Path.of(args[++i]);
                case "--fail-on" -> failOnConditions.add(args[++i]);
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
        BlueprintValidator blueprintValidator = new BlueprintValidator();

        List<Snapshot> snapshots = snapshotSource.fetchSnapshots();
        List<CommitEntry> history = historySource.fetchHistory();
        Map<Module, ModuleGitStats> gitStats = computeGitStats.compute(blueprint, history);

        Trend trend = analyzeHistory.analyze(blueprint, snapshots);
        List<ArchitectureProfile> profiles = snapshots.stream()
                .map(s -> computeMetrics.compute(blueprint, s, gitStats))
                .toList();
        HealthReport report = reportHealth.report(trend, profiles);

        List<StaleModuleWarning> staleWarnings = snapshots.isEmpty()
                ? List.of()
                : blueprintValidator.validate(blueprint, snapshots.get(snapshots.size() - 1));

        switch (format) {
            case "console" -> HealthReportPrinter.print(trend, report, snapshots, staleWarnings);
            case "json" -> writeOutput(JsonReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "markdown" -> writeOutput(MarkdownReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "html" -> writeOutput(HtmlReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            default -> {
                System.err.println("Unknown format: " + format + ". Valid values: console, json, markdown, html");
                System.exit(1);
            }
        }

        int exitCode = evaluateFailOn(failOnConditions, report, staleWarnings);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int evaluateFailOn(List<String> conditions, HealthReport report,
                                      List<StaleModuleWarning> staleWarnings) {
        int exitCode = 0;
        for (String condition : conditions) {
            boolean triggered = switch (condition) {
                case "new-violations" -> !report.newViolations().isEmpty();
                case "any-violations" -> report.totalViolations() > 0;
                case "new-cycles" -> report.latestProfile() != null
                        && !report.latestProfile().cycles().isEmpty();
                case "stale-blueprint" -> !staleWarnings.isEmpty();
                default -> {
                    if (condition.startsWith("instability-threshold=")) {
                        try {
                            double threshold = Double.parseDouble(
                                    condition.substring("instability-threshold=".length()));
                            yield report.latestProfile() != null
                                    && report.latestProfile().moduleMetrics().stream()
                                       .anyMatch(m -> m.instability() > threshold);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid instability threshold in: " + condition);
                            yield false;
                        }
                    }
                    System.err.println("Unknown --fail-on condition: " + condition
                            + ". Valid: new-violations, any-violations, new-cycles, "
                            + "instability-threshold=<N>, stale-blueprint");
                    yield false;
                }
            };
            if (triggered) {
                System.err.println("FAIL: --fail-on " + condition + " condition triggered");
                exitCode = 1;
            }
        }
        return exitCode;
    }

    private static void writeOutput(String content, Path outPath) {
        if (outPath == null) {
            System.out.print(content);
        } else {
            try {
                Files.writeString(outPath, content);
                System.err.println("Report written to: " + outPath);
            } catch (IOException e) {
                System.err.println("Failed to write report: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    private static void printUsage() {
        System.err.println("Usage: archtelemetry --repo <path> --blueprint <path> [--commits <n>] "
                + "[--format console|json|markdown|html] [--out <file>] "
                + "[--fail-on new-violations|any-violations|new-cycles|instability-threshold=<N>|stale-blueprint]");
        System.exit(1);
    }
}
