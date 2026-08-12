#version 460 core

layout(location = 0) in uvec4 a_data;

layout(std430, binding = 0) readonly buffer DrawOffsets {
    vec4 u_DrawOffsets[];
};

uniform vec2 u_DetailFade;
uniform vec2 u_DetailCamSnap;

layout(location = 0) out vec3 vs_position;
layout(location = 1) out vec3 vs_texCoord;
layout(location = 2) flat out vec3 vs_color;
layout(location = 3) flat out float vs_visible;

void main() {
    vec4 drawOffset = u_DrawOffsets[gl_DrawID];
    vec3 position = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16),
            float(a_data.y & 0xFFFFu)) * drawOffset.w - 1.0;
    position += drawOffset.xyz;

    vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu))
            * (1.0 / 1024.0) - 1.0;
    vs_position = position;
    vs_texCoord = vec3(uv, float(a_data.z >> 16));
    vs_color = vec3(float(a_data.w & 0xFFu), float((a_data.w >> 8) & 0xFFu),
            float((a_data.w >> 16) & 0xFFu)) * (1.0 / 255.0);
    vs_visible = 1.0;

    if (u_DetailFade.y > 0.0) {
        float sectionDistance = length(drawOffset.xz + vec2(16.0) + u_DetailCamSnap);
        float density = 1.0 - clamp((sectionDistance - u_DetailFade.x)
                * u_DetailFade.y, 0.0, 1.0);
        if (float((a_data.w >> 24) & 0xFFu) > density * 255.0) {
            vs_visible = 0.0;
        }
    }

    /* Die eigentliche Lichtprojektion geschieht erst nach der Unterteilung im TE-Shader. */
    gl_Position = vec4(position, 1.0);
}
