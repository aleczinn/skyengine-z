#version 460 core

const mat3 REC709_TO_XYZ = mat3(
    0.4124, 0.3576, 0.1805,
    0.2126, 0.7152, 0.0722,
    0.0193, 0.1192, 0.9505);
const mat3 XYZ_TO_REC2020 = mat3(
     1.7166084, -0.3556621, -0.2533601,
    -0.6666829,  1.6164776,  0.0157685,
     0.0176422, -0.0427763,  0.94222867);
const float TAU = 6.28318530718;
const float GOLDEN_ANGLE = 2.39996322973;

vec3 srgbToWorking(vec3 srgb) {
    vec3 linear = srgb * (srgb * (srgb * 0.305306011 + 0.682171111)
            + 0.012522878);
    return linear * REC709_TO_XYZ * XYZ_TO_REC2020;
}

float dielectricFresnel(float cosine, float indexOfRefraction) {
    float gSquared = indexOfRefraction * indexOfRefraction + cosine * cosine - 1.0;
    if (gSquared <= 0.0) return 1.0;
    float g = sqrt(gSquared);
    float a = g - cosine;
    float b = g + cosine;
    float c = (b * cosine - 1.0) / max(a * cosine + 1.0, 1.0e-5);
    return 0.5 * (a * a) / max(b * b, 1.0e-5) * (1.0 + c * c);
}

float photonNoHSquared(float noL, float noV, float loV, float lightRadius) {
    float radiusCos = cos(lightRadius);
    float radiusTan = tan(lightRadius);
    float roL = 2.0 * noL * noV - loV;
    if (roL >= radiusCos) return 1.0;

    float rOverLengthT = radiusCos * radiusTan
            * inversesqrt(max(1.0 - roL * roL, 1.0e-8));
    float notR = rOverLengthT * (noV - roL * noL);
    float votR = rOverLengthT * (2.0 * noV * noV - 1.0 - roL * loV);
    float triple = sqrt(clamp(1.0 - noL * noL - noV * noV - loV * loV
            + 2.0 * noL * noV * loV, 0.0, 1.0));

    float noBr = rOverLengthT * triple;
    float voBr = rOverLengthT * (2.0 * triple * noV);
    float noLvtr = noL * radiusCos + noV + notR;
    float loVvtr = loV * radiusCos + 1.0 + votR;
    float p = noBr * loVvtr;
    float q = noLvtr * loVvtr;
    float s = voBr * noLvtr;
    float xNum = q * (-0.5 * p + 0.25 * voBr * noLvtr);
    float xDenom = p * p + s * (s - 2.0 * p)
            + noLvtr * ((noL * radiusCos + noV) * loVvtr * loVvtr
            + q * (-0.5 * (loVvtr + loV * radiusCos) - 0.5));
    float twoX1 = 2.0 * xNum / max(xDenom * xDenom + xNum * xNum, 1.0e-8);
    float sinTheta = twoX1 * xDenom;
    float cosTheta = 1.0 - twoX1 * xNum;
    notR = cosTheta * notR + sinTheta * noBr;
    votR = cosTheta * votR + sinTheta * voBr;

    float newNoL = noL * radiusCos + notR;
    float newLoV = loV * radiusCos + votR;
    float noH = noV + newNoL;
    float hoH = 2.0 * newLoV + 2.0;
    return clamp(noH * noH / max(hoH, 1.0e-8), 0.0, 1.0);
}

float photonDistributionGgx(float noHSquared, float alphaSquared) {
    float denominator = 1.0 - noHSquared + noHSquared * alphaSquared;
    return alphaSquared / (3.14159265359 * denominator * denominator);
}

float photonV2SmithGgx(float noL, float noV, float alphaSquared) {
    float ggxL = noV * sqrt((-noL * alphaSquared + noL) * noL
            + alphaSquared);
    float ggxV = noL * sqrt((-noV * alphaSquared + noV) * noV
            + alphaSquared);
    return 0.5 / max(ggxL + ggxV, 1.0e-8);
}

vec3 photonWaterSpecular(float noL, float noV, float loV, float loH,
        float roughness, float sunElevation, float moonPhase) {
    if (noL <= 1.0e-6) return vec3(0.0);
    bool night = sunElevation < 0.0;
    if (night && moonPhase == 4.0) return vec3(0.0);

    float lightRadius = radians(night ? 3.0 : 2.0);
    float f0 = 0.02;
    float sqrtF0 = sqrt(f0) * 0.99999;
    float refractiveIndex = (1.0 + sqrtF0) / (1.0 - sqrtF0);
    vec3 fresnel = vec3(dielectricFresnel(loH, refractiveIndex));
    float noHSquared = photonNoHSquared(noL, noV, loV, lightRadius);
    float alphaSquared = roughness * roughness;
    float distribution = photonDistributionGgx(noHSquared, alphaSquared);
    float visibility = photonV2SmithGgx(max(noL, 1.0e-2),
            max(noV, 1.0e-2), alphaSquared);
    return min(vec3(noL * distribution * visibility) * fresnel, vec3(4.0));
}

float henyeyGreenstein(float cosine, float g) {
    float gg = g * g;
    return (1.0 - gg) / (4.0 * 3.14159265359
            * pow(max(1.0 + gg - 2.0 * g * cosine, 1.0e-5), 1.5));
}

float dampen(float value) {
    value = clamp(value, 0.0, 1.0);
    return value * (2.0 - value);
}

float linearStep(float edge0, float edge1, float value) {
    return clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
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
uniform sampler2D u_WaterNoise;
uniform sampler2D u_OpaqueColor;
uniform sampler2D u_OpaqueDepth;
uniform samplerCube u_SkyReflection;
uniform sampler2D u_ShadowDepthSolid;
uniform sampler2D u_ShadowDepthAll;
uniform sampler2D u_ShadowColor;
uniform mat4 u_ProjectionView;
uniform mat4 u_InvProjectionView;
uniform mat4 u_ShadowProjectionView;
uniform vec2 u_Viewport;
uniform vec3 u_CameraPosition;
uniform float u_AlphaCutoff;
uniform float u_FogStart;
uniform float u_FogEnd;
uniform float u_FogEnabled;
uniform float u_TranslucentPass;
uniform float u_MinLight;
uniform float u_Brightness;
uniform float u_Time;
uniform float u_Frame;
uniform float u_WaterStillLayer;
uniform float u_WaterFlowLayer;
uniform float u_ZeroToOneDepth;
uniform float u_WaterWaves;
uniform float u_WaterWaveIterations;
uniform float u_WaterWaveStrength;
uniform float u_WaterWaveFrequency;
uniform float u_WaterWaveSpeedStill;
uniform float u_WaterWaveSpeedFlowing;
uniform float u_WaterWavePersistence;
uniform float u_WaterWaveLacunarity;
uniform float u_WaterHeightVariation;
uniform float u_WaterParallax;
uniform float u_WaterDisplacement;
uniform float u_WaterTexture;
uniform float u_BiomeWaterColor;
uniform float u_WaterRefraction;
uniform float u_WaterRefractionIntensity;
uniform float u_WaterEdgeHighlight;
uniform float u_WaterEdgeHighlightIntensity;
uniform float u_WaterCaustics;
uniform float u_WaterCausticsIntensity;
uniform float u_SnellsWindow;
uniform float u_CameraUnderwater;
uniform float u_EnvironmentReflections;
uniform float u_SkyReflections;
uniform float u_SsrRayCount;
uniform float u_SsrSteps;
uniform float u_WaterAbsorptionR;
uniform float u_WaterAbsorptionG;
uniform float u_WaterAbsorptionB;
uniform float u_WaterScattering;
uniform float u_Shadows;
uniform float u_ShadowPcf;
uniform float u_ShadowColored;
uniform float u_ShadowVps;
uniform float u_ShadowPenumbraScale;
uniform float u_ShadowPcfStepsMin;
uniform float u_ShadowPcfStepsMax;
uniform float u_ShadowPcfStepsScale;
uniform float u_ShadowBlockerSearchRadius;

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

out vec4 fragColor;

float lightCurve(float value) {
    return value / (4.0 - 3.0 * value);
}

float liftSignal(float value, float amount) {
    return (value + value * amount) / (1.0 + value * amount);
}

float pulse(float value, float center, float width) {
    float x = abs(value - center) / width;
    if (x > 1.0) return 0.0;
    return 1.0 - x * x * (3.0 - 2.0 * x);
}

vec2 lightSphereIntersection(float mu, float radius, float sphereRadius) {
    float discriminant = radius * radius * (mu * mu - 1.0)
            + sphereRadius * sphereRadius;
    if (discriminant < 0.0) return vec2(-1.0);
    discriminant = sqrt(discriminant);
    return vec2(-radius * mu - discriminant, -radius * mu + discriminant);
}

float chapmanLight(float x, float cosine) {
    float c = sqrt(0.5 * 3.14159265359 * x);
    if (cosine >= 0.0) return c / ((c - 1.0) * cosine + 1.0);
    float sine = sqrt(clamp(1.0 - cosine * cosine, 0.0, 1.0));
    return c / ((c - 1.0) * cosine - 1.0)
            + 2.0 * c * exp(x - x * sine) * sqrt(sine);
}

vec3 lightAtmosphereTransmittance(float mu) {
    const float planetRadius = 6371000.0;
    const vec2 scaleHeights = vec2(8400.0, 1250.0);
    float radius = planetRadius + 10.0;
    if (lightSphereIntersection(mu, radius, planetRadius).x >= 0.0) {
        return vec3(0.0);
    }
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
    float blueHour = clamp((exp(-190.0 * (sun.y + 0.09604)
            * (sun.y + 0.09604)) - 0.05) / 0.95, 0.0, 1.0);

    float sunExposure = 7.0 * (1.0 + 0.5 * (sunrise + sunset)
            + 40.0 * blueHour);
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

void shCoefficients(vec3 direction, out float coefficients[9]) {
    float x = direction.x;
    float y = direction.y;
    float z = direction.z;
    coefficients[0] = 0.2820947918;
    coefficients[1] = 0.4886025119 * x;
    coefficients[2] = 0.4886025119 * z;
    coefficients[3] = 0.4886025119 * y;
    coefficients[4] = 1.0925484310 * x * y;
    coefficients[5] = 1.0925484310 * y * z;
    coefficients[6] = 0.3153915653 * (3.0 * z * z - 1.0);
    coefficients[7] = 0.7725484040 * x * z;
    coefficients[8] = 0.3862742020 * (x * x - y * y);
}

vec3 photonSkyIrradiance(vec3 normal) {
    float coefficients[9];
    shCoefficients(normal, coefficients);
    const vec3 kernel = vec3(
            sqrt(3.14159265359) * 0.5,
            sqrt(3.0 * 3.14159265359) / 3.0,
            sqrt(5.0 * 3.14159265359) * 0.125);
    const vec3 normalization = sqrt(4.0 * 3.14159265359
            / vec3(1.0, 3.0, 5.0));
    vec3 multiplier = normalization * kernel;
    vec3 value = u_SkySh[0].rgb * coefficients[0] * multiplier.x;
    for (int band = 1; band < 4; ++band) {
        value += u_SkySh[band].rgb * coefficients[band] * multiplier.y;
    }
    for (int band = 4; band < 9; ++band) {
        value += u_SkySh[band].rgb * coefficients[band] * multiplier.z;
    }
    return max(value, vec3(0.0));
}

vec3 photonAmbientLight() {
    return u_SkySh[9].rgb;
}

vec3 photonAmbientLight(vec3 normal, float skylight) {
    return mix(u_SkySh[9].rgb, photonSkyIrradiance(normal),
            skylight * skylight);
}

vec3 photonBlockLight(float blocklight, float skylight) {
    float falloff = pow(blocklight, 8.0) + 0.18 * blocklight * blocklight
            + 0.16 * dampen(blocklight);
    vec3 sun = normalize(u_SunDirection.xyz);
    float meFade = sun.y < 0.18 ? 0.37 + 1.2 * max(0.0, -sun.y) : 1.7;
    float meWeight = pow(clamp(1.0 - meFade * abs(sun.y - 0.18), 0.0, 1.0), 2.0);
    float timeNoon = (sun.y > 0.0 ? 1.0 : 0.0) * (1.0 - meWeight);
    falloff *= 1.0 - 0.2 * timeNoon * skylight - 0.2 * skylight;
    falloff += min(2.7 * pow(blocklight, 12.0), 0.9);
    falloff *= smoothstep(0.0, 0.125, blocklight);
    vec3 tint = pow(vec3(1.0, 0.75, 0.63), vec3(2.2))
            * REC709_TO_XYZ * XYZ_TO_REC2020;
    return 6.0 * falloff * tint;
}

float quarticShadowLength(vec2 value) {
    vec2 squared = value * value;
    return sqrt(sqrt(dot(squared, squared)));
}

vec3 shadowClipPosition(vec3 relativePosition) {
    vec4 clip = u_ShadowProjectionView * vec4(relativePosition, 1.0);
    return clip.xyz / clip.w;
}

float shadowDistortion(vec2 clipPosition) {
    return quarticShadowLength(clipPosition) * 0.85 + 0.15;
}

vec3 shadowScreenPosition(vec3 clipPosition) {
    clipPosition.xy /= shadowDistortion(clipPosition.xy);
    clipPosition.z *= 0.20;
    return clipPosition * 0.5 + 0.5;
}

vec2 vogelDisc(int index, int count, float rotation) {
    float radius = sqrt((float(index) + 0.5) / float(count));
    float angle = float(index) * GOLDEN_ANGLE + rotation;
    return vec2(cos(angle), sin(angle)) * radius;
}

vec2 distortedShadowUv(vec2 clipPosition) {
    return clipPosition / shadowDistortion(clipPosition) * 0.5 + 0.5;
}

float shadowDither() {
    ivec2 size = textureSize(u_WaterNoise, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy) % max(size, ivec2(1));
    float seed = texelFetch(u_WaterNoise, texel, 0).b;
    return fract(seed + u_Frame * (1.0 / 1.6180339887));
}

/* Photon liest shadowtex1 als hardwaregefilterten sampler2DShadow. Da dieselbe native
   Depth-Textur in anderen Pack-Paessen auch ungefiltert gebraucht wird, bilden wir den
   bilinearen LEQUAL-Vergleich hier explizit nach. Ein einzelner NEAREST-Vergleich pro
   Vogel-Sample war die Ursache der sichtbaren Ringe und des starken Pixelkriselns. */
float shadowCompareLinear(vec2 uv, float referenceDepth) {
    ivec2 size = textureSize(u_ShadowDepthSolid, 0);
    vec2 texelPosition = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(texelPosition));
    vec2 weight = fract(texelPosition);
    ivec2 maximum = size - 1;
    float s00 = float(referenceDepth <= texelFetch(u_ShadowDepthSolid,
            clamp(base, ivec2(0), maximum), 0).r);
    float s10 = float(referenceDepth <= texelFetch(u_ShadowDepthSolid,
            clamp(base + ivec2(1, 0), ivec2(0), maximum), 0).r);
    float s01 = float(referenceDepth <= texelFetch(u_ShadowDepthSolid,
            clamp(base + ivec2(0, 1), ivec2(0), maximum), 0).r);
    float s11 = float(referenceDepth <= texelFetch(u_ShadowDepthSolid,
            clamp(base + ivec2(1, 1), ivec2(0), maximum), 0).r);
    return mix(mix(s00, s10, weight.x), mix(s01, s11, weight.x), weight.y);
}

vec3 photonShadow(vec3 relativePosition, vec3 normal, float skylight,
        float sssAmount, out float sssDepth) {
    sssDepth = 0.0;
    float distantShadow = smoothstep(13.5 / 15.0, 14.5 / 15.0, skylight);
    if (u_Shadows < 0.5) return vec3(distantShadow);

    vec3 lightDirection = normalize(u_SunDirection.y >= 0.0
            ? u_SunDirection.xyz : u_MoonDirection.xyz);
    float noL = dot(normal, lightDirection);
    vec3 bias = 0.25 * normal
            * clamp(0.12 + 0.01 * length(relativePosition), 0.0, 1.0)
            * (2.0 - clamp(noL, 0.0, 1.0));
    // Complementary Reimagined light-leak prevention, exactly as used by Photon.
    vec3 edgeFactor = 0.1 - 0.2
            * fract(relativePosition + u_CameraPosition + normal * 0.01);
    edgeFactor -= edgeFactor * skylight;
    vec3 clip = shadowClipPosition(relativePosition + bias + edgeFactor);
    vec3 screen = shadowScreenPosition(clip);
    float edge = max(max(abs(screen.x * 2.0 - 1.0), abs(screen.y * 2.0 - 1.0)),
            dot(relativePosition, relativePosition) / (128.0 * 128.0));
    float distanceFade = linearStep(0.1, 1.0, pow(edge, 32.0));
    if (any(lessThan(screen, vec3(0.0))) || any(greaterThan(screen, vec3(1.0)))) {
        return vec3(distantShadow);
    }
    if (distanceFade >= 1.0) return vec3(distantShadow);

    float shadowPixel = 1.0 / float(textureSize(u_ShadowDepthSolid, 0).x);
    float dither = shadowDither();
    float penumbra;
    if (u_ShadowVps > 0.5 && u_ShadowPcf > 0.5) {
        int blockerSteps = sssAmount > 1.0e-6 ? 12 : 3;
        float searchRadius = (u_ShadowBlockerSearchRadius / 128.0)
                * (0.5 + 0.5 * linearStep(0.2, 0.4, lightDirection.y));
        // Photons blocker search uses the unbiased scene position.
        vec3 blockerClip = shadowClipPosition(relativePosition);
        float blockerReference = blockerClip.z * (0.20 * 0.5) + 0.5;
        float depthSum = 0.0;
        float weightSum = 0.0;
        float depthSumSss = 0.0;
        for (int index = 0; index < 12; ++index) {
            if (index >= blockerSteps) break;
            vec2 sampleClip = blockerClip.xy
                    + vogelDisc(index, blockerSteps, dither * TAU) * searchRadius;
            vec2 uv = distortedShadowUv(sampleClip);
            ivec2 size = textureSize(u_ShadowDepthAll, 0);
            ivec2 texel = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
            float depth = texelFetch(u_ShadowDepthAll, texel, 0).r;
            float weight = depth <= blockerReference ? 1.0 : 0.0;
            depthSum += depth * weight;
            weightSum += weight;
            depthSumSss += max(blockerReference - depth, 0.0);
        }
        /* -shadowProjectionInverse[2].z for the 0.1..256 Photon shadow
           orthographic projection. SHADOW_DEPTH_SCALE is 0.2. */
        sssDepth = 127.95 * depthSumSss / (0.20 * float(blockerSteps));
        if (noL < 1.0e-3) {
            return mix(vec3(0.0), vec3(distantShadow), distanceFade);
        }
        if (weightSum == 0.0) {
            return mix(vec3(1.0), vec3(distantShadow), distanceFade);
        }
        float blockerDepth = depthSum / weightSum;
        penumbra = 16.0 * u_ShadowPenumbraScale * (screen.z - blockerDepth)
                / max(blockerDepth, 1.0e-5) / 128.0;
        penumbra = min(max(penumbra, 0.0),
                u_ShadowBlockerSearchRadius / 128.0);
    } else {
        penumbra = sqrt(0.5) * shadowPixel * u_ShadowPenumbraScale;
    }

    if (u_ShadowPcf < 0.5) {
        float visibility = shadowCompareLinear(screen.xy, screen.z);
        ivec2 size = textureSize(u_ShadowDepthAll, 0);
        ivec2 texel = clamp(ivec2(screen.xy * vec2(size)), ivec2(1), size - 2);
        float depthAll = texelFetch(u_ShadowDepthAll, texel, 0).r;
        vec3 color = texelFetch(u_ShadowColor, texel, 0).rgb * 4.0;
        float coloredWeight = float(depthAll <= screen.z);
        vec3 averageColor = vec3(0.0);
        for (int y = -1; y <= 1; ++y) {
            for (int x = -1; x <= 1; ++x) {
                averageColor += texelFetch(u_ShadowColor,
                        texel + ivec2(x, y), 0).rgb;
            }
        }
        coloredWeight *= float(max(averageColor.r,
                max(averageColor.g, averageColor.b)) > 1.0e-6);
        color = mix(vec3(1.0), color,
                u_ShadowColored * coloredWeight);
        return mix(visibility * color, vec3(distantShadow), distanceFade);
    }

    // Photon omits the Complementary light-leak offset for translucent shadow
    // color; applying it there makes water caustics and colored seams swim.
    vec3 translucentClip = shadowClipPosition(relativePosition + bias);
    vec3 translucentScreen = shadowScreenPosition(translucentClip);

    float distortion = shadowDistortion(clip.xy);
    float minFilterRadius = 2.0 * shadowPixel * distortion;
    float filterRadius = max(penumbra, minFilterRadius);
    float filterScale = filterRadius / minFilterRadius;
    int minimumSamples = min(int(u_ShadowPcfStepsMin), int(u_ShadowPcfStepsMax));
    int maximumSamples = max(int(u_ShadowPcfStepsMin), int(u_ShadowPcfStepsMax));
    int samples = clamp(int(float(minimumSamples)
            + u_ShadowPcfStepsScale * filterScale * filterScale),
            minimumSamples, maximumSamples);
    float visibility = 0.0;
    vec3 colorSum = vec3(0.0);
    // Photon filters shadow color only in the first four taps and can then reject fully
    // illuminated or fully occluded pixels before the remaining PCF work.
    for (int index = 0; index < 4; ++index) {
        vec2 sampleClip = clip.xy
                + vogelDisc(index, samples, dither * TAU) * filterRadius;
        vec2 uv = distortedShadowUv(sampleClip);
        visibility += shadowCompareLinear(uv, screen.z);
        vec2 translucentUv = distortedShadowUv(translucentClip.xy
                + vogelDisc(index, samples, dither * TAU) * filterRadius);
        ivec2 size = textureSize(u_ShadowDepthAll, 0);
        ivec2 texel = clamp(ivec2(translucentUv * vec2(size)), ivec2(0), size - 1);
        float allDepth = texelFetch(u_ShadowDepthAll, texel, 0).r;
        vec3 color = texelFetch(u_ShadowColor, texel, 0).rgb * 4.0;
        colorSum += mix(vec3(1.0), color, u_ShadowColored
                * float(allDepth <= translucentScreen.z));
    }
    vec3 color = colorSum * 0.25;
    if (visibility > 4.0 - 1.0e-6) {
        return mix(color, vec3(distantShadow), distanceFade);
    }
    if (visibility < 1.0e-6) {
        return mix(vec3(0.0), vec3(distantShadow), distanceFade);
    }
    for (int index = 4; index < 32; ++index) {
        if (index >= samples) break;
        vec2 sampleClip = clip.xy
                + vogelDisc(index, samples, dither * TAU) * filterRadius;
        visibility += shadowCompareLinear(distortedShadowUv(sampleClip), screen.z);
    }
    visibility /= float(samples);
    float sharpen = 0.4 * max((minFilterRadius - penumbra) / minFilterRadius, 0.0);
    visibility = linearStep(sharpen, 1.0 - sharpen, visibility);

    return mix(visibility * color, vec3(distantShadow), distanceFade);
}

float gerstnerWave(vec2 coord, vec2 direction, float time, float noiseValue,
        float wavelength) {
    float k = TAU / wavelength;
    float w = sqrt(9.8 * k);
    float x = w * time - k * (dot(direction, coord) + noiseValue);
    float s = sin(x) * 0.5 + 0.5;
    return s * s;
}

float caustics(vec3 position) {
    float time = u_Time * 0.25;
    vec2 direction0 = normalize(vec2(cos(0.5), sin(0.5)));
    vec2 direction1 = normalize(vec2(cos(3.0), sin(3.0)));
    float value = 0.67 * texture(u_WaterNoise,
            (position.xz + direction0 * time) * 0.02).g;
    value += 0.33 * texture(u_WaterNoise,
            (position.xz + direction1 * time) * 0.04).g;
    return smoothstep(0.40, 0.50, value) + 0.15;
}

float waterHeight(vec2 coord, bool flowing) {
    float time = (flowing ? 0.7 * u_WaterWaveSpeedFlowing
            : 0.5 * u_WaterWaveSpeedStill) * u_Time;
    vec2 direction = flowing ? vec2(0.0, 1.0)
            : vec2(cos(0.5235987756), sin(0.5235987756));
    mat2 rotation = flowing ? mat2(1.0) : mat2(
            cos(GOLDEN_ANGLE), sin(GOLDEN_ANGLE),
            -sin(GOLDEN_ANGLE), cos(GOLDEN_ANGLE));
    vec2 noiseCoord = (coord + vec2(0.0, 0.25 * time)) * 0.007;
    float height = 0.0;
    float persistence = 0.5 * u_WaterWavePersistence;
    float amplitude = 1.0;
    float frequency = 0.7 * u_WaterWaveFrequency;
    float wavelength = 1.0;
    int iterations = int(u_WaterWaveIterations + 0.5);
    for (int i = 0; i < 8; ++i) {
        if (i >= iterations) break;
        float noiseValue = texture(u_WaterNoise, noiseCoord).g;
        height += gerstnerWave(coord * frequency, direction, time,
                noiseValue * 2.0, wavelength) * amplitude;
        amplitude *= persistence;
        frequency *= 1.7 * u_WaterWaveLacunarity;
        wavelength *= 1.5;
        noiseCoord *= 2.5;
        direction = rotation * direction;
    }
    float variation = texture(u_WaterNoise,
            (coord + vec2(0.0, 0.1 * time)) * 0.001).g;
    if (u_WaterHeightVariation > 0.5) height *= max(0.4, variation * 2.0 - 0.5);
    float normalization = (1.0 - persistence)
            / max(1.0 - pow(persistence, float(iterations)), 0.001);
    return height * normalization;
}

vec2 waterParallaxCoord(vec2 coord, vec3 toCamera, bool flowing) {
    if (u_WaterParallax < 0.5 || u_WaterDisplacement < 0.5) return coord;
    vec2 rayStep = toCamera.xz / max(abs(toCamera.y), 0.08) * 0.05;
    float marched = 0.0;
    float height = waterHeight(coord, flowing);
    vec2 previous = coord;
    for (int i = 0; i < 4; ++i) {
        if (marched >= height) break;
        previous = coord;
        coord += rayStep;
        marched += 0.25;
        height = waterHeight(coord, flowing);
    }
    return mix(previous, coord, clamp(height - marched + 0.25, 0.0, 1.0));
}

vec3 photonWaterNormal(vec3 flatNormal, bool flowing, vec2 coord) {
    if (u_WaterWaves < 0.5) return flatNormal;
    const float h = 0.1;
    float wave0 = waterHeight(coord, flowing);
    float wave1 = waterHeight(coord + vec2(h, 0.0), flowing);
    float wave2 = waterHeight(coord + vec2(0.0, h), flowing);
    float influence = flowing ? 0.1 : mix(0.01, 0.04, v_light.x);
    influence *= u_WaterWaveStrength * smoothstep(0.0, 0.15,
            abs(dot(flatNormal, normalize(v_relativePosition))));
    vec3 tangentNormal = normalize(vec3(
            (wave1 - wave0) * influence,
            (wave2 - wave0) * influence,
            h));
    if (abs(flatNormal.y) < 0.7) return flatNormal;
    return normalize(vec3(tangentNormal.x, tangentNormal.z, tangentNormal.y))
            * sign(flatNormal.y);
}

vec3 reconstructPosition(vec2 uv, float depth) {
    float ndcZ = mix(depth * 2.0 - 1.0, depth, u_ZeroToOneDepth);
    vec4 position = u_InvProjectionView * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    return position.xyz / position.w;
}

bool projectPosition(vec3 position, out vec2 uv) {
    vec4 clip = u_ProjectionView * vec4(position, 1.0);
    if (clip.w <= 0.0) return false;
    uv = clip.xy / clip.w * 0.5 + 0.5;
    return all(greaterThan(uv, vec2(0.002))) && all(lessThan(uv, vec2(0.998)));
}

vec3 screenSpaceReflection(vec3 origin, vec3 direction, vec3 fallback,
        out float hitConfidence) {
    float previousT = 0.25;
    float t = previousT;
    vec2 hitUv = vec2(0.0);
    bool hit = false;
    for (int i = 0; i < 32; ++i) {
        if (i >= int(u_SsrSteps + 0.5)) break;
        t += 0.30 + float(i) * 0.22;
        vec3 rayPosition = origin + direction * t;
        vec2 uv;
        if (!projectPosition(rayPosition, uv)) break;
        float depth = texture(u_OpaqueDepth, uv).r;
        vec3 scenePosition = reconstructPosition(uv, depth);
        float delta = length(rayPosition) - length(scenePosition);
        if (delta > 0.0 && delta < 2.5 + t * 0.025) {
            hit = true;
            hitUv = uv;
            break;
        }
        previousT = t;
    }
    if (!hit) {
        hitConfidence = 0.0;
        return fallback;
    }
    for (int i = 0; i < 4; ++i) {
        float middle = (previousT + t) * 0.5;
        vec2 uv;
        if (!projectPosition(origin + direction * middle, uv)) break;
        vec3 scenePosition = reconstructPosition(uv, texture(u_OpaqueDepth, uv).r);
        if (length(origin + direction * middle) > length(scenePosition)) {
            t = middle;
            hitUv = uv;
        } else {
            previousT = middle;
        }
    }
    vec2 edge = smoothstep(vec2(0.0), vec2(0.08), hitUv)
            * smoothstep(vec2(0.0), vec2(0.08), vec2(1.0) - hitUv);
    hitConfidence = edge.x * edge.y;
    return texture(u_OpaqueColor, hitUv).rgb;
}

/* Photon gbuffers_water writes a premultiplied translucent layer: RGB contains
   surface/scattering energy and A contains the transmission lost to absorption. */
vec4 waterAbsorptionLayer(float distanceThroughWater,
        float lightDotView, float skylight, float blocklight) {
    vec3 absorption = vec3(u_WaterAbsorptionR, u_WaterAbsorptionG,
            u_WaterAbsorptionB) * REC709_TO_XYZ * XYZ_TO_REC2020;
    if (u_BiomeWaterColor > 0.5) {
        const float densityScale = 0.15;
        vec3 biomeColor = max(srgbToWorking(clamp(v_color, 0.0, 1.0)), vec3(0.0001));
        vec3 forestAbsorption = -densityScale * log(vec3(0.1245, 0.1797, 0.7108));
        vec3 biomeAbsorption = -densityScale * log(biomeColor) - forestAbsorption;
        absorption = max(absorption + biomeAbsorption * 0.33, vec3(0.0));
    }
    vec3 scatteringCoefficient = vec3(u_WaterScattering);
    vec3 extinction = max(absorption + scatteringCoefficient, vec3(0.00001));
    float skylightFactor = skylight * skylight * skylight;
    float distanceAdjusted = max(distanceThroughWater, 2.0 - skylightFactor);
    vec3 transmittance = exp(-extinction * distanceAdjusted);
    vec3 albedo = scatteringCoefficient / extinction;
    vec3 multiple = 0.84 * albedo;
    vec3 multipleEnergy = multiple / (1.0 - multiple);
    float phase = 0.7 * henyeyGreenstein(lightDotView, 0.4)
            + 0.3 * (1.0 / (4.0 * 3.14159265359));
    vec3 direct = photonDirectionalLight()
            * smoothstep(0.0, 0.25, skylight) * phase;
    vec3 blockTint = pow(vec3(1.0, 0.75, 0.63), vec3(2.2))
            * REC709_TO_XYZ * XYZ_TO_REC2020;
    vec3 ambient = photonAmbientLight() * skylight;
    ambient += 1.41 * 6.0 * blockTint * blocklight * blocklight;
    ambient *= 1.0 / (4.0 * 3.14159265359);
    vec3 scattering = (direct + ambient) * (1.0 - transmittance)
            * scatteringCoefficient / extinction * (1.0 + multipleEnergy);
    float brightnessControl = 1.0 - exp(-0.33 * distanceThroughWater);
    brightnessControl = (1.0 - skylight) + brightnessControl * skylight;
    vec3 layerScattering = scattering
            * (1.0 + 6.0 * dot(transmittance, transmittance) / 3.0)
            * brightnessControl;
    return vec4(layerScattering, 1.0 - transmittance.r);
}

void main() {
    vec4 textureColor = texture(u_Textures, v_texCoord);
    if (textureColor.a < u_AlphaCutoff) discard;

    float skyLight = v_light.x;
    float blockLight = v_light.y;
    vec3 albedo = srgbToWorking(textureColor.rgb * clamp(v_color, 0.0, 1.0));
    vec3 lit = albedo;

    bool stillWater = abs(v_texCoord.z - u_WaterStillLayer) < 0.25;
    bool flowingWater = abs(v_texCoord.z - u_WaterFlowLayer) < 0.25;
    bool water = stillWater || flowingWater;
    if (!water) {
        vec3 surfaceNormal = normalize(cross(dFdx(v_worldPosition), dFdy(v_worldPosition)));
        if (dot(surfaceNormal, normalize(-v_relativePosition)) < 0.0) surfaceNormal *= -1.0;
        vec3 lightDirection = normalize(u_SunDirection.y >= 0.0
                ? u_SunDirection.xyz : u_MoonDirection.xyz);
        float noL = max(dot(surfaceNormal, lightDirection), 0.0);
        float shadowSssDepth;
        vec3 visibility = noL > 1.0e-3
                ? photonShadow(v_relativePosition, surfaceNormal, v_light.x,
                        0.0, shadowSssDepth)
                : vec3(0.0);
        vec3 diffuse = vec3(liftSignal(noL, 0.25)) * visibility;
        vec3 bounced = 0.033 * (1.0 - visibility)
                * (1.0 - 0.1 * max(surfaceNormal.y, 0.0)) * pow(skyLight, 4.0);
        vec3 direct = (diffuse + bounced) * photonDirectionalLight();
        vec3 ambient = photonAmbientLight(surfaceNormal, skyLight)
                * skyLight * skyLight;
        float directional = (0.9 + 0.1 * surfaceNormal.x)
                * (0.8 + 0.2 * abs(surfaceNormal.y));
        vec3 block = photonBlockLight(blockLight, skyLight) * directional;
        vec3 cave = vec3(0.15 * directional * (1.0 - skyLight * skyLight));
        lit = max(direct + ambient + block + cave, vec3(0.0))
                * albedo * (1.0 / 3.14159265359);
        // Preserve the engine's explicit fullbright mode without changing Photon defaults.
        lit = mix(lit, albedo, u_MinLight);
    }
    float outputAlpha = textureColor.a;
    if (water) {
        vec2 screenUv = gl_FragCoord.xy / u_Viewport;
        vec3 flatNormal = normalize(cross(dFdx(v_worldPosition), dFdy(v_worldPosition)));
        vec3 toCamera = normalize(-v_relativePosition);
        if (dot(flatNormal, toCamera) < 0.0) flatNormal = -flatNormal;
        vec2 waveCoord = waterParallaxCoord(v_worldPosition.xz, toCamera, flowingWater);
        vec3 normal = photonWaterNormal(flatNormal, flowingWater, waveCoord);
        float noV = clamp(abs(dot(normal, toCamera)), 0.0, 1.0);
        /* Photons exakte dielektrische Fresnel-Kurve (Luft -> Wasser) statt der zuvor
           zusätzlich um 0.05 angehobenen Schlick-Näherung. Dadurch bleibt der türkise
           Wasserkörper bis zu deutlich flacheren Blickwinkeln sichtbar. */
        float fresnel = dielectricFresnel(noV, 1.333);
        if (u_CameraUnderwater > 0.5 && u_SnellsWindow > 0.5 && noV < 0.66) {
            fresnel = 1.0;
        }

        vec3 backPosition = reconstructPosition(screenUv,
                texture(u_OpaqueDepth, screenUv).r);
        float layerDistance = max(abs(length(backPosition) - length(v_relativePosition)), 0.0);
        vec2 refractionOffset = normal.xz / max(length(v_relativePosition), 1.0)
                * min(layerDistance, 8.0) * (0.10 * u_WaterRefractionIntensity)
                * u_WaterRefraction;
        vec2 refractedUv = clamp(screenUv + refractionOffset, vec2(0.001), vec2(0.999));
        vec3 refractedPosition = reconstructPosition(refractedUv,
                texture(u_OpaqueDepth, refractedUv).r);
        if (length(refractedPosition) < length(v_relativePosition)) refractedUv = screenUv;
        vec3 background = texture(u_OpaqueColor, refractedUv).rgb;
        float causticNoise = caustics(refractedPosition + vec3(u_CameraPosition));
        float shallowCaustics = exp(-0.12 * layerDistance) * u_WaterCaustics
                * u_WaterCausticsIntensity;
        background *= 1.0 + shallowCaustics * max(causticNoise - 0.55, 0.0) * 0.45;

        vec3 reflectionDirection = reflect(-toCamera, normal);
        /* The sky map already contains Photon's sun/moon discs.  The separate
           microfacet highlight is evaluated below, exactly like gbuffers_water. */
        vec3 skyReflection = texture(u_SkyReflection, reflectionDirection).rgb
                * u_SkyReflections;
        vec3 reflection = vec3(0.0);
        float reflectionWeight = 0.0;
        int rayCount = int(u_SsrRayCount + 0.5);
        for (int ray = 0; ray < 8; ++ray) {
            if (ray >= rayCount || u_EnvironmentReflections < 0.5) break;
            float angle = TAU * (float(ray) + 0.5) / float(max(rayCount, 1));
            vec3 rayDirection = normalize(reflectionDirection
                    + vec3(cos(angle), 0.0, sin(angle)) * 0.0025);
            float confidence;
            vec3 sampleReflection = screenSpaceReflection(
                    v_relativePosition + normal * 0.08, rayDirection,
                    skyReflection, confidence);
            reflection += mix(skyReflection, sampleReflection, confidence);
            reflectionWeight += 1.0;
        }
        reflection = reflectionWeight > 0.0
                ? reflection / reflectionWeight : skyReflection;

        vec3 directionWorld = normalize(v_relativePosition);
        vec3 lightDirection = normalize(u_SunDirection.y >= 0.0
                ? u_SunDirection.xyz : u_MoonDirection.xyz);
        float lightDotView = dot(directionWorld, lightDirection);
        vec4 absorptionLayer = waterAbsorptionLayer(layerDistance,
                lightDotView, v_light.x, v_light.y);

        float textureHighlight = dampen(0.5 * pow(clamp(
                (textureColor.r - 0.63) / 0.37, 0.0, 1.0), 2.0)
                + 0.03 * textureColor.r);
        float textureMask = u_WaterTexture > 1.5 ? 1.0
                : (u_WaterTexture > 0.5
                ? 1.0 - pow(clamp(v_light.x / 0.5, 0.0, 1.0), 3.0) : 0.0);
        textureHighlight *= textureMask;

        /* water_material.roughness = 0.002, raised by the vanilla texture
           highlight in get_water_material(). */
        float waterRoughness = 0.002 + 0.3 * textureHighlight;

        vec3 waterAbsorption = vec3(u_WaterAbsorptionR, u_WaterAbsorptionG,
                u_WaterAbsorptionB) * REC709_TO_XYZ * XYZ_TO_REC2020;
        vec3 surfaceAlbedo = clamp(0.5 * exp(-2.0 * waterAbsorption)
                * textureHighlight, 0.0, 1.0);
        float edgeDistance = layerDistance * max(abs(directionWorld.y), 1.0e-5);
        float edgeHighlight = pow(max(1.0 - 2.0 * edgeDistance, 0.0), 3.0)
                * (1.0 + 8.0 * textureHighlight)
                * u_WaterEdgeHighlight * u_WaterEdgeHighlightIntensity
                * max(normal.y, 0.0) * (1.0 - 0.5 * v_light.x * v_light.x);
        float ambientLuma = dot(photonAmbientLight(), vec3(0.2627, 0.6780, 0.0593));
        surfaceAlbedo += 0.1 * edgeHighlight
                / mix(1.0, max(ambientLuma, 0.5), v_light.x);
        surfaceAlbedo = clamp(surfaceAlbedo, 0.0, 1.0);

        float waterSssDepth;
        vec3 waterVisibility = photonShadow(v_relativePosition, flatNormal,
                v_light.x, 1.0, waterSssDepth);
        float waterNoL = max(dot(normal, lightDirection), 0.0);
        float waterLoV = dot(lightDirection, toCamera);
        vec3 bouncedWater = 0.033 * (1.0 - waterVisibility)
                * (1.0 - 0.1 * max(normal.y, 0.0)) * pow(v_light.x, 4.0);
        vec3 sssCoefficient = surfaceAlbedo
                * inversesqrt(dot(surfaceAlbedo, vec3(0.2627, 0.6780, 0.0593))
                + 1.0e-6);
        sssCoefficient = 0.75 * clamp(sssCoefficient, 0.0, 1.0);
        sssCoefficient = (1.0 - sssCoefficient) * 14.0;
        float sssPhase = mix(1.0 / (4.0 * 3.14159265359),
                henyeyGreenstein(-waterLoV, 0.7), 0.33);
        vec3 waterSss = 5.0 * sssPhase
                * exp2(-sssCoefficient * waterSssDepth) * 3.14159265359;
        vec3 surfaceLighting = (0.5 * liftSignal(waterNoL, 0.25)
                * waterVisibility + bouncedWater + waterSss)
                * photonDirectionalLight();
        surfaceLighting += 0.5 * photonAmbientLight()
                * v_light.x * v_light.x;
        float waterDirectional = (0.9 + 0.1 * normal.x)
                * (0.8 + 0.2 * abs(flatNormal.y));
        surfaceLighting += photonBlockLight(v_light.y, v_light.x)
                * waterDirectional;
        surfaceLighting += vec3(0.15 * waterDirectional
                * (1.0 - v_light.x * v_light.x));
        vec3 surfaceLayer = surfaceAlbedo * surfaceLighting * (1.0 / 3.14159265359);
        float waterNoV = clamp(dot(normal, toCamera), 0.0, 1.0);
        float halfwayNorm = inversesqrt(max(2.0 * waterLoV + 2.0, 1.0e-8));
        float waterLoH = (waterLoV + 1.0) * halfwayNorm;
        surfaceLayer += photonWaterSpecular(waterNoL, waterNoV,
                waterLoV, waterLoH, waterRoughness,
                u_SunDirection.y, u_MoonDirection.w)
                * photonDirectionalLight() * waterVisibility;
        float reflectionsEnabled = max(u_SkyReflections, u_EnvironmentReflections);
        surfaceLayer += reflection * fresnel * reflectionsEnabled;
        float scalarTransmittance = 1.0 - absorptionLayer.a;
        vec3 refractionCorrection = (background
                - texture(u_OpaqueColor, screenUv).rgb) * scalarTransmittance;
        lit = surfaceLayer + absorptionLayer.rgb + refractionCorrection;
        /* Photon stores one minus red-channel transmission as layer alpha. */
        outputAlpha = absorptionLayer.a;
    }

    float edgeFog = clamp((v_viewDist - u_FogStart) / max(u_FogEnd - u_FogStart, 0.001),
            0.0, 1.0);
    /* Local aerial perspective is applied after translucency by the Photon c0/c1
       volumetric pass. This material pass only hides the finite chunk boundary. */
    float fog = edgeFog * u_FogEnabled;
    vec3 directionalFog = texture(u_AtmosphereFog, normalize(v_viewDirection)).rgb;
    float atmosphereEnergy = dot(directionalFog, vec3(0.2627, 0.6780, 0.0593));
    vec3 fogColor = mix(u_EnvFogColor.rgb, directionalFog,
            smoothstep(0.0001, 0.002, atmosphereEnergy));
    vec3 finalColor = mix(lit, fogColor, fog);
    float finalAlpha = water ? outputAlpha
            : mix(1.0, outputAlpha, u_TranslucentPass);
    if (u_TranslucentPass > 0.5 && !water) finalColor *= finalAlpha;
    fragColor = vec4(finalColor, finalAlpha);
}
