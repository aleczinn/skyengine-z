#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Low;
uniform sampler2D u_Source;
void main() {
    fragColor = vec4(mix(texture(u_Low, v_uv).rgb, texture(u_Source, v_uv).rgb, 0.575), 1.0);
}
