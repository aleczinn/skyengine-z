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
uniform mat4 u_ProjectionView;
uniform mat4 u_InvProjectionView;
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
    if (u_WaterParallax < 0.5) return coord;
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

vec3 waterScattering(vec3 background, float distanceThroughWater,
        float sunFacing, float skylight) {
    vec3 absorption = vec3(u_WaterAbsorptionR, u_WaterAbsorptionG,
            u_WaterAbsorptionB);
    if (u_BiomeWaterColor > 0.5) {
        const float densityScale = 0.15;
        vec3 biomeColor = max(srgbToWorking(clamp(v_color, 0.0, 1.0)), vec3(0.0001));
        vec3 forestAbsorption = -densityScale * log(vec3(0.1245, 0.1797, 0.7108));
        vec3 biomeAbsorption = -densityScale * log(biomeColor) - forestAbsorption;
        absorption = max(absorption + biomeAbsorption * 0.33, vec3(0.0));
    }
    vec3 scatteringCoefficient = vec3(u_WaterScattering);
    vec3 extinction = max(absorption + scatteringCoefficient, vec3(0.00001));
    float distanceAdjusted = max(distanceThroughWater, 2.0 - skylight * skylight * skylight);
    vec3 transmittance = exp(-extinction * distanceAdjusted);
    vec3 albedo = scatteringCoefficient / extinction;
    vec3 multiple = 0.84 * albedo;
    vec3 multipleEnergy = multiple / (1.0 - multiple);
    vec3 direct = u_SkyLightColor.rgb * smoothstep(0.0, 0.25, skylight)
            * mix(0.024, 0.11, pow(max(sunFacing, 0.0), 3.0));
    vec3 ambient = u_EnvFogColor.rgb * skylight * 0.07957747;
    vec3 scattering = (direct + ambient) * (1.0 - transmittance)
            * scatteringCoefficient / extinction * (1.0 + multipleEnergy);
    float brightnessControl = 1.0 - exp(-0.33 * distanceThroughWater);
    return background * transmittance
            + scattering * (1.0 + 6.0 * dot(transmittance, transmittance) / 3.0)
                    * brightnessControl;
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
        vec3 skyReflection = texture(u_SkyReflection, reflectionDirection).rgb
                * u_SkyReflections;
        float sunDisc = smoothstep(0.9975, 0.99985,
                dot(reflectionDirection, normalize(u_SunDirection.xyz)));
        skyReflection += u_SkyLightColor.rgb * sunDisc * 0.5 * u_SunDirection.w;
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

        float sunFacing = dot(normalize(-v_relativePosition), normalize(u_SunDirection.xyz));
        vec3 transmission = waterScattering(background, layerDistance,
                sunFacing, v_light.x);
        float textureHighlight = pow(clamp(textureColor.r - 0.58, 0.0, 0.42)
                / 0.42, 3.0);
        float textureMask = u_WaterTexture > 1.5 ? 1.0
                : (u_WaterTexture > 0.5 ? 1.0 - smoothstep(0.65, 0.95, v_light.x) : 0.0);
        transmission += u_SkyLightColor.rgb * textureHighlight * textureMask * 0.08;
        float edgeHighlight = smoothstep(0.0, 0.75, layerDistance)
                * (1.0 - smoothstep(0.75, 2.5, layerDistance))
                * u_WaterEdgeHighlight * u_WaterEdgeHighlightIntensity;
        transmission += u_SkyLightColor.rgb * edgeHighlight * 0.035;
        float reflectionsEnabled = max(u_SkyReflections, u_EnvironmentReflections);
        lit = mix(transmission, reflection,
                clamp(fresnel * reflectionsEnabled, 0.0, 0.96));
        /* Der Wasserzweig ist bereits der vollständige Photon-Layer-Composite aus der
           eingefrorenen Szene; erneutes Alpha-Blending würde den Hintergrund doppelt addieren. */
        outputAlpha = 1.0;
    }

    float edgeFog = clamp((v_viewDist - u_FogStart) / max(u_FogEnd - u_FogStart, 0.001),
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
            water ? outputAlpha : mix(1.0, outputAlpha, u_TranslucentPass));
}
