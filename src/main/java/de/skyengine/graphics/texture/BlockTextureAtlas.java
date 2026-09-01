package de.skyengine.graphics.texture;

import de.skyengine.core.resource.Resources;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.model.CompactCompositeMaterialTable;
import de.skyengine.graphics.GlDebug;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

/** Welt-unabhaengiger Besitzer der Block-Farb-, Normal- und Material-Arrays. */
public final class BlockTextureAtlas {
    public static final int TEXTURE_SIZE = 16;

    private TextureArray textures;
    private TextureArray normals;
    private TextureArray materials;
    private SpriteAnimations animations;
    private int compositeMaterialBuffer;
    private long lastAnimNanos;
    private static BlockTextureAtlas active;

    public void init() {
        active = this;
        String[] paths = BlockTextures.getOrderedPaths();
        int size = TextureArray.detectSize(paths);
        this.animations = SpriteAnimations.build(paths, size);
        this.textures = new TextureArray(size, paths, this.animations.animatedLayers());
        this.buildMaterials(paths, size);
        this.compositeMaterialBuffer = createCompositeMaterialBuffer();
        this.animations.uploadInitial(this.textures);
        this.textures.regenerateMipmaps();
        this.lastAnimNanos = System.nanoTime();
    }

    /** Tauscht die GL-Inhalte aus, waehrend Referenzen auf den Farbatlas stabil bleiben. */
    public void reload() {
        String[] paths = BlockTextures.getOrderedPaths();
        int size = TextureArray.detectSize(paths);
        SpriteAnimations nextAnimations = SpriteAnimations.build(paths, size);
        TextureArray nextTextures = new TextureArray(size, paths, nextAnimations.animatedLayers());
        nextAnimations.uploadInitial(nextTextures);
        nextTextures.regenerateMipmaps();

        String[] normalPaths = sidecars(paths, "_n");
        String[] materialPaths = sidecars(paths, "_s");
        TextureArray nextNormals = null, nextMaterials = null;
        if (hasAny(normalPaths) || hasAny(materialPaths)) {
            nextNormals = new TextureArray(size, normalPaths, java.util.Set.of(), TextureArray.Fallback.FLAT_NORMAL);
            nextMaterials = new TextureArray(size, materialPaths, java.util.Set.of(), TextureArray.Fallback.DEFAULT_MATERIAL);
        }
        int nextCompositeMaterialBuffer = createCompositeMaterialBuffer();

        if (this.animations != null) this.animations.dispose();
        this.animations = nextAnimations;
        this.textures.replaceWith(nextTextures);
        if (nextNormals == null) {
            if (this.normals != null) this.normals.dispose();
            if (this.materials != null) this.materials.dispose();
            this.normals = null;
            this.materials = null;
        } else if (this.normals == null) {
            this.normals = nextNormals;
            this.materials = nextMaterials;
        } else {
            this.normals.replaceWith(nextNormals);
            this.materials.replaceWith(nextMaterials);
        }
        this.lastAnimNanos = System.nanoTime();
        int previousCompositeMaterialBuffer = this.compositeMaterialBuffer;
        this.compositeMaterialBuffer = nextCompositeMaterialBuffer;
        if (previousCompositeMaterialBuffer != 0) GL15.glDeleteBuffers(previousCompositeMaterialBuffer);
    }

    private void buildMaterials(String[] paths, int size) {
        String[] normalPaths = sidecars(paths, "_n");
        String[] materialPaths = sidecars(paths, "_s");
        if (hasAny(normalPaths) || hasAny(materialPaths)) {
            this.normals = new TextureArray(size, normalPaths, java.util.Set.of(), TextureArray.Fallback.FLAT_NORMAL);
            this.materials = new TextureArray(size, materialPaths, java.util.Set.of(), TextureArray.Fallback.DEFAULT_MATERIAL);
        }
    }

    public void tick() {
        if (this.animations == null || this.textures == null) return;
        long now = System.nanoTime();
        this.animations.tick(this.textures, (now - this.lastAnimNanos) / 1.0e9);
        this.lastAnimNanos = now;
    }

    public TextureArray textures() { return this.textures; }
    public TextureArray normals() { return this.normals; }
    public TextureArray materials() { return this.materials; }
    public boolean hasMaterials() { return this.normals != null && this.materials != null; }
    public int compositeMaterialBuffer() { return this.compositeMaterialBuffer; }

    private static int createCompositeMaterialBuffer() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Attribute a following error to this allocation, not to an earlier GL call.
        }
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                CompactCompositeMaterialTable.gpuSnapshot(), GL15.GL_STATIC_DRAW);
        int error = GL11.glGetError();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        if (error != GL11.GL_NO_ERROR) {
            GL15.glDeleteBuffers(buffer);
            throw new IllegalStateException("Compact composite material SSBO upload failed: GL 0x"
                    + Integer.toHexString(error));
        }
        GlDebug.labelBuffer(buffer, "Compact Composite Materials");
        return buffer;
    }

    /** Bindet optionale Materialarrays fuer alle kleineren Voxelrenderer. */
    public static void bindOptionalMaterials(de.skyengine.graphics.shader.ShaderProgram shader) {
        boolean enabled = active != null && active.hasMaterials();
        shader.setUniformi("u_PbrEnabled", enabled ? 1 : 0);
        if (enabled) {
            active.normals.bind(1);
            active.materials.bind(2);
        }
    }

    private static String[] sidecars(String[] paths, String suffix) {
        String[] out = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            int dot = paths[i].lastIndexOf('.');
            out[i] = dot >= 0 ? paths[i].substring(0, dot) + suffix + paths[i].substring(dot)
                    : paths[i] + suffix + ".png";
        }
        return out;
    }

    private static boolean hasAny(String[] paths) {
        for (String path : paths) if (Resources.get().exists(path)) return true;
        return false;
    }

    public void dispose() {
        if (this.animations != null) this.animations.dispose();
        if (this.textures != null) this.textures.dispose();
        if (this.normals != null) this.normals.dispose();
        if (this.materials != null) this.materials.dispose();
        if (this.compositeMaterialBuffer != 0) GL15.glDeleteBuffers(this.compositeMaterialBuffer);
    }
}
