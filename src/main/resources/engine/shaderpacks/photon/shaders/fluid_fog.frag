#version 460 core

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_Scene;
uniform int u_Fluid;

const mat3 REC709_TO_XYZ = mat3(
    0.4124, 0.3576, 0.1805,
    0.2126, 0.7152, 0.0722,
    0.0193, 0.1192, 0.9505);
const mat3 XYZ_TO_REC2020 = mat3(
     1.7166084, -0.3556621, -0.2533601,
    -0.6666829,  1.6164776,  0.0157685,
     0.0176422, -0.0427763,  0.94222867);

vec3 srgbToWorking(vec3 srgb) {
    vec3 linear = srgb * (srgb * (srgb * 0.305306011 + 0.682171111)
            + 0.012522878);
    return linear * REC709_TO_XYZ * XYZ_TO_REC2020;
}

void main() {
    vec4 scene = texture(u_Scene, v_uv);
    if (u_Fluid == 1) {
        const vec3 absorption = vec3(0.20, 0.08, 0.04);
        const float scattering = 0.03;
        vec3 transmittance = exp(-absorption * 8.0);
        vec3 waterLight = srgbToWorking(vec3(0.18, 0.43, 0.68));
        scene.rgb = scene.rgb * transmittance
                + waterLight * (1.0 - transmittance) * (0.55 + 6.0 * scattering);
    } else {
        vec3 lavaLight = srgbToWorking(vec3(1.0, 0.16, 0.015));
        scene.rgb = scene.rgb * vec3(0.16, 0.035, 0.006) + lavaLight * 1.7;
    }
    fragColor = scene;
}
