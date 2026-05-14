# Arx

Architecture health monitoring for Java and TypeScript codebases. Tracks dependency violations, module coupling, churn hotspots, and architectural drift over git history — all from a single binary.

## Quick start

```bash
# Generate a blueprint from your repo
arx infer --repo .

# Review the output, save it
arx infer --repo . > arch.blu

# Analyze architecture health
arx scan --repo . --blueprint arch.blu
```

That's it. Two commands, zero to value.

---

## Installation

### Native binary (recommended)

Download the binary for your platform from [GitHub Releases](https://github.com/WattWozy/archiTele/releases/latest):

| Platform | Binary |
|----------|--------|
| Linux x86-64 | `arx-linux-amd64` |
| macOS arm64 | `arx-darwin-arm64` |
| Windows x86-64 | `arx-windows-amd64.exe` |

```bash
# Linux / macOS
chmod +x arx-linux-amd64
mv arx-linux-amd64 /usr/local/bin/arx
```

### Fat JAR (any platform with Java 21+)

```bash
java -jar arx.jar scan --repo . --blueprint arch.blu
```

### Build from source

Requires GraalVM 21.

```bash
mvn package -Pnative -DskipTests
# binary at target/arx
```

---

## The blueprint

A blueprint is a plain-text file that declares your intended architecture. Two directives:

```
module <name>  <package-pattern>  [layer=<N>]
allow  <source> -> <target>
```

Blueprint files conventionally use the `.blu` extension. The parser also accepts `.blueprint` for backward compatibility.

**Example — clean layered architecture:**

```
module domain      dev.myapp.domain.**      layer=0
module application dev.myapp.application.** layer=1
module adapter     dev.myapp.adapter.**     layer=2

allow application -> domain
allow adapter     -> application
allow adapter     -> domain
```

Everything not in an `allow` rule is a violation. Modules at lower `layer` numbers are inner (more stable); higher `layer` is outer.

### Generating a blueprint with `infer`

```bash
arx infer --repo . > arch.blu
arx infer --repo . --depth 3 > arch.blu
```

`infer` scans your source, groups packages by prefix depth, and emits `module` + `allow` declarations from observed imports. Edit the output to reflect your *intended* architecture (the inferred deps are your current actual deps — the point is to tighten them).

---

## Subcommands

### `scan` — full analysis report

```bash
arx scan --repo <path> --blueprint <path> [options]
```

Analyzes git history, computes all metrics, and prints a health report.

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Number of commits to analyze |
| `--format <fmt>` | `console` | `console` \| `json` \| `markdown` \| `html` \| `ai-feedback` |
| `--out <file>` | stdout | Write output to file |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--coverage <file>` | — | JaCoCo XML or `lcov.info` for CRAP scores |

**Examples:**

```bash
# Console report (default)
arx scan --repo . --blueprint arch.blu

# Export HTML report
arx scan --repo . --blueprint arch.blu --format html --out report.html

# Analyze last 50 commits with coverage
arx scan --repo . --blueprint arch.blu \
  --commits 50 --coverage target/site/jacoco/jacoco.xml

# TypeScript monorepo
arx scan --repo . --blueprint arch.blu --language typescript
```

---

### `check` — CI gate

```bash
arx check --repo <path> --blueprint <path> [options]
```

Like `scan` but designed for pipelines: silent on pass, exits 1 on violations.

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Number of commits to analyze |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--coverage <file>` | — | JaCoCo XML or `lcov.info` |
| `--fail-on <condition>` | `any-violations` | See conditions below |

**Fail conditions:**

| Condition | Triggers when |
|-----------|--------------|
| `any-violations` | Any violation exists (default) |
| `new-violations` | New violations appeared since previous commit |
| `new-cycles` | Dependency cycles exist in latest snapshot |
| `stale-blueprint` | Blueprint declares modules with no matching files |
| `instability-threshold=<N>` | Any module instability exceeds N (0.0–1.0) |

Multiple `--fail-on` flags are OR'd together.

**GitHub Actions example:**

```yaml
- name: Architecture check
  run: |
    arx check \
      --repo . \
      --blueprint arch.blu \
      --fail-on new-violations \
      --fail-on new-cycles
```

---

### `watch` — real-time feedback

```bash
arx watch --blueprint <path> [options]
```

Two modes depending on whether `--changed` is provided:

**Filesystem watcher (continuous)** — monitors source files and re-analyzes on every save:

```bash
arx watch --blueprint arch.blu --src src/main/java
arx watch --blueprint arch.blu --repo .
```

**One-shot incremental** — analyzes only the listed changed files against the last committed baseline:

```bash
arx watch --blueprint arch.blu --changed Foo.java Bar.java
git diff --name-only | arx watch --blueprint arch.blu --changed
```

| Flag | Default | Description |
|------|---------|-------------|
| `--blueprint <path>` | — | Blueprint file (required) |
| `--src <dir>` | `<repo>/src/main/java` | Source directory |
| `--repo <path>` | — | Git repository (for baseline in incremental mode) |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--format <fmt>` | `console` | `console` \| `ai-feedback` |
| `--changed <files>...` | — | Triggers one-shot incremental mode |

**AI harness mode** — incremental with `ai-feedback` format outputs structured JSON for AI coding assistants:

```bash
arx watch --blueprint arch.blu \
  --changed src/Foo.java \
  --format ai-feedback
```

---

### `infer` — blueprint generation

```bash
arx infer --repo <path> [--depth 2]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--depth <n>` | 2 | Package segments after common prefix to use as module name |

Scans all `.java` files, groups by package prefix, and emits a blueprint draft. Increase `--depth` for more granular modules.

---

### `query` — natural language interface

```bash
arx query --repo <path> --blueprint <path> "question"
```

Asks an LLM about your architecture based on the current metrics and violations.

```bash
arx query --repo . --blueprint arch.blu "where is my highest risk?"
arx query --repo . --blueprint arch.blu "which modules should I refactor first?"
arx query --repo . --blueprint arch.blu "explain the current violations"
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Commits to include in context |

Requires `ARX_API_KEY` (Anthropic API key). Optionally set `ARX_MODEL` to override the model (default: `claude-haiku-4-5-20251001`).

---

## Metrics reference

Every module in the latest snapshot gets these metrics:

| Metric | Description |
|--------|-------------|
| **Fan-In** | Modules that depend on this module |
| **Fan-Out** | Modules this module depends on |
| **Instability** | `Fan-Out / (Fan-In + Fan-Out)`. 0 = stable, 1 = unstable |
| **Abstractness** | Ratio of abstract types (interfaces/abstract classes) |
| **Distance** | `|Abstractness + Instability − 1|`. 0 = on main sequence |
| **WMC** | Weighted Method Count — sum of method complexities |
| **Hotspot** | `WMC × commit count` — high = complex and frequently changed |
| **ChurnAcceleration** | Rate of change increase across recent commits |
| **BusFactor Risk** | Concentration of commits among few authors |
| **PageRank** | Graph centrality — how many other modules point to this |
| **Betweenness** | How often this module lies on shortest dependency paths |
| **HubScore** | `PageRank × Betweenness × WMC` — architectural risk multiplier |
| **CRAP Score** | `Complexity² × (1 − coverage)²`. Requires `--coverage` |
| **Test Debt** | Aggregate uncovered complexity. Requires `--coverage` |

**Trend:** Arx analyzes N commits, computing a snapshot at each one, and reports whether violations are IMPROVING, STABLE, or DEGRADING over time.

**Violations** that persist 3+ snapshots are flagged as **chronic**.

**Cycles** are detected with Tarjan's SCC algorithm. Any SCC of size ≥ 2 is reported.

**Refactoring suggestions** are emitted when a module is too large (SPLIT) or when two modules are tightly coupled with no violations (MERGE candidate).

**Architecture communities** group modules by their actual coupling graph (union-find), and flag cross-layer communities as warnings.

---

## Output formats

| Format | Use case |
|--------|----------|
| `console` | Human-readable terminal report |
| `json` | Machine-readable, for dashboards or further processing |
| `markdown` | Documentation, PR comments |
| `html` | Standalone report with styled tables |
| `ai-feedback` | Structured JSON for AI coding assistants (file + line + fix suggestion) |

---

## Environment variables

| Variable | Description |
|----------|-------------|
| `ARX_API_KEY` | Anthropic API key (required for `query`) |
| `ARX_MODEL` | Model for `query` (default: `claude-haiku-4-5-20251001`) |

---

## Coverage integration

Pass a JaCoCo XML or lcov report to get CRAP scores and test debt per module:

```bash
# Maven: generate coverage first
mvn test jacoco:report

arx scan --repo . --blueprint arch.blu \
  --coverage target/site/jacoco/jacoco.xml
```

```bash
# JavaScript/TypeScript with lcov
arx scan --repo . --blueprint arch.blu \
  --coverage coverage/lcov.info
```

CRAP score: `complexity² × (1 − line_coverage)²`. A score above 30 indicates a method that is both complex and poorly tested.

---

## Blueprint syntax reference

```
# comment

# Declare a module: name, package glob, optional layer
module <name>  <package-prefix>.**  [layer=<N>]

# Permit a dependency direction
allow <source-module> -> <target-module>
```

- Package patterns support `.**` suffix (matches the prefix and all sub-packages)
- `layer=0` is innermost (domain), higher numbers are outer (adapters, infrastructure)
- Dependencies from lower layer to higher layer are flagged with dependency-inversion guidance
- Multiple `module` lines with the same name add multiple package patterns to the same module

**Full example:**

```
module domain         com.myapp.domain.**          layer=0
module application    com.myapp.application.**     layer=1
module ports          com.myapp.application.port.** layer=1
module adapter-web    com.myapp.adapter.web.**     layer=2
module adapter-db     com.myapp.adapter.db.**      layer=2
module adapter-cli    com.myapp.adapter.cli.**     layer=2

allow application  -> domain
allow application  -> ports
allow adapter-web  -> application
allow adapter-web  -> ports
allow adapter-db   -> application
allow adapter-db   -> ports
allow adapter-cli  -> application
allow adapter-cli  -> ports
```
