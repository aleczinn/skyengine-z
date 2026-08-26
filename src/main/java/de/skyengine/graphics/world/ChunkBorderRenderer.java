package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.structure.StructureBounds;
import de.skyengine.graphics.camera.Camera;
import org.joml.Vector3d;

import java.util.Arrays;

/** Debug-Overlay fuer Chunk-Grenzen, Structure-Auswahlen und Preview-Bounds. */
public class ChunkBorderRenderer {

    private static final int RADIUS = 1;
    private final DebugLineRenderer lines = new DebugLineRenderer();
    private float[] buf = new float[6 * 24];
    private int count;

    public void init() {
        this.lines.init("ChunkBorder VBO (Streaming)");
    }

    /** centerCX/CZ = Chunk-Koordinaten des Spielers; mode 1 = Chunk, 2 = Chunk + Sections. */
    public void render(Camera camera, ChunkManager chunks, int centerCX, int centerCZ, int mode) {
        if (mode <= 0) return;
        Vector3d cam = camera.getPosition();
        int size = ChunkSection.SIZE;

        this.count = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int ox = (centerCX + dx) << ChunkSection.SHIFT;
                int oz = (centerCZ + dz) << ChunkSection.SHIFT;
                box(cam, ox, 0, oz, ox + size, Chunk.HEIGHT, oz + size);
            }
        }
        draw(camera, 2.0F, 0.95F, 0.95F, 0.15F);

        if (mode >= 2) {
            this.count = 0;
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    Chunk chunk = chunks.getChunk(centerCX + dx, centerCZ + dz);
                    if (chunk == null) continue;
                    int ox = (centerCX + dx) << ChunkSection.SHIFT;
                    int oz = (centerCZ + dz) << ChunkSection.SHIFT;
                    for (int i = 0; i < Chunk.SECTIONS; i++) {
                        ChunkSection section = chunk.getSection(i);
                        if (section == null || section.isEmpty()) continue;
                        int sy = i << ChunkSection.SHIFT;
                        box(cam, ox, sy, oz, ox + size, sy + size, oz + size);
                    }
                }
            }
            draw(camera, 2.0F, 0.2F, 0.9F, 0.9F);
        }
    }

    /** Einzelne Debug-AABB. Max-Koordinaten sind inklusiv. */
    public void renderBox(Camera camera, StructureBounds bounds, float r, float g, float b) {
        if (bounds == null) return;
        this.count = 0;
        box(camera.getPosition(), bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() + 1,
                bounds.maxY() + 1, bounds.maxZ() + 1);
        draw(camera, 2.5F, r, g, b);
    }

    private void draw(Camera camera, float width, float r, float g, float b) {
        this.lines.render(camera, this.buf, this.count, width, r, g, b, 1.0F);
    }

    private void box(Vector3d cam, int x0, int y0, int z0, int x1, int y1, int z1) {
        float ax = (float) (x0 - cam.x), ay = (float) (y0 - cam.y), az = (float) (z0 - cam.z);
        float bx = (float) (x1 - cam.x), by = (float) (y1 - cam.y), bz = (float) (z1 - cam.z);
        line(ax, ay, az, bx, ay, az); line(bx, ay, az, bx, ay, bz);
        line(bx, ay, bz, ax, ay, bz); line(ax, ay, bz, ax, ay, az);
        line(ax, by, az, bx, by, az); line(bx, by, az, bx, by, bz);
        line(bx, by, bz, ax, by, bz); line(ax, by, bz, ax, by, az);
        line(ax, ay, az, ax, by, az); line(bx, ay, az, bx, by, az);
        line(bx, ay, bz, bx, by, bz); line(ax, ay, bz, ax, by, bz);
    }

    private void line(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (this.count + 6 > this.buf.length) this.buf = Arrays.copyOf(this.buf, this.buf.length * 2);
        this.buf[this.count++] = x0; this.buf[this.count++] = y0; this.buf[this.count++] = z0;
        this.buf[this.count++] = x1; this.buf[this.count++] = y1; this.buf[this.count++] = z1;
    }

    public void dispose() {
        this.lines.dispose();
    }
}
