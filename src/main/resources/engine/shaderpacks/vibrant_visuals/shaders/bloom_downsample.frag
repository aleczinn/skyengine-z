#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Input;
uniform vec2 u_TexelSize;
uniform float u_Threshold;

void main() {
    vec2 d = u_TexelSize;
    vec3 c = texture(u_Input, v_uv).rgb * 0.125;
    c += texture(u_Input, v_uv + vec2( d.x,  d.y)).rgb * 0.125;
    c += texture(u_Input, v_uv + vec2(-d.x,  d.y)).rgb * 0.125;
    c += texture(u_Input, v_uv + vec2( d.x, -d.y)).rgb * 0.125;
    c += texture(u_Input, v_uv + vec2(-d.x, -d.y)).rgb * 0.125;
    c += texture(u_Input, v_uv + vec2( 2.0*d.x, 0.0)).rgb * 0.0625;
    c += texture(u_Input, v_uv + vec2(-2.0*d.x, 0.0)).rgb * 0.0625;
    c += texture(u_Input, v_uv + vec2(0.0,  2.0*d.y)).rgb * 0.0625;
    c += texture(u_Input, v_uv + vec2(0.0, -2.0*d.y)).rgb * 0.0625;
    c += texture(u_Input, v_uv + vec2( 2.0*d.x,  2.0*d.y)).rgb * 0.03125;
    c += texture(u_Input, v_uv + vec2(-2.0*d.x,  2.0*d.y)).rgb * 0.03125;
    c += texture(u_Input, v_uv + vec2( 2.0*d.x, -2.0*d.y)).rgb * 0.03125;
    c += texture(u_Input, v_uv + vec2(-2.0*d.x, -2.0*d.y)).rgb * 0.03125;

    // Luminance-preserving HDR prefilter. u_Threshold is zero on subsequent mip levels,
    // therefore highlights are selected exactly once and remain stable while downsampling.
    // Rec.2020 luminance keeps a saturated red block from blooming merely because its
    // red channel is high. Only actual HDR luminance enters the bloom pyramid.
    float luminance = dot(c, vec3(0.2627, 0.6780, 0.0593));
    float contribution = max(luminance - u_Threshold, 0.0) / max(luminance, 1e-5);
    c *= contribution;
    fragColor = vec4(c, 1.0);
}
