package de.skyengine.graphics.world;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.network.EnergyNetworkManager;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.Texture;
import org.joml.Vector3d;
import org.lwjgl.opengl.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Full-bright, bufferless RF contents; grouped by connection mask for at most 64 draw calls. */
final class EnergyCableFlowRenderer {
    private static final Map<String, float[]> CONTENT_GROUPS = loadContents();
    private final EnergyNetworkManager networks;
    private final Mesh[] meshes = new Mesh[64];
    private ShaderProgram shader;
    private Texture energyTexture;

    EnergyCableFlowRenderer(EnergyNetworkManager networks) { this.networks = networks; }

    void init() {
        this.shader = new ShaderProgram(new Shader(VERTEX, ShaderType.VERTEX), new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.shader.bind(); this.shader.setUniformi("u_Texture", 0); this.shader.unbind();
        this.energyTexture = new Texture(new FileHandle("game/textures/liquid/mekanism/energy.png", FileType.RESOURCE), false);
        for (int mask = 0; mask < 64; mask++) this.meshes[mask] = new Mesh(vertices(mask));
    }

    void render(Camera camera) {
        List<EnergyNetworkManager.FlowCable> cables = this.networks.flowCables();
        if (cables.isEmpty()) return;
        @SuppressWarnings("unchecked") List<EnergyNetworkManager.FlowCable>[] groups = new List[64];
        for (EnergyNetworkManager.FlowCable cable : cables) {
            int mask = cable.connectionMask() & 63;
            if (groups[mask] == null) groups[mask] = new ArrayList<>();
            groups[mask].add(cable);
        }
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformf("u_Pulse", .88F + .12F * (float) Math.sin(System.nanoTime() * 4E-9));
        this.shader.setUniformf("u_Frame", (System.nanoTime() / 100_000_000L) % 32);
        this.energyTexture.bind(0);
        Vector3d cam = camera.getPosition();
        for (int mask = 0; mask < groups.length; mask++) {
            List<EnergyNetworkManager.FlowCable> group = groups[mask];
            if (group == null) continue;
            float[] instances = new float[group.size() * 4];
            int at = 0;
            for (EnergyNetworkManager.FlowCable cable : group) {
                instances[at++] = (float) (cable.pos().x() - cam.x);
                instances[at++] = (float) (cable.pos().y() - cam.y);
                instances[at++] = (float) (cable.pos().z() - cam.z);
                instances[at++] = cable.intensity();
            }
            this.meshes[mask].render(instances, group.size());
        }
        this.shader.unbind();
        GL11.glDepthMask(true);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }

    void dispose() {
        for (Mesh mesh : this.meshes) if (mesh != null) mesh.dispose();
        if (this.energyTexture != null) this.energyTexture.dispose();
        if (this.shader != null) this.shader.dispose();
    }

    private static float[] vertices(int mask) {
        List<Float> out = new ArrayList<>();
        for (Direction direction : Direction.sharedValues()) {
            String group = direction.name().toLowerCase(Locale.ROOT)
                    + ((mask & 1 << direction.ordinal()) != 0 ? "NORMAL" : "NONE");
            float[] data = CONTENT_GROUPS.get(group);
            if (data == null) throw new IllegalStateException("Missing Mekanism contents group " + group);
            for (float value : data) out.add(value);
        }
        float[] data = new float[out.size()];
        for (int i = 0; i < data.length; i++) data[i] = out.get(i);
        return data;
    }

    /** Reads the original Mekanism transmitter_contents.obj without altering its geometry. */
    private static Map<String, float[]> loadContents() {
        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        Map<String, List<Float>> groups = new HashMap<>();
        String group = null;
        for (String raw : new FileHandle("game/models/block/mekanism/transmitter_contents.obj", FileType.RESOURCE).readList()) {
            String line = raw.trim();
            if (line.startsWith("g ")) {
                group = line.substring(2).trim();
                groups.computeIfAbsent(group, ignored -> new ArrayList<>());
            } else if (line.startsWith("v ")) {
                String[] p = line.substring(2).trim().split("\\s+");
                positions.add(new float[]{Float.parseFloat(p[0]) + .5F,
                        Float.parseFloat(p[1]) + .5F, Float.parseFloat(p[2]) + .5F});
            } else if (line.startsWith("vt ")) {
                String[] p = line.substring(3).trim().split("\\s+");
                uvs.add(new float[]{Float.parseFloat(p[0]), 1F - Float.parseFloat(p[1])});
            } else if (line.startsWith("f ") && group != null) {
                String[] refs = line.substring(2).trim().split("\\s+");
                int[] order = refs.length == 3 ? new int[]{0,1,2} : new int[]{0,1,2,0,2,3};
                List<Float> target = groups.get(group);
                for (int corner : order) {
                    String[] indices = refs[corner].split("/");
                    for (float value : positions.get(Integer.parseInt(indices[0]) - 1)) target.add(value);
                    for (float value : uvs.get(Integer.parseInt(indices[1]) - 1)) target.add(value);
                }
            }
        }
        Map<String, float[]> result = new HashMap<>();
        for (Map.Entry<String, List<Float>> entry : groups.entrySet()) {
            float[] data = new float[entry.getValue().size()];
            for (int i = 0; i < data.length; i++) data[i] = entry.getValue().get(i);
            result.put(entry.getKey(), data);
        }
        return Map.copyOf(result);
    }

    private static final class Mesh {
        private final int vao=GL30.glGenVertexArrays(), vertices=GL15.glGenBuffers(), instances=GL15.glGenBuffers();
        private final int count;
        Mesh(float[] data) {
            this.count=data.length/5; GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,this.vertices); GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,5*Float.BYTES,0); GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,5*Float.BYTES,3L*Float.BYTES); GL20.glEnableVertexAttribArray(1);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,this.instances); GL20.glVertexAttribPointer(2,4,GL11.GL_FLOAT,false,4*Float.BYTES,0);
            GL20.glEnableVertexAttribArray(2); GL33.glVertexAttribDivisor(2,1); GL30.glBindVertexArray(0);
        }
        void render(float[] data,int amount){GL30.glBindVertexArray(this.vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,this.instances);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STREAM_DRAW);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,this.count,amount);}
        void dispose(){GL30.glDeleteVertexArrays(this.vao);GL15.glDeleteBuffers(this.vertices);GL15.glDeleteBuffers(this.instances);}
    }

    private static final String VERTEX="""
            #version 330 core
            layout(location=0) in vec3 a_position; layout(location=1) in vec2 a_uv; layout(location=2) in vec4 a_instance;
            uniform mat4 u_ProjectionView; out float v_alpha; out vec2 v_uv;
            void main(){v_alpha=a_instance.w;v_uv=a_uv;gl_Position=u_ProjectionView*vec4(a_position+a_instance.xyz,1);}
            """;
    private static final String FRAGMENT="""
            #version 330 core
            in float v_alpha; in vec2 v_uv; uniform float u_Pulse; uniform float u_Frame;
            uniform sampler2D u_Texture; layout(location=0) out vec4 fragColor;
            void main(){vec4 c=texture(u_Texture,vec2(v_uv.x,(u_Frame+v_uv.y)/32.0));if(c.a<.01)discard;
                fragColor=vec4(c.rgb*u_Pulse,c.a*.78*v_alpha);}
            """;
}
