#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Scene;
uniform sampler2D u_Bloom;
uniform float u_Intensity;
void main() {
    fragColor = vec4(mix(texture(u_Scene, v_uv).rgb, texture(u_Bloom, v_uv).rgb,
                         0.12 * u_Intensity), 1.0);
}
