package de.skyengine.graphics.blockentity;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.EnergyCubeBlockEntity;
import de.skyengine.game.world.block.entity.EnergySideMode;
import de.skyengine.game.world.block.entity.RelativeSide;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Translucent RF core and colored side indicators; dispatcher supplies frustum and light culling. */
public final class EnergyCubeRenderer implements BlockEntityRenderer {
    private ShaderProgram shader;
    private int vao, vbo, modelLocation, colorLocation, lightLocation, projectionLocation;
    private final Matrix4f model = new Matrix4f();

    @Override public void init() {
        this.shader = new ShaderProgram(new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.projectionLocation = this.shader.getUniformLocation("u_ProjectionView");
        this.modelLocation = this.shader.getUniformLocation("u_Model");
        this.colorLocation = this.shader.getUniformLocation("u_Color");
        this.lightLocation = this.shader.getUniformLocation("u_Light");
        float[] vertices = cubeVertices();
        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    @Override public void render(BlockEntity be, Camera camera, float partialTick, float light) {
        EnergyCubeBlockEntity cube = (EnergyCubeBlockEntity) be;
        Vector3d cam = camera.getPosition();
        float ox = (float) (cube.getPos().x() - cam.x);
        float oy = (float) (cube.getPos().y() - cam.y);
        float oz = (float) (cube.getPos().z() - cam.z);

        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        this.shader.bind();
        this.shader.setUniformMatrix4f(this.projectionLocation, camera.getProjectionViewMatrix());
        this.shader.setUniformf(this.lightLocation, Math.max(.35F, light));
        GL30.glBindVertexArray(this.vao);

        float fill = cube.getEnergy() / (float) cube.getCapacity();
        if (fill > 0) {
            float height = .18F + .54F * fill;
            draw(ox + .25F, oy + .14F, oz + .25F, .50F, height, .50F,
                    .08F, .72F, 1F, .40F + .35F * fill);
        }
        for (Direction direction : Direction.sharedValues()) {
            RelativeSide relative = RelativeSide.fromWorld(cube.getFacing(), direction);
            EnergySideMode mode = cube.getSideMode(relative);
            float r = mode == EnergySideMode.OUTPUT ? 1F : mode == EnergySideMode.DISABLED ? .25F : .10F;
            float g = mode == EnergySideMode.INPUT ? .72F : mode == EnergySideMode.DISABLED ? .25F : .28F;
            float b = mode == EnergySideMode.INPUT ? 1F : mode == EnergySideMode.DISABLED ? .25F : .08F;
            drawIndicator(direction, ox, oy, oz, r, g, b);
        }

        GL30.glBindVertexArray(0);
        this.shader.unbind();
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }

    private void drawIndicator(Direction direction, float x, float y, float z, float r, float g, float b) {
        switch (direction) {
            case NORTH -> draw(x + .40F, y + .40F, z + .002F, .20F, .20F, .018F, r, g, b, .9F);
            case SOUTH -> draw(x + .40F, y + .40F, z + .98F, .20F, .20F, .018F, r, g, b, .9F);
            case WEST -> draw(x + .002F, y + .40F, z + .40F, .018F, .20F, .20F, r, g, b, .9F);
            case EAST -> draw(x + .98F, y + .40F, z + .40F, .018F, .20F, .20F, r, g, b, .9F);
            case DOWN -> draw(x + .40F, y + .002F, z + .40F, .20F, .018F, .20F, r, g, b, .9F);
            case UP -> draw(x + .40F, y + .98F, z + .40F, .20F, .018F, .20F, r, g, b, .9F);
        }
    }

    private void draw(float x, float y, float z, float sx, float sy, float sz,
                      float r, float g, float b, float a) {
        this.model.translation(x, y, z).scale(sx, sy, sz);
        this.shader.setUniformMatrix4f(this.modelLocation, this.model);
        this.shader.setUniformVector4f(this.colorLocation, r, g, b, a);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
    }

    @Override public void dispose() {
        if (this.vao != 0) GL30.glDeleteVertexArrays(this.vao);
        if (this.vbo != 0) GL15.glDeleteBuffers(this.vbo);
        if (this.shader != null) this.shader.dispose();
    }

    private static float[] cubeVertices() {
        return new float[] {
                0,0,0, 1,0,0, 1,1,0, 0,0,0, 1,1,0, 0,1,0,
                1,0,1, 0,0,1, 0,1,1, 1,0,1, 0,1,1, 1,1,1,
                0,0,1, 0,0,0, 0,1,0, 0,0,1, 0,1,0, 0,1,1,
                1,0,0, 1,0,1, 1,1,1, 1,0,0, 1,1,1, 1,1,0,
                0,1,0, 1,1,0, 1,1,1, 0,1,0, 1,1,1, 0,1,1,
                0,0,1, 1,0,1, 1,0,0, 0,0,1, 1,0,0, 0,0,0
        };
    }

    private static final String VERTEX = """
            #version 330 core
            layout(location=0) in vec3 a_Position;
            uniform mat4 u_ProjectionView;
            uniform mat4 u_Model;
            void main(){ gl_Position = u_ProjectionView * u_Model * vec4(a_Position, 1.0); }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            out vec4 fragColor;
            uniform vec4 u_Color;
            uniform float u_Light;
            void main(){ fragColor = vec4(u_Color.rgb * u_Light, u_Color.a); }
            """;
}
