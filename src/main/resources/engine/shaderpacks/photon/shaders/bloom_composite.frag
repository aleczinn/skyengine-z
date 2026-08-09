#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Scene;
uniform sampler2D u_Bloom;
uniform float u_Intensity;
void main() {
    vec3 scene = texture(u_Scene, v_uv).rgb;
    vec3 bloom = texture(u_Bloom, v_uv).rgb;
    // Bloom is emitted light added to the HDR scene. Mixing towards the blurred image also
    // removed contrast from every non-emissive pixel and was the source of the milky veil.
    fragColor = vec4(scene + bloom * (0.06 * u_Intensity), 1.0);
}
