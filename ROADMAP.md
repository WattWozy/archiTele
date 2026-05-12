# ArchTelemetry Roadmap

**Product thesis:** Single-binary CLI for architectural observability. Zero infrastructure — no Neo4j, no Docker. One binary, one `.blueprint` file, immediate value. Language support is a plugin; Java is first.

**Current baseline (v0.1 — done):**
- Hexagonal architecture: domain, application, adapter rings clean
- Blueprint DSL: `module` + `allow` rules, `layer=N` annotations
- Git history walk via JGit: extracts `.java` files per commit into temp dirs
- Java static analysis: package/import regex → `Dependency` edges
- Module metrics: fanIn, fanOut, instability (fanOut/(fanIn+fanOut))
- Abstractness field exists on `ModuleMetrics` but **hardcoded to 0.0** — no parser support yet
- Cycle detection: DFS coloring in `ComputeMetrics` — finds back-edges but not guaranteed minimal SCC decomposition
- Trend: IMPROVING / STABLE / DEGRADING across violation counts
- New/resolved violation diff between consecutive snapshots
- Instability warnings for inner-layer modules (layer ≤ 1, instability > 0.5)
- Console report via `HealthReportPrinter`
- CLI: `--repo`, `--blueprint`, `--commits`
- Test coverage: 8 domain/application unit tests, 2 adapter integration tests (in-memory git)

---

## Phase 1 — Enrich the analysis (make the report worth reading)

**Value:** Answers "what's dangerous, not just what's wrong."

### 1.1 Method count proxy for complexity (WMC)

Current `JavaDependencyResolver` reads package + imports only. Add method declaration counting (regex or simple line scan: count `{` at method level as proxy). This gives a per-file method count that feeds hotspot scores.

- **Domain:** Add `int methodCount` field to `Snapshot` via a new `FileMetrics` record or extend `Dependency` into a richer `ModuleSnapshot` that carries file-level counts. Simplest: new domain record `FileStats(Path file, Module module, int methodCount)`.
- **Application port:** Extend `DependencyResolver` interface to return `ResolvedFile` (dependencies + method count) rather than raw `Set<Dependency>`. Or add a separate `ComplexityResolver` port.
- **Adapter (java):** `JavaDependencyResolver` counts `methodCount` per file; rolls up to module total as WMC proxy.
- **DoD:** `ModuleMetrics.wmc` populated from latest snapshot. Printed in console report.

### 1.2 Git author extraction

`GitSnapshotSource` currently ignores commit author. Add author name extraction per commit and track which modules changed in that commit (files changed → module mapping via blueprint patterns).

- **Domain:** New `CommitInfo(String commitId, String authorEmail, Instant timestamp)`. New `ModuleGitStats(Module module, int commitCount, int authorCount, Set<String> authorEmails)`.
- **Application:** New use case `ComputeGitStats` — given all commits for a repo window, produce `ModuleGitStats` per module.
- **Adapter (git):** `GitSnapshotSource` or a new `GitHistorySource` extracts `(commitId, authorEmail, changedFiles[])` per commit. File-to-module mapping reuses blueprint pattern matching.
- **DoD:** `ModuleGitStats` produced alongside `ArchitectureProfile`. Console report shows commit count and author count per module.

### 1.3 Composite risk scores

Requires 1.1 + 1.2. All computed in application layer; no domain changes beyond adding fields to `ModuleMetrics`.

| Score | Formula | Notes |
|---|---|---|
| `hotspot` | `wmc × commitCount` | complex + churning = bug magnet |
| `churnAcceleration` | `commits(last30d) / avg(commits(31-90d per 30d))` | ratio > 1.0 = getting worse |
| `busFactorRisk` | `commitCount / distinctAuthorCount` | high = knowledge concentration |

- **Domain:** Extend `ModuleMetrics` with `wmc`, `hotspot`, `churnAcceleration`, `busFactorRisk` (nullable/0 when git data unavailable).
- **Application:** `ComputeMetrics.compute()` accepts `ModuleGitStats` alongside snapshot; populates composite scores.
- **DoD:** All three scores printed in console report module table. Modules flagged with `⚠ hotspot` or `⚠ bus-factor` symbols.

### 1.4 Tarjan SCC for true cycle detection

Current DFS coloring finds back-edges but reports one cycle per back-edge, not minimal SCCs. Replace `ComputeMetrics.detectCycles()` with Tarjan's SCC algorithm. Modules in the same SCC of size > 1 form a cycle.

- **Domain:** No change — `DependencyCycle` already holds `List<Module>`.
- **Application:** Replace `detectCycles()` in `ComputeMetrics` with Tarjan impl. Each non-trivial SCC (size ≥ 2) becomes one `DependencyCycle`.
- **DoD:** Existing cycle tests pass. Add test: 4-module diamond with cycle produces exactly one SCC.

### 1.5 Chronic violations and violation age

Track how many consecutive snapshots a violation has existed. "Chronic" = present in last N snapshots without interruption.

- **Domain:** New `ViolationRecord(Violation violation, int ageInSnapshots, boolean isChronic)`.
- **Application:** `ReportHealth` extends diff logic: for each violation in latest snapshot, walk back through `Trend.entries()` to count how many consecutive snapshots it appeared. Threshold configurable (default: chronic if age ≥ 3).
- **Adapter (cli):** Console report adds "Chronic violations (N+ snapshots):" section.
- **DoD:** `HealthReport` exposes `List<ViolationRecord>`. Console report separates chronic from new.

**Phase 1 definition of done:** `archtelemetry --repo X --blueprint Y` prints hotspot score, churn acceleration, bus factor, true SCC cycles, and flags chronic violations. All new logic covered by unit tests.

---

## Phase 2 — Make the output shareable

**Value:** Teams can embed reports in PRs, wikis, and dashboards without custom tooling.

### 2.1 JSON output

- **Adapter (cli):** Add `--format json` flag to `Main`. New `JsonReportWriter` serializes `HealthReport` + `Trend` + `List<ArchitectureProfile>` to JSON using hand-written serialization (no Jackson dependency — keep the binary lean). Output to stdout or `--out <file>`.
- **DoD:** `--format json` produces valid JSON parseable by `jq`. Schema documented in README.

### 2.2 Markdown output

- **Adapter (cli):** `MarkdownReportWriter`. Produces a self-contained `.md` file with: summary table, violation list (new/chronic/resolved), module metrics table, trend sparkline (ASCII).
- **DoD:** Output renders correctly on GitHub PR comment preview.

### 2.3 HTML report

- **Adapter (cli):** `HtmlReportWriter`. Single self-contained `.html` — inline CSS + JS, no CDN. Sections: module health table (sortable), dependency graph (SVG or Canvas force-directed), violation list, trend chart (SVG line chart). All data embedded as JSON in a `<script>` block.
- **DoD:** File opens in browser with no internet connection. Module graph shows nodes (modules) and edges (dependencies), color-coded by violation/clean.

**Phase 2 definition of done:** `--format [json|markdown|html]` flags work. HTML file self-contained. JSON schema stable.

---

## Phase 3 — CI integration

**Value:** Product becomes sticky when it blocks bad merges automatically.

### 3.1 Exit code behavior

- **Adapter (cli):** `Main` exits with non-zero code on configurable conditions. New `--fail-on` flag: `new-violations`, `any-violations`, `new-cycles`, `instability-threshold=<N>`. Multiple `--fail-on` flags combinable.
- **DoD:** `echo $?` returns 1 when threshold breached. Documented in README with CI snippet.

### 3.2 Blueprint validation (stale module detection)

Warn if a declared module matches zero files in the scanned commit.

- **Application:** New `BlueprintValidator` use case — for each `Module` in blueprint, check if any file in the latest snapshot resolved to it. Emit `StaleModuleWarning(Module module)` for zero-match modules.
- **Adapter (cli):** Print warnings before report. Optionally `--fail-on stale-blueprint`.
- **DoD:** `petclinic.blueprint` tested against a commit where one module is renamed — warning fires.

### 3.3 GitHub Action

- New file: `.github/action/action.yml` defining a composite action. Runs `archtelemetry` JAR (downloaded from release), posts PR comment with markdown report via GitHub API.
- **DoD:** Action usable with `uses: archtelemetry/archtelemetry@v1`. Comment shows new violations and module instability delta vs base branch.

### 3.4 GitLab CI template

- New file: `ci/gitlab-ci-template.yml`. Equivalent to GitHub Action: runs binary, uploads HTML report as GitLab artifact, posts MR comment.
- **DoD:** Template documented with minimal `.gitlab-ci.yml` example.

**Phase 3 definition of done:** A PR adding a forbidden import triggers a failing CI check and posts a comment identifying the specific violation.

---

## Phase 4 — Second language plugin (prove the architecture)

**Value:** Validates that the domain is truly language-agnostic. Unlocks JS/TS monorepos.

### 4.1 TypeScript/JavaScript DependencyResolver

- **Application port:** `DependencyResolver` interface unchanged — takes `Set<Path>`, returns `Set<Dependency>`. Language plugin = one new class.
- **Adapter (ts):** `TypeScriptDependencyResolver`. Parses `import ... from '...'` and `require('...')` statements via regex. Maps import paths to modules via blueprint package patterns (reinterpreted as path prefixes for TS, e.g., `src/domain/**`). Handles barrel re-exports as transparent.
- **Adapter (git):** `GitSnapshotSource` currently filters `*.java`. Add `--language [java|typescript]` CLI flag. `SnapshotSource` factory selects correct resolver and file suffix filter.
- **Blueprint DSL:** Pattern syntax extended: `module domain src/domain/**` (path-prefix patterns for non-Java). Backward-compatible — Java patterns contain `.` not `/`.
- **DoD:** Running against a TypeScript monorepo with known import violations produces correct violation list. Integration test using in-memory git with `.ts` files.

### 4.2 Multi-language blueprint

- **Domain:** `Module.packagePatterns()` already supports multiple patterns. Blueprint DSL already supports multiple pattern tokens per module. No domain change needed.
- **Adapter (cli):** `--language auto` detects dominant file type per commit and selects resolver, or runs both and merges dependency sets.
- **DoD:** A monorepo with both Java backend and TS frontend analyzed in one run from a single blueprint.

**Phase 4 definition of done:** TypeScript resolver passes its own integration test suite. A mixed-language repo analyzed end-to-end.

---

## Phase 5 — Deeper structural analysis

**Value:** Moves from "what's broken" to "what should be reorganized."

### 5.1 Real abstractness metric

`ModuleMetrics.abstractness` is currently hardcoded to `0.0` in `ModuleMetrics.compute()`. Fix requires the parser to distinguish type declarations.

- **Application port:** Extend `DependencyResolver` return type (or add `AbstractnessResolver` port) to report per-module counts of: total types, abstract types (interfaces + abstract classes).
- **Adapter (java):** `JavaDependencyResolver` adds regex patterns for `interface `, `abstract class `, `enum ` declarations. Counts per file, rolls up to module.
- **Application:** `ComputeMetrics` passes abstractness ratio to `ModuleMetrics.compute(module, fanIn, fanOut, abstractness)`.
- **Domain:** `ModuleMetrics.compute()` overload accepting `abstractness`. Existing zero-arg call keeps backward compat.
- **DoD:** `ModuleMetrics.abstractness` and `distanceFromMainSequence` populated correctly. Test: a module of only interfaces gets abstractness=1.0.

### 5.2 Module split and merge suggestions

- **Application:** New use case `SuggestRefactorings`. Rules:
  - Split candidate: `typeCount > 30 && cohesionRatio < 0.4` (large + loosely internally connected). Cohesion proxy: ratio of intra-module dependencies to total possible.
  - Merge candidate: `typeCount < 5 && single dominant external partner` (tiny + tightly coupled to one neighbor).
- **Domain:** New `RefactoringSuggestion(Module module, SuggestionType type, String reason)`.
- **Adapter (cli):** Console report adds "--- Refactoring Suggestions ---" section.
- **DoD:** Applied to `petclinic.blueprint` — produces at least one actionable suggestion.

### 5.3 Community detection (algorithmic vs declared structure)

Identify clusters in the actual dependency graph that don't align with declared modules — reveals where the real architecture has drifted from the intended one.

- **Application:** Implement Union-Find over `Dependency` edges (undirected projection). Groups of strongly connected modules form communities. Compare community membership to module membership — mismatches = reorganization candidates.
- **Domain:** New `ArchitectureCommunity(Set<Module> modules, String suggestedName)`.
- **DoD:** Communities printed in report. Test: 3-module graph with two tightly coupled and one isolated produces two communities.

**Phase 5 definition of done:** Distance from main sequence uses real abstractness. Report includes split/merge suggestions and community mismatches.

---

## Phase 6 — Distribution and adoption

**Value:** Lowers installation barrier from "clone and build" to "one curl command."

### 6.1 GraalVM native-image

- **Build:** Add `native-image` Maven profile. Configure reflection metadata for JGit. Produce platform binaries: `archtelemetry-linux-amd64`, `archtelemetry-darwin-arm64`, `archtelemetry-windows-amd64.exe`.
- **CI:** GitHub Actions matrix build: 3 platforms, upload to release artifacts.
- **DoD:** `curl ... | bash` installs and runs the binary with no JVM. `archtelemetry --version` works.

### 6.2 Homebrew tap

- New repo: `archtelemetry/homebrew-tap`. Auto-updated on release via GitHub Action.
- `brew install archtelemetry/tap/archtelemetry` works.
- **DoD:** Tap formula tested on macOS arm64.

### 6.3 GitHub Releases with curl installer

- `install.sh`: detects OS/arch, downloads correct binary from release, places in `/usr/local/bin`.
- **DoD:** `curl -sSL https://... | sh` on fresh Ubuntu/macOS produces working binary.

**Phase 6 definition of done:** Three one-line install paths work (Homebrew, curl, direct binary download).

---

## Phase 7 — Advanced insights (enterprise tier)

**Value:** Combines structural analysis with test quality and graph algorithms for enterprise-grade risk prioritization.

### 7.1 Coverage integration (CRAP score)

Parse JaCoCo XML (`jacoco.xml`) or lcov (`lcov.info`) reports. Compute per-method CRAP score: `cc² × (1 - coverage)³ + cc`. Roll up to module `testDebtScore = totalCrap × (undercoveredMethods / measuredMethods)`.

- **Application port:** New `CoverageSource` interface. Implementations: `JacocoXmlCoverageSource`, `LcovCoverageSource`.
- **Domain:** `MethodCoverage(String fqn, int cyclomaticComplexity, double lineCoverage)`. `ModuleMetrics` extended with `crapScore`, `testDebtScore`.
- **Adapter (cli):** `--coverage <jacoco.xml>` flag.
- **DoD:** `testDebtScore` printed per module. Integration test with fixture JaCoCo XML.

### 7.2 PageRank and betweenness centrality

Identify modules that are transitively critical (PageRank) and architectural chokepoints (betweenness).

- **Application:** Implement PageRank iteration (10-20 iterations sufficient) on module dependency graph. Implement betweenness centrality via Brandes algorithm (or sampled for large graphs). Compute `hubScore = pageRank × betweenness × typeCount`.
- **Domain:** `ModuleMetrics` extended with `pageRank`, `betweenness`, `hubScore`.
- **DoD:** Modules ranked by hubScore in report. Test: star-topology graph — center module gets highest betweenness.

### 7.3 Blueprint inference

Point the tool at a codebase with no blueprint — it infers module structure from package naming conventions and existing import patterns, then asks "is this what you intended?"

- **Application:** New `InferBlueprint` use case. Groups packages by common prefix depth (configurable: default top-2 segments). Derives allow rules from observed import patterns with frequency threshold.
- **Adapter (cli):** `archtelemetry infer --repo <path> [--language java]` prints a candidate `.blueprint` file to stdout.
- **DoD:** Running `infer` on this repo produces a blueprint close to `arch.blueprint`. Output is a valid blueprint file that can be loaded by `BlueprintLoader`.

### 7.4 Natural language query interface

Ask architecture questions in plain English; get specific, actionable findings.

- **Application:** New `QueryArchitecture` use case — takes a `HealthReport` + `ArchitectureProfile` + natural language question, formats structured context, calls LLM API, returns findings.
- **Output format per finding:** WHAT / WHY IT HURTS / NEXT ACTION / EFFORT (S/M/L) / SKIP IF
- **Adapter (cli):** `archtelemetry query --repo X --blueprint Y "which modules are highest risk?"`. Requires `ARCHTELEMETRY_API_KEY` env var.
- **DoD:** Query produces actionable output with specific module names and next-step recommendations.

**Phase 7 definition of done:** Coverage, PageRank/betweenness, blueprint inference, and NL query each working end-to-end with at least one integration test.

---

## Metric implementation order (cross-phase reference)

Derived from `powers.md` priority list, mapped to phases above:

| Metric | Phase | Prerequisite | Status |
|---|---|---|---|
| fanIn, fanOut, instability | — | — | Done |
| Cycle detection (pairs) | — | — | Done (DFS) |
| Tarjan SCC cycles | 1.4 | — | Needed |
| WMC proxy (method count) | 1.1 | — | Needed |
| Git author extraction | 1.2 | — | Needed |
| Hotspot = wmc × commitCount | 1.3 | 1.1 + 1.2 | Needed |
| ChurnAcceleration | 1.3 | 1.2 | Needed |
| BusFactorRisk | 1.3 | 1.2 | Needed |
| Chronic violation age | 1.5 | — | Needed |
| Abstractness (real) | 5.1 | — | Needed |
| Distance from main sequence | 5.1 | 5.1 | Partial (abstractness=0) |
| Split/merge suggestions | 5.2 | 5.1 | Needed |
| Community detection | 5.3 | — | Needed |
| CRAP score | 7.1 | — | Needed |
| PageRank | 7.2 | — | Needed |
| Betweenness centrality | 7.2 | — | Needed |
| HubScore | 7.2 | 7.2 | Needed |
| Blueprint inference | 7.3 | — | Needed |
| NL query interface | 7.4 | all | Needed |
