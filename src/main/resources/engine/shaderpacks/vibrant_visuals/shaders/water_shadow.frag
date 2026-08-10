#version 460 core

in vec3 v_texCoord;

uniform sampler2DArray u_Textures;
uniform float u_WaterStillLayer;
uniform float u_WaterFlowLayer;
uniform float u_WaterOnly;
uniform float u_WaterAbsorptionR;
uniform float u_WaterAbsorptionG;
uniform float u_WaterAbsorptionB;

layout(location = 0) out vec4 shadowColor;

const mat3 REC709_TO_XYZ = mat3(
    0.4124, 0.3576, 0.1805,
    0.2126, 0.7152, 0.0722,
    0.0193, 0.1192, 0.9505);
const mat3 XYZ_TO_REC2020 = mat3(
     1.7166084, -0.3556621, -0.2533601,
    -0.6666829,  1.6164776,  0.0157685,
     0.0176422, -0.0427763,  0.94222867);

void main() {
    vec4 texel = texture(u_Textures, v_texCoord);
    bool water = abs(v_texCoord.z - u_WaterStillLayer) < 0.25
            || abs(v_texCoord.z - u_WaterFlowLayer) < 0.25;
    if (u_WaterOnly > 0.5 ? !water : texel.a < 0.5) discard;
    vec3 absorption = vec3(u_WaterAbsorptionR, u_WaterAbsorptionG,
            u_WaterAbsorptionB) * REC709_TO_XYZ * XYZ_TO_REC2020;
    vec3 waterColor = clamp(0.25 * exp(-absorption * 5.0),
            vec3(1.0 / 255.0), vec3(1.0));
    shadowColor = water ? vec4(waterColor, 1.0) : vec4(0.0);
}
