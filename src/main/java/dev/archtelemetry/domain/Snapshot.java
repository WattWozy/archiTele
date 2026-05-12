package dev.archtelemetry.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record Snapshot(String commitId, Instant timestamp, Set<Dependency> dependencies, Map<Module, Integer> moduleWmc) {
    public Snapshot {
        dependencies = Set.copyOf(dependencies);
        moduleWmc = Map.copyOf(moduleWmc);
    }

    public Snapshot(String commitId, Instant timestamp, Set<Dependency> dependencies) {
        this(commitId, timestamp, dependencies, Map.of());
    }
}
