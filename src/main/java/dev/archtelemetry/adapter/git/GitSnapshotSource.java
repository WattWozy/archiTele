package dev.archtelemetry.adapter.git;

import dev.archtelemetry.application.port.DependencyResolver;
import dev.archtelemetry.application.port.SnapshotSource;
import dev.archtelemetry.domain.Dependency;
import dev.archtelemetry.domain.Snapshot;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GitSnapshotSource implements SnapshotSource {

    private final Path repoPath;
    private final DependencyResolver resolver;
    private final SnapshotConfig config;

    public GitSnapshotSource(Path repoPath, DependencyResolver resolver, SnapshotConfig config) {
        this.repoPath = repoPath;
        this.resolver = resolver;
        this.config = config;
    }

    @Override
    public List<Snapshot> fetchSnapshots() {
        try (Git git = Git.open(repoPath.toFile())) {
            Repository repo = git.getRepository();
            List<RevCommit> commits = collectCommits(repo);
            List<Snapshot> snapshots = new ArrayList<>();
            for (RevCommit commit : commits) {
                snapshots.add(snapshotFor(repo, commit));
            }
            return List.copyOf(snapshots);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<RevCommit> collectCommits(Repository repo) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            if (config instanceof SnapshotConfig.DateRange) {
                walk.sort(RevSort.COMMIT_TIME_DESC);
            }
            walk.markStart(walk.parseCommit(repo.resolve("HEAD")));
            switch (config) {
                case SnapshotConfig.LastN lastN -> {
                    int count = 0;
                    for (RevCommit c : walk) {
                        if (count++ >= lastN.n()) break;
                        commits.add(c);
                    }
                }
                case SnapshotConfig.DateRange range -> {
                    for (RevCommit c : walk) {
                        Instant ts = Instant.ofEpochSecond(c.getCommitTime());
                        if (ts.isBefore(range.from())) break;
                        if (!ts.isAfter(range.to())) commits.add(c);
                    }
                }
                case SnapshotConfig.All all -> {
                    for (RevCommit c : walk) commits.add(c);
                }
            }
        }
        Collections.reverse(commits);
        return commits;
    }

    private Snapshot snapshotFor(Repository repo, RevCommit commit) throws IOException {
        Path tempDir = Files.createTempDirectory("archtelemetry-");
        try {
            Set<Path> javaFiles = extractJavaFiles(repo, commit, tempDir);
            Set<Dependency> deps = resolver.resolve(javaFiles);
            Instant ts = Instant.ofEpochSecond(commit.getCommitTime());
            return new Snapshot(commit.getId().getName(), ts, deps);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private Set<Path> extractJavaFiles(Repository repo, RevCommit commit, Path tempDir) throws IOException {
        Set<Path> files = new HashSet<>();
        try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            treeWalk.setFilter(PathSuffixFilter.create(".java"));
            while (treeWalk.next()) {
                String gitPath = treeWalk.getPathString();
                ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
                Path target = resolvePath(tempDir, gitPath);
                Files.createDirectories(target.getParent());
                Files.write(target, loader.getBytes());
                files.add(target);
            }
        }
        return files;
    }

    private Path resolvePath(Path base, String gitPath) {
        Path result = base;
        for (String part : gitPath.split("/")) {
            result = result.resolve(part);
        }
        return result;
    }

    private void deleteRecursive(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {
        }
    }
}
