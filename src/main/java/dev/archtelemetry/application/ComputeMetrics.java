package dev.archtelemetry.application;

import dev.archtelemetry.domain.ArchitectureProfile;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.DependencyCycle;
import dev.archtelemetry.domain.Blueprint;
import dev.archtelemetry.domain.Module;
import dev.archtelemetry.domain.ModuleGitStats;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.Violation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
        return compute(blueprint, snapshot, Map.of());
    }

    public ArchitectureProfile compute(Blueprint blueprint, Snapshot snapshot, Map<Module, ModuleGitStats> gitStats) {
        Set<Dependency> deps = snapshot.dependencies();
        Set<Module> modules = blueprint.modules();
        Map<Module, Integer> wmcByModule = snapshot.moduleWmc();

        Set<ModuleMetrics> metricsSet = new HashSet<>();
        for (Module m : modules) {
            int fanOut = 0;
            int fanIn = 0;
            for (Dependency dep : deps) {
                if (dep.source().equals(m)) fanOut++;
                if (dep.target().equals(m)) fanIn++;
            }
            int wmc = wmcByModule.getOrDefault(m, 0);
            metricsSet.add(ModuleMetrics.compute(m, fanIn, fanOut, wmc, gitStats.get(m)));
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

        Map<Module, Integer> index = new HashMap<>();
        Map<Module, Integer> lowlink = new HashMap<>();
        Map<Module, Boolean> onStack = new HashMap<>();
        Deque<Module> stack = new ArrayDeque<>();
        Set<DependencyCycle> cycles = new HashSet<>();
        int[] counter = {0};

        for (Module m : modules) {
            if (!index.containsKey(m)) {
                tarjan(m, adj, index, lowlink, onStack, stack, cycles, counter);
            }
        }
        return cycles;
    }

    private void tarjan(Module v, Map<Module, Set<Module>> adj,
                        Map<Module, Integer> index, Map<Module, Integer> lowlink,
                        Map<Module, Boolean> onStack, Deque<Module> stack,
                        Set<DependencyCycle> cycles, int[] counter) {
        index.put(v, counter[0]);
        lowlink.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.put(v, true);

        for (Module w : adj.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                tarjan(w, adj, index, lowlink, onStack, stack, cycles, counter);
                lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
            } else if (Boolean.TRUE.equals(onStack.get(w))) {
                lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
            }
        }

        if (lowlink.get(v).equals(index.get(v))) {
            List<Module> scc = new ArrayList<>();
            Module w;
            do {
                w = stack.pop();
                onStack.put(w, false);
                scc.add(w);
            } while (!w.equals(v));
            if (scc.size() >= 2) {
                cycles.add(new DependencyCycle(scc));
            }
        }
    }
}
