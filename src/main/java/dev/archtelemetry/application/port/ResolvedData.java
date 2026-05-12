package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Module;

import java.util.Map;
import java.util.Set;

public record ResolvedData(Set<Dependency> dependencies, Map<Module, Integer> moduleWmc) {}
