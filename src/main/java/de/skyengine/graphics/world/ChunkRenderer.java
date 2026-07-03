package de.skyengine.graphics.world;

import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.SpriteAnimations;
import de.skyengine.graphics.texture.TextureArray;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ChunkRenderer {

    private final ChunkManager chunkManager;
    private ShaderProgram shader;
    private TextureArray textures;
    private SpriteAnimations animations;
    private long lastAnimNanos;

    /* sectionKey -> mesh, render thread only */
    private final Map<Long, SectionMesh> meshes = new HashMap<>();

    /* Pro Frame neu befüllt: alle Sections, die den Frustum-Test bestanden haben */
    private final List<SectionMesh> visible = new ArrayList<>();

    /* Teilmenge von visible mit TRANSLUCENT-Layer - nur diese werden back-to-front sortiert */
    private final List<SectionMesh> translucentVisible = new ArrayList<>();

    private static final int MAX_UPLOADS_PER_FRAME = 8;

    /* Deckelt Quad-Sorts pro Frame — bei Kamerabewegung wollen sonst alle sichtbaren
       Translucent-Sections gleichzeitig neu sortieren (Ozean -> Upload-Spike). */
    private static final int MAX_TRANSLUCENT_SORTS_PER_FRAME = 8;

    private static final int TEXTURE_SIZE = 16;

    private int renderedSections = 0;
    private int totalSections = 0;

    public ChunkRenderer(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    /** Render thread, GL context required. Blocks.bootstrap() muss vorher gelaufen sein! */
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX_SOURCE, ShaderType.VERTEX),
                new Shader(FRAGMENT_SOURCE, ShaderType.FRAGMENT)
        );
        /* Layer-Reihenfolge kommt aus dem Model-Bake (BlockTextures) */
        String[] paths = BlockTextures.getOrderedPaths();
        this.animations = SpriteAnimations.build(paths, TEXTURE_SIZE);
        this.textures = new TextureArray(TEXTURE_SIZE, paths, this.animations.animatedLayers());
        this.animations.uploadInitial(this.textures);
        /* Mipmaps neu bauen, jetzt mit echten Fluid-Frame-0-Daten (animierte Layer waren beim
           ersten glGenerateMipmap noch leer → hätten in der Ferne transparente Mips). */
        this.textures.regenerateMipmaps();
        this.lastAnimNanos = System.nanoTime();
    }

    /**
     * Opaque- und Cutout-Pass (inkl. Upload/Cleanup/Frustum-Culling). Der Translucent-Pass
     * folgt separat in {@link #renderTranslucent}, damit Entities dazwischen rendern können
     * (Vanilla-Reihenfolge: Wasser blendet über Entities).
     */
    public void renderSolid(Camera camera) {
        /* 0. Texturanimationen vorrücken (Frame-Tausch, kein Re-Mesh) */
        long now = System.nanoTime();
        this.animations.tick(this.textures, (now - this.lastAnimNanos) / 1.0e9);
        this.lastAnimNanos = now;

        /* 1a. Prioritäts-Batches (Edit-/Fluid-Remeshes) immer zuerst und vollständig —
           das Volumen ist klein und der Spieler soll seine Änderung sofort sehen. */
        ChunkManager.MeshBatch batch;
        while ((batch = this.chunkManager.getPriorityUploadQueue().poll()) != null) {
            this.applyBatch(batch);
        }

        /* 1b. Normale Upload-Queue (Initial-Load), gedeckelt pro Frame */
        int uploads = 0;
        while (uploads < MAX_UPLOADS_PER_FRAME && (batch = this.chunkManager.getUploadQueue().poll()) != null) {
            this.applyBatch(batch);
            uploads++;
        }

        /* 2. Dispose meshes of unloaded chunks */
        Iterator<Map.Entry<Long, SectionMesh>> it = this.meshes.entrySet().iterator();
        while (it.hasNext()) {
            SectionMesh mesh = it.next().getValue();
            if (!this.chunkManager.getChunks().containsKey(Chunk.key(mesh.chunkX, mesh.chunkZ))) {
                mesh.dispose();
                it.remove();
            }
        }

        /* 3. Frustum culling, einmal pro Frame */
        Vector3d cam = camera.getPosition();
        int size = ChunkSection.SIZE;

        this.visible.clear();
        this.translucentVisible.clear();
        this.totalSections = this.meshes.size();

        for (SectionMesh mesh : this.meshes.values()) {
            float ox = offsetX(mesh, cam);
            float oy = offsetY(mesh, cam);
            float oz = offsetZ(mesh, cam);

            if (!camera.getFrustum().testAab(ox, oy, oz, ox + size, oy + size, oz + size)) continue;
            this.visible.add(mesh);
            if (mesh.hasLayer(RenderLayer.TRANSLUCENT)) this.translucentVisible.add(mesh);
        }
        this.renderedSections = this.visible.size();

        /* 4. Render-Pässe */
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformi("u_Textures", 0);
        this.textures.bind(0);

        /* Pass 1 + 2: opaque & cutout (Alpha-Test bei 0.5) */
        this.shader.setUniformf("u_AlphaCutoff", 0.5F);
        this.drawLayer(RenderLayer.OPAQUE, this.visible, cam);
        this.drawLayer(RenderLayer.CUTOUT, this.visible, cam);

        this.shader.unbind();
    }

    /**
     * Pass 3: translucent — zuletzt, mit Blending, Sections von hinten nach vorn sortiert,
     * Quads innerhalb der Sections per {@link SectionMesh#sortTranslucent} (Vanilla-Stil).
     * Nutzt die in {@link #renderSolid} befüllten visible-Listen desselben Frames.
     */
    public void renderTranslucent(Camera camera) {
        Vector3d cam = camera.getPosition();

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformi("u_Textures", 0);
        this.textures.bind(0);

        /* Nur die Sections mit Translucent-Layer sortieren, nicht die ganze visible-Liste. */
        this.translucentVisible.sort((a, b) -> Double.compare(distanceSq(b, cam), distanceSq(a, cam)));

        /* Per-Quad-Sortierung: nahe Sections zuerst (Liste ist fern -> nah). */
        int sortBudget = MAX_TRANSLUCENT_SORTS_PER_FRAME;
        for (int i = this.translucentVisible.size() - 1; i >= 0 && sortBudget > 0; i--) {
            if (this.translucentVisible.get(i).sortTranslucent(cam)) sortBudget--;
        }

        GL11.glEnable(GL11.GL_BLEND);
        this.shader.setUniformf("u_AlphaCutoff", 0.001F);
        this.drawLayer(RenderLayer.TRANSLUCENT, this.translucentVisible, cam);
        GL11.glDisable(GL11.GL_BLEND);

        this.shader.unbind();
    }

    /** Wendet einen Mesh-Batch an: alte Section-Meshes ersetzen, leere entfernen. */
    private void applyBatch(ChunkManager.MeshBatch batch) {
        for (ChunkManager.MeshResult result : batch.results()) {
            long key = sectionKey(result.chunkX(), result.sectionY(), result.chunkZ());

            SectionMesh old = this.meshes.remove(key);
            if (old != null) old.dispose();

            if (result.data() != null && !result.data().isEmpty()) {
                this.meshes.put(key, new SectionMesh(result.chunkX(), result.sectionY(), result.chunkZ(), result.data()));
            }
        }
    }

    private void drawLayer(RenderLayer layer, List<SectionMesh> meshes, Vector3d cam) {
        for (SectionMesh mesh : meshes) {
            if (!mesh.hasLayer(layer)) continue;

            float ox = offsetX(mesh, cam);
            float oy = offsetY(mesh, cam);
            float oz = offsetZ(mesh, cam);

            /* Mesh-Koordinaten sind section-lokal (0-32) */
            this.shader.setUniformVector3f("u_Offset", ox, oy, oz);
            mesh.render(layer);
        }
    }

    private static float offsetX(SectionMesh mesh, Vector3d cam) {
        return (float) (((long) mesh.chunkX << ChunkSection.SHIFT) - cam.x);
    }

    private static float offsetY(SectionMesh mesh, Vector3d cam) {
        return (float) (((long) mesh.sectionY << ChunkSection.SHIFT) - cam.y);
    }

    private static float offsetZ(SectionMesh mesh, Vector3d cam) {
        return (float) (((long) mesh.chunkZ << ChunkSection.SHIFT) - cam.z);
    }

    private static double distanceSq(SectionMesh mesh, Vector3d cam) {
        double cx = ((long) mesh.chunkX << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.x;
        double cy = ((long) mesh.sectionY << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.y;
        double cz = ((long) mesh.chunkZ << ChunkSection.SHIFT) + ChunkSection.SIZE / 2.0 - cam.z;
        return cx * cx + cy * cy + cz * cz;
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void dispose() {
        for (SectionMesh mesh : this.meshes.values()) mesh.dispose();
        this.meshes.clear();
        if (this.animations != null) this.animations.dispose();
        if (this.shader != null) this.shader.dispose();
        if (this.textures != null) this.textures.dispose();
    }

    /* Gepacktes Vertex-Format (16 Bytes, siehe ChunkMesher.VERTEX_SIZE):
       x: posX | posY<<16 (u16 fixed 8.8, Bias +1) — y: posZ | u<<16 (uv fixed 6.10, Bias +1)
       z: v | layer<<16 — w: rgb8 */
    private static final String VERTEX_SOURCE = """
            #version 460 core
            layout(location = 0) in uvec4 a_data;

            uniform mat4 u_ProjectionView;
            uniform vec3 u_Offset;

            out vec3 v_texCoord;
            out vec3 v_color;

            void main() {
                vec3 pos = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16), float(a_data.y & 0xFFFFu)) * (1.0 / 256.0) - 1.0;
                vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu)) * (1.0 / 1024.0) - 1.0;
                float layer = float(a_data.z >> 16);
                vec3 color = vec3(float(a_data.w & 0xFFu), float((a_data.w >> 8) & 0xFFu), float((a_data.w >> 16) & 0xFFu)) * (1.0 / 255.0);

                v_texCoord = vec3(uv, layer);
                v_color = color;
                gl_Position = u_ProjectionView * vec4(pos + u_Offset, 1.0);
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 460 core
            in vec3 v_texCoord;
            in vec3 v_color;

            uniform sampler2DArray u_Textures;
            uniform float u_AlphaCutoff;

            out vec4 fragColor;

            void main() {
                vec4 color = texture(u_Textures, v_texCoord);
                if (color.a < u_AlphaCutoff) discard;
                fragColor = vec4(color.rgb * v_color, color.a);
            }
            """;

    /** Das Block-TextureArray (von der GUI für Item-Icons mitgenutzt). Erst nach {@link #init} gültig. */
    public TextureArray getTextureArray() {
        return textures;
    }

    public int getRenderedSections() {
        return renderedSections;
    }

    public int getTotalSections() {
        return totalSections;
    }
}