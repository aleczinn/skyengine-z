#version 460 core

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_Scene;
uniform sampler2D u_Depth;
uniform sampler2D u_WorldDepth;
uniform sampler2D u_WaterNoise;
uniform sampler2D u_WaterShadow;
uniform mat4 u_InvProjectionView;
uniform mat4 u_WaterLightProjection;
uniform vec2 u_Viewport;
uniform float u_Time;
uniform float u_ZeroToOneDepth;
uniform int u_Fluid;
uniform float u_UnderwaterAbsorptionR;
uniform float u_UnderwaterAbsorptionG;
uniform float u_UnderwaterAbsorptionB;
uniform float u_UnderwaterScattering;
uniform float u_WaterLightShafts;

layout(std140, binding = 1) uniform Environment {
    vec4 u_SunDirection;
    vec4 u_MoonDirection;
    vec4 u_SkyLightColor;
    vec4 u_EnvFogColor;
    vec4 u_SkyTint;
};

const float PI = 3.14159265359;

vec3 reconstructPosition(vec2 uv, float depth) {
    float ndcZ = mix(depth * 2.0 - 1.0, depth, u_ZeroToOneDepth);
    vec4 position = u_InvProjectionView * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    return position.xyz / position.w;
}

float henyeyGreenstein(float cosine, float g) {
    float gg = g * g;
    return (1.0 - gg) / (4.0 * PI * pow(1.0 + gg - 2.0 * g * cosine, 1.5));
}

float caustics(vec3 position) {
    float time = u_Time * 0.25;
    vec2 direction0 = vec2(cos(0.5), sin(0.5));
    vec2 direction1 = vec2(cos(3.0), sin(3.0));
    float value = 0.67 * texture(u_WaterNoise,
            (position.xz + direction0 * time) * 0.02).g;
    value += 0.33 * texture(u_WaterNoise,
            (position.xz + direction1 * time) * 0.04).g;
    return smoothstep(0.40, 0.50, value) + 0.15;
}

float sunlightVisibility(vec3 position) {
    vec4 clip = u_WaterLightProjection * vec4(position, 1.0);
    vec3 projected = clip.xyz / clip.w;
    vec2 uv = projected.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.002))) || any(greaterThan(uv, vec2(0.998)))) return 1.0;
    float result = 0.0;
    vec2 texel = 1.0 / vec2(textureSize(u_WaterShadow, 0));
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            float blocker = texture(u_WaterShadow, uv + vec2(x, y) * texel).r;
            result += projected.z <= blocker + 0.0015 ? 1.0 : 0.0;
        }
    }
    return result / 9.0;
}

vec3 underwaterFog(vec3 scene, vec3 rayDirection, float rayLength) {
    vec3 absorption = vec3(u_UnderwaterAbsorptionR, u_UnderwaterAbsorptionG,
            u_UnderwaterAbsorptionB);
    vec3 scatteringCoefficient = vec3(u_UnderwaterScattering);
    vec3 extinction = max(absorption + scatteringCoefficient, vec3(0.00001));
    vec3 albedo = scatteringCoefficient / extinction;
    vec3 multipleFactor = 0.84 * albedo;
    vec3 multipleEnergy = multipleFactor / (1.0 - multipleFactor);

    int steps = int(clamp(16.0 + rayLength * 0.18, 16.0, 25.0));
    float stepLength = rayLength / float(steps);
    float jitter = texture(u_WaterNoise,
            gl_FragCoord.xy / u_Viewport * vec2(341.0, 197.0)
                    + fract(u_Time) * vec2(0.17, -0.11)).g;
    vec3 position = rayDirection * stepLength * jitter;
    vec3 transmittance = vec3(1.0);
    vec3 scattering = vec3(0.0);
    vec3 stepTransmittance = exp(-extinction * stepLength);
    vec3 sun = normalize(u_SunDirection.xyz);
    float phase = 0.70 * henyeyGreenstein(dot(rayDirection, sun), 0.4)
            + 0.30 / (4.0 * PI);

    for (int i = 0; i < 25; ++i) {
        if (i >= steps) break;
        float visible = mix(1.0, sunlightVisibility(position), u_WaterLightShafts);
        float focus = mix(1.0, caustics(position), u_WaterLightShafts);
        vec3 directLight = u_SkyLightColor.rgb * u_SunDirection.w
                * visible * focus * phase * 8.0;
        vec3 ambientLight = u_EnvFogColor.rgb * 0.26;
        vec3 source = (directLight + ambientLight) * albedo
                * (1.0 + multipleEnergy);
        scattering += transmittance * (1.0 - stepTransmittance) * source;
        transmittance *= stepTransmittance;
        position += rayDirection * stepLength;
    }

    /* Photons volumetrischer Pass schwächt die analytische Extinktion leicht ab, damit
       Texturen im Nahbereich erhalten bleiben. */
    transmittance = pow(transmittance, vec3(0.75));
    return scene * transmittance + scattering;
}

void main() {
    vec4 scene = texture(u_Scene, v_uv);
    if (u_Fluid == 1) {
        /* Der Handpass leert den gemeinsamen Depth Buffer und schreibt nur seine eigenen
           Pixel neu. Für den Rest des Bildes deshalb die vor Wasser/Hand gesicherte
           Welttiefe verwenden; auf Handpixeln bleibt deren korrekte kurze Distanz erhalten. */
        float handDepth = texture(u_Depth, v_uv).r;
        float clearDepth = 1.0 - u_ZeroToOneDepth;
        bool hasHandDepth = abs(handDepth - clearDepth) > 1.0e-6;
        float depth = hasHandDepth ? handDepth : texture(u_WorldDepth, v_uv).r;
        vec3 endPosition = reconstructPosition(v_uv, depth);
        float rayLength = min(length(endPosition), 50.0);
        vec3 rayDirection = normalize(endPosition);
        scene.rgb = underwaterFog(scene.rgb, rayDirection, rayLength);
    } else {
        vec3 lavaLight = vec3(1.20, 0.13, 0.008);
        scene.rgb = scene.rgb * vec3(0.16, 0.035, 0.006) + lavaLight * 1.7;
    }
    fragColor = scene;
}
