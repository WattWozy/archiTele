package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.adapter.git.GitHistorySource;
import dev.archtelemetry.adapter.git.GitSnapshotSource;
import dev.archtelemetry.adapter.git.Language;
import dev.archtelemetry.adapter.git.SnapshotConfig;
import dev.archtelemetry.adapter.java.JavaDependencyResolver;
import dev.archtelemetry.adapter.typescript.TypeScriptDependencyResolver;
import dev.archtelemetry.application.port.LocatingDependencyResolver;
import dev.archtelemetry.application.port.ResolvedDataWithLocations;
import dev.archtelemetry.application.AnalyzeHistory;
import dev.archtelemetry.application.AnalyzeIncremental;
import dev.archtelemetry.application.AnalyzeSnapshot;
import dev.archtelemetry.application.BlueprintValidator;
import dev.archtelemetry.application.ComputeGitStats;
import dev.archtelemetry.application.ComputeMetrics;
import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.application.IncrementalResult;
import dev.archtelemetry.application.ReportHealth;
import dev.archtelemetry.application.port.ResolvedData;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public final class Main {

    public static void main(String[] args) {
        Path repoPath = null;
        Path blueprintPath = null;
        Path outPath = null;
        Path srcDir = null;
        int commitCount = 20;
        String format = "console";
        String language = "java";
        List<String> failOnConditions = new ArrayList<>();
        boolean incrementalMode = false;
        boolean watchMode = false;
        List<Path> changedFiles = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo"        -> repoPath = Path.of(args[++i]);
                case "--blueprint"   -> blueprintPath = Path.of(args[++i]);
                case "--commits"     -> commitCount = Integer.parseInt(args[++i]);
                case "--format"      -> format = args[++i];
                case "--out"         -> outPath = Path.of(args[++i]);
                case "--fail-on"     -> failOnConditions.add(args[++i]);
                case "--src"         -> srcDir = Path.of(args[++i]);
                case "--language"    -> language = args[++i];
                case "--incremental" -> incrementalMode = true;
                case "--watch"       -> watchMode = true;
                case "--changed"     -> {
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        changedFiles.add(Path.of(args[++i]));
                    }
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                }
            }
        }

        if (blueprintPath == null) {
            printUsage();
            return;
        }

        Blueprint blueprint = BlueprintLoader.load(blueprintPath);
        JavaDependencyResolver javaResolver = new JavaDependencyResolver(blueprint.modules());

        Language lang = switch (language) {
            case "typescript" -> Language.TYPESCRIPT;
            case "auto"       -> Language.AUTO;
            default           -> Language.JAVA;
        };

        // Resolver for watch/incremental: TypeScript needs sourceRoot, resolved later from srcDir/repo
        // For normal mode: GitSnapshotSource creates per-snapshot TS resolvers via factory
        // For watch/incremental: we build the resolver once the effective srcDir is known
        Path effectiveSrcDir = resolveSrcDir(repoPath, srcDir);
        Path tsRoot = effectiveSrcDir != null ? effectiveSrcDir : Path.of(".");
        LocatingDependencyResolver resolver = switch (lang) {
            case TYPESCRIPT -> new TypeScriptDependencyResolver(blueprint.modules(), tsRoot);
            default         -> javaResolver;
        };
        String fileExt = lang == Language.TYPESCRIPT ? ".ts" : ".java";

        if (watchMode) {
            runWatch(blueprint, resolver, repoPath, srcDir, fileExt, format);
            return;
        }

        if (incrementalMode) {
            runIncremental(blueprint, resolver, repoPath, srcDir, changedFiles, fileExt, format);
            return;
        }

        // Normal mode — requires --repo
        if (repoPath == null) {
            printUsage();
            return;
        }

        runNormal(blueprint, javaResolver, lang, blueprint.modules(), repoPath, commitCount, format, outPath, failOnConditions);
    }

    // -------------------------------------------------------------------------
    // Normal (git history) mode
    // -------------------------------------------------------------------------

    private static void runNormal(Blueprint blueprint, JavaDependencyResolver javaResolver,
                                  Language lang, Set<Module> modules,
                                  Path repoPath, int commitCount, String format,
                                  Path outPath, List<String> failOnConditions) {
        GitSnapshotSource snapshotSource = new GitSnapshotSource(
                repoPath, javaResolver,
                root -> new TypeScriptDependencyResolver(modules, root),
                lang, new SnapshotConfig.LastN(commitCount));
        GitHistorySource historySource = new GitHistorySource(
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
            case "console"     -> HealthReportPrinter.print(trend, report, snapshots, staleWarnings);
            case "json"        -> writeOutput(JsonReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "markdown"    -> writeOutput(MarkdownReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "html"        -> writeOutput(HtmlReportWriter.generate(trend, report, snapshots, staleWarnings), outPath);
            case "ai-feedback" -> {
                Set<dev.archtelemetry.domain.Violation> violations = report.latestProfile() != null
                        ? report.latestProfile().violations()
                        : Set.of();
                writeOutput(AiFeedbackWriter.generate(violations, blueprint), outPath);
            }
            default -> {
                System.err.println("Unknown format: " + format
                        + ". Valid: console, json, markdown, html, ai-feedback");
                System.exit(1);
            }
        }

        int exitCode = evaluateFailOn(failOnConditions, report, staleWarnings);
        if (exitCode != 0) System.exit(exitCode);
    }

    // -------------------------------------------------------------------------
    // Incremental mode — fast check of changed files against HEAD or working tree
    // -------------------------------------------------------------------------

    private static void runIncremental(Blueprint blueprint, LocatingDependencyResolver resolver,
                                       Path repoPath, Path srcDir,
                                       List<Path> changedFiles, String fileExt, String format) {
        // Derive source dir for working-tree baseline when no --repo
        Path effectiveSrcDir = resolveSrcDir(repoPath, srcDir);

        // Baseline snapshot
        Snapshot baseline;
        if (repoPath != null) {
            // Single git HEAD commit — fast
            GitSnapshotSource snapshotSource = new GitSnapshotSource(
                    repoPath, resolver, new SnapshotConfig.LastN(1));
            List<Snapshot> snapshots = snapshotSource.fetchSnapshots();
            baseline = snapshots.isEmpty() ? emptySnapshot() : snapshots.get(0);
        } else {
            // Full working-tree scan (no git)
            if (effectiveSrcDir == null || !Files.isDirectory(effectiveSrcDir)) {
                System.err.println("--incremental without --repo requires --src <source-dir>");
                System.exit(1);
                return;
            }
            Set<Path> allFiles = WorkingTreeScanner.scanFiles(effectiveSrcDir, fileExt);
            ResolvedData data = resolver.resolve(allFiles);
            baseline = new Snapshot("baseline", Instant.now(), data.dependencies(), data.moduleWmc());
        }

        // Changed files — from args or stdin
        if (changedFiles.isEmpty()) {
            readChangedFilesFromStdin(changedFiles);
        }
        if (changedFiles.isEmpty()) {
            System.err.println("No changed files. Use --changed <file>... or pipe paths to stdin.");
            System.exit(1);
            return;
        }

        List<Path> existing = changedFiles.stream().filter(Files::exists).toList();
        if (existing.isEmpty()) {
            System.err.println("All changed files are deleted. No incremental analysis possible.");
            System.exit(0);
            return;
        }

        AnalyzeSnapshot analyzeSnapshot = new AnalyzeSnapshot();
        AnalyzeIncremental analyzeIncremental = new AnalyzeIncremental(resolver, analyzeSnapshot);

        ResolvedDataWithLocations located = resolver.resolveWithLocations(Set.copyOf(existing));
        IncrementalResult result = analyzeIncremental.analyze(Set.copyOf(existing), blueprint, baseline);

        switch (format) {
            case "ai-feedback" -> {
                System.out.print(AiFeedbackWriter.generate(
                        result.newViolations(), located.locatedDependencies(), blueprint));
            }
            default -> {
                System.out.println("Changed files : " + existing.size());
                System.out.println("New violations: " + result.newViolations().size());
                System.out.println("All violations: " + result.allViolations().size());
                if (!result.newViolations().isEmpty()) {
                    System.out.println();
                    result.newViolations().forEach(v ->
                            System.out.println("  [VIOLATION] "
                                    + v.dependency().source().name() + " -> "
                                    + v.dependency().target().name()));
                }
            }
        }

        if (!result.newViolations().isEmpty()) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // Watch mode — continuous incremental analysis via NIO WatchService
    // -------------------------------------------------------------------------

    private static void runWatch(Blueprint blueprint, LocatingDependencyResolver resolver,
                                 Path repoPath, Path srcDir, String fileExt, String format) {
        Path effectiveSrcDir = resolveSrcDir(repoPath, srcDir);
        if (effectiveSrcDir == null || !Files.isDirectory(effectiveSrcDir)) {
            System.err.println("--watch requires --src <source-dir> (or --repo with a src/main/java subdirectory)");
            System.exit(1);
            return;
        }

        boolean aiFeedback = "ai-feedback".equals(format);
        WatchMode watchMode = new WatchMode(effectiveSrcDir, blueprint, resolver, fileExt, aiFeedback);
        try {
            watchMode.run();
        } catch (IOException e) {
            System.err.println("Watch error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path resolveSrcDir(Path repoPath, Path srcDir) {
        if (srcDir != null) return srcDir;
        if (repoPath != null) {
            Path candidate = repoPath.resolve("src/main/java");
            return Files.isDirectory(candidate) ? candidate : repoPath;
        }
        return null;
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot("empty", Instant.now(), Set.of(), Map.of());
    }

    private static void readChangedFilesFromStdin(List<Path> changedFiles) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) changedFiles.add(Path.of(line));
            }
        }
    }

    private static int evaluateFailOn(List<String> conditions, HealthReport report,
                                      List<StaleModuleWarning> staleWarnings) {
        int exitCode = 0;
        for (String condition : conditions) {
            boolean triggered = switch (condition) {
                case "new-violations"  -> !report.newViolations().isEmpty();
                case "any-violations"  -> report.totalViolations() > 0;
                case "new-cycles"      -> report.latestProfile() != null
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
                            System.err.println("Invalid threshold in: " + condition);
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
                System.err.println("FAIL: --fail-on " + condition + " triggered");
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
        System.err.println("""
                Usage:
                  archtelemetry --repo <path> --blueprint <path> [options]
                  archtelemetry --blueprint <path> --incremental [--repo <path>] [--src <dir>] [--changed <files>...] [--format console|ai-feedback]
                  archtelemetry --blueprint <path> --watch [--repo <path>] [--src <dir>] [--format console|ai-feedback]

                Options:
                  --commits <n>         Commits to analyze (default: 20)
                  --format <fmt>        console | json | markdown | html | ai-feedback
                  --out <file>          Write output to file (default: stdout)
                  --language <lang>     java | typescript | auto (default: java)
                  --fail-on <cond>      Exit 1 on: new-violations, any-violations, new-cycles,
                                        instability-threshold=<N>, stale-blueprint
                  --src <dir>           Source directory for watch/incremental (default: <repo>/src/main/java)
                  --changed <files>...  Changed files for --incremental (or pipe to stdin)
                """);
        System.exit(1);
    }
}
