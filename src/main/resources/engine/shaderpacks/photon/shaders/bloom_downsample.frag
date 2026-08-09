#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Input;
uniform vec2 u_TexelSize;

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
    fragColor = vec4(c, 1.0);
}
