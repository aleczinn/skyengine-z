#version 460 core

/* Photon c0_vl + include/fog/overworld/raymarched.glsl. Weather, clouds and
   biome weather classes are absent in SkyEngine, so this is Photon's clear,
   temperate atmosphere path with its original default constants. */
in vec2 v_uv;
layout(location = 0) out vec3 fogTransmittance;
layout(location = 1) out vec3 fogScattering;

uniform sampler2D u_DepthFront;
uniform sampler2D u_WaterNoise;
uniform sampler2D u_ShadowDepthSolid;
uniform mat4 u_InvProjectionView;
uniform mat4 u_ShadowProjectionView;
uniform vec3 u_CameraPosition;
uniform float u_FogEnd;
uniform float u_Frame;
uniform float u_ZeroToOneDepth;
uniform float u_EyeSkylight;

layout(std140, binding = 1) uniform Environment {
    vec4 u_SunDirection;
    vec4 u_MoonDirection;
    vec4 u_SkyLightColor;
    vec4 u_EnvFogColor;
    vec4 u_SkyTint;
};

layout(std430, binding = 3) readonly buffer SkySphericalHarmonics {
    vec4 u_SkySh[10];
};

const float PI = 3.141592653589793;
const float SHADOW_DISTORTION = 0.85;
const float SHADOW_DEPTH_SCALE = 0.20;
const float SEA_LEVEL = 63.0;
const float AIR_FOG_VOLUME_BOTTOM = SEA_LEVEL - 24.0;
const float AIR_FOG_VOLUME_TOP = 320.0;
const vec2 AIR_FOG_FALLOFF_START = vec2(30.0, 7.0) + SEA_LEVEL;
const vec2 AIR_FOG_FALLOFF_HALF_LIFE = vec2(30.0, 7.0);
const mat3 REC709_TO_XYZ = mat3(
    0.4124, 0.3576, 0.1805,
    0.2126, 0.7152, 0.0722,
    0.0193, 0.1192, 0.9505);
const mat3 XYZ_TO_REC2020 = mat3(
     1.7166084, -0.3556621, -0.2533601,
    -0.6666829,  1.6164776,  0.0157685,
     0.0176422, -0.0427763,  0.94222867);

float linearStep(float lower, float upper, float value) {
    return clamp((value - lower) / (upper - lower), 0.0, 1.0);
}

float pulse(float value, float center, float width) {
    float x = abs(value - center) / width;
    return x > 1.0 ? 0.0 : 1.0 - x * x * (3.0 - 2.0 * x);
}

float henyeyGreenstein(float cosine, float g) {
    float gg = g * g;
    return (1.0 - gg) / (4.0 * PI
            * pow(max(1.0 + gg - 2.0 * g * cosine, 1.0e-5), 1.5));
}

vec2 lightSphereIntersection(float mu, float radius, float sphereRadius) {
    float discriminant = radius * radius * (mu * mu - 1.0)
            + sphereRadius * sphereRadius;
    if (discriminant < 0.0) return vec2(-1.0);
    discriminant = sqrt(discriminant);
    return vec2(-radius * mu - discriminant, -radius * mu + discriminant);
}

float chapmanLight(float x, float cosine) {
    float c = sqrt(0.5 * PI * x);
    if (cosine >= 0.0) return c / ((c - 1.0) * cosine + 1.0);
    float sine = sqrt(clamp(1.0 - cosine * cosine, 0.0, 1.0));
    return c / ((c - 1.0) * cosine - 1.0)
            + 2.0 * c * exp(x - x * sine) * sqrt(sine);
}

vec3 lightAtmosphereTransmittance(float mu) {
    const float planetRadius = 6371000.0;
    const vec2 scaleHeights = vec2(8400.0, 1250.0);
    float radius = planetRadius + 10.0;
    if (lightSphereIntersection(mu, radius, planetRadius).x >= 0.0) return vec3(0.0);
    vec2 inverseHeight = 1.0 / scaleHeights;
    vec2 density = exp(radius * -inverseHeight + planetRadius * inverseHeight);
    vec2 airmass = scaleHeights * density;
    airmass.x *= chapmanLight(radius * inverseHeight.x, mu);
    airmass.y *= chapmanLight(radius * inverseHeight.y, mu);
    mat3 rec709To2020 = REC709_TO_XYZ * XYZ_TO_REC2020;
    vec3 rayleigh = vec3(8.059375432e-6, 1.671209429e-5, 4.080133294e-5)
            * rec709To2020;
    vec3 mie = vec3(1.666442358e-6, 1.812685127e-6, 1.958927896e-6)
            * rec709To2020;
    vec3 ozone = vec3(8.304280072e-7, 1.314911970e-6, 5.440679729e-8)
            * rec709To2020;
    return clamp(exp(-(rayleigh * airmass.x + mie * airmass.y
            + ozone * airmass.x)), 0.0, 1.0);
}

float moonPhaseBrightness(float phase) {
    return phase == 0.0 ? 1.0 : phase == 1.0 ? 0.875
            : phase == 2.0 ? 0.75 : phase == 3.0 ? 0.625
            : phase == 4.0 ? 0.5 : phase == 5.0 ? 0.75
            : phase == 6.0 ? 0.875 : 1.0;
}

vec3 photonDirectionalLight() {
    vec3 sun = normalize(u_SunDirection.xyz);
    vec3 moon = normalize(u_MoonDirection.xyz);
    bool night = sun.y < 0.0;
    vec3 direction = night ? moon : sun;
    float meFade = sun.y < 0.18 ? 0.37 + 1.2 * max(0.0, -sun.y) : 1.7;
    float meWeight = pow(clamp(1.0 - meFade * abs(sun.y - 0.18), 0.0, 1.0), 2.0);
    float sunrise = (sun.x > 0.0 ? 1.0 : 0.0) * meWeight;
    float sunset = (sun.x < 0.0 ? 1.0 : 0.0) * meWeight;
    float blueHour = linearStep(0.05, 1.0,
            exp(-190.0 * (sun.y + 0.09604) * (sun.y + 0.09604)));
    float sunExposure = 7.0 * (1.0 + 0.5 * (sunrise + sunset) + 40.0 * blueHour);
    vec3 sunTint = mix(vec3(1.0), vec3(1.05, 0.84, 0.93) * 1.2,
            pow(pulse(sun.y, 0.17, 0.40), 2.0));
    sunTint *= mix(vec3(1.0), vec3(0.95, 0.80, 1.0), blueHour);
    float moonExposure = 0.66 * moonPhaseBrightness(u_MoonDirection.w)
            * (1.0 + 0.33 / clamp(1.25 * max(-sun.y, 0.1), 0.0, 1.0));
    vec3 moonTint = pow(vec3(0.75, 0.83, 1.0), vec3(2.2))
            * REC709_TO_XYZ * XYZ_TO_REC2020;
    vec3 color = vec3(1.051, 0.985, 0.940)
            * lightAtmosphereTransmittance(direction.y);
    float atmosphereBoost = 1.10 + 0.20
            * exp(-150.0 * (sun.y + 0.07283) * (sun.y + 0.07283));
    color = mix(vec3(dot(color, vec3(0.2627, 0.6780, 0.0593))),
            color, atmosphereBoost);
    color *= night ? moonExposure * moonTint : sunExposure * sunTint;
    color *= clamp(direction.y / 0.02, 0.0, 1.0);
    color *= 1.0 - 0.25 * pulse(abs(direction.y), 0.15, 0.11);
    return color;
}

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
    return projected * 0.5 + 0.5;
}

float interleavedDither() {
    ivec2 size = textureSize(u_WaterNoise, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy) % max(size, ivec2(1));
    return fract(texelFetch(u_WaterNoise, texel, 0).b
            + u_Frame * 0.61803398875);
}

vec2 airFogDensity(vec3 worldPosition) {
    const vec2 multiplier = -1.0 / AIR_FOG_FALLOFF_HALF_LIFE;
    const vec2 addition = -multiplier * AIR_FOG_FALLOFF_START;
    vec2 density = exp2(min(worldPosition.y * multiplier + addition, 0.0));
    density *= linearStep(AIR_FOG_VOLUME_BOTTOM, SEA_LEVEL, worldPosition.y);
    return density * 0.5;
}

void main() {
    float depth = texture(u_DepthFront, v_uv).r;
    bool sky = isClearDepth(depth);
    vec3 relativeEnd = reconstructPosition(v_uv, depth);
    vec3 worldDirection = normalize(relativeEnd);
    float rayLength = length(relativeEnd);

    float lowerDistance = (AIR_FOG_VOLUME_BOTTOM - u_CameraPosition.y)
            / worldDirection.y;
    float upperDistance = (AIR_FOG_VOLUME_TOP - u_CameraPosition.y)
            / worldDirection.y;
    float volumeStart;
    float volumeEnd;
    if (u_CameraPosition.y < AIR_FOG_VOLUME_BOTTOM) {
        volumeStart = lowerDistance;
        volumeEnd = worldDirection.y < 0.0 ? -1.0 : upperDistance;
    } else if (u_CameraPosition.y < AIR_FOG_VOLUME_TOP) {
        volumeStart = 0.0;
        volumeEnd = worldDirection.y < 0.0 ? lowerDistance : upperDistance;
    } else {
        volumeStart = upperDistance;
        volumeEnd = worldDirection.y < 0.0 ? upperDistance : -1.0;
    }
    if (volumeEnd < 0.0) {
        fogTransmittance = vec3(1.0);
        fogScattering = vec3(0.0);
        return;
    }
    rayLength = sky ? volumeEnd : rayLength;
    rayLength = clamp(rayLength - volumeStart, 0.0, u_FogEnd);
    if (rayLength <= 1.0e-5) {
        fogTransmittance = vec3(1.0);
        fogScattering = vec3(0.0);
        return;
    }

    int stepCount = min(8 + int(0.1 * rayLength), 25);
    float stepLength = rayLength / float(stepCount);
    vec3 relativeStep = worldDirection * stepLength;
    vec3 relativePosition = worldDirection
            * (volumeStart + stepLength * interleavedDither());

    /* Photon clear-temperate defaults. u_EnvFogColor.w retains the engine biome
       density multiplier while 0.00062 is the old overworld reference value. */
    float biomeDensity = u_EnvFogColor.w / 0.00062;
    vec3 rayleighCoefficient = pow(vec3(0.31, 0.67, 1.0), vec3(2.2))
            * REC709_TO_XYZ * XYZ_TO_REC2020 * (0.0005 * biomeDensity);
    vec3 sun = normalize(u_SunDirection.xyz);
    float meFade = sun.y < 0.18 ? 0.37 + 1.2 * max(0.0, -sun.y) : 1.7;
    float meWeight = pow(clamp(1.0 - meFade * abs(sun.y - 0.18), 0.0, 1.0), 2.0);
    float sunrise = (sun.x > 0.0 ? 1.0 : 0.0) * meWeight;
    float noon = (sun.y > 0.0 ? 1.0 : 0.0) * (1.0 - meWeight);
    float sunset = (sun.x < 0.0 ? 1.0 : 0.0) * meWeight;
    float midnight = (sun.y < 0.0 ? 1.0 : 0.0) * (1.0 - meWeight);
    float blueHour = linearStep(0.05, 1.0,
            exp(-190.0 * (sun.y + 0.07283) * (sun.y + 0.07283)));
    float mie = (0.0070 * sunrise + 0.0001 * noon + 0.0050 * sunset
            + 0.0050 * midnight + 0.0020 * blueHour) * biomeDensity;
    vec3 mieExtinction = vec3(mie);
    vec3 mieScattering = vec3(0.9 * mie);

    vec3 transmittance = vec3(1.0);
    vec3 rayleighSun = vec3(0.0);
    vec3 mieSun = vec3(0.0);
    vec3 rayleighSky = vec3(0.0);
    vec3 mieSky = vec3(0.0);
    for (int stepIndex = 0; stepIndex < 25; ++stepIndex) {
        if (stepIndex >= stepCount) break;
        vec3 shadowPosition = distortedShadowPosition(relativePosition);
        bool inside = all(greaterThanEqual(shadowPosition, vec3(0.0)))
                && all(lessThanEqual(shadowPosition, vec3(1.0)));
        float shadow = inside
                ? step(shadowPosition.z,
                        texture(u_ShadowDepthSolid, shadowPosition.xy).r)
                : 1.0;
        vec2 density = airFogDensity(relativePosition + u_CameraPosition)
                * stepLength;
        vec3 opticalDepth = rayleighCoefficient * density.x
                + mieExtinction * density.y;
        vec3 stepTransmittance = exp(-opticalDepth);
        vec3 transmittedFraction = (1.0 - stepTransmittance)
                / max(opticalDepth, vec3(1.0e-6));
        vec3 visibleScattering = transmittedFraction * transmittance;
        rayleighSun += visibleScattering * density.x * shadow;
        mieSun += visibleScattering * density.y * shadow;
        rayleighSky += visibleScattering * density.x;
        mieSky += visibleScattering * density.y;
        transmittance *= stepTransmittance;
        relativePosition += relativeStep;
    }
    rayleighSun *= rayleighCoefficient;
    mieSun *= mieScattering;
    rayleighSky *= rayleighCoefficient;
    mieSky *= mieScattering;
    if (!sky) {
        rayleighSky *= u_EyeSkylight;
        mieSky *= u_EyeSkylight;
    }

    vec3 lightDirection = normalize(sun.y >= 0.0
            ? u_SunDirection.xyz : u_MoonDirection.xyz);
    float lightDotView = dot(worldDirection, lightDirection);
    vec3 scattering = 2.0 * (rayleighSky + mieSky)
            * (1.0 / (4.0 * PI)) * u_SkySh[9].rgb;
    float scatterAmount = 1.0;
    float anisotropy = 1.0;
    for (int bounce = 0; bounce < 4; ++bounce) {
        float miePhase = 0.7 * henyeyGreenstein(lightDotView, 0.5 * anisotropy)
                + 0.3 * henyeyGreenstein(lightDotView, -0.2 * anisotropy);
        scattering += scatterAmount * (rayleighSun * (1.0 / (4.0 * PI))
                + mieSun * miePhase) * photonDirectionalLight();
        scatterAmount *= 0.5;
        anisotropy *= 0.7;
    }
    float eveningGlow = 0.75 * linearStep(0.05, 1.0,
            exp(-300.0 * (sun.y + 0.02) * (sun.y + 0.02)));
    scattering += scattering * eveningGlow;

    fogTransmittance = max(transmittance, vec3(0.0));
    fogScattering = max(scattering, vec3(0.0));
}
