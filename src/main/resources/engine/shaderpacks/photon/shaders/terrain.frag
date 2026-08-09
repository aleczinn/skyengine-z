#version 460 core

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

in vec3 v_texCoord;
in vec3 v_color;
in vec2 v_light;
in float v_viewDist;
in vec3 v_viewDirection;
in vec3 v_relativePosition;
in vec3 v_worldPosition;

uniform sampler2DArray u_Textures;
uniform samplerCube u_AtmosphereFog;
uniform float u_AlphaCutoff;
uniform float u_FogStart;
uniform float u_FogEnd;
uniform float u_FogEnabled;
uniform float u_TranslucentPass;
uniform float u_MinLight;
uniform float u_Brightness;
uniform float u_Time;
uniform float u_WaterStillLayer;
uniform float u_WaterFlowLayer;

layout(std140, binding = 1) uniform Environment {
    vec4 u_SunDirection;
    vec4 u_MoonDirection;
    vec4 u_SkyLightColor;
    vec4 u_EnvFogColor;
    vec4 u_SkyTint;
};

out vec4 fragColor;

float lightCurve(float value) {
    return value / (4.0 - 3.0 * value);
}

float wave(vec2 position, vec2 direction, float frequency, float speed,
        float phase) {
    return sin(dot(position, direction) * frequency + u_Time * speed + phase);
}

vec3 waterNormal(vec3 flatNormal, float skylight, bool flowing) {
    vec2 p = v_worldPosition.xz;
    vec2 slope = vec2(0.0);
    slope += vec2( 0.866,  0.500) * wave(p, vec2( 0.866,  0.500), 1.05, 1.25, 0.0);
    slope += vec2(-0.358,  0.934) * wave(p, vec2(-0.358,  0.934), 1.78, 1.62, 2.1) * 0.50;
    slope += vec2(-0.971, -0.239) * wave(p, vec2(-0.971, -0.239), 2.95, 2.08, 4.7) * 0.25;
    float influence = flowing ? 0.025 : mix(0.008, 0.055, skylight);
    vec3 normal = normalize(vec3(-slope.x * influence, 1.0,
            -slope.y * influence));
    return normal * sign(flatNormal.y == 0.0 ? 1.0 : flatNormal.y);
}

void main() {
    vec4 textureColor = texture(u_Textures, v_texCoord);
    if (textureColor.a < u_AlphaCutoff) discard;

    float skyLight = v_light.x * u_SunDirection.w;
    float blockLight = v_light.y;
    float light = lightCurve(clamp(max(skyLight, blockLight), 0.0, 1.0));
    light = u_MinLight + (1.0 - u_MinLight) * light;
    float inv = 1.0 - light;
    float inv2 = inv * inv;
    light = mix(light, 1.0 - inv2 * inv2, u_Brightness);

    float skyDominance = smoothstep(blockLight, blockLight + 0.05, skyLight);
    vec3 lightTint = mix(vec3(1.0), u_SkyLightColor.rgb,
            skyDominance * (1.0 - u_MinLight));
    vec3 lit = srgbToWorking(textureColor.rgb) * clamp(v_color, 0.0, 1.0)
            * light * lightTint;

    bool stillWater = abs(v_texCoord.z - u_WaterStillLayer) < 0.25;
    bool flowingWater = abs(v_texCoord.z - u_WaterFlowLayer) < 0.25;
    bool water = stillWater || flowingWater;
    float outputAlpha = textureColor.a;
    if (water) {
        vec3 flatNormal = normalize(cross(dFdx(v_worldPosition), dFdy(v_worldPosition)));
        vec3 toCamera = normalize(-v_relativePosition);
        if (dot(flatNormal, toCamera) < 0.0) flatNormal = -flatNormal;
        vec3 normal = abs(flatNormal.y) > 0.7
                ? waterNormal(flatNormal, v_light.x, flowingWater) : flatNormal;
        float noV = clamp(abs(dot(normal, toCamera)), 0.0, 1.0);
        float fresnel = 0.02 + 0.98 * pow(1.0 - noV, 5.0);
        vec3 reflectionDirection = reflect(-toCamera, normal);
        vec3 reflection = texture(u_AtmosphereFog, reflectionDirection).rgb;

        const vec3 absorption = vec3(0.39, 0.14, 0.07);
        vec3 transmission = lit * exp(-absorption * 1.35);
        float textureHighlight = pow(clamp(textureColor.r - 0.58, 0.0, 0.42)
                / 0.42, 3.0);
        vec3 highlight = u_SkyLightColor.rgb * textureHighlight * 0.08;
        lit = mix(transmission + highlight, reflection,
                clamp(0.22 + fresnel * 0.72, 0.0, 0.94));
        outputAlpha = clamp(0.38 + fresnel * 0.50 + textureHighlight * 0.12,
                0.0, 0.94);
    }

    float edgeFog = clamp((v_viewDist - u_FogStart) / (u_FogEnd - u_FogStart),
            0.0, 1.0);
    float aerialOnset = smoothstep(u_FogEnd * 0.20, u_FogEnd * 0.65, v_viewDist);
    float aerialFog = (1.0 - exp(-u_EnvFogColor.w * v_viewDist))
            * aerialOnset * u_FogEnabled;
    float fog = 1.0 - (1.0 - edgeFog) * (1.0 - aerialFog);
    vec3 directionalFog = texture(u_AtmosphereFog, normalize(v_viewDirection)).rgb;
    float atmosphereEnergy = dot(directionalFog, vec3(0.2627, 0.6780, 0.0593));
    vec3 fogColor = mix(u_EnvFogColor.rgb, directionalFog,
            smoothstep(0.0001, 0.002, atmosphereEnergy));
    fragColor = vec4(mix(lit, fogColor, fog),
            mix(1.0, outputAlpha, u_TranslucentPass));
}
