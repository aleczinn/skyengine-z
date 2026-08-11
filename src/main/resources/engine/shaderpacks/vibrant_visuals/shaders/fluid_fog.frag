#version 460 core

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_Scene;
uniform sampler2D u_FogTransmittance;
uniform sampler2D u_FogScattering;
uniform int u_Fluid;

// Photons quintische Filterkurve fuer das halbauflösende Volumetric-Ziel.
vec4 smoothFilter(sampler2D source, vec2 coordinate) {
    vec2 resolution = vec2(textureSize(source, 0));
    coordinate = coordinate * resolution + 0.5;
    vec2 integerPart;
    vec2 fraction = modf(coordinate, integerPart);
    fraction = fraction * fraction * fraction
            * (fraction * (fraction * 6.0 - 15.0) + 10.0);
    return texture(source, (integerPart + fraction - 0.5) / resolution);
}

void main() {
    vec4 scene = texture(u_Scene, v_uv);
    if (u_Fluid == 0 || u_Fluid == 1) {
        vec3 transmittance = smoothFilter(u_FogTransmittance, v_uv).rgb;
        vec3 scattering = smoothFilter(u_FogScattering, v_uv).rgb;
        scene.rgb = scene.rgb * transmittance + scattering;
    } else {
        scene.rgb = scene.rgb * vec3(0.16, 0.035, 0.006)
                + vec3(1.20, 0.13, 0.008) * 1.7;
    }
    fragColor = scene;
}
