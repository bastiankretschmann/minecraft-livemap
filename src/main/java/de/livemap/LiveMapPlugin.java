package de.livemap;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LiveMapPlugin extends JavaPlugin {
    private TileStore store;
    private GitHubPublisher publisher;
    private ExecutorService renderThread;
    private ExecutorService uploadThread;
    private final ArrayDeque<Chunk> chunkQueue = new ArrayDeque<>();
    private final AtomicBoolean uploading = new AtomicBoolean();
    private volatile String lastStable = "";
    private byte[] indexHtml;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new TileStore(getDataFolder().toPath().resolve("tiles"), getLogger());
        renderThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "LiveMap-Render"));
        uploadThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "LiveMap-Upload"));

        try (InputStream in = getResource("web/index.html")) {
            indexHtml = in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            getLogger().warning("Could not read bundled web page: " + e);
        }

        String token = System.getenv("LIVEMAP_TOKEN");
        if (token == null || token.isBlank()) token = getConfig().getString("github.token", "");
        if (token == null || token.isBlank()) {
            getLogger().warning("No GitHub token configured - tiles are rendered but NOT published. "
                    + "Set github.token in config.yml or LIVEMAP_TOKEN.");
        } else {
            publisher = new GitHubPublisher(token.trim(), getConfig().getString("github.repo"),
                    getConfig().getString("github.branch", "gh-pages"));
        }

        long renderTicks = Math.max(5, getConfig().getLong("render-interval-seconds", 30)) * 20L;
        long publishTicks = Math.max(15, getConfig().getLong("publish-interval-seconds", 60)) * 20L;
        int perTick = Math.max(1, getConfig().getInt("chunks-per-tick", 4));

        getServer().getScheduler().runTaskTimer(this, this::queueChunks, 100L, renderTicks);
        getServer().getScheduler().runTaskTimer(this, () -> snapshotBatch(perTick), 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> triggerPublish(false), publishTicks, publishTicks);
    }

    @Override
    public void onDisable() {
        if (renderThread != null) {
            renderThread.shutdown();
            try {
                renderThread.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // final flush so nothing is lost; upload happens on next start via pending.txt
            try {
                store.flush();
            } catch (Exception e) {
                getLogger().warning("Final flush failed: " + e);
            }
        }
        if (uploadThread != null) uploadThread.shutdown();
    }

    private boolean worldEnabled(World w) {
        List<String> list = getConfig().getStringList("worlds");
        return list.isEmpty() || list.contains(w.getName());
    }

    private static String worldId(World w) {
        return w.getName().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void queueChunks() {
        if (!chunkQueue.isEmpty()) return;
        int radius = Math.max(1, getConfig().getInt("render-radius-chunks", 10));
        Set<Chunk> seen = new HashSet<>();
        for (Player p : getServer().getOnlinePlayers()) {
            World w = p.getWorld();
            if (!worldEnabled(w)) continue;
            int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!w.isChunkLoaded(pcx + dx, pcz + dz)) continue;
                    Chunk c = w.getChunkAt(pcx + dx, pcz + dz, false);
                    if (seen.add(c)) chunkQueue.add(c);
                }
            }
        }
    }

    private void snapshotBatch(int max) {
        for (int i = 0; i < max; i++) {
            Chunk c = chunkQueue.poll();
            if (c == null) return;
            World w = c.getWorld();
            if (!c.isLoaded()) continue;
            ChunkSnapshot snap = c.getChunkSnapshot(true, false, false);
            String id = worldId(w);
            World.Environment env = w.getEnvironment();
            int minY = w.getMinHeight(), maxY = w.getMaxHeight();
            renderThread.submit(() -> {
                try {
                    store.draw(id, snap, env, minY, maxY);
                } catch (Throwable t) {
                    getLogger().warning("Render failed: " + t);
                }
            });
        }
    }

    /** Must be called on the main thread (reads player/world state). */
    private void triggerPublish(boolean force) {
        if (publisher == null) return;
        if (uploading.get()) return;
        boolean showPlayers = getConfig().getBoolean("show-players", true);

        StringBuilder worlds = new StringBuilder();
        for (World w : getServer().getWorlds()) {
            if (!worldEnabled(w)) continue;
            if (worlds.length() > 0) worlds.append(',');
            worlds.append("{\"id\":\"").append(worldId(w)).append("\",\"name\":\"").append(esc(w.getName()))
                    .append("\",\"env\":\"").append(w.getEnvironment().name()).append("\"}");
        }
        StringBuilder players = new StringBuilder();
        if (showPlayers) {
            for (Player p : getServer().getOnlinePlayers()) {
                if (!worldEnabled(p.getWorld())) continue;
                if (players.length() > 0) players.append(',');
                players.append("{\"name\":\"").append(esc(p.getName())).append("\",\"world\":\"")
                        .append(worldId(p.getWorld())).append("\",\"x\":").append(p.getLocation().getBlockX())
                        .append(",\"z\":").append(p.getLocation().getBlockZ()).append('}');
            }
        }
        String w = worlds.toString(), pl = players.toString();

        renderThread.submit(() -> {
            try {
                TileStore.Flush flush = store.flush();
                String versions = store.versionsJson();
                String stable = "\"worlds\":[" + w + "],\"players\":[" + pl + "],\"versions\":" + versions;
                if (!force && flush.keys().isEmpty() && stable.equals(lastStable)) return;
                Map<String, byte[]> files = new LinkedHashMap<>(flush.files());
                String data = "{\"time\":" + System.currentTimeMillis() / 1000 + "," + stable + "}";
                files.put("data.json", data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                files.put(".nojekyll", new byte[0]);
                if (indexHtml != null) files.put("index.html", indexHtml);
                if (!uploading.compareAndSet(false, true)) return;
                uploadThread.submit(() -> {
                    try {
                        publisher.publish(files);
                        store.markUploaded(flush.keys());
                        lastStable = stable;
                        getLogger().info("Published " + flush.keys().size() + " tiles to GitHub.");
                    } catch (Exception e) {
                        getLogger().warning("Publish failed: " + e);
                    } finally {
                        uploading.set(false);
                    }
                });
            } catch (Throwable t) {
                getLogger().warning("Publish preparation failed: " + t);
            }
        });
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("publish")) {
            if (publisher == null) {
                sender.sendMessage("No GitHub token configured.");
            } else {
                queueChunks();
                triggerPublish(true);
                sender.sendMessage("Publish started.");
            }
            return true;
        }
        sender.sendMessage("Pending tiles: " + store.pendingCount() + ", publishing: " + (publisher != null));
        return true;
    }
}
