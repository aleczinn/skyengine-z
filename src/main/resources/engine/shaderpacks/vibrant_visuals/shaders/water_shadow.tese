#version 460 core

layout(quads, equal_spacing, ccw) in;

layout(location = 0) in vec3 tc_position[];
layout(location = 1) in vec3 tc_texCoord[];
layout(location = 2) flat in vec3 tc_color[];
layout(location = 3) flat in float tc_visible[];

layout(location = 0) out vec3 v_texCoord;
layout(location = 1) flat out vec3 v_color;

uniform mat4 u_LightProjectionView;

const float SHADOW_DISTORTION = 0.85;
const float SHADOW_DEPTH_SCALE = 0.20;

float quarticLength(vec2 value) {
    vec2 squared = value * value;
    return sqrt(sqrt(dot(squared, squared)));
}

vec3 interpolateQuad(vec3 p0, vec3 p1, vec3 p2, vec3 p3, vec2 uv) {
    return mix(mix(p0, p1, uv.x), mix(p3, p2, uv.x), uv.y);
}

void main() {
    vec2 patchUv = gl_TessCoord.xy;
    vec3 position = interpolateQuad(tc_position[0], tc_position[1],
            tc_position[2], tc_position[3], patchUv);
    v_texCoord = interpolateQuad(tc_texCoord[0], tc_texCoord[1],
            tc_texCoord[2], tc_texCoord[3], patchUv);
    v_color = tc_color[0];

    if (tc_visible[0] < 0.5) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }

    /* Photon verzerrt die bereits projizierte Position. Bei Greedy-Quads muss diese Funktion
       pro Blockraster-Vertex laufen, sonst wird die gekruemmte Projektion ueber bis zu 32
       Bloecke als eine Gerade angenaehert und erzeugt wandernde Flaechen und Mesh-Seams. */
    vec4 clip = u_LightProjectionView * vec4(position, 1.0);
    vec2 projected = clip.xy / clip.w;
    float factor = quarticLength(projected) * SHADOW_DISTORTION
            + (1.0 - SHADOW_DISTORTION);
    clip.xy /= factor;
    clip.z *= SHADOW_DEPTH_SCALE;
    gl_Position = clip;
}
