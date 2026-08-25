package de.skyengine.graphics.entity;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.Dimension;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;

/** F3+B-Debugdarstellung für Entity-AABBs und ihre Blick-/Fahrtrichtung. */
public final class EntityHitboxRenderer {

    private ShaderProgram shader;
    private int vao, vbo;
    private float[] boxes = new float[24 * 3 * 32];
    private float[] directions = new float[10 * 3 * 32];
    private int boxCount, directionCount;

    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(GEOMETRY, ShaderType.GEOMETRY),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.boxes.length * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
        GlDebug.labelBuffer(this.vbo, "Entity-Hitbox VBO (Streaming)");
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    public void render(Camera camera, EntityPlayer player, Dimension world, float partialTick) {
        this.boxCount = 0;
        this.directionCount = 0;
        this.add(player, camera, partialTick, true);
        world.forEachLoadedEntity(entity -> {
            if (!entity.isRemoved()) this.add(entity, camera, partialTick, false);
        });
        if (this.boxCount == 0) return;

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformVector2f("u_Viewport",
                SkyEngine.get().getWindow().getWidth(), SkyEngine.get().getWindow().getHeight());
        this.shader.setUniformf("u_LineWidth", 2.0F);
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWriteWasEnabled = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);

        this.draw(this.boxes, this.boxCount, 1.0F, 1.0F, 1.0F, 0.85F);
        this.draw(this.directions, this.directionCount, 0.1F, 0.25F, 1.0F, 1.0F);

        GL11.glDepthMask(depthWriteWasEnabled);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (!blendWasEnabled) GL11.glDisable(GL11.GL_BLEND);
        this.shader.unbind();
    }

    private void add(Entity entity, Camera camera, float partialTick, boolean player) {
        Vector3d cam = camera.getPosition();
        double ix = entity.lastX + (entity.x - entity.lastX) * partialTick;
        double iy = entity.lastY + (entity.y - entity.lastY) * partialTick;
        double iz = entity.lastZ + (entity.z - entity.lastZ) * partialTick;
        AABB current = entity.getBoundingBox();
        double dx = ix - entity.x - cam.x;
        double dy = iy - entity.y - cam.y;
        double dz = iz - entity.z - cam.z;
        double epsilon = 0.002;
        this.box(current.minX + dx - epsilon, current.minY + dy - epsilon,
                current.minZ + dz - epsilon, current.maxX + dx + epsilon,
                current.maxY + dy + epsilon, current.maxZ + dz + epsilon);

        double eyeY = player
                ? iy + ((EntityPlayer) entity).getEyeHeight(partialTick)
                : iy + (current.maxY - current.minY) * 0.85;
        double yaw = Math.toRadians(entity.yaw);
        double pitch = Math.toRadians(entity.pitch);
        double cosPitch = Math.cos(pitch);
        double vx = Math.sin(yaw) * cosPitch;
        double vy = -Math.sin(pitch);
        double vz = -Math.cos(yaw) * cosPitch;
        this.arrow(ix - cam.x, eyeY - cam.y, iz - cam.z, vx, vy, vz);
    }

    private void box(double x0, double y0, double z0, double x1, double y1, double z1) {
        this.boxLine(x0,y0,z0,x1,y0,z0); this.boxLine(x1,y0,z0,x1,y0,z1);
        this.boxLine(x1,y0,z1,x0,y0,z1); this.boxLine(x0,y0,z1,x0,y0,z0);
        this.boxLine(x0,y1,z0,x1,y1,z0); this.boxLine(x1,y1,z0,x1,y1,z1);
        this.boxLine(x1,y1,z1,x0,y1,z1); this.boxLine(x0,y1,z1,x0,y1,z0);
        this.boxLine(x0,y0,z0,x0,y1,z0); this.boxLine(x1,y0,z0,x1,y1,z0);
        this.boxLine(x1,y0,z1,x1,y1,z1); this.boxLine(x0,y0,z1,x0,y1,z1);
    }

    private void arrow(double x, double y, double z, double vx, double vy, double vz) {
        double length = 2.0;
        double tx = x + vx * length, ty = y + vy * length, tz = z + vz * length;
        this.directionLine(x, y, z, tx, ty, tz);
        double sx = -vz, sz = vx;
        double sideLength = Math.sqrt(sx * sx + sz * sz);
        if (sideLength < 1.0E-6) { sx = 1; sz = 0; }
        else { sx /= sideLength; sz /= sideLength; }
        double back = 0.28, side = 0.14;
        this.directionLine(tx, ty, tz,
                tx - vx * back + sx * side, ty - vy * back, tz - vz * back + sz * side);
        this.directionLine(tx, ty, tz,
                tx - vx * back - sx * side, ty - vy * back, tz - vz * back - sz * side);
    }

    private void boxLine(double x0,double y0,double z0,double x1,double y1,double z1) {
        if (this.boxCount + 6 > this.boxes.length) this.boxes = Arrays.copyOf(this.boxes, this.boxes.length * 2);
        this.boxCount = line(this.boxes, this.boxCount, x0,y0,z0,x1,y1,z1);
    }

    private void directionLine(double x0,double y0,double z0,double x1,double y1,double z1) {
        if (this.directionCount + 6 > this.directions.length) {
            this.directions = Arrays.copyOf(this.directions, this.directions.length * 2);
        }
        this.directionCount = line(this.directions, this.directionCount, x0,y0,z0,x1,y1,z1);
    }

    private static int line(float[] data, int at,
                            double x0,double y0,double z0,double x1,double y1,double z1) {
        data[at++] = (float) x0; data[at++] = (float) y0; data[at++] = (float) z0;
        data[at++] = (float) x1; data[at++] = (float) y1; data[at++] = (float) z1;
        return at;
    }

    private void draw(float[] data, int count, float r, float g, float b, float a) {
        if (count == 0) return;
        this.shader.setUniformVector4f("u_Color", r, g, b, a);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, Arrays.copyOf(data, count), GL15.GL_DYNAMIC_DRAW);
        GL11.glDrawArrays(GL11.GL_LINES, 0, count / 3);
    }

    public void dispose() {
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        this.shader.dispose();
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        uniform mat4 u_ProjectionView;
        void main() { gl_Position = u_ProjectionView * vec4(a_position, 1.0); }
        """;

    private static final String GEOMETRY = """
        #version 460 core
        layout(lines) in;
        layout(triangle_strip, max_vertices = 4) out;
        uniform vec2 u_Viewport;
        uniform float u_LineWidth;
        void main() {
            vec4 p0 = gl_in[0].gl_Position;
            vec4 p1 = gl_in[1].gl_Position;
            vec2 n0 = p0.xy / p0.w;
            vec2 n1 = p1.xy / p1.w;
            vec2 delta = (n1 - n0) * u_Viewport;
            if (dot(delta, delta) < 0.0001) return;
            vec2 normal = normalize(vec2(-delta.y, delta.x));
            vec2 offset = normal * u_LineWidth / u_Viewport;
            gl_Position = vec4((n0 + offset) * p0.w, p0.z, p0.w); EmitVertex();
            gl_Position = vec4((n0 - offset) * p0.w, p0.z, p0.w); EmitVertex();
            gl_Position = vec4((n1 + offset) * p1.w, p1.z, p1.w); EmitVertex();
            gl_Position = vec4((n1 - offset) * p1.w, p1.z, p1.w); EmitVertex();
            EndPrimitive();
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        uniform vec4 u_Color;
        out vec4 fragColor;
        void main() { fragColor = u_Color; }
        """;
}
