#version 460 core

in vec2 v_uv;
layout(location = 0) out vec3 fogTransmittance;
layout(location = 1) out vec3 fogScattering;

uniform sampler2D u_DepthFront;
uniform sampler2D u_DepthBack;
uniform sampler2D u_WaterNoise;
uniform sampler2D u_ShadowDepthAll;
uniform sampler2D u_ShadowDepthSolid;
uniform mat4 u_InvProjectionView;
uniform mat4 u_ShadowProjectionView;
uniform mat4 u_ShadowView;
uniform vec3 u_CameraPosition;
uniform float u_ShadowDepthRange;
uniform float u_Time;
uniform float u_Frame;
uniform float u_ZeroToOneDepth;
uniform float u_EyeSkylight;
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
const float SHADOW_DISTORTION = 0.85;
const float SHADOW_DEPTH_SCALE = 0.20;
const mat3 REC709_TO_XYZ = mat3(
    0.4124, 0.3576, 0.1805,
    0.2126, 0.7152, 0.0722,
    0.0193, 0.1192, 0.9505);
const mat3 XYZ_TO_REC2020 = mat3(
     1.7166084, -0.3556621, -0.2533601,
    -0.6666829,  1.6164776,  0.0157685,
     0.0176422, -0.0427763,  0.94222867);

vec3 reconstructPosition(vec2 uv, float depth) {
    float ndcZ = mix(depth * 2.0 - 1.0, depth, u_ZeroToOneDepth);
    vec4 position = u_InvProjectionView * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    return position.xyz / position.w;
}

bool isClearDepth(float depth) {
    return u_ZeroToOneDepth > 0.5 ? depth <= 1.0e-6 : depth >= 1.0 - 1.0e-6;
}

float quarticLength(vec2 value) {
    vec2 squared = value * value;
    return sqrt(sqrt(dot(squared, squared)));
}

vec3 distortedShadowPosition(vec3 position) {
    vec4 clip = u_ShadowProjectionView * vec4(position, 1.0);
    vec3 projected = clip.xyz / clip.w;
    float factor = quarticLength(projected.xy) * SHADOW_DISTORTION
            + (1.0 - SHADOW_DISTORTION);
    projected.xy /= factor;
    projected.z *= SHADOW_DEPTH_SCALE;
    return projected;
}

float henyeyGreenstein(float cosine, float g) {
    float gg = g * g;
    return (1.0 - gg) / (4.0 * PI
            * pow(max(1.0 + gg - 2.0 * g * cosine, 1.0e-5), 1.5));
}

float caustics(vec2 lightPosition) {
    const vec2 direction0 = vec2(cos(0.5), sin(0.5));
    const vec2 direction1 = vec2(cos(3.0), sin(3.0));
    float t = u_Time * 0.25;
    float value = 0.67 * texture(u_WaterNoise,
            (lightPosition + direction0 * t) * 0.02).g;
    value += 0.33 * texture(u_WaterNoise,
            (lightPosition + direction1 * t) * 0.04).g;
    return smoothstep(0.40, 0.50, value) + 0.15;
}

float interleavedDither() {
    ivec2 size = textureSize(u_WaterNoise, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy) % max(size, ivec2(1));
    float noise = texelFetch(u_WaterNoise, texel, 0).b;
    return fract(noise + u_Frame * 0.61803398875);
}

void main() {
    float frontDepth = texture(u_DepthFront, v_uv).r;
    float backDepth = texture(u_DepthBack, v_uv).r;
    float depth = frontDepth;
    if (isClearDepth(depth)) depth = backDepth;

    vec3 endPosition = isClearDepth(depth)
            ? normalize(reconstructPosition(v_uv, u_ZeroToOneDepth > 0.5 ? 0.0 : 1.0)) * 50.0
            : reconstructPosition(v_uv, depth);
    float rayLength = min(length(endPosition), 50.0);
    vec3 rayDirection = endPosition / max(length(endPosition), 1.0e-5);

    int stepCount = min(16 + int(0.5 * rayLength), 25);
    float stepLength = rayLength / float(max(stepCount, 1));
    vec3 worldStep = rayDirection * stepLength;
    vec3 worldPosition = worldStep * interleavedDither();

    vec3 absorption = vec3(u_UnderwaterAbsorptionR, u_UnderwaterAbsorptionG,
            u_UnderwaterAbsorptionB) * REC709_TO_XYZ * XYZ_TO_REC2020;
    vec3 scatteringCoefficient = vec3(u_UnderwaterScattering);
    vec3 extinction = max(absorption + scatteringCoefficient, vec3(1.0e-5));
    vec3 stepTransmittance = exp(-extinction * stepLength);
    vec3 transmittance = vec3(1.0);
    vec3 scattering = vec3(0.0);
    vec3 lightDirection = normalize(u_SunDirection.y >= 0.0
            ? u_SunDirection.xyz : u_MoonDirection.xyz);
    float lightDotView = dot(rayDirection, lightDirection);

    for (int stepIndex = 0; stepIndex < 25; ++stepIndex) {
        if (stepIndex >= stepCount) break;

        vec3 shadowClip = distortedShadowPosition(worldPosition);
        vec3 shadowScreen = shadowClip * 0.5 + 0.5;
        bool inside = all(greaterThanEqual(shadowScreen, vec3(0.0)))
                && all(lessThanEqual(shadowScreen, vec3(1.0)));
        float depthAll = texture(u_ShadowDepthAll, shadowScreen.xy).r;
        float depthSolid = texture(u_ShadowDepthSolid, shadowScreen.xy).r;
        float shadow = inside && shadowScreen.z <= depthSolid + 0.00035 ? 1.0 : 0.0;
        float distanceTraveled = abs(depthAll - shadowScreen.z)
                * u_ShadowDepthRange / SHADOW_DEPTH_SCALE;
        float distanceToSky = min(distanceTraveled * max(lightDirection.y, 0.0),
                15.0 - 15.0 * u_EyeSkylight + max(-worldPosition.y, 0.0));
        vec3 lightTransmittance = exp(-extinction * distanceTraveled) * shadow;
        vec3 skyTransmittance = exp(-extinction * distanceToSky);
        vec2 causticsPosition = (mat3(u_ShadowView)
                * mod(worldPosition + u_CameraPosition, 512.0)).xy;
        float focusedLight = caustics(causticsPosition);

        float anisotropy = 1.0;
        float scatteringAmount = 1.0;
        for (int bounce = 0; bounce < 4; ++bounce) {
            float phase = 0.7 * henyeyGreenstein(lightDotView,
                    0.5 * anisotropy) + 0.3 / (4.0 * PI);
            vec3 direct = u_SkyLightColor.rgb * u_SunDirection.w * focusedLight
                    * phase * lightTransmittance * u_WaterLightShafts;
            vec3 ambient = u_EnvFogColor.rgb * (1.0 / (4.0 * PI))
                    * skyTransmittance;
            scattering += (direct + ambient) * transmittance * scatteringAmount;
            anisotropy *= 0.5;
            scatteringAmount *= 0.5;
            lightTransmittance = sqrt(lightTransmittance);
            skyTransmittance = sqrt(skyTransmittance);
        }

        transmittance *= stepTransmittance;
        worldPosition += worldStep;
    }

    scattering *= (1.0 - stepTransmittance) * scatteringCoefficient / extinction;
    fogTransmittance = pow(transmittance, vec3(0.75));
    fogScattering = scattering;
}
