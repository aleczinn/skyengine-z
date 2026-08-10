#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Low;
uniform sampler2D u_Source;
uniform float u_AccumulatedLevels;
void main() {
    vec3 accumulated = texture(u_Low, v_uv).rgb;
    vec3 source = texture(u_Source, v_uv).rgb;
    fragColor = vec4((accumulated * u_AccumulatedLevels + source)
                   / (u_AccumulatedLevels + 1.0), 1.0);
}
