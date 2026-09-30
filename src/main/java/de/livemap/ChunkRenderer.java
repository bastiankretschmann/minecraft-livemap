package de.livemap;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Renders a chunk snapshot top-down into 16x16 ARGB pixels using vanilla map colors. */
final class ChunkRenderer {
    private static final int NONE = Integer.MIN_VALUE;

    private ChunkRenderer() {}

    static int[] render(ChunkSnapshot s, World.Environment env, int minY, int maxY) {
        int[] heights = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                heights[z * 16 + x] = surfaceY(s, x, z, env, minY, maxY);
            }
        }
        int[] out = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = heights[z * 16 + x];
                if (y == NONE) continue;
                BlockData d = s.getBlockData(x, y, z);
                Color c = d.getMapColor();
                // skip blocks without map color (glass, etc.)
                int guard = 0;
                while (c.getAlpha() == 0 && y > minY && guard++ < 32) {
                    y--;
                    d = s.getBlockData(x, y, z);
                    if (d.getMaterial().isAir()) continue;
                    c = d.getMapColor();
                }
                if (c.getAlpha() == 0) continue;
                int b;
                if (d.getMaterial() == Material.WATER) {
                    int depth = 0;
                    int yy = y;
                    while (yy > minY && s.getBlockData(x, yy, z).getMaterial() == Material.WATER) {
                        depth++;
                        yy--;
                    }
                    b = Math.max(110, 240 - depth * 12);
                } else {
                    int north = z > 0 ? heights[(z - 1) * 16 + x] : y;
                    if (north == NONE) north = y;
                    b = north < y ? 255 : (north == y ? 220 : 180);
                }
                int r = c.getRed() * b / 255;
                int g = c.getGreen() * b / 255;
                int bl = c.getBlue() * b / 255;
                out[z * 16 + x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
        return out;
    }

    private static int surfaceY(ChunkSnapshot s, int x, int z, World.Environment env, int minY, int maxY) {
        if (env == World.Environment.NETHER) {
            for (int y = Math.min(120, maxY - 2); y > minY; y--) {
                if (!s.getBlockData(x, y, z).getMaterial().isAir() && s.getBlockData(x, y + 1, z).getMaterial().isAir()) return y;
            }
            return NONE;
        }
        int y = s.getHighestBlockYAt(x, z);
        if (y < minY || y >= maxY) return NONE;
        while (y > minY && s.getBlockData(x, y, z).getMaterial().isAir()) y--;
        return s.getBlockData(x, y, z).getMaterial().isAir() ? NONE : y;
    }
}
