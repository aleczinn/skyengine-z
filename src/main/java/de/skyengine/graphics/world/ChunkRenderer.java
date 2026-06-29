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
        this.lastAnimNanos = System.nanoTime();
    }

    public void render(Camera camera) {
        /* 0. Texturanimationen vorrücken (Frame-Tausch, kein Re-Mesh) */
        long now = System.nanoTime();
        this.animations.tick(this.textures, (now - this.lastAnimNanos) / 1.0e9);
        this.lastAnimNanos = now;

        /* 1. Drain upload queue (bounded per frame) */
        int uploads = 0;
        ChunkManager.MeshBatch batch;
        while (uploads < MAX_UPLOADS_PER_FRAME && (batch = this.chunkManager.getUploadQueue().poll()) != null) {
            for (ChunkManager.MeshResult result : batch.results()) {
                long key = sectionKey(result.chunkX(), result.sectionY(), result.chunkZ());

                SectionMesh old = this.meshes.remove(key);
                if (old != null) old.dispose();

                if (result.data() != null && !result.data().isEmpty()) {
                    this.meshes.put(key, new SectionMesh(result.chunkX(), result.sectionY(), result.chunkZ(), result.data()));
                }
            }
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

        /* Pass 3: translucent - zuletzt, mit Blending, von hinten nach vorn sortiert.
           Nur die Sections mit Translucent-Layer sortieren, nicht die ganze visible-Liste. */
        this.translucentVisible.sort((a, b) -> Double.compare(distanceSq(b, cam), distanceSq(a, cam)));

        GL11.glEnable(GL11.GL_BLEND);
        this.shader.setUniformf("u_AlphaCutoff", 0.001F);
        this.drawLayer(RenderLayer.TRANSLUCENT, this.translucentVisible, cam);
        GL11.glDisable(GL11.GL_BLEND);

        this.shader.unbind();
    }

    private void drawLayer(RenderLayer layer, List<SectionMesh> meshes, Vector3d cam) {
        for (SectionMesh mesh : meshes) {
            if (!mesh.hasLayer(layer)) continue;

            float ox = offsetX(mesh, cam);
            float oy = offsetY(mesh, cam);
            float oz = offsetZ(mesh, cam);

            /* Mesh-Y ist column-lokal (0-511), daher die Section-Höhe aus dem Offset rausrechnen */
            this.shader.setUniformVector3f("u_Offset", ox, oy - (mesh.sectionY << ChunkSection.SHIFT), oz);
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

    private static final String VERTEX_SOURCE = """
            #version 460 core
            layout(location = 0) in vec3 a_position;
            layout(location = 1) in vec3 a_texCoord;   // u, v, layer
            layout(location = 2) in vec3 a_color;      // helligkeit * tint (rgb)

            uniform mat4 u_ProjectionView;
            uniform vec3 u_Offset;

            out vec3 v_texCoord;
            out vec3 v_color;

            void main() {
                v_texCoord = a_texCoord;
                v_color = a_color;
                gl_Position = u_ProjectionView * vec4(a_position + u_Offset, 1.0);
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