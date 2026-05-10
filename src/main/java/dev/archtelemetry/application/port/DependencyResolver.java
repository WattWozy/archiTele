package dev.archtelemetry.application.port;

import dev.archtelemetry.domain.Dependency;

import java.nio.file.Path;
import java.util.Set;

public interface DependencyResolver {
    Set<Dependency> resolve(Set<Path> sourceFiles);
}
