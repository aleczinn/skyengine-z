#version 460 core

layout(location = 0) in uvec4 a_data;

layout(std430, binding = 0) readonly buffer DrawOffsets {
    vec4 u_DrawOffsets[];
};

uniform mat4 u_LightProjectionView;

void main() {
    vec3 position = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16),
            float(a_data.y & 0xFFFFu)) * u_DrawOffsets[gl_DrawID].w - 1.0;
    position += u_DrawOffsets[gl_DrawID].xyz;
    gl_Position = u_LightProjectionView * vec4(position, 1.0);
}
