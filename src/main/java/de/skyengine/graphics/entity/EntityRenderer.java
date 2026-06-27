package de.skyengine.graphics.entity;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zeichnet die Welt-Entities ({@link FallingBlockEntity}, {@link ItemEntity}) im Welt-Pass nach dem
 * Chunk-Mesh. Wiederverwendet die bereits gebackenen Block-Quads ({@code BlockState.getModel()}) und
 * das Block-{@link TextureArray}; gerendert wird kamerarelativ (Offset = Weltpos − Kamerapos, wie
 * {@code ChestRenderer}), damit es zur kamerarelativen Chunk-Darstellung passt.
 *
 * <p>Erbt den globalen Welt-GL-State (Reversed-Z, Depth-Test, Back-Face-Culling) und schaltet ihn
 * NICHT um; Alpha-Cutout via {@code discard} im Shader.
 */
public final class EntityRenderer {

    private static final int FLOATS_PER_VERTEX = 7;   // pos3 + texCoord3(u,v,layer) + brightness1
    private static final float ITEM_SCALE = 0.25f;

    private ShaderProgram shader;
    private TextureArray textures;

    /** Würfel-Mesh je Block-State-ID (Wert null = kein/leeres Modell -> nicht zeichnen). */
    private final Map<Short, Mesh> cache = new HashMap<>();

    private final Matrix4f model = new Matrix4f();

    public void init(TextureArray textures) {
        this.textures = textures;
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
    }

    public void render(List<Entity> entities, Camera camera, float partialTick) {
        if (entities.isEmpty()) return;

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformi("u_Textures", 0);
        this.textures.bind(0);

        Vector3d cam = camera.getPosition();
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e.isRemoved()) continue;

            float ox = (float) (e.lastX + (e.x - e.lastX) * partialTick - cam.x);
            float oy = (float) (e.lastY + (e.y - e.lastY) * partialTick - cam.y);
            float oz = (float) (e.lastZ + (e.z - e.lastZ) * partialTick - cam.z);

            if (e instanceof FallingBlockEntity fb) {
                Mesh mesh = this.meshFor(fb.getBlockId());
                if (mesh == null) continue;
                /* Voller Würfel: Modell liegt in 0..1, Entity-x/z sind Zentrum, y der Fußpunkt. */
                this.model.translation(ox - 0.5f, oy, oz - 0.5f);
                this.shader.setUniformMatrix4f("u_Model", this.model);
                mesh.render();
            } else if (e instanceof ItemEntity item) {
                int id = blockStateId(item.getStack());
                if (id < 0) continue;
                Mesh mesh = this.meshFor((short) id);
                if (mesh == null) continue;
                /* Kleiner, um Y rotierender und sanft wippender Würfel über dem Boden. */
                float a = item.getAge() + partialTick;
                float bob = (float) Math.sin(a * 0.1f) * 0.05f + 0.1f;
                this.model.identity()
                        .translate(ox, oy + bob, oz)
                        .rotateY(a * 0.1f)
                        .scale(ITEM_SCALE)
                        .translate(-0.5f, -0.5f, -0.5f);
                this.shader.setUniformMatrix4f("u_Model", this.model);
                mesh.render();
            }
        }
        this.shader.unbind();
    }

    /** Block-State-ID für ein (Block-)Item, oder -1 wenn das Item keinen Würfel hat. */
    private static int blockStateId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return -1;
        return bi.getBlock().getDefaultState().getId();
    }

    /** Liefert das gecachte Würfel-Mesh (lazy gebacken) oder null bei leerem Modell. */
    private Mesh meshFor(short stateId) {
        if (this.cache.containsKey(stateId)) return this.cache.get(stateId);
        Mesh mesh = build(stateId);
        this.cache.put(stateId, mesh);
        return mesh;
    }

    /** Backt die Quads des States in ein interleaved Mesh [x,y,z,u,v,layer,brightness]. */
    private static Mesh build(short stateId) {
        BakedQuad[] quads = Blocks.getState(stateId).getModel();
        if (quads == null || quads.length == 0) return null;

        int verts = 0;
        for (BakedQuad q : quads) verts += q.vertices().length / 5;
        if (verts == 0) return null;

        float[] data = new float[verts * FLOATS_PER_VERTEX];
        int p = 0;
        for (BakedQuad q : quads) {
            float[] v = q.vertices();
            int n = v.length / 5;
            for (int i = 0; i < n; i++) {
                data[p++] = v[i * 5];
                data[p++] = v[i * 5 + 1];
                data[p++] = v[i * 5 + 2];
                data[p++] = v[i * 5 + 3];
                data[p++] = v[i * 5 + 4];
                data[p++] = q.textureLayer();
                data[p++] = q.brightness();
            }
        }
        return new Mesh(data);
    }

    public void dispose() {
        for (Mesh m : this.cache.values()) if (m != null) m.dispose();
        this.cache.clear();
        if (this.shader != null) this.shader.dispose();
    }

    /* --- kleine VAO/VBO-Hülle (identisches Layout wie ItemIconRenderer) --- */
    private static final class Mesh {
        private final int vao, vbo, count;

        Mesh(float[] data) {
            this.count = data.length / FLOATS_PER_VERTEX;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL30.glBindVertexArray(0);
        }

        void render() {
            GL30.glBindVertexArray(this.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.count);
        }

        void dispose() {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.vbo);
        }
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_texCoord;
        layout(location = 2) in float a_brightness;
        uniform mat4 u_ProjectionView;
        uniform mat4 u_Model;
        out vec3 v_texCoord;
        out float v_brightness;
        void main() {
            v_texCoord = a_texCoord;
            v_brightness = a_brightness;
            gl_Position = u_ProjectionView * u_Model * vec4(a_position, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_texCoord;
        in float v_brightness;
        uniform sampler2DArray u_Textures;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Textures, v_texCoord);
            if (c.a < 0.5) discard;
            fragColor = vec4(c.rgb * v_brightness, c.a);
        }
        """;
}
