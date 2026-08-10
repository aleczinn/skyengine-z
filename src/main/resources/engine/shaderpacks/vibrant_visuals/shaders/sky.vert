#version 460 core
uniform mat4 u_InvProjectionView;
uniform float u_FarDepth;
out vec3 v_RayDirection;

void main() {
    vec2 ndc = vec2((gl_VertexID << 1 & 2) * 2 - 1, (gl_VertexID & 2) * 2 - 1);
    vec4 world = u_InvProjectionView * vec4(ndc, u_FarDepth, 1.0);
    v_RayDirection = world.xyz / max(abs(world.w), 1e-6);
    gl_Position = vec4(ndc, u_FarDepth, 1.0);
}
