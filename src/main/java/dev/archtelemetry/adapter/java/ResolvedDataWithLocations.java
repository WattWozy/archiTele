package dev.archtelemetry.adapter.java;

import dev.archtelemetry.application.port.ResolvedData;

import java.util.List;

public record ResolvedDataWithLocations(
        ResolvedData data,
        List<LocatedDependency> locatedDependencies
) {}
