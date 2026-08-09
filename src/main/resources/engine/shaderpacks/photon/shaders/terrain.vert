#version 460 core

layout(location = 0) in uvec4 a_data;
layout(location = 1) in uint a_light;

layout(std430, binding = 0) readonly buffer DrawOffsets {
    vec4 u_DrawOffsets[];
};

uniform mat4 u_ProjectionView;
uniform vec2 u_DetailFade;
uniform vec2 u_DetailCamSnap;
uniform vec3 u_CameraPosition;

out vec3 v_texCoord;
out vec3 v_color;
out vec2 v_light;
out float v_viewDist;
out vec3 v_viewDirection;
out vec3 v_relativePosition;
out vec3 v_worldPosition;

void main() {
    vec3 pos = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16),
            float(a_data.y & 0xFFFFu)) * u_DrawOffsets[gl_DrawID].w - 1.0;
    vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu))
            * (1.0 / 1024.0) - 1.0;
    float layer = float(a_data.z >> 16);
    vec3 color = vec3(float(a_data.w & 0xFFu), float((a_data.w >> 8) & 0xFFu),
            float((a_data.w >> 16) & 0xFFu)) * (1.0 / 255.0);

    v_texCoord = vec3(uv, layer);
    v_color = color;
    v_light = vec2(float(a_light & 0xFu), float((a_light >> 4) & 0xFu))
            * (1.0 / 15.0);
    if (gl_BaseInstance != 0) {
        v_color = mix(v_color, vec3(1.0, 0.1, 0.1), 0.7);
    }

    vec3 relative = pos + u_DrawOffsets[gl_DrawID].xyz;
    v_relativePosition = relative;
    v_worldPosition = relative + u_CameraPosition;
    v_viewDist = length(relative.xz);
    v_viewDirection = normalize(relative);

    if (u_DetailFade.y > 0.0) {
        float sectionDist = length(u_DrawOffsets[gl_DrawID].xz + vec2(16.0)
                + u_DetailCamSnap);
        float density = 1.0 - clamp((sectionDist - u_DetailFade.x)
                * u_DetailFade.y, 0.0, 1.0);
        if (float((a_data.w >> 24) & 0xFFu) > density * 255.0) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            return;
        }
    }

    gl_Position = u_ProjectionView * vec4(relative, 1.0);
}
