package de.livemap;

import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Stores 256x256 tiles (16x16 chunks each) on disk. draw()/flush() must be called from one thread only;
 * markUploaded()/pendingCount() are thread-safe.
 */
final class TileStore {
    private static final int TILE = 256;
    private static final int CACHE_SIZE = 64;

    private final Path dir;
    private final Logger log;
    private final Map<String, Long> versions = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> dirty = new HashSet<>();
    private final Map<String, BufferedImage> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            if (size() <= CACHE_SIZE) return false;
            if (dirty.contains(eldest.getKey())) write(eldest.getKey(), eldest.getValue());
            return true;
        }
    };

    record Flush(Map<String, byte[]> files, Set<String> keys) {}

    TileStore(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
        try {
            Files.createDirectories(dir);
            Path v = dir.resolve("versions.txt");
            if (Files.exists(v)) {
                for (String line : Files.readAllLines(v, StandardCharsets.UTF_8)) {
                    int i = line.lastIndexOf('=');
                    if (i > 0) versions.put(line.substring(0, i), Long.parseLong(line.substring(i + 1)));
                }
            }
            Path p = dir.resolve("pending.txt");
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) pending.add(line);
                }
            }
        } catch (Exception e) {
            log.warning("Could not load tile state: " + e);
        }
    }

    void draw(String worldId, ChunkSnapshot snap, World.Environment env, int minY, int maxY) {
        int cx = snap.getX(), cz = snap.getZ();
        int tx = Math.floorDiv(cx, 16), tz = Math.floorDiv(cz, 16);
        String key = worldId + "/" + tx + "_" + tz;
        BufferedImage img = load(key);
        int px = (cx - tx * 16) * 16, pz = (cz - tz * 16) * 16;
        int[] pixels = ChunkRenderer.render(snap, env, minY, maxY);
        boolean changed = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int c = pixels[z * 16 + x];
                if (img.getRGB(px + x, pz + z) != c) {
                    img.setRGB(px + x, pz + z, c);
                    changed = true;
                }
            }
        }
        if (changed) dirty.add(key);
    }

    /** Writes dirty tiles to disk and returns the PNG bytes of all tiles that still need uploading. */
    Flush flush() {
        long now = System.currentTimeMillis() / 1000;
        for (String key : dirty) {
            BufferedImage img = cache.get(key);
            if (img != null) write(key, img);
            versions.put(key, now);
            pending.add(key);
        }
        dirty.clear();
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>(pending);
        for (String key : keys) {
            try {
                files.put("tiles/" + key + ".png", Files.readAllBytes(file(key)));
            } catch (IOException e) {
                log.warning("Missing tile " + key + ": " + e);
                pending.remove(key);
            }
        }
        saveState();
        return new Flush(files, keys);
    }

    void markUploaded(Set<String> keys) {
        pending.removeAll(keys);
        saveState();
    }

    int pendingCount() {
        return pending.size();
    }

    String versionsJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : new TreeMap<>(versions).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private synchronized void saveState() {
        try {
            StringBuilder sb = new StringBuilder();
            versions.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            Files.writeString(dir.resolve("versions.txt"), sb.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("pending.txt"), String.join("\n", pending), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("Could not save tile state: " + e);
        }
    }

    private Path file(String key) {
        return dir.resolve(key + ".png");
    }

    private BufferedImage load(String key) {
        BufferedImage img = cache.get(key);
        if (img != null) return img;
        img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        Path f = file(key);
        if (Files.exists(f)) {
            try {
                BufferedImage read = ImageIO.read(f.toFile());
                if (read != null) img.getGraphics().drawImage(read, 0, 0, null);
            } catch (IOException e) {
                log.warning("Could not read tile " + key + ": " + e);
            }
        }
        cache.put(key, img);
        return img;
    }

    private void write(String key, BufferedImage img) {
        try {
            Path f = file(key);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            ImageIO.write(img, "png", tmp.toFile());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Could not write tile " + key + ": " + e);
        }
    }
}
