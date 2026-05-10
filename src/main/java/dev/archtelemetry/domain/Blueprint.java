package dev.archtelemetry.domain;

import java.util.Set;

public final class Blueprint {

    private final Set<Module> modules;
    private final Set<Dependency> allowedDependencies;

    public Blueprint(Set<Module> modules, Set<Dependency> allowedDependencies) {
        this.modules = Set.copyOf(modules);
        this.allowedDependencies = Set.copyOf(allowedDependencies);
    }

    public boolean isAllowed(Dependency dependency) {
        return allowedDependencies.contains(dependency);
    }

    public Set<Module> modules() {
        return modules;
    }

    public Set<Dependency> allowedDependencies() {
        return allowedDependencies;
    }
}
