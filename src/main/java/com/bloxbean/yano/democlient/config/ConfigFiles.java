package com.bloxbean.yano.democlient.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The cluster's public app-chain files, read from {@code demo.config-dir} so the UI can show how each chain is
 * configured. Only the files `yano.sh appchain` produces for sharing are served:
 * <ul>
 *   <li>{@code <app>.chain} - the sealed application (genesis of every chain; public, no keys)</li>
 *   <li>{@code <app>.draft.yaml} - the draft the operators sealed</li>
 *   <li>{@code <chain>.bindings.yaml}, {@code <chain>.role-plan.yaml} - per-chain plans</li>
 * </ul>
 * Works for any layout: a manual `appchain new/seal` folder, a showcase.sh instance directory
 * ({@code data/showcase/<instance>}) or a node's {@code apps/} directory. Node directories' secrets, data and logs
 * are never descended into, symlinks are not followed, and sizes are capped.
 */
@ApplicationScoped
public class ConfigFiles {
    private static final Logger LOG = Logger.getLogger(ConfigFiles.class);
    private static final int MAX_DEPTH = 4;
    private static final int MAX_FILES = 64;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final long CACHE_MILLIS = 30_000;
    private static final Set<String> SKIPPED_DIRS = Set.of("secrets", "data", "logs", "run", "outbox", "plugins",
            "node_modules", ".git");

    public enum Kind { SEALED, DRAFT, BINDINGS, ROLE_PLAN }

    @RegisterForReflection
    public record ConfigFile(String path, Kind kind, String content) {
    }

    @RegisterForReflection
    public record Listing(boolean configured, String note, List<ConfigFile> files) {
    }

    private final Optional<Path> root;
    private final ObjectMapper json;
    private volatile long loadedAt;
    private volatile Listing cached;

    @jakarta.inject.Inject
    public ConfigFiles(DemoConfig config, ObjectMapper json) {
        this(config.configDir().filter(dir -> !dir.isBlank())
                .map(dir -> Path.of(dir.startsWith("~/") ? System.getProperty("user.home") + dir.substring(1) : dir)), json);
    }

    ConfigFiles(Optional<Path> root, ObjectMapper json) {
        this.root = root.map(Path::toAbsolutePath).map(Path::normalize);
        this.json = json;
    }

    public Listing listing() {
        Listing current = cached;
        if (current != null && System.currentTimeMillis() - loadedAt < CACHE_MILLIS) {
            return current;
        }
        current = load();
        cached = current;
        loadedAt = System.currentTimeMillis();
        return current;
    }

    private Listing load() {
        if (root.isEmpty()) {
            return new Listing(false, "demo.config-dir is not set", List.of());
        }
        Path dir = root.get();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new Listing(true, "configured directory is not readable", List.of());
        }
        List<ConfigFile> files = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                    return !path.equals(dir) && SKIPPED_DIRS.contains(path.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (files.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    Kind kind = kindOf(path.getFileName().toString());
                    if (kind != null && attrs.isRegularFile() && attrs.size() <= MAX_FILE_BYTES) {
                        read(dir, path, kind).ifPresent(files::add);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            LOG.warnf("cannot scan demo.config-dir: %s", failure.getMessage());
        }
        // Nodes keep copies of the same sealed files (node*/apps/): serve each content once, the shortest path first.
        files.sort(Comparator.comparing((ConfigFile f) -> f.path().split("/").length).thenComparing(ConfigFile::path));
        Set<String> seen = new java.util.HashSet<>();
        files.removeIf(f -> !seen.add(f.kind() + "\n" + f.content()));
        files.sort(Comparator.comparing(ConfigFile::path));
        return new Listing(true, files.isEmpty() ? "no app-chain files found in the configured directory" : null,
                List.copyOf(files));
    }

    private Optional<ConfigFile> read(Path dir, Path path, Kind kind) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (kind == Kind.SEALED && !isSealedApplication(content)) {
                return Optional.empty();
            }
            return Optional.of(new ConfigFile(dir.relativize(path).toString().replace('\\', '/'), kind, content));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** A sealed application: {"format": "yano-appchain-…", "genesis": {"chains": […]}}. */
    private boolean isSealedApplication(String content) {
        try {
            JsonNode node = json.readTree(content);
            return node.path("format").asText("").startsWith("yano-appchain") && node.path("genesis").path("chains").isArray();
        } catch (IOException malformed) {
            return false;
        }
    }

    static Kind kindOf(String name) {
        if (name.endsWith(".chain")) {
            return Kind.SEALED;
        }
        if (name.endsWith(".draft.yaml")) {
            return Kind.DRAFT;
        }
        if (name.endsWith(".bindings.yaml")) {
            return Kind.BINDINGS;
        }
        if (name.endsWith(".role-plan.yaml")) {
            return Kind.ROLE_PLAN;
        }
        return null;
    }
}
