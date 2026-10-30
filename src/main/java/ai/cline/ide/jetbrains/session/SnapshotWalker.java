package ai.cline.ide.jetbrains.session;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks the project tree and keeps the content of every text file that Cline
 * could plausibly edit. Directories that never hold source (VCS metadata, build
 * output, dependency caches) are pruned so a turn snapshot stays fast.
 */
final class SnapshotWalker {

    private static final Logger LOG = Logger.getInstance(SnapshotWalker.class);

    /** Largest single file kept in a snapshot (512 KiB). */
    private static final long MAX_FILE_BYTES = 512L * 1024;
    /** Total memory budget for one snapshot (64 MiB). */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", ".vscode", ".vs",
            "node_modules", "bower_components", "vendor",
            "build", "out", "dist", "target", "bin", "obj",
            ".venv", "venv", "env", "__pycache__", ".mypy_cache", ".pytest_cache",
            ".next", ".nuxt", ".cache", ".parcel-cache", "coverage",
            ".cline", ".opencode", ".claude", ".terraform"
    );

    private final String basePath;
    private final Map<String, byte[]> files = new HashMap<>();
    private long totalBytes;

    SnapshotWalker(String basePath) {
        this.basePath = basePath;
    }

    Map<String, byte[]> walk() {
        if (basePath == null || basePath.isBlank()) return files;
        Path root = Paths.get(basePath);
        if (!Files.isDirectory(root)) return files;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String name = dir.getFileName() == null
                            ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_BYTES) {
                        add(file, attrs.size());
                    }
                    return totalBytes >= MAX_TOTAL_BYTES
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debug("workspace snapshot walk failed", e);
        }
        return files;
    }

    private void add(Path file, long size) {
        if (totalBytes + size > MAX_TOTAL_BYTES) return;
        try {
            byte[] data = Files.readAllBytes(file);
            if (WorkspaceSnapshot.isBinary(data)) return;
            files.put(relativize(file), data);
            totalBytes += data.length;
        } catch (Exception e) {
            // Locked / vanished file: skip it, it simply won't be diffed.
        }
    }

    private String relativize(Path file) {
        String rel = Paths.get(basePath).relativize(file).toString();
        return rel.replace('\\', '/');
    }
}
