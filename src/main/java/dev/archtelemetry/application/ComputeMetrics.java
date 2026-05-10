package dev.archtelemetry.application;

import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ComputeMetrics {

    private final AnalyzeSnapshot analyzeSnapshot;

    public ComputeMetrics(AnalyzeSnapshot analyzeSnapshot) {
        this.analyzeSnapshot = analyzeSnapshot;
    }

    public ArchitectureProfile compute(Blueprint blueprint, Snapshot snapshot) {
        Set<Dependency> deps = snapshot.dependencies();
        Set<Module> modules = blueprint.modules();

        Set<ModuleMetrics> metricsSet = new HashSet<>();
        for (Module m : modules) {
            int fanOut = 0;
            int fanIn = 0;
            for (Dependency dep : deps) {
                if (dep.source().equals(m)) fanOut++;
                if (dep.target().equals(m)) fanIn++;
            }
            metricsSet.add(ModuleMetrics.compute(m, fanIn, fanOut));
        }

        Set<DependencyCycle> cycles = detectCycles(new ArrayList<>(modules), deps);
        Set<Violation> violations = analyzeSnapshot.analyze(blueprint, snapshot);

        return new ArchitectureProfile(metricsSet, cycles, violations);
    }

    private Set<DependencyCycle> detectCycles(List<Module> modules, Set<Dependency> deps) {
        Map<Module, Set<Module>> adj = new HashMap<>();
        for (Module m : modules) adj.put(m, new HashSet<>());
        for (Dependency dep : deps) {
            if (adj.containsKey(dep.source()) && adj.containsKey(dep.target())) {
                adj.get(dep.source()).add(dep.target());
            }
        }

        Set<DependencyCycle> cycles = new HashSet<>();
        Map<Module, Color> color = new HashMap<>();
        for (Module m : modules) color.put(m, Color.WHITE);

        for (Module m : modules) {
            if (color.get(m) == Color.WHITE) {
                dfs(m, adj, color, new ArrayList<>(), cycles);
            }
        }
        return cycles;
    }

    private enum Color { WHITE, GRAY, BLACK }

    private void dfs(Module node, Map<Module, Set<Module>> adj, Map<Module, Color> color,
                     List<Module> path, Set<DependencyCycle> cycles) {
        color.put(node, Color.GRAY);
        path.add(node);

        for (Module neighbor : adj.getOrDefault(node, Set.of())) {
            Color neighborColor = color.getOrDefault(neighbor, Color.BLACK);
            if (neighborColor == Color.GRAY) {
                int cycleStart = path.indexOf(neighbor);
                cycles.add(new DependencyCycle(new ArrayList<>(path.subList(cycleStart, path.size()))));
            } else if (neighborColor == Color.WHITE) {
                dfs(neighbor, adj, color, path, cycles);
            }
        }

        path.remove(path.size() - 1);
        color.put(node, Color.BLACK);
    }
}
