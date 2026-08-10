#version 460 core

layout(location = 0) in uvec4 a_data;

layout(std430, binding = 0) readonly buffer DrawOffsets {
    vec4 u_DrawOffsets[];
};

uniform mat4 u_LightProjectionView;

out vec3 v_texCoord;

const float SHADOW_DISTORTION = 0.85;
const float SHADOW_DEPTH_SCALE = 0.20;

float quarticLength(vec2 value) {
    vec2 squared = value * value;
    return sqrt(sqrt(dot(squared, squared)));
}

void main() {
    vec3 position = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16),
            float(a_data.y & 0xFFFFu)) * u_DrawOffsets[gl_DrawID].w - 1.0;
    vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu))
            * (1.0 / 1024.0) - 1.0;
    v_texCoord = vec3(uv, float(a_data.z >> 16));
    position += u_DrawOffsets[gl_DrawID].xyz;
    vec4 clip = u_LightProjectionView * vec4(position, 1.0);
    vec2 projected = clip.xy / clip.w;
    float factor = quarticLength(projected) * SHADOW_DISTORTION
            + (1.0 - SHADOW_DISTORTION);
    clip.xy /= factor;
    clip.z *= SHADOW_DEPTH_SCALE;
    gl_Position = clip;
}
