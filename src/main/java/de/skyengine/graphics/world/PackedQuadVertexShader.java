package de.skyengine.graphics.world;

/**
 * Vertex-Pulling-Shader fuer das 24-Byte-Nahformat. Es existiert absichtlich kein Vertex-
 * Attribut: {@code gl_VertexID / 4} adressiert das Quad im flachen uint-SSBO, die unteren zwei
 * uint sind das 8-Byte-Basisquad und die folgenden vier uint der optionale Shadingblock.
 * Ein flaches uint-Array vermeidet das 32-Byte-Alignment, das eine std430-Struct aus uvec2 und
 * uvec4 sonst erzwingen wuerde.
 */
public final class PackedQuadVertexShader {

    public static final int UINTS_PER_SHADED_QUAD = 6;
    public static final int SHADED_QUAD_BYTES = UINTS_PER_SHADED_QUAD * Integer.BYTES;
    public static final int MATERIAL_UINTS = 4;

    private static final String TEMPLATE = """
            #version 460 core
            #define SHADED_FORMAT 1

            layout(std430, binding = 0) readonly buffer DrawOffsets {
                vec4 u_DrawOffsets[];
            };
            layout(std430, binding = 1) readonly buffer PackedQuads {
                uint u_QuadData[];
            };
            /* Vier uint je Material: textureLayer, tintRGB, metadata, reserviert. */
            layout(std430, binding = 2) readonly buffer QuadMaterials {
                uint u_Materials[];
            };

            uniform mat4 u_ProjectionView;

            out vec3 v_texCoord;
            out vec3 v_color;
            out vec2 v_light;
            out float v_viewDist;
            out vec3 v_relPos;
            out vec2 v_debugLocalXZ;
            flat out uint v_denseAlpha;
            flat out uint v_debugLevel;
            flat out uint v_debugConflictMask;
            flat out uint v_gpuCullDebug;

            uint shade6(uvec4 shade, uint bit) {
                uint word = bit >> 5u;
                uint shift = bit & 31u;
                uint value = shade[word] >> shift;
                if (shift > 26u && word < 3u) value |= shade[word + 1u] << (32u - shift);
                return value & 63u;
            }

            void main() {
                uint quad = uint(gl_VertexID) >> 2u;
                uint inputCorner = uint(gl_VertexID) & 3u;
                uint base = quad * (SHADED_FORMAT != 0 ? 6u : 2u);
                uint geometry = u_QuadData[base];
                uint attributes = u_QuadData[base + 1u];
                uvec4 shade = uvec4(0u);
                if (SHADED_FORMAT != 0) {
                    shade = uvec4(u_QuadData[base + 2u], u_QuadData[base + 3u],
                                  u_QuadData[base + 4u], u_QuadData[base + 5u]);
                }

                uint axis = (geometry >> 15u) & 3u;
                bool positive = ((geometry >> 17u) & 1u) != 0u;
                uint width = ((geometry >> 18u) & 31u) + 1u;
                uint height = ((geometry >> 23u) & 31u) + 1u;
                uint transform = (geometry >> 28u) & 7u;
                bool diagonal = ((geometry >> 31u) & 1u) != 0u;
                uint corner = diagonal ? ((inputCorner + 1u) & 3u) : inputCorner;
                bool basePositive = axis != 1u;
                if (positive != basePositive && corner != 0u) corner = 4u - corner;
                float alongWidth = corner == 1u || corner == 2u ? 1.0 : 0.0;
                float alongHeight = corner >= 2u ? 1.0 : 0.0;

                vec3 pos = vec3(float(geometry & 31u), float((geometry >> 5u) & 31u),
                                float((geometry >> 10u) & 31u));
                if (positive) pos[axis] += 1.0;
                if (axis == 0u) { pos.y += alongWidth * float(width); pos.z += alongHeight * float(height); }
                else if (axis == 1u) { pos.x += alongWidth * float(width); pos.z += alongHeight * float(height); }
                else { pos.x += alongWidth * float(width); pos.y += alongHeight * float(height); }

                vec2 st = vec2(alongWidth, alongHeight);
                if ((transform & 4u) != 0u) st.x = 1.0 - st.x;
                vec2 uv;
                switch (transform & 3u) {
                    case 1u: uv = vec2(st.y, 1.0 - st.x); break;
                    case 2u: uv = vec2(1.0 - st.x, 1.0 - st.y); break;
                    case 3u: uv = vec2(1.0 - st.y, st.x); break;
                    default: uv = st; break;
                }
                uv *= (transform & 1u) != 0u ? vec2(float(height), float(width))
                                             : vec2(float(width), float(height));

                uint materialId = attributes & 0xFFFFu;
                uint materialBase = materialId * 4u;
                uint materialMeta = u_Materials[materialBase + 2u];
                if (SHADED_FORMAT != 0) {
                    uint tint = shade.w >> 8u;
                    float ao = 0.4 + 0.2 * float((shade.w >> (corner * 2u)) & 3u);
                    v_color = vec3(float((tint >> 16u) & 255u), float((tint >> 8u) & 255u),
                                   float(tint & 255u)) * (ao / 255.0);
                    uint lightBit = corner * 24u;
                    float sky = float(shade6(shade, lightBit));
                    float red = float(shade6(shade, lightBit + 6u));
                    float green = float(shade6(shade, lightBit + 12u));
                    float blue = float(shade6(shade, lightBit + 18u));
                    v_light = vec2(sky, max(red, max(green, blue))) * (1.0 / 60.0);
                } else {
                    uint tint = u_Materials[materialBase + 1u];
                    float faceShade = float((materialMeta >> 8u) & 255u) * (1.0 / 255.0);
                    v_color = vec3(float((tint >> 16u) & 255u), float((tint >> 8u) & 255u),
                                   float(tint & 255u)) * (faceShade / 255.0);
                    v_light = vec2(1.0, 0.0);
                }
                v_texCoord = vec3(uv, float(u_Materials[materialBase]));
                v_denseAlpha = materialMeta & 1u;

                uint drawMetadata = uint(u_DrawOffsets[gl_DrawID].w + 0.5);
                v_debugLevel = drawMetadata & 7u;
                v_debugConflictMask = (drawMetadata >> 3u) & 0xFFFFu;
                v_gpuCullDebug = gl_BaseInstance != 0 ? 1u : 0u;
                v_debugLocalXZ = pos.xz;
                vec3 rel = pos + u_DrawOffsets[gl_DrawID].xyz;
                v_viewDist = length(rel.xz);
                v_relPos = rel;
                gl_Position = u_ProjectionView * vec4(rel, 1.0);
            }
            """;

    public static final String SHADED_SOURCE = TEMPLATE;
    public static final String BASE_SOURCE = TEMPLATE.replace("#define SHADED_FORMAT 1",
            "#define SHADED_FORMAT 0");

    private PackedQuadVertexShader() {}
}
