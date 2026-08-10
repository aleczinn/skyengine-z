#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Input;
uniform vec2 u_Direction;

void main() {
    vec3 c = texture(u_Input, v_uv).rgb * 0.2734375;
    c += (texture(u_Input, v_uv + u_Direction).rgb + texture(u_Input, v_uv - u_Direction).rgb) * 0.21875;
    c += (texture(u_Input, v_uv + 2.0*u_Direction).rgb + texture(u_Input, v_uv - 2.0*u_Direction).rgb) * 0.109375;
    c += (texture(u_Input, v_uv + 3.0*u_Direction).rgb + texture(u_Input, v_uv - 3.0*u_Direction).rgb) * 0.03125;
    c += (texture(u_Input, v_uv + 4.0*u_Direction).rgb + texture(u_Input, v_uv - 4.0*u_Direction).rgb) * 0.00390625;
    fragColor = vec4(c, 1.0);
}
