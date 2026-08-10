#version 460 core

layout(location = 0) in uvec4 a_data;
layout(location = 1) in uint a_light;

layout(std430, binding = 0) readonly buffer DrawOffsets {
    vec4 u_DrawOffsets[];
};

uniform mat4 u_ProjectionView;
uniform vec2 u_DetailFade;
uniform vec2 u_DetailCamSnap;
uniform vec3 u_CameraPosition;
uniform sampler2D u_WaterNoise;
uniform float u_Time;
uniform float u_WaterStillLayer;
uniform float u_WaterFlowLayer;
uniform float u_WaterWaves;
uniform float u_WaterDisplacement;
uniform float u_WaterWaveIterations;
uniform float u_WaterWaveFrequency;
uniform float u_WaterWaveSpeedStill;
uniform float u_WaterWaveSpeedFlowing;
uniform float u_WaterWavePersistence;
uniform float u_WaterWaveLacunarity;
uniform float u_WaterHeightVariation;

const float TAU = 6.28318530718;
const float GOLDEN_ANGLE = 2.39996322973;

float gerstnerWave(vec2 coord, vec2 direction, float time, float noiseValue,
        float wavelength) {
    float k = TAU / wavelength;
    float phase = sqrt(9.8 * k) * time - k * (dot(direction, coord) + noiseValue);
    float value = sin(phase) * 0.5 + 0.5;
    return value * value;
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
    float persistence = 0.5 * u_WaterWavePersistence;
    float amplitude = 1.0;
    float frequency = 0.7 * u_WaterWaveFrequency;
    float wavelength = 1.0;
    float height = 0.0;
    int iterations = int(u_WaterWaveIterations + 0.5);
    for (int i = 0; i < 8; ++i) {
        if (i >= iterations) break;
        height += gerstnerWave(coord * frequency, direction, time,
                texture(u_WaterNoise, noiseCoord).g * 2.0, wavelength) * amplitude;
        amplitude *= persistence;
        frequency *= 1.7 * u_WaterWaveLacunarity;
        wavelength *= 1.5;
        noiseCoord *= 2.5;
        direction = rotation * direction;
    }
    if (u_WaterHeightVariation > 0.5) {
        float variation = texture(u_WaterNoise,
                (coord + vec2(0.0, 0.1 * time)) * 0.001).g;
        height *= max(0.4, variation * 2.0 - 0.5);
    }
    return height * (1.0 - persistence)
            / max(1.0 - pow(persistence, float(iterations)), 0.001);
}

out vec3 v_texCoord;
out vec3 v_color;
out vec2 v_light;
out float v_viewDist;
out vec3 v_viewDirection;
out vec3 v_relativePosition;
out vec3 v_worldPosition;

void main() {
    vec3 pos = vec3(float(a_data.x & 0xFFFFu), float(a_data.x >> 16),
            float(a_data.y & 0xFFFFu)) * u_DrawOffsets[gl_DrawID].w - 1.0;
    vec2 uv = vec2(float(a_data.y >> 16), float(a_data.z & 0xFFFFu))
            * (1.0 / 1024.0) - 1.0;
    float layer = float(a_data.z >> 16);
    vec3 color = vec3(float(a_data.w & 0xFFu), float((a_data.w >> 8) & 0xFFu),
            float((a_data.w >> 16) & 0xFFu)) * (1.0 / 255.0);

    v_texCoord = vec3(uv, layer);
    v_color = color;
    v_light = vec2(float(a_light & 0xFu), float((a_light >> 4) & 0xFu))
            * (1.0 / 15.0);
    if (gl_BaseInstance != 0) {
        v_color = mix(v_color, vec3(1.0, 0.1, 0.1), 0.7);
    }

    vec3 relative = pos + u_DrawOffsets[gl_DrawID].xyz;
    bool stillWater = abs(layer - u_WaterStillLayer) < 0.25;
    bool flowingWater = abs(layer - u_WaterFlowLayer) < 0.25;
    if ((stillWater || flowingWater) && u_WaterWaves > 0.5
            && u_WaterDisplacement > 0.5) {
        float height = waterHeight(relative.xz + u_CameraPosition.xz, flowingWater);
        relative.y += (height - 0.5) * 0.18;
    }
    v_relativePosition = relative;
    v_worldPosition = relative + u_CameraPosition;
    v_viewDist = length(relative.xz);
    v_viewDirection = normalize(relative);

    if (u_DetailFade.y > 0.0) {
        float sectionDist = length(u_DrawOffsets[gl_DrawID].xz + vec2(16.0)
                + u_DetailCamSnap);
        float density = 1.0 - clamp((sectionDist - u_DetailFade.x)
                * u_DetailFade.y, 0.0, 1.0);
        if (float((a_data.w >> 24) & 0xFFu) > density * 255.0) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            return;
        }
    }

    gl_Position = u_ProjectionView * vec4(relative, 1.0);
}
