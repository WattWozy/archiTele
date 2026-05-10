package dev.archtelemetry.domain;

import java.time.Instant;
import java.util.Set;

public record Snapshot(String commitId, Instant timestamp, Set<Dependency> dependencies) {
    public Snapshot {
        dependencies = Set.copyOf(dependencies);
    }
}
