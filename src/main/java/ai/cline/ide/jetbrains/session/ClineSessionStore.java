package ai.cline.ide.jetbrains.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for Cline's on-disk session store.
 *
 * <p>Cline CLI records every run under
 * <code>~/.cline/data/sessions/&lt;sessionId&gt;/&lt;sessionId&gt;.json</code>
 * (on Windows: <code>%USERPROFILE%\.cline\data\sessions</code>). That file is
 * Cline's equivalent of OpenCode's <code>service.json</code>: it carries the
 * status (<code>running</code> / <code>idle</code> / <code>completed</code>),
 * the working directory, the model / provider and the Plan/Act mode, so the IDE
 * can follow a turn without scraping the terminal.
 *
 * <p>The root can be overridden with the {@code CLINE_DATA_DIR} environment
 * variable (the CLI's <code>--data-dir</code> is not visible to the IDE).
 */
public final class ClineSessionStore {

    private static final Logger LOG = Logger.getInstance(ClineSessionStore.class);

    private ClineSessionStore() {}

    /** Root of the CLI state directory (<code>~/.cline/data</code>). */
    public static Path dataDir() {
        String override = System.getenv("CLINE_DATA_DIR");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override.trim());
            // Accept both "--data-dir ~/.cline/data" and "--data-dir ~/.cline".
            Path name = p.getFileName();
            if (name != null && "cline".equals(name.toString())) return p.resolve("data");
            return p;
        }
        String home = System.getProperty("user.home", "");
        return Paths.get(home, ".cline", "data");
    }

    public static Path sessionsDir() {
        return dataDir().resolve("sessions");
    }

    /**
     * Find the Cline session that belongs to this project: the newest
     * interactive session whose working directory is the project root, falling
     * back to the newest session overall.
     */
    public static ClineSession find(Project project) {
        List<ClineSession> all = list();
        if (all.isEmpty()) return null;
        String base = project.getBasePath();
        if (base != null) {
            String wanted = normalize(base);
            ClineSession best = null;
            for (ClineSession s : all) {
                if (!s.interactive) continue;
                if (!wanted.equals(normalize(s.cwd))) continue;
                if (best == null || s.startedAt > best.startedAt) best = s;
            }
            if (best != null) return best;
        }
        return all.get(0);
    }

    /** All sessions found on disk, newest first. */
    public static List<ClineSession> list() {
        List<ClineSession> out = new ArrayList<>();
        Path root = sessionsDir();
        if (!Files.isDirectory(root)) return out;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                Path file = dir.resolve(name + ".json");
                if (!Files.isRegularFile(file)) continue;
                ClineSession s = read(file);
                if (s != null) out.add(s);
            }
        } catch (IOException e) {
            LOG.debug("could not scan Cline sessions", e);
        }
        out.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        return out;
    }

    /** Parse one session file; returns null when it is unreadable or malformed. */
    public static ClineSession read(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            ClineSession s = new ClineSession();
            Path dir = file.getParent() != null ? file.getParent() : file;
            String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
            s.id = str(o, "session_id", dirName);
            s.status = str(o, "status", "unknown");
            s.cwd = str(o, "cwd", str(o, "workspace_root", ""));
            s.model = str(o, "model", "");
            s.provider = str(o, "provider", "");
            s.prompt = str(o, "prompt", "");
            s.interactive = bool(o, "interactive", true);
            s.pid = num(o, "pid");
            s.startedAt = parseInstantMs(str(o, "started_at", ""));
            if (s.startedAt == 0) s.startedAt = dirNameMillis(dirName);
            JsonElement metaElement = o.get("metadata");
            JsonObject meta = metaElement != null && metaElement.isJsonObject()
                    ? metaElement.getAsJsonObject() : null;
            s.mode = str(meta, "mode", "");
            s.title = str(meta, "title", "");
            s.messagesPath = dir.resolve(s.id + ".messages.json");
            s.file = file;
            return s;
        } catch (Exception e) {
            LOG.debug("could not read Cline session " + file, e);
            return null;
        }
    }

    private static long parseInstantMs(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Session directory names start with epoch millis ({@code 1793328051761_abcd}). */
    private static long dirNameMillis(String name) {
        int i = name.indexOf('_');
        String head = i > 0 ? name.substring(0, i) : name;
        try {
            return Long.parseLong(head);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return def;
        }
    }

    private static long num(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String normalize(String path) {
        if (path == null) return "";
        String v = path.replace('\\', '/');
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v.toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Turn tracking (read from the transcript Cline writes itself)
    // ------------------------------------------------------------------

    /** Whether the agent is currently working on a turn. */
    public enum TurnState { RUNNING, IDLE, UNKNOWN }

    /** Result of inspecting a session transcript. */
    public static final class Probe {
        public final TurnState state;
        public final long messagesMtime;
        public final int messageCount;
        public final String lastRole;

        Probe(TurnState state, long messagesMtime, int messageCount, String lastRole) {
            this.state = state;
            this.messagesMtime = messagesMtime;
            this.messageCount = messageCount;
            this.lastRole = lastRole;
        }
    }

    private static volatile String cacheKey;
    private static volatile Probe cache;

    /**
     * Inspect the session transcript to decide whether a turn is running.
     *
     * <p>The session JSON keeps {@code status=running} for as long as the TUI
     * process lives, so it cannot tell an idle prompt from a working agent. The
     * transcript can: it is an Anthropic-style message list, so
     * <ul>
     *   <li>last message is {@code assistant} with a {@code tool_use} block →
     *       the agent is executing tools (running),</li>
     *   <li>last message is {@code user} (a prompt, or a {@code tool_result} the
     *       model still has to consume) → running,</li>
     *   <li>last message is {@code assistant} with only text / thinking → the
     *       turn is finished and Cline is waiting for input (idle).</li>
     * </ul>
     * Parsing is cached on (path, mtime, size), so an unchanged transcript costs
     * nothing.
     */
    public static Probe probe(ClineSession session) {
        if (session == null || session.messagesPath == null) {
            return new Probe(TurnState.UNKNOWN, 0, 0, "");
        }
        Path path = session.messagesPath;
        if (!Files.isRegularFile(path)) return new Probe(TurnState.UNKNOWN, 0, 0, "");
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            String key = path + "|" + mtime + "|" + size;
            Probe cached = cache;
            if (cached != null && key.equals(cacheKey)) return cached;

            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement messagesElement = root.get("messages");
            JsonArray messages = messagesElement != null && messagesElement.isJsonArray()
                    ? messagesElement.getAsJsonArray() : new JsonArray();
            int count = messages.size();
            String lastRole = "";
            TurnState state = TurnState.IDLE;
            if (count > 0 && messages.get(count - 1).isJsonObject()) {
                JsonObject last = messages.get(count - 1).getAsJsonObject();
                lastRole = str(last, "role", "");
                state = stateOf(last, lastRole);
            }
            Probe probe = new Probe(state, mtime, count, lastRole);
            cacheKey = key;
            cache = probe;
            return probe;
        } catch (Exception e) {
            LOG.debug("could not read the Cline transcript " + path, e);
            return new Probe(TurnState.UNKNOWN, 0, 0, "");
        }
    }

    private static TurnState stateOf(JsonObject message, String role) {
        if ("assistant".equals(role)) {
            return hasBlockType(message, "tool_use") ? TurnState.RUNNING : TurnState.IDLE;
        }
        if ("user".equals(role)) {
            // A prompt or a tool result: the model still has to answer it.
            return TurnState.RUNNING;
        }
        return TurnState.IDLE;
    }

    private static boolean hasBlockType(JsonObject message, String type) {
        JsonElement contentElement = message.get("content");
        if (contentElement == null || !contentElement.isJsonArray()) return false;
        for (JsonElement block : contentElement.getAsJsonArray()) {
            if (block.isJsonObject() && type.equals(str(block.getAsJsonObject(), "type", ""))) {
                return true;
            }
        }
        return false;
    }
}
