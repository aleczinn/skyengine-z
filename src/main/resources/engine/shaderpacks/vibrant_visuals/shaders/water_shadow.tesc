#version 460 core

layout(vertices = 4) out;

layout(location = 0) in vec3 vs_position[];
layout(location = 1) in vec3 vs_texCoord[];
layout(location = 2) flat in vec3 vs_color[];
layout(location = 3) flat in float vs_visible[];

layout(location = 0) out vec3 tc_position[];
layout(location = 1) out vec3 tc_texCoord[];
layout(location = 2) flat out vec3 tc_color[];
layout(location = 3) flat out float tc_visible[];

float blockEdgeLevel(vec3 a, vec3 b) {
    vec3 extent = abs(b - a);
    /* Quad-Kanten sind achsenparallel oder die Diagonalen einer Blockpflanze. max() liefert
       fuer beide die Blocklaenge, ohne diagonale Pflanzen unnoetig hoch zu tessellieren. */
    float blocks = max(extent.x, max(extent.y, extent.z));
    return clamp(ceil(blocks - 1e-4), 1.0, 32.0);
}

void main() {
    tc_position[gl_InvocationID] = vs_position[gl_InvocationID];
    tc_texCoord[gl_InvocationID] = vs_texCoord[gl_InvocationID];
    tc_color[gl_InvocationID] = vs_color[gl_InvocationID];
    tc_visible[gl_InvocationID] = vs_visible[gl_InvocationID];
    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;

    if (gl_InvocationID == 0) {
        /* ChunkMesher speichert jedes Quad in umlaufender Reihenfolge 0-1-2-3. */
        float edge01 = blockEdgeLevel(vs_position[0], vs_position[1]);
        float edge12 = blockEdgeLevel(vs_position[1], vs_position[2]);
        float edge23 = blockEdgeLevel(vs_position[2], vs_position[3]);
        float edge30 = blockEdgeLevel(vs_position[3], vs_position[0]);

        gl_TessLevelOuter[0] = edge30;
        gl_TessLevelOuter[1] = edge01;
        gl_TessLevelOuter[2] = edge12;
        gl_TessLevelOuter[3] = edge23;
        gl_TessLevelInner[0] = max(edge01, edge23);
        gl_TessLevelInner[1] = max(edge30, edge12);
    }
}
