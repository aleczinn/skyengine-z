package de.skyengine.graphics.world;

import de.skyengine.core.EngineProperties;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.dimension.DimensionEnvironment;
import de.skyengine.game.world.lod.LodMaterialTable;
import de.skyengine.game.world.lod.LodManager;
import de.skyengine.game.world.lod.LodVolumeHierarchy;
import de.skyengine.game.world.lod.LodVoxelSection;
import de.skyengine.game.world.lod.VoxelLodMesher;
import de.skyengine.graphics.GlDebug;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Experimenteller produktiver Renderer der 32³-Volumenhierarchie. */
final class VolumetricLodRenderer {

    private static final int SLOTS = 3;
    private static final int COMMAND_BYTES = 16; // DrawArraysIndirectCommand
    private static final int OFFSET_BYTES = 16;
    private static final int MAX_UPLOADS_PER_FRAME = 4;
    private static final int MAX_REQUESTS_PER_FRAME = 4;
    private static final long EVICT_AFTER_FRAMES = 600;
    private final Logger logger = LogManager.getLogger(VolumetricLodRenderer.class.getName());

    private static final class NodeMesh {
        final LodVolumeHierarchy.Key key;
        final PackedQuadArena.Region[] regions = new PackedQuadArena.Region[RenderLayer.VALUES.length];
        long sourceRevision;
        long nextRevisionCheckFrame;
        long lastReferencedFrame;

        NodeMesh(LodVolumeHierarchy.Key key, long frame) {
            this.key = key;
            this.lastReferencedFrame = frame;
            this.nextRevisionCheckFrame = frame + 1 + (key.hashCode() & 31);
        }
        boolean has(RenderLayer layer) { return this.regions[layer.ordinal()] != null; }
        boolean hasGeometry() {
            for (PackedQuadArena.Region region : this.regions) if (region != null) return true;
            return false;
        }
    }

    private final LodManager manager;
    private final LodMaterialTable materials;
    private final int materialBuffer;
    private final BlockTextureAtlas atlas;
    private final PackedQuadArena[] arenas = new PackedQuadArena[RenderLayer.VALUES.length];
    private final Map<LodVolumeHierarchy.Key, NodeMesh> meshes = new HashMap<>();
    private final List<NodeMesh> selected = new ArrayList<>();
    private final Set<LodVolumeHierarchy.Key> selectedKeys = new HashSet<>();
    private final MappedRing commands = new MappedRing("Volumetric LOD Commands", SLOTS, 4096);
    private final MappedRing offsets = new MappedRing("Volumetric LOD Offsets", SLOTS, 4096);
    private final ShaderProgram shader;
    private final int vao;
    private final int locProjectionView, locAlphaCutoff, locFogStart, locFogEnd, locFogColor,
            locMinLight, locBrightness, locLodLevelColors, locPbrEnabled;
    private DimensionEnvironment environment;
    private long frameId;
    private int frameSlot;
    private long translucentCommandOffset, translucentOffsetOffset;
    private int translucentDraws;
    private int requestsThisFrame;
    private int observedEpoch = Integer.MIN_VALUE;
    private long lastGpuBudgetWarningFrame = Long.MIN_VALUE;
    private Set<LodVolumeHierarchy.Key> publishedSelection = Set.of();
    private int visibleRootCount, missingRootCount, coverageMissingRootCount;
    private int missingChildrenThisFrame;
    private long contentRefreshRequests;
    private long staleUploadResults;
    private long retainedRootFrontiers;
    private final Set<LodVolumeHierarchy.Key> splitNodes = new HashSet<>();
    private FrustumIntersection drawFrustum;

    VolumetricLodRenderer(LodManager manager, LodMaterialTable materials, int materialBuffer,
                          BlockTextureAtlas atlas, DimensionEnvironment environment) {
        this.manager = manager;
        this.materials = materials;
        this.materialBuffer = materialBuffer;
        this.atlas = atlas;
        this.environment = environment;
        this.arenas[RenderLayer.OPAQUE.ordinal()] = new PackedQuadArena(
                "Volume OPAQUE", 1, 32L << 20, 128L << 20);
        this.arenas[RenderLayer.CUTOUT.ordinal()] = new PackedQuadArena(
                "Volume CUTOUT", 1, 16L << 20, 96L << 20);
        this.arenas[RenderLayer.TRANSLUCENT.ordinal()] = new PackedQuadArena(
                "Volume TRANSLUCENT", 1, 8L << 20, 32L << 20);

        String fragment = ChunkRenderer.FRAGMENT_SOURCE
                .replace("flat in uint v_gpuCullDebug;",
                        "flat in uint v_gpuCullDebug;\nflat in uint v_denseAlpha;")
                .replace("if (color.a < u_AlphaCutoff) discard;",
                        "if (v_denseAlpha == 0u && color.a < u_AlphaCutoff) discard;\n"
                                + "    if (v_denseAlpha != 0u) color.a = 1.0;")
                ;
        this.shader = new ShaderProgram(new Shader(PackedQuadVertexShader.BASE_SOURCE, ShaderType.VERTEX),
                new Shader(fragment, ShaderType.FRAGMENT));
        this.locProjectionView = this.shader.getUniformLocation("u_ProjectionView");
        this.locAlphaCutoff = this.shader.getUniformLocation("u_AlphaCutoff");
        this.locFogStart = this.shader.getUniformLocation("u_FogStart");
        this.locFogEnd = this.shader.getUniformLocation("u_FogEnd");
        this.locFogColor = this.shader.getUniformLocation("u_FogColor");
        this.locMinLight = this.shader.getUniformLocation("u_MinLight");
        this.locBrightness = this.shader.getUniformLocation("u_Brightness");
        this.locLodLevelColors = this.shader.getUniformLocation("u_LodLevelColors");
        this.locPbrEnabled = this.shader.getUniformLocation("u_PbrEnabled");
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.setUniformi("u_NormalTextures", 1);
        this.shader.setUniformi("u_MaterialTextures", 2);
        this.shader.setUniformf("u_LodMask", 1F);
        this.shader.unbind();
        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);
        GL30.glBindVertexArray(0);
        GlDebug.labelVertexArray(this.vao, "Volumetric LOD Vertex Pulling");
    }

    void setEnvironment(DimensionEnvironment environment) {
        this.environment = environment == null ? DimensionEnvironment.OVERWORLD : environment;
    }

    /**
     * Uebernimmt Resultate und publiziert die LOD-Auswahl vor dem L0-Culling desselben Frames.
     * Das verhindert, dass GPU-Gates und gezeichnetes LOD mit zwei verschiedenen Frontiers
     * arbeiten.
     */
    void prepare(Camera camera, long frameId) {
        this.frameId = frameId;
        this.frameSlot = (int) (frameId % SLOTS);
        int epoch = this.manager.volumeEpoch();
        if (epoch != this.observedEpoch) {
            this.observedEpoch = epoch;
            this.manager.resetVolumetricCoverage();
            for (NodeMesh mesh : this.meshes.values()) this.free(mesh);
            this.meshes.clear();
            this.selected.clear();
            this.selectedKeys.clear();
            this.splitNodes.clear();
        }
        /* Der ChunkRenderer hat den zu diesem Slot gehoerenden Fence bereits abgewartet. Frees
           muessen VOR neuen Uploads verfuegbar werden, sonst waechst die Arena trotz freiem
           Speicher oder meldet faelschlich Budgetmangel. */
        for (PackedQuadArena arena : this.arenas) arena.collect(frameId - SLOTS);
        this.collectResults();
        this.select(camera);

        if ((frameId & 2047L) == 0L) this.logMemoryStats();
    }

    void renderSolid(Camera camera) {
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.LOD_OPAQUE);
        int opaque = count(RenderLayer.OPAQUE), cutout = count(RenderLayer.CUTOUT);
        this.translucentDraws = count(RenderLayer.TRANSLUCENT);
        long commandCapacity = MappedRing.align((long) opaque * COMMAND_BYTES)
                + MappedRing.align((long) cutout * COMMAND_BYTES)
                + MappedRing.align((long) this.translucentDraws * COMMAND_BYTES);
        long offsetCapacity = MappedRing.align((long) opaque * OFFSET_BYTES)
                + MappedRing.align((long) cutout * OFFSET_BYTES)
                + MappedRing.align((long) this.translucentDraws * OFFSET_BYTES);
        this.commands.ensureSlotCapacity(Math.max(256, commandCapacity));
        this.offsets.ensureSlotCapacity(Math.max(256, offsetCapacity));
        IntBuffer commandData = this.commands.intView(this.frameSlot);
        FloatBuffer offsetData = this.offsets.floatView(this.frameSlot);
        Vector3d cam = camera.getPosition();
        long cmdOpaque = 0, offOpaque = 0;
        int nOpaque = write(RenderLayer.OPAQUE, commandData, offsetData, cmdOpaque, offOpaque, cam);
        long cmdCutout = MappedRing.align((long) nOpaque * COMMAND_BYTES);
        long offCutout = MappedRing.align((long) nOpaque * OFFSET_BYTES);
        int nCutout = write(RenderLayer.CUTOUT, commandData, offsetData, cmdCutout, offCutout, cam);
        this.translucentCommandOffset = cmdCutout + MappedRing.align((long) nCutout * COMMAND_BYTES);
        this.translucentOffsetOffset = offCutout + MappedRing.align((long) nCutout * OFFSET_BYTES);
        this.translucentDraws = write(RenderLayer.TRANSLUCENT, commandData, offsetData,
                this.translucentCommandOffset, this.translucentOffsetOffset, cam);

        this.bind(camera);
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(0F, properties.isUseInverseDepth() ? -8F : 8F);
        this.shader.setUniformf(this.locAlphaCutoff, 0.5F);
        draw(RenderLayer.OPAQUE, cmdOpaque, offOpaque, nOpaque);
        GL11.glDepthFunc(properties.orEqualDepthFunc());
        draw(RenderLayer.CUTOUT, cmdCutout, offCutout, nCutout);
        GL11.glDepthFunc(properties.baseDepthFunc());
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        this.shader.unbind();
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.LOD_OPAQUE);
    }

    void renderTranslucent(Camera camera) {
        if (this.translucentDraws == 0) return;
        this.bind(camera);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.LOD_TRANSLUCENT);
        this.shader.setUniformf(this.locAlphaCutoff, 0.001F);
        EngineProperties properties = SkyEngine.get().getWindow().getProperties();
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(0F, properties.isUseInverseDepth() ? -8F : 8F);
        GL11.glEnable(GL11.GL_BLEND);
        GL30.glDisablei(GL11.GL_BLEND, 1);
        draw(RenderLayer.TRANSLUCENT, this.translucentCommandOffset,
                this.translucentOffsetOffset, this.translucentDraws);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        this.shader.unbind();
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.LOD_TRANSLUCENT);
    }

    int meshCount() {
        return this.meshes.size();
    }

    void deactivate() {
        this.selected.clear();
        this.selectedKeys.clear();
        this.splitNodes.clear();
        this.translucentDraws = 0;
        if (!this.publishedSelection.isEmpty()) {
            this.manager.setVisibleVolumeNodes(java.util.List.of());
        }
        this.publishedSelection = Set.of();
        this.manager.resetVolumetricCoverage();
    }

    private void collectResults() {
        for (int n = 0; n < MAX_UPLOADS_PER_FRAME; n++) {
            LodManager.VolumeMeshResult result = this.manager.pollVolumeResult();
            if (result == null) break;
            NodeMesh currentMesh = this.meshes.get(result.key());
            if (!this.manager.isVolumeResultCurrent(result)) {
                this.staleUploadResults++;
                if (currentMesh != null) currentMesh.nextRevisionCheckFrame = this.frameId;
                continue;
            }
            NodeMesh replacement = new NodeMesh(result.key(), this.frameId);
            replacement.sourceRevision = result.sourceRevision();
            boolean uploaded = upload(replacement, RenderLayer.OPAQUE, result.mesh().opaque())
                    && upload(replacement, RenderLayer.CUTOUT, result.mesh().cutout())
                    && upload(replacement, RenderLayer.TRANSLUCENT, result.mesh().translucent());
            if (!uploaded) {
                this.free(replacement);
                this.evictOldestUnselected();
                if (this.frameId - this.lastGpuBudgetWarningFrame >= 120) {
                    this.lastGpuBudgetWarningFrame = this.frameId;
                    this.logger.warning("Volumen-LOD-GPU-Budget erreicht; einzelnes Mesh verworfen");
                }
                continue;
            }
            /* Ein Live-Snapshot kann auch waehrend der Uploads eintreffen. In dem Fall bleibt
               das alte Mesh sichtbar und die Revision wird sofort neu angefragt. */
            if (!this.manager.isVolumeResultCurrent(result)) {
                this.staleUploadResults++;
                this.free(replacement);
                if (currentMesh != null) currentMesh.nextRevisionCheckFrame = this.frameId;
                continue;
            }
            NodeMesh old = this.meshes.put(result.key(), replacement);
            if (old != null) this.free(old);
        }
    }

    /** Gibt bei Budgetdruck zuerst einen nicht mehr ausgewaehlten Detailknoten frei. Der
        aktuelle sichtbare Parent-/Kind-Frontier bleibt unangetastet; nach Ablauf der drei
        Fence-Frames steht der Platz fuer den automatisch erneut angefragten Build bereit. */
    private void evictOldestUnselected() {
        NodeMesh oldest = null;
        for (NodeMesh candidate : this.meshes.values()) {
            if (this.selected.contains(candidate)) continue;
            if (oldest == null || candidate.lastReferencedFrame < oldest.lastReferencedFrame) {
                oldest = candidate;
            }
        }
        if (oldest != null && this.meshes.remove(oldest.key, oldest)) this.free(oldest);
    }

    private boolean upload(NodeMesh mesh, RenderLayer layer, long[] data) {
        if (data.length == 0) return true;
        PackedQuadArena.Region region = this.arenas[layer.ordinal()].tryAlloc(data);
        if (region == null) return false;
        mesh.regions[layer.ordinal()] = region;
        return true;
    }

    private void select(Camera camera) {
        Set<LodVolumeHierarchy.Key> previousSelection = Set.copyOf(this.selectedKeys);
        this.selected.clear();
        this.selectedKeys.clear();
        this.requestsThisFrame = 0;
        this.missingChildrenThisFrame = 0;
        GameSettings settings = GameSettings.get();
        Vector3d cam = camera.getPosition();
        this.drawFrustum = camera.getFrustum();
        double outer = settings.lodMaxDistance * (double) ChunkSection.SIZE;
        int rootLevel = LodVoxelSection.MAX_LEVEL;
        int rootExtent = LodVoxelSection.SIZE << rootLevel;
        int minX = Math.floorDiv((int) Math.floor(cam.x - outer), rootExtent);
        int maxX = Math.floorDiv((int) Math.floor(cam.x + outer), rootExtent);
        int minZ = Math.floorDiv((int) Math.floor(cam.z - outer), rootExtent);
        int maxZ = Math.floorDiv((int) Math.floor(cam.z + outer), rootExtent);
        List<LodVolumeHierarchy.Key> roots = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            double cx = (x + 0.5) * rootExtent - cam.x;
            double cz = (z + 0.5) * rootExtent - cam.z;
            if (cx * cx + cz * cz > (outer + rootExtent) * (outer + rootExtent)) continue;
            LodVolumeHierarchy.Key root = new LodVolumeHierarchy.Key(x, 0, z, rootLevel);
            double ox = (double) x * rootExtent - cam.x;
            double oz = (double) z * rootExtent - cam.z;
            roots.add(root);
        }
        roots.sort(java.util.Comparator.comparingDouble(root -> distanceSq(root, cam)));
        boolean rootsReady = true;
        boolean coverageReady = true;
        double nearestMissing = outer;
        int missingRoots = 0;
        int coverageMissingRoots = 0;
        List<LodVolumeHierarchy.Key> warmRoots = new ArrayList<>();
        for (LodVolumeHierarchy.Key root : roots) {
            NodeMesh rootMesh = this.meshes.get(root);
            boolean fullyOwned = this.manager.fullyL0Owned(root);
            if (rootMesh == null && !fullyOwned) {
                rootsReady = false;
                missingRoots++;
            }
            /* Fuer den Bootstrap zaehlt das Mesh ODER ein noch tatsaechlich residentes L0.
               pendingUnload aendert die gewuenschte Ownership schon vor dem atomaren Swap,
               darf die globale Nebelgrenze aber nicht fuer einen Frame nach innen ziehen. */
            if (rootMesh == null && !this.manager.fullyL0Resident(root)) {
                coverageReady = false;
                coverageMissingRoots++;
                nearestMissing = Math.min(nearestMissing, horizontalAabbDistance(root, cam));
            }
            if (fullyOwned) {
                if (rootMesh == null) warmRoots.add(root);
                else rootMesh.lastReferencedFrame = this.frameId;
            }
        }
        this.visibleRootCount = roots.size();
        this.missingRootCount = missingRoots;
        this.coverageMissingRootCount = coverageMissingRoots;
        /* Fehlende Roots behalten die erste Requestchance. Der separate Root-Slot verhindert,
           dass sie kanonische Verfeinerung oder Live-Snapshots vollstaendig verdraengen. */
        if (!rootsReady) {
            for (LodVolumeHierarchy.Key root : roots) {
                if (!this.manager.fullyL0Owned(root)
                        && !this.meshes.containsKey(root) && request(root, distanceSq(root, cam))) break;
            }
        }
        for (LodVolumeHierarchy.Key root : roots) {
            if (!visit(root, camera, true)) {
                int restored = restorePreviousRoot(root, previousSelection);
                if (restored > 0) this.retainedRootFrontiers++;
            }
        }

        /* Erst nach allen sichtbaren Requests genau einen verdeckten Root mit bewusst
           schlechtester Worker-Prioritaet vorwaermen. So ist beim spaeteren L0-Unload bereits
           ein GPU-Fallback vorhanden, ohne den initialen Sichtring auszubremsen. */
        if (rootsReady && this.requestsThisFrame < MAX_REQUESTS_PER_FRAME) {
            for (LodVolumeHierarchy.Key root : warmRoots) {
                if (requestBackground(root, distanceSq(root, cam))) break;
            }
        }

        float safeFloor = settings.renderDistance * (float) ChunkSection.SIZE;
        float candidateEnd = (float) (coverageReady ? outer
                : Math.clamp(nearestMissing, safeFloor, outer));
        this.manager.advanceVolumetricCoverage(safeFloor, candidateEnd, coverageReady);
        this.publishSelectionIfChanged();
        Iterator<Map.Entry<LodVolumeHierarchy.Key, NodeMesh>> iterator = this.meshes.entrySet().iterator();
        while (iterator.hasNext()) {
            NodeMesh mesh = iterator.next().getValue();
            if (!shouldEvict(frameId, mesh.lastReferencedFrame)) continue;
            iterator.remove();
            this.splitNodes.remove(mesh.key);
            this.free(mesh);
        }
    }

    private boolean visit(LodVolumeHierarchy.Key key, Camera camera, boolean allowSplit) {
        if (this.manager.fullyL0Owned(key)) return true;
        Vector3d cam = camera.getPosition();
        NodeMesh parent = this.meshes.get(key);
        double distanceSq = distanceSq(key, camera.getPosition());
        if (parent == null) return false;
        if (this.frameId >= parent.nextRevisionCheckFrame) {
            parent.nextRevisionCheckFrame = this.frameId + 60;
            if (parent.sourceRevision != this.manager.volumeMeshRevision(key)) {
                /* Der alte Proxy bleibt bis zum erfolgreichen Upload sichtbar. */
                if (request(key, distanceSq)) this.contentRefreshRequests++;
            }
        }
        /* Leere oder von gleichstufigen Nachbarn vollstaendig eingeschlossene Knoten sind
           terminal. Insbesondere Luft und tiefer Vollstein erzeugen so keine achtfachen
           Kindanfragen bis L0. */
        boolean wasSplit = this.splitNodes.contains(key);
        boolean ownershipSplit = key.level() > 0 && this.manager.hasL0Ownership(key);
        double horizontalDistance = horizontalAabbDistance(key, cam);
        boolean distanceSplit = allowSplit && shouldSplitForDistance(wasSplit,
                parent.hasGeometry(), key.level(), horizontalDistance,
                GameSettings.get().renderDistance);
        /* Ownership schneidet nur die tatsaechlich sichtbare Geometrie bis auf Chunk-Ebene
           herunter. Leere Luft-/Vollstein-Knoten brauchen auch im L0-Footprint keine acht
           Kindjobs. Wichtigkeit einzelner Voxel beeinflusst die Terrain-Topologie bewusst
           nicht mehr: Ein Baum darf keinen kompletten 32^3-Knoten bis L0 ziehen. */
        boolean split = parent.hasGeometry() && (ownershipSplit || distanceSplit);
        if (split) {
            LodVolumeHierarchy.Key[] children = children(key);
            boolean ready = true;
            for (LodVolumeHierarchy.Key child : children) {
                if (this.manager.fullyL0Owned(child)) continue;
                NodeMesh childMesh = this.meshes.get(child);
                if (childMesh == null) {
                    ready = false;
                    this.missingChildrenThisFrame++;
                    request(child, distanceSq(child, camera.getPosition()));
                } else {
                    /* Noch nicht ausgewaehlte Geschwister sind Teil des aktiven atomaren
                       Uebergangs und duerfen nicht mit lastReferencedFrame=0 verschwinden. */
                    childMesh.lastReferencedFrame = this.frameId;
                }
            }
            if (ready) {
                int selectedBefore = this.selected.size();
                this.splitNodes.add(key);
                boolean covered = true;
                for (LodVolumeHierarchy.Key child : children) {
                    if (!this.manager.fullyL0Owned(child)) {
                        covered &= visit(child, camera, true);
                    }
                }
                if (covered) return true;
                while (this.selected.size() > selectedBefore) {
                    NodeMesh removed = this.selected.removeLast();
                    this.selectedKeys.remove(removed.key);
                }
                this.splitNodes.remove(key);
            }
            /* Der Parent bleibt als atomarer Fallback sichtbar, bis die komplette benoetigte
               Kindgruppe hochgeladen ist. Fertiges L0 wird danach gezeichnet und gewinnt den
               Tiefentest; deshalb erzeugt dieser kurze Uebergang weder Loecher noch Flackern. */
        }
        if (!split) this.splitNodes.remove(key);
        parent.lastReferencedFrame = this.frameId;
        this.selected.add(parent);
        this.selectedKeys.add(parent.key);
        return true;
    }

    /** Behält die zuletzt vollstaendige Root-Frontier, wenn ein Ersatz-Root voruebergehend
        nicht verfuegbar ist. Die Map liefert dabei stets die aktuelle Mesh-Instanz des Keys. */
    private int restorePreviousRoot(LodVolumeHierarchy.Key root,
                                    Set<LodVolumeHierarchy.Key> previousSelection) {
        int restored = 0;
        for (LodVolumeHierarchy.Key key : previousSelection) {
            if (!belongsToRoot(key, root)) continue;
            NodeMesh mesh = this.meshes.get(key);
            if (mesh == null || this.manager.fullyL0Owned(key)
                    || !this.selectedKeys.add(key)) continue;
            mesh.lastReferencedFrame = this.frameId;
            this.selected.add(mesh);
            restored++;
        }
        return restored;
    }

    static boolean belongsToRoot(LodVolumeHierarchy.Key key, LodVolumeHierarchy.Key root) {
        if (root.level() < key.level()) return false;
        int divisor = 1 << (root.level() - key.level());
        return Math.floorDiv(key.x(), divisor) == root.x()
                && Math.floorDiv(key.y(), divisor) == root.y()
                && Math.floorDiv(key.z(), divisor) == root.z();
    }

    static boolean shouldEvict(long frame, long lastReferencedFrame) {
        return frame - lastReferencedFrame > EVICT_AFTER_FRAMES;
    }

    static boolean shouldSplit(boolean wasSplit, boolean hasGeometry, int level,
                               double projectedPixels, double threshold) {
        return hasGeometry && level > 0
                && projectedPixels > threshold * (wasSplit ? 0.85 : 1.15);
    }

    /**
     * Feste Distanzbaender mit zehn Prozent Hysterese. Bei Renderdistanz 16 gilt damit
     * L0 bis 16, L1 bis 32, L2 bis 64 und L3 bis 128 Chunks. L4 ist ausserhalb davon
     * beziehungsweise waehrend des atomaren Bootstrap als Parent sichtbar.
     */
    static boolean shouldSplitForDistance(boolean wasSplit, boolean hasGeometry, int level,
                                          double horizontalBlocks, int renderDistanceChunks) {
        if (!hasGeometry || level <= 0) return false;
        double boundary = Math.max(1, renderDistanceChunks) * (double) ChunkSection.SIZE
                * (1 << (level - 1));
        return horizontalBlocks < boundary * (wasSplit ? 1.05 : 0.95);
    }

    private boolean request(LodVolumeHierarchy.Key key, double distanceSq) {
        if (this.requestsThisFrame >= MAX_REQUESTS_PER_FRAME) return false;
        if (!this.manager.requestVolumeMesh(key, this.materials, distanceSq)) return false;
        this.requestsThisFrame++;
        return true;
    }

    private boolean requestBackground(LodVolumeHierarchy.Key key, double distanceSq) {
        if (this.requestsThisFrame >= MAX_REQUESTS_PER_FRAME) return false;
        if (!this.manager.requestVolumeMesh(key, this.materials, distanceSq, true)) return false;
        this.requestsThisFrame++;
        return true;
    }

    private int count(RenderLayer layer) {
        int result = 0;
        for (NodeMesh mesh : this.selected) if (mesh.has(layer)) result++;
        return result;
    }

    private void publishSelectionIfChanged() {
        if (this.selectedKeys.equals(this.publishedSelection)) return;
        List<LodManager.VisibleVolumeNode> visible = new ArrayList<>(this.selected.size());
        for (NodeMesh mesh : this.selected) {
            visible.add(new LodManager.VisibleVolumeNode(mesh.key));
        }
        this.manager.setVisibleVolumeNodes(visible);
        this.publishedSelection = Set.copyOf(this.selectedKeys);
    }

    private void logMemoryStats() {
        long gpuUsed = 0, gpuCapacity = 0, quads = 0;
        for (PackedQuadArena arena : this.arenas) {
            gpuUsed += arena.getUsedBytes();
            gpuCapacity += arena.getCapacity();
        }
        for (NodeMesh mesh : this.meshes.values()) {
            for (PackedQuadArena.Region region : mesh.regions) {
                if (region != null) quads += region.quadCount();
            }
        }
        this.logger.debug("Volumen-LOD: " + this.meshes.size() + " Meshes/" + this.selected.size()
                + " sichtbar, " + quads + " Quads, GPU " + (gpuUsed >> 20) + "/"
                + (gpuCapacity >> 20) + " MB, Voxel " + this.manager.volumeNodeCount()
                + " Knoten/" + (this.manager.volumeEstimatedBytes() >> 20) + " MB, Builds "
                + this.manager.volumeActiveBuilds() + "/" + this.manager.volumeMaxBuilds()
                + ", Cache " + this.manager.volumeCacheHits() + " Treffer/"
                + this.manager.volumeCacheMisses() + " Misses/"
                + this.manager.volumeGeneratedColumns() + " generiert/"
                + this.manager.volumeDirtyColumns() + " dirty, Roots " + this.visibleRootCount
                + " im Radius/" + this.missingRootCount + " Streaming-/"
                + this.coverageMissingRootCount + " Coverage-fehlend, Kinder "
                + this.missingChildrenThisFrame + " fehlend, Inhalts-Refreshes "
                + this.contentRefreshRequests + ", stale Ergebnisse "
                + this.manager.volumeStaleMeshResults() + "/" + this.staleUploadResults
                + ", doppelte Requests " + this.manager.volumeDuplicateMeshRequests()
                + ", Root-Frontier behalten " + this.retainedRootFrontiers);
    }

    private int write(RenderLayer layer, IntBuffer commands, FloatBuffer offsets,
                      long commandOffset, long offsetOffset, Vector3d camera) {
        int ci = (int) (commandOffset / 4), oi = (int) (offsetOffset / 4), draws = 0;
        for (NodeMesh mesh : this.selected) {
            PackedQuadArena.Region region = mesh.regions[layer.ordinal()];
            if (region == null) continue;
            int extent = LodVoxelSection.SIZE << mesh.key.level();
            float ox = (float) ((double) mesh.key.x() * extent - camera.x);
            float oy = (float) ((double) mesh.key.y() * extent - camera.y);
            float oz = (float) ((double) mesh.key.z() * extent - camera.z);
            if (this.drawFrustum != null && !this.drawFrustum.testAab(
                    ox, oy, oz, ox + extent, oy + extent, oz + extent)) continue;
            int command = ci + draws * 4;
            commands.put(command, 6);
            commands.put(command + 1, region.quadCount());
            commands.put(command + 2, 0);
            commands.put(command + 3, region.quadOffset());
            int offset = oi + draws * 4;
            offsets.put(offset, ox);
            offsets.put(offset + 1, oy);
            offsets.put(offset + 2, oz);
            offsets.put(offset + 3, DrawMetadata.pack(mesh.key.level(), 0, 0));
            draws++;
        }
        return draws;
    }

    private void bind(Camera camera) {
        GameSettings settings = GameSettings.get();
        this.shader.bind();
        this.shader.setUniformMatrix4f(this.locProjectionView, camera.getProjectionViewMatrix());
        this.shader.setUniformi(this.locLodLevelColors, de.skyengine.graphics.DebugFlags.lodLevelColors ? 1 : 0);
        float configuredRange = settings.lodMaxDistance * (float) ChunkSection.SIZE;
        float range = this.manager.effectiveFogEnd(configuredRange);
        boolean progressive = range < configuredRange;
        float fogStart = progressive
                ? ChunkRenderer.progressiveFogStart(settings.renderDistance * (float) ChunkSection.SIZE, range)
                : Math.max(0F, range - 4F * ChunkSection.SIZE);
        this.shader.setUniformf(this.locFogStart, settings.fog || progressive ? fogStart : 1.0e30F);
        this.shader.setUniformf(this.locFogEnd, settings.fog || progressive ? range : 2.0e30F);
        Color4 clear = SkyEngine.get().getConfig().getWindowClearColor();
        this.shader.setUniformVector3f(this.locFogColor,
                this.environment.forceFog() ? this.environment.fogRed() : clear.red,
                this.environment.forceFog() ? this.environment.fogGreen() : clear.green,
                this.environment.forceFog() ? this.environment.fogBlue() : clear.blue);
        boolean fullbright = settings.brightness <= 0;
        this.shader.setUniformf(this.locMinLight, fullbright ? 1F : this.environment.ambientLight());
        this.shader.setUniformf(this.locBrightness, fullbright ? 0F : settings.brightness / 100F);
        this.atlas.textures().bind(0);
        boolean pbr = this.atlas.hasMaterials();
        this.shader.setUniformi(this.locPbrEnabled, pbr ? 1 : 0);
        if (pbr) { this.atlas.normals().bind(1); this.atlas.materials().bind(2); }
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, this.materialBuffer);
    }

    private void draw(RenderLayer layer, long commandOffset, long offsetOffset, int draws) {
        if (draws == 0) return;
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.commands.getBuffer());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.offsets.getBuffer(),
                this.offsets.slotOffset(this.frameSlot) + offsetOffset, (long) draws * OFFSET_BYTES);
        this.arenas[layer.ordinal()].bind(1);
        GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES,
                this.commands.slotOffset(this.frameSlot) + commandOffset, draws, 0);
        GL30.glBindVertexArray(0);
    }

    private static LodVolumeHierarchy.Key[] children(LodVolumeHierarchy.Key parent) {
        LodVolumeHierarchy.Key[] result = new LodVolumeHierarchy.Key[8];
        for (int i = 0; i < 8; i++) result[i] = new LodVolumeHierarchy.Key(
                parent.x() * 2 + (i & 1), parent.y() * 2 + (i >>> 2 & 1),
                parent.z() * 2 + (i >>> 1 & 1), parent.level() - 1);
        return result;
    }

    private static double distanceSq(LodVolumeHierarchy.Key key, Vector3d camera) {
        int extent = LodVoxelSection.SIZE << key.level();
        double cx = (key.x() + 0.5) * extent - camera.x;
        double cy = (key.y() + 0.5) * extent - camera.y;
        double cz = (key.z() + 0.5) * extent - camera.z;
        return cx * cx + cy * cy + cz * cz;
    }

    private static double horizontalAabbDistance(LodVolumeHierarchy.Key key, Vector3d camera) {
        int extent = LodVoxelSection.SIZE << key.level();
        double minX = (double) key.x() * extent;
        double minZ = (double) key.z() * extent;
        double dx = Math.max(Math.max(minX - camera.x, 0.0), camera.x - (minX + extent));
        double dz = Math.max(Math.max(minZ - camera.z, 0.0), camera.z - (minZ + extent));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void free(NodeMesh mesh) {
        for (int i = 0; i < mesh.regions.length; i++) {
            if (mesh.regions[i] != null) this.arenas[i].free(mesh.regions[i], this.frameId);
        }
    }

    void dispose() {
        this.manager.resetVolumetricCoverage();
        for (NodeMesh mesh : this.meshes.values()) this.free(mesh);
        this.meshes.clear();
        for (PackedQuadArena arena : this.arenas) arena.dispose();
        this.commands.dispose();
        this.offsets.dispose();
        this.shader.dispose();
        GL30.glDeleteVertexArrays(this.vao);
    }
}
