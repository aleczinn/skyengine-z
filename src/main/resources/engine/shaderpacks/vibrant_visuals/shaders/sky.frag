#version 460 core

in vec3 v_RayDirection;
out vec4 fragColor;

layout(std140, binding = 1) uniform Environment {
    vec4 u_SunDirection;
    vec4 u_MoonDirection;
    vec4 u_SkyLightColor;
    vec4 u_EnvFogColor;
    vec4 u_SkyTint;
};

uniform sampler3D u_AtmosphereScattering;
uniform sampler2D u_Noise;
uniform float u_DayFraction;
uniform float u_Time;
uniform float u_SunIntensity;
uniform float u_MoonIntensity;
uniform float u_FogCapture;
uniform mat4 u_ShadowViewInverse;

const float PI = 3.141592653589793;
const float TAU = 6.283185307179586;
const float PLANET_RADIUS = 6371000.0;
const float INNER_RADIUS = PLANET_RADIUS - 1000.0;
const float OUTER_RADIUS = PLANET_RADIUS + 110000.0;
const float OUTER_RADIUS_SQ = OUTER_RADIUS * OUTER_RADIUS;
const float INNER_RADIUS_SQ = INNER_RADIUS * INNER_RADIUS;
const float SUN_RADIUS = radians(2.0);
const float MOON_RADIUS = radians(3.0);
const ivec3 SCATTERING_RES = ivec3(16, 64, 32);
const float MIN_MU_S = -0.35;
const vec2 AIR_SCALE_HEIGHT = vec2(8400.0, 1250.0);
const mat3 REC709_TO_XYZ = mat3(0.4124,0.3576,0.1805, 0.2126,0.7152,0.0722, 0.0193,0.1192,0.9505);
const mat3 XYZ_TO_REC2020 = mat3(1.7166084,-0.3556621,-0.2533601, -0.6666829,1.6164776,0.0157685, 0.0176422,-0.0427763,0.94222867);

float sqr(float x) { return x * x; }
float cube(float x) { return x * x * x; }
float pow1d5(float x) { return x * sqrt(max(x, 0.0)); }
float dampen(float x) { x = clamp(x, 0.0, 1.0); return x * (2.0 - x); }
float unitUv(float x, int size) { return (x * float(size - 1) + 0.5) / float(size); }

vec2 intersectSphere(float mu, float r, float radius) {
    float d = r * r * (mu * mu - 1.0) + radius * radius;
    if (d < 0.0) return vec2(-1.0);
    d = sqrt(d);
    return vec2(-r * mu - d, -r * mu + d);
}

float nvidiaPhase(float nu, float g, float alpha, float angularRadius) {
    float radius = max(angularRadius, 1e-6);
    float cosr = cos(radius);
    float sinr = sin(radius);
    float mu = nu * cosr + sqrt(max(0.0, 1.0 - nu * nu)) * sinr;
    if (nu > cosr) mu = 1.0;
    float gg = g * g;
    return ((1.0 - gg) * (1.0 + alpha * mu * mu)) /
           (PI * pow1d5(1.0 + gg - 2.0 * g * mu) * 4.0 *
            (1.0 + alpha * (1.0 + 2.0 * gg) / 3.0));
}

/* Photon include/utility/phase_functions.glsl + include/sky/sky.glsl.
   Unlike a binary disc this area light keeps a finite, chromatic forward-scattering
   shoulder. That shoulder is what Bloom expands into Photon's soft solar edge. */
float kleinNishinaPhaseArea(float nu, float energy, float angularRadius) {
    float radius = max(angularRadius, 1.0e-6);
    float cosRadius = cos(radius);
    float sinRadius = sin(radius);
    float mu = nu * cosRadius
            + sqrt(max(0.0, 1.0 - nu * nu)) * sinRadius;
    if (nu > cosRadius) mu = 1.0;
    return energy / (TAU * (energy - energy * mu + 1.0)
            * log(2.0 * energy + 1.0));
}

vec3 drawSun(vec3 ray, vec3 sun, vec3 sunColor) {
    const float energy = 9000.1;
    float nu = dot(ray, sun);
    vec3 phase = vec3(
            kleinNishinaPhaseArea(nu, 0.79 * energy, SUN_RADIUS),
            kleinNishinaPhaseArea(nu, 1.00 * energy, SUN_RADIUS),
            kleinNishinaPhaseArea(nu, 1.22 * energy, SUN_RADIUS));
    return phase * sunColor * (PI / 360.0);
}

vec2 intersectSphere(vec3 origin, vec3 ray, float radius) {
    float b = dot(origin, ray);
    float d = sqr(b) - dot(origin, origin) + sqr(radius);
    if (d < 0.0) return vec2(-1.0);
    d = sqrt(d);
    return -b + vec2(-d, d);
}

float chapman(float x, float cosTheta) {
    float c = sqrt(0.5 * PI * x);
    if (cosTheta >= 0.0) return c / ((c - 1.0) * cosTheta + 1.0);
    float sinTheta = sqrt(clamp(1.0 - sqr(cosTheta), 0.0, 1.0));
    return c / ((c - 1.0) * cosTheta - 1.0)
         + 2.0 * c * exp(x - x * sinTheta) * sqrt(sinTheta);
}

vec3 atmosphereTransmittance(float mu) {
    if (intersectSphere(mu, PLANET_RADIUS + 10.0, PLANET_RADIUS).x >= 0.0) return vec3(0.0);
    vec2 inverseHeight = 1.0 / AIR_SCALE_HEIGHT;
    vec2 density = exp((PLANET_RADIUS + 10.0) * -inverseHeight + PLANET_RADIUS * inverseHeight);
    vec2 airmass = AIR_SCALE_HEIGHT * density;
    airmass.x *= chapman((PLANET_RADIUS + 10.0) * inverseHeight.x, mu);
    airmass.y *= chapman((PLANET_RADIUS + 10.0) * inverseHeight.y, mu);
    mat3 rec709To2020 = REC709_TO_XYZ * XYZ_TO_REC2020;
    vec3 rayleigh = vec3(8.059375432e-6,1.671209429e-5,4.080133294e-5) * rec709To2020;
    vec3 mie = vec3(1.666442358e-6,1.812685127e-6,1.958927896e-6) * rec709To2020;
    vec3 ozone = vec3(8.304280072e-7,1.314911970e-6,5.440679729e-8) * rec709To2020;
    return clamp(exp(-(rayleigh * airmass.x + mie * airmass.y + ozone * airmass.x)), 0.0, 1.0);
}

vec3 scatteringUv(float nu, float mu, float muS) {
    float halfNu = sqrt(max((1.0 - mu * mu) * (1.0 - muS * muS), 0.0));
    float nuMin = mu * muS - halfNu;
    float nuMax = mu * muS + halfNu;
    float uNu = nuMin == nuMax ? nuMin : (nu - nuMin) / (nuMax - nuMin);
    uNu = unitUv(clamp(uNu, 0.0, 1.0), SCATTERING_RES.x);

    if (mu > 0.0) mu *= sqrt(sqrt(mu));
    float r = PLANET_RADIUS;
    float H = sqrt(OUTER_RADIUS_SQ - INNER_RADIUS_SQ);
    float rho = sqrt(max(r * r - INNER_RADIUS_SQ, 0.0));
    float rmu = r * mu;
    float discriminant = rmu * rmu - r * r + INNER_RADIUS_SQ;
    float uMu;
    if (mu < 0.0 && discriminant >= 0.0) {
        float d = -rmu - sqrt(max(discriminant, 0.0));
        uMu = unitUv((d - (r - INNER_RADIUS)) / max(rho - (r - INNER_RADIUS), 1e-5), SCATTERING_RES.y / 2);
        uMu = 0.5 - 0.5 * uMu;
    } else {
        float d = -rmu + sqrt(max(discriminant + H * H, 0.0));
        uMu = unitUv((d - (OUTER_RADIUS - r)) / max(rho + H - (OUTER_RADIUS - r), 1e-5), SCATTERING_RES.y / 2);
        uMu = 0.5 + 0.5 * uMu;
    }

    float dMin = OUTER_RADIUS - INNER_RADIUS;
    float dMax = H;
    float D = intersectSphere(MIN_MU_S, INNER_RADIUS, OUTER_RADIUS).y;
    float A = (D - dMin) / (dMax - dMin);
    float d = intersectSphere(muS, INNER_RADIUS, OUTER_RADIUS).y;
    float a = (d - dMin) / (dMax - dMin);
    float uMuS = unitUv(max(1.0 - a / A, 0.0) / (1.0 + a), SCATTERING_RES.z);
    return vec3(uNu, clamp(uMu, 0.0, 1.0), clamp(uMuS, 0.0, 1.0));
}

vec3 atmosphereFor(vec3 ray, vec3 light, bool moon) {
    float nu = dot(ray, light);
    vec3 sun = normalize(u_SunDirection.xyz);
    vec3 moonDirection = normalize(u_MoonDirection.xyz);
    float horizonMu = mix(-0.01, 0.03, clamp(smoothstep(-0.05, 0.1, sun.y)
                         + smoothstep(0.05, 0.1, moonDirection.y), 0.0, 1.0));
    vec3 uv = scatteringUv(nu, max(ray.y, horizonMu), light.y);
    vec3 rayleigh = texture(u_AtmosphereScattering, vec3(uv.x * 0.5, uv.yz)).rgb;
    vec3 mie = texture(u_AtmosphereScattering, vec3(uv.x * 0.5 + 0.5, uv.yz)).rgb;
    vec3 phase;
    if (moon) {
        float moonPhase = u_MoonDirection.w / 4.0;
        moonPhase = moonPhase > 1.0 ? 2.0 - moonPhase : moonPhase;
        float phaseScale = sqr(1.0 - moonPhase) * 0.04 + 0.96;
        phase = vec3(nvidiaPhase(nu, 0.895 * phaseScale, 1.0, 2.0 * MOON_RADIUS),
                     nvidiaPhase(nu, 0.900 * phaseScale, 1.0, 2.0 * MOON_RADIUS),
                     nvidiaPhase(nu, 0.905 * phaseScale, 1.0, 2.0 * MOON_RADIUS)) * 1.02;
    } else {
        phase = vec3(nvidiaPhase(nu, 0.85, 1.0, SUN_RADIUS),
                     nvidiaPhase(nu, 0.86, 1.0, SUN_RADIUS),
                     nvidiaPhase(nu, 0.87, 1.0, SUN_RADIUS)) * 0.2;
    }
    return rayleigh + mie * phase;
}

vec4 hash4(vec2 p) {
    vec4 p4 = fract(vec4(p.xyxy) * vec4(.1031, .1030, .0973, .1099));
    p4 += dot(p4, p4.wzxy + 33.33);
    return fract((p4.xxyz + p4.yzzw) * p4.zywx);
}

vec3 blackbody(float temperature) {
    float t = temperature / 100.0;
    return vec3(t <= 66.0 ? 1.0 : clamp(1.292936 * pow(t - 60.0, -0.133205), 0.0, 1.0),
                t <= 66.0 ? clamp(0.390082 * log(max(t, 1.0)) - 0.631841, 0.0, 1.0) : clamp(1.129891 * pow(t - 60.0, -0.075515), 0.0, 1.0),
                t >= 66.0 ? 1.0 : (t <= 19.0 ? 0.0 : clamp(0.543207 * log(t - 10.0) - 1.196254, 0.0, 1.0)));
}

vec3 unstableStar(vec2 coord, float threshold) {
    vec4 noise = hash4(coord);
    float star = clamp((noise.x - threshold) / max(1.0 - threshold, 1e-5), 0.0, 1.0);
    star = star * star; star = star * star; star = star * star; star = star * star;
    star *= 1.0 - noise.z * cos(u_Time * 2.0 + noise.w * TAU);
    return star * blackbody(mix(4500.0, 8500.0, noise.y));
}

vec3 stars(vec3 ray, float threshold) {
    vec2 coord = ray.xy / (abs(ray.z) + length(ray.xy)) + 41.21 * sign(ray.z);
    coord *= 600.0;
    coord = abs(coord) + 33.3 * step(vec2(0.0), coord);
    vec2 cell = floor(coord), f = fract(coord); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(unstableStar(cell, threshold), unstableStar(cell + vec2(1,0), threshold), f.x),
               mix(unstableStar(cell + vec2(0,1), threshold), unstableStar(cell + vec2(1,1), threshold), f.x), f.y);
}

vec4 drawMoon(vec3 ray, vec3 moon) {
    float MoV = dot(ray, moon);
    if (MoV < cos(MOON_RADIUS)) return vec4(0.0);
    float dist = clamp(acos(clamp(MoV, -1.0, 1.0)) / MOON_RADIUS, 0.0, 1.0);
    vec3 tangent = abs(moon.y) > 0.999 ? vec3(1,0,0) : normalize(cross(vec3(0,1,0), moon));
    vec3 bitangent = normalize(cross(tangent, moon));
    mat3 tbn = mat3(tangent, bitangent, moon);
    vec2 offset = fract(((ray - normalize(u_SunDirection.xyz)) * tbn).xy + 0.5);
    vec3 noise = texture(u_Noise, 2.0 * offset).xyz;
    float moonTexture = pow1d5(noise.x) * 0.75 + 0.6 * cube(noise.y) - 0.1 * noise.z;
    float moonDistance = intersectSphere(-moon, ray, MOON_RADIUS).x;
    vec3 normal = normalize(ray * moonDistance - moon);
    float lightAngle = 0.125 * TAU * u_MoonDirection.w;
    vec3 left = normalize(cross(vec3(0,1,0), ray));
    vec3 light = cos(lightAngle) * -ray + sin(lightAngle) * left;
    float shadow = dampen(clamp(dot(normal, light), 0.0, 1.0));
    float edge = dist; edge *= edge; edge *= edge; edge *= edge;
    vec3 lit = vec3(0.75, 0.80, 1.0);
    vec3 glow = vec3(0.70, 0.83, 1.0);
    vec3 color = max(shadow * lit * (1.0 + 4.0 * edge),
            0.5 * glow * (0.1 + 0.1 * edge)) * (0.2 + 0.8 * moonTexture);
    color = 10.0 * color * color * u_MoonIntensity;
    return vec4(color, 1.0);
}

void main() {
    vec3 ray = normalize(v_RayDirection);
    vec3 sun = normalize(u_SunDirection.xyz);
    vec3 moon = normalize(u_MoonDirection.xyz);

    float meFade = sun.y < 0.18 ? 0.37 + 1.2 * max(0.0, -sun.y) : 1.7;
    float meWeight = sqr(clamp(1.0 - meFade * abs(sun.y - 0.18), 0.0, 1.0));
    float sunrise = step(0.0, sun.x) * meWeight;
    float sunset = step(sun.x, 0.0) * meWeight;
    float blueHour = clamp((exp(-190.0 * sqr(sun.y + 0.09604)) - 0.05) / 0.95, 0.0, 1.0);

    float sunExposure = 7.0 * (1.0 + 0.5 * (sunrise + sunset) + 40.0 * blueHour) * u_SunIntensity;
    vec3 sunTint = mix(vec3(1.0), vec3(1.05, 0.84, 0.93) * 1.2,
                       sqr(clamp(1.0 - abs(sun.y - 0.17) / 0.40, 0.0, 1.0)));
    sunTint *= mix(vec3(1.0), vec3(0.95, 0.80, 1.0), blueHour);
    float phaseBrightness = u_MoonDirection.w == 0.0 ? 1.0
            : u_MoonDirection.w == 1.0 ? 0.875
            : u_MoonDirection.w == 2.0 ? 0.75
            : u_MoonDirection.w == 3.0 ? 0.625
            : u_MoonDirection.w == 4.0 ? 0.5
            : u_MoonDirection.w == 5.0 ? 0.75
            : u_MoonDirection.w == 6.0 ? 0.875 : 1.0;
    float moonExposure = 0.66 * phaseBrightness
            * (1.0 + 0.33 / clamp(1.25 * max(-sun.y, 0.1), 0.0, 1.0));
    vec3 moonTint = pow(vec3(0.75, 0.83, 1.0), vec3(2.2))
            * REC709_TO_XYZ * XYZ_TO_REC2020;

    vec3 atmosphere = atmosphereFor(ray, sun, false) * sunExposure * sunTint;
    atmosphere += atmosphereFor(ray, moon, true) * moonExposure * moonTint;
    float saturationBoost = 0.10 + 0.20
            * exp(-150.0 * sqr(sun.y + 0.07283));
    atmosphere = mix(vec3(dot(atmosphere, vec3(0.2627, 0.6780, 0.0593))),
                     atmosphere, 1.0 + saturationBoost);
    vec3 celestial = vec3(0.0);

    celestial += drawSun(ray, sun, sunExposure * sunTint);

    mat3 starRotation = sun.y >= 0.0
            ? mat3(u_ShadowViewInverse)
            : mat3(-u_ShadowViewInverse[0].xyz,
                    u_ShadowViewInverse[1].xyz,
                    -u_ShadowViewInverse[2].xyz);
    vec3 celestialRay = ray * starRotation;
    float starThreshold = 1.0 - 0.025 * smoothstep(-0.2, 0.05, -sun.y);
    celestial += stars(celestialRay, starThreshold) * smoothstep(-0.1, 0.1, ray.y);
    vec4 moonDisc = drawMoon(ray, moon);
    celestial = celestial * (1.0 - moonDisc.a) + moonDisc.rgb;
    celestial *= 1.0 - u_FogCapture;

    // Photon draw_sky(): every celestial body is attenuated by the same atmosphere;
    // the previous moon-only tint/transmittance caused the visible dark outline.
    vec3 sky = celestial * atmosphereTransmittance(ray.y) + atmosphere;
    fragColor = vec4(max(sky * u_SkyTint.rgb, vec3(0.0)), 1.0);
}
