#version 460 core
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_Scene;
uniform sampler2D u_Bloom;
uniform float u_Intensity;
void main() {
    vec4 sceneSample = texture(u_Scene, v_uv);
    vec3 scene = sceneSample.rgb;
    vec3 bloom = texture(u_Bloom, v_uv).rgb;
    // Photon c14_color_grading.fsh: bloom replaces 12 % of the unblurred HDR signal at
    // intensity 1.0. Besides spreading energy this deliberately softens a hard solar disc.
    fragColor = vec4(mix(scene, bloom, 0.12 * u_Intensity), sceneSample.a);
}
