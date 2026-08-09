#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Scene;

layout(std140, binding = 2) uniform u_PostSettings {
    vec4 egcs;
    vec4 vbtt;
    vec4 lgsh;
    vec4 mtdr;
};

const mat3 REC2020_TO_XYZ = mat3(
    0.6369736, 0.1446172, 0.1688585,
    0.2627066, 0.6779996, 0.0592938,
    0.0000000, 0.0280728, 1.0608437);
const mat3 XYZ_TO_REC709 = mat3(
    3.2406, -1.5372, -0.4986,
   -0.9689,  1.8758,  0.0415,
    0.0557, -0.2040,  1.0570);
const vec3 LUMA_2020 = vec3(0.2627, 0.6780, 0.0593);

vec3 lottes(vec3 x) {
    const float a = 1.5, d = 0.91, hdrMax = 8.0, midIn = 0.26, midOut = 0.32;
    const float b = (-pow(midIn,a) + pow(hdrMax,a)*midOut) /
                    ((pow(hdrMax,a*d)-pow(midIn,a*d))*midOut);
    const float c = (pow(hdrMax,a*d)*pow(midIn,a)-pow(hdrMax,a)*pow(midIn,a*d)*midOut) /
                    ((pow(hdrMax,a*d)-pow(midIn,a*d))*midOut);
    return pow(x, vec3(a)) / (pow(x, vec3(a*d))*b + c);
}

vec3 gainCurve(vec3 x, float k) {
    vec3 a = 0.5 * pow(2.0 * mix(x, 1.0-x, step(0.5, x)), vec3(k));
    return mix(a, 1.0-a, step(0.5, x));
}

vec3 linearToSrgb(vec3 x) {
    return 1.14374 * (-0.126893 * x + sqrt(max(x, 0.0)));
}

vec3 rgbToHsl(vec3 c) {
    const vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    vec4 p = mix(vec4(c.bg,K.wz), vec4(c.gb,K.xy), step(c.b,c.g));
    vec4 q = mix(vec4(p.xyw,c.r), vec4(c.r,p.yzx), step(p.x,c.r));
    float d = q.x-min(q.w,q.y);
    return vec3(abs(q.z+(q.w-q.y)/(6.0*d+1e-6)), d/(q.x+1e-6), q.x);
}

vec3 hslToRgb(vec3 c) {
    const vec4 K = vec4(1.0,2.0/3.0,1.0/3.0,3.0);
    c.yz = clamp(c.yz,0.0,1.0);
    vec3 p = abs(fract(c.xxx+K.xyz)*6.0-K.www);
    return c.z*mix(K.xxx,clamp(p-K.xxx,0.0,1.0),c.y);
}

float huePulse(float hue, float center, float width) {
    float x = fract((hue*360.0-center+180.0)/360.0)*360.0-180.0;
    x = abs(x)/width;
    if (x > 1.0) return 0.0;
    float smoothed = x*x*(3.0-2.0*x);
    return 1.0-smoothed;
}

void main() {
    vec3 c = max(texture(u_Scene, v_uv).rgb * (0.83 * egcs.x), 0.0);
    const float midpoint = log2(0.18);
    c = max(exp2(1.0 * (log2(c + 1e-6) - midpoint) + midpoint) - 1e-6, 0.0);
    c = max(mix(vec3(dot(c, LUMA_2020)), c, 0.98), 0.0);
    c = lottes(c);
    c = clamp(c * REC2020_TO_XYZ * XYZ_TO_REC709, 0.0, 1.0);
    c = sqrt(c);
    vec3 hsl = rgbToHsl(c);
    float teal = hsl.y < 1e-2 || hsl.z < 1e-2 ? 0.0 : huePulse(hsl.x, 210.0, 20.0);
    hsl.y *= 1.0 + 0.10 * teal;
    c = hslToRgb(hsl);
    c = gainCurve(c, 1.05);
    c *= c;
    fragColor = vec4(linearToSrgb(clamp(c, 0.0, 1.0)), 1.0);
}
