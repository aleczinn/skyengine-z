#version 460 core

in vec3 v_texCoord;
flat in vec3 v_color;

uniform sampler2DArray u_Textures;
uniform float u_WaterStillLayer;
uniform float u_WaterFlowLayer;
uniform float u_WaterOnly;
uniform float u_WaterAbsorptionR;
uniform float u_WaterAbsorptionG;
uniform float u_WaterAbsorptionB;
uniform float u_BiomeWaterColor;

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
    /* Photon shadow.fsh samples the base texture at explicit LoD 0.  Letting
       derivatives choose a mip here changes alpha-tested grass/leaf silhouettes
       as the light projection moves and makes close shadows appear/disappear. */
    vec4 texel = textureLod(u_Textures, v_texCoord, 0.0);
    bool water = abs(v_texCoord.z - u_WaterStillLayer) < 0.25
            || abs(v_texCoord.z - u_WaterFlowLayer) < 0.25;
    if (u_WaterOnly > 0.5 ? !water : texel.a < 0.1) discard;
    if (water) {
        const float densityScale = 0.15;
        const vec3 forestAbsorption = -densityScale
                * log(vec3(0.1245, 0.1797, 0.7108));
        vec3 baseAbsorption = vec3(u_WaterAbsorptionR, u_WaterAbsorptionG,
                u_WaterAbsorptionB) * REC709_TO_XYZ * XYZ_TO_REC2020;
        vec3 biomeColor = max(v_color
                * (v_color * (v_color * 0.305306011 + 0.682171111)
                + 0.012522878) * REC709_TO_XYZ * XYZ_TO_REC2020, vec3(1e-6));
        vec3 biomeAbsorption = -densityScale * log(biomeColor) - forestAbsorption;
        vec3 absorption = max(baseAbsorption
                + biomeAbsorption * (0.33 * u_BiomeWaterColor), vec3(0.0));
        vec3 waterColor = clamp(0.25 * exp(-absorption * 5.0),
                vec3(1.0 / 255.0), vec3(1.0));
        shadowColor = vec4(waterColor, 1.0);
    } else {
        vec3 transmitted = mix(vec3(1.0), texel.rgb * v_color, texel.a);
        transmitted = 0.25 * (transmitted
                * (transmitted * (transmitted * 0.305306011 + 0.682171111)
                + 0.012522878)) * REC709_TO_XYZ * XYZ_TO_REC2020;
        transmitted *= float(texel.a <= 1.0 - 1.0 / 255.0);
        shadowColor = vec4(transmitted, 1.0);
    }
}
