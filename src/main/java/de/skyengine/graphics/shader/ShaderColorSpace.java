package de.skyengine.graphics.shader;

/** Shared GLSL colour transforms for renderers that feed the linear Rec.2020 HDR scene. */
public final class ShaderColorSpace {

    /** Photon-compatible sRGB transfer approximations and Rec.709/Rec.2020 matrices. */
    public static final String GLSL = """
            const mat3 SE_REC709_TO_XYZ = mat3(
                0.4124, 0.3576, 0.1805,
                0.2126, 0.7152, 0.0722,
                0.0193, 0.1192, 0.9505);
            const mat3 SE_XYZ_TO_REC2020 = mat3(
                 1.7166084, -0.3556621, -0.2533601,
                -0.6666829,  1.6164776,  0.0157685,
                 0.0176422, -0.0427763,  0.94222867);
            const mat3 SE_REC2020_TO_XYZ = mat3(
                0.6369736, 0.1446172, 0.1688585,
                0.2627066, 0.6779996, 0.0592938,
                0.0000000, 0.0280728, 1.0608437);
            const mat3 SE_XYZ_TO_REC709 = mat3(
                 3.2406, -1.5372, -0.4986,
                -0.9689,  1.8758,  0.0415,
                 0.0557, -0.2040,  1.0570);

            vec3 seSrgbToWorking(vec3 srgb) {
                vec3 linear = srgb * (srgb * (srgb * 0.305306011 + 0.682171111) + 0.012522878);
                return linear * SE_REC709_TO_XYZ * SE_XYZ_TO_REC2020;
            }

            vec3 seWorkingToSrgb(vec3 working) {
                vec3 linear = clamp(working * SE_REC2020_TO_XYZ * SE_XYZ_TO_REC709, 0.0, 1.0);
                return 1.14374 * (-0.126893 * linear + sqrt(linear));
            }
            """;

    private ShaderColorSpace() {}
}
