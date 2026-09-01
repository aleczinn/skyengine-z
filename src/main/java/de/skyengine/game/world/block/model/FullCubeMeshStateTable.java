package de.skyengine.game.world.block.model;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.StateFlags;

import java.util.Arrays;

/**
 * Immutable, registry-sized lookup data for the compact full-cube mesher.
 *
 * <p>The expensive model validation runs when block visuals are baked or reloaded. Mesher
 * workers only read primitive flags while classifying cells and dereference a {@link FaceSet}
 * after a face survived visibility culling.</p>
 */
public final class FullCubeMeshStateTable {

    public static final int GREEDY = 1;
    public static final int CULL_SAME_EXCEPTION = 1 << 1;
    public static final int LEAVES_EXCEPTION = 1 << 2;
    public static final int ALL_FACES = 0x3F;

    private static final int[] UNIQUE_VERTS = {0, 1, 2, 4};
    private static final int[] AXIS_N = {1, 1, 2, 2, 0, 0};
    private static final int[] AXIS_T1 = {0, 0, 0, 0, 1, 1};
    private static final int[] AXIS_T2 = {2, 2, 1, 1, 2, 2};

    private static volatile Snapshot current = Snapshot.EMPTY;
    private static volatile Snapshot legacyReference = Snapshot.EMPTY;

    public static final class FaceSet {
        public final BlockState state;
        public final BakedQuad[] quads;
        public final boolean[] uAlongT1;
        public final byte[] uvTransform;
        public final int[] materialHandles;
        public final byte[] compositeKinds;
        public final BakedQuad[] overlays;

        private FaceSet(BlockState state, BakedQuad[] quads, boolean[] uAlongT1,
                        byte[] uvTransform, int[] materialHandles, byte[] compositeKinds,
                        BakedQuad[] overlays) {
            this.state = state;
            this.quads = quads;
            this.uAlongT1 = uAlongT1;
            this.uvTransform = uvTransform;
            this.materialHandles = materialHandles;
            this.compositeKinds = compositeKinds;
            this.overlays = overlays;
        }
    }

    public static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(new byte[0], new byte[0],
                new boolean[0], new FaceSet[0]);

        /** GREEDY and the exceptional scalar-culling flags, indexed by runtime state ID. */
        public final byte[] meshFlags;
        /** Six-bit face-occlusion mask. Current engine semantics are either zero or all faces. */
        public final byte[] faceOcclusionMask;
        /** AO occlusion is deliberately independent from face culling. */
        public final boolean[] aoOccluder;
        /** Null for states that remain in the generic model path. */
        public final FaceSet[] faceSets;

        private Snapshot(byte[] meshFlags, byte[] faceOcclusionMask,
                         boolean[] aoOccluder, FaceSet[] faceSets) {
            this.meshFlags = meshFlags;
            this.faceOcclusionMask = faceOcclusionMask;
            this.aoOccluder = aoOccluder;
            this.faceSets = faceSets;
        }
    }

    public static Snapshot current() {
        return current;
    }

    /** Benchmark-only snapshot that preserves the former base-plus-coplanar-overlay geometry. */
    public static Snapshot current(boolean legacyOverlayReference) {
        return legacyOverlayReference ? legacyReference : current;
    }

    /** Rebuild after every model/overlay bake. Publication is one volatile snapshot swap. */
    public static void rebuildFromRegistry() {
        int count = BlockRegistry.getStateCount();
        byte[] meshFlags = new byte[count];
        byte[] faceOcclusion = new byte[count];
        boolean[] aoOccluder = new boolean[count];
        FaceSet[] faceSets = new FaceSet[count];
        FaceSet[] legacyFaceSets = new FaceSet[count];

        for (int id = 0; id < count; id++) {
            BlockState state = BlockRegistry.getState(id);
            int stateFlags = state.getFlags();
            if ((stateFlags & StateFlags.OPAQUE_CUBE) != 0) {
                faceOcclusion[id] = (byte) ALL_FACES;
            }
            aoOccluder[id] = (stateFlags & StateFlags.AO_OCCLUDER) != 0;

            FaceSet[] variants = buildFaceSets(state);
            if (variants == null) continue;
            int flags = GREEDY;
            if ((stateFlags & StateFlags.CULL_SAME) != 0) flags |= CULL_SAME_EXCEPTION;
            if ((stateFlags & StateFlags.LEAVES) != 0) flags |= LEAVES_EXCEPTION;
            meshFlags[id] = (byte) flags;
            faceSets[id] = variants[0];
            legacyFaceSets[id] = variants[1];
        }
        current = new Snapshot(meshFlags, faceOcclusion, aoOccluder, faceSets);
        legacyReference = new Snapshot(meshFlags, faceOcclusion, aoOccluder, legacyFaceSets);
    }

    private static FaceSet[] buildFaceSets(BlockState state) {
        if (!state.isOpaqueCube() || state.isFluid() || state.hasRandomOffset()
                || state.getRenderLayer() != RenderLayer.OPAQUE) return null;
        BakedQuad[] model = state.getModel();
        if (model == null || model.length != 6) return null;

        BakedQuad[] quads = new BakedQuad[6];
        for (BakedQuad quad : model) {
            int face = quad.cullFace();
            if (face < 0 || face >= 6 || quads[face] != null) return null;
            quads[face] = quad;
        }

        boolean[] uAlongT1 = new boolean[6];
        byte[] uvTransform = new byte[6];
        for (int face = 0; face < 6; face++) {
            BakedQuad quad = quads[face];
            if (quad.brightness() != BlockModels.FACE_BRIGHTNESS[face]) return null;
            int axisN = AXIS_N[face], axisT1 = AXIS_T1[face], axisT2 = AXIS_T2[face];
            float plane = face == 0 || face == 3 || face == 5 ? 1F : 0F;
            float[] vertices = quad.vertices();
            int cornerMask = 0;
            float[] cu = new float[4], cv = new float[4];
            for (int cornerIndex = 0; cornerIndex < 4; cornerIndex++) {
                int i = UNIQUE_VERTS[cornerIndex] * 5;
                if (vertices[i + axisN] != plane) return null;
                float c1 = vertices[i + axisT1], c2 = vertices[i + axisT2];
                float u = vertices[i + 3], v = vertices[i + 4];
                if ((c1 != 0F && c1 != 1F) || (c2 != 0F && c2 != 1F)) return null;
                if ((u != 0F && u != 1F) || (v != 0F && v != 1F)) return null;
                int corner = (c1 == 1F ? 1 : 0) | (c2 == 1F ? 2 : 0);
                cornerMask |= 1 << corner;
                cu[corner] = u;
                cv[corner] = v;
            }
            if (cornerMask != 0b1111) return null;

            boolean uT1 = cu[0] != cu[1];
            if (uT1) {
                if (cu[2] != cu[0] || cu[3] != cu[1] || cv[0] != cv[1]
                        || cv[2] != cv[3] || cv[0] == cv[2]) return null;
            } else if (cu[0] != cu[1] || cu[2] != cu[3] || cu[0] == cu[2]
                    || cv[0] != cv[2] || cv[1] != cv[3] || cv[0] == cv[1]) return null;
            uAlongT1[face] = uT1;
            int transform = findUvTransform(cu, cv);
            if (transform < 0) return null;
            uvTransform[face] = (byte) transform;
        }

        BakedQuad[] originalOverlays = null;
        for (BakedQuad quad : state.getOverlay()) {
            int face = quad.cullFace();
            if (face < 0 || face >= 6) return null;
            if (originalOverlays == null) originalOverlays = new BakedQuad[6];
            if (originalOverlays[face] != null) return null;
            originalOverlays[face] = quad;
        }

        int[] directHandles = new int[6];
        int[] compositeHandles = new int[6];
        byte[] compositeKinds = new byte[6];
        BakedQuad[] unsupportedOverlays = null;
        for (int face = 0; face < 6; face++) {
            BakedQuad base = quads[face];
            int direct = CompactCompositeMaterialTable.directHandle(base.textureLayer());
            directHandles[face] = direct;
            compositeHandles[face] = direct;
            BakedQuad overlay = originalOverlays == null ? null : originalOverlays[face];
            if (overlay == null) continue;
            if (!compatibleOverlay(base, overlay)) {
                if (unsupportedOverlays == null) unsupportedOverlays = new BakedQuad[6];
                unsupportedOverlays[face] = overlay;
                continue;
            }
            compositeHandles[face] = CompactCompositeMaterialTable.intern(
                    new CompactCompositeMaterialTable.Entry(base.textureLayer(), overlay.textureLayer(),
                            overlay.tintType(), overlay.tint(),
                            CompactCompositeMaterialTable.MODE_CUTOUT_REPLACE));
            if (overlay.tintType() == BakedQuad.TINT_GRASS) {
                compositeKinds[face] = CompactCompositeMaterialTable.KIND_GRASS;
            }
        }

        FaceSet composite = new FaceSet(state, quads, uAlongT1, uvTransform,
                compositeHandles, compositeKinds, unsupportedOverlays);
        FaceSet legacy = new FaceSet(state, quads, uAlongT1, uvTransform,
                directHandles, new byte[6], originalOverlays);
        return new FaceSet[] {composite, legacy};
    }

    private static boolean compatibleOverlay(BakedQuad base, BakedQuad overlay) {
        return base.cullFace() == overlay.cullFace()
                && base.face() == overlay.face()
                && base.brightness() == overlay.brightness()
                && Arrays.equals(base.vertices(), overlay.vertices())
                && overlay.tintType() >= BakedQuad.TINT_NONE
                && overlay.tintType() <= BakedQuad.TINT_FOLIAGE
                && overlay.textureLayer() >= 0
                && overlay.textureLayer() <= CompactCompositeMaterialTable.INDEX_MASK;
    }

    private static int findUvTransform(float[] u, float[] v) {
        for (int transform = 0; transform < 8; transform++) {
            boolean matches = true;
            for (int t = 0; t <= 1 && matches; t++) for (int s = 0; s <= 1; s++) {
                int corner = s | t << 1;
                float expectedU = switch (transform) {
                    case 0 -> s; case 1 -> 1 - s; case 2 -> s; case 3 -> 1 - s;
                    case 4 -> t; case 5 -> 1 - t; case 6 -> t; default -> 1 - t;
                };
                float expectedV = switch (transform) {
                    case 0 -> t; case 1 -> t; case 2 -> 1 - t; case 3 -> 1 - t;
                    case 4 -> s; case 5 -> s; case 6 -> 1 - s; default -> 1 - s;
                };
                if (u[corner] != expectedU || v[corner] != expectedV) {
                    matches = false;
                    break;
                }
            }
            if (matches) return transform;
        }
        return -1;
    }

    private FullCubeMeshStateTable() {}
}
