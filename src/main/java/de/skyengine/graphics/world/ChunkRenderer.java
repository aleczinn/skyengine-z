package de.skyengine.graphics.world;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ChunkRenderer {

    private final ChunkManager chunkManager;
    private ShaderProgram shader;
    private TextureArray textures;

    /* sectionKey -> mesh, render thread only */
    private final Map<Long, SectionMesh> meshes = new HashMap<>();

    private static final int MAX_UPLOADS_PER_FRAME = 8; // avoid upload spikes

    private int renderedSections = 0;
    private int totalSections = 0;

    public ChunkRenderer(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    /**
     * Render thread, GL context required
     */
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX_SOURCE, ShaderType.VERTEX),
                new Shader(FRAGMENT_SOURCE, ShaderType.FRAGMENT)
        );
        this.textures = new TextureArray(16, new String[]{
                "./src/main/resources/game/texture/block/stone.png",      // layer 0
                "./src/main/resources/game/texture/block/dirt.png",       // layer 1
                "./src/main/resources/game/texture/block/grass_side.png", // layer 2
                "./src/main/resources/game/texture/block/grass_top.png"   // layer 3
        });
    }

    public void render(Camera camera) {
        /* 1. Drain upload queue (bounded per frame) */
        int uploads = 0;
        ChunkManager.MeshResult result;
        while (uploads < MAX_UPLOADS_PER_FRAME && (result = this.chunkManager.getUploadQueue().poll()) != null) {
            long key = sectionKey(result.chunkX(), result.sectionY(), result.chunkZ());

            SectionMesh old = this.meshes.remove(key);
            if (old != null) old.dispose();

            if (result.vertexData() != null) {
                this.meshes.put(key, new SectionMesh(result.chunkX(), result.sectionY(), result.chunkZ(), result.vertexData()));
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

        /* 3. Render with frustum culling, camera-relative */
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformi("u_Textures", 0);
        this.textures.bind(0);

        Vector3d cam = camera.getPosition();
        int size = ChunkSection.SIZE;

        this.totalSections = this.meshes.size();
        this.renderedSections = 0;

        for (SectionMesh mesh : this.meshes.values()) {
            float ox = (float) (((long) mesh.chunkX << ChunkSection.SHIFT) - cam.x);
            float oy = (float) (((long) mesh.sectionY << ChunkSection.SHIFT) - cam.y);
            float oz = (float) (((long) mesh.chunkZ << ChunkSection.SHIFT) - cam.z);

            /* Section AABB in camera-relative space.
               Mesh Y is column-local (0-511), so the AABB Y spans the section but the offset Y skips the section part */
            if (!camera.getFrustum().testAab(ox, oy, oz, ox + size, oy + size, oz + size)) continue;

            this.renderedSections++;

            this.shader.setUniformVector3f("u_Offset", ox, oy - (mesh.sectionY << ChunkSection.SHIFT), oz);
            mesh.render();
        }

        this.shader.unbind();
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void dispose() {
        for (SectionMesh mesh : this.meshes.values()) mesh.dispose();
        this.meshes.clear();
        if (this.shader != null) this.shader.dispose();
        if (this.textures != null) this.textures.dispose();
    }

    private static final String VERTEX_SOURCE = """
            #version 460 core
            layout(location = 0) in vec3 a_position;
            layout(location = 1) in vec3 a_texCoord;   // u, v, layer
            layout(location = 2) in float a_brightness;
            
            uniform mat4 u_ProjectionView;
            uniform vec3 u_Offset;
            
            out vec3 v_texCoord;
            out float v_brightness;
            
            void main() {
                v_texCoord = a_texCoord;
                v_brightness = a_brightness;
                gl_Position = u_ProjectionView * vec4(a_position + u_Offset, 1.0);
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 460 core
            in vec3 v_texCoord;
            in float v_brightness;
            
            uniform sampler2DArray u_Textures;
            
            out vec4 fragColor;
            
            void main() {
                vec4 color = texture(u_Textures, v_texCoord);
                if (color.a < 0.5) discard;
                fragColor = vec4(color.rgb * v_brightness, color.a);
            }
            """;

    public int getRenderedSections() {
        return renderedSections;
    }

    public int getTotalSections() {
        return totalSections;
    }
}