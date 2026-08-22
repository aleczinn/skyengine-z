package de.skyengine.game.world.particle;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockParticleSprite;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.world.ChunkRenderer;
import org.joml.FrustumIntersection;
import org.joml.Vector3d;

import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Weltgebundener, dichter SoA-Pool für kosmetische Partikel. Sämtliche Arrays besitzen eine feste
 * Obergrenze; Tick, Spawn und Render-Verdichtung erzeugen dadurch keinen Heap-Müll.
 */
public final class ParticleEngine {

    public static final int MAX_PARTICLES = 8192;
    public static final int INSTANCE_FLOATS = 16;
    private static final ParticleType[] TYPES = ParticleType.values();

    private final World world;
    private final Random random;
    private final double[] x = new double[MAX_PARTICLES], y = new double[MAX_PARTICLES], z = new double[MAX_PARTICLES];
    private final double[] prevX = new double[MAX_PARTICLES], prevY = new double[MAX_PARTICLES], prevZ = new double[MAX_PARTICLES];
    private final float[] vx = new float[MAX_PARTICLES], vy = new float[MAX_PARTICLES], vz = new float[MAX_PARTICLES];
    private final float[] size = new float[MAX_PARTICLES], rotation = new float[MAX_PARTICLES];
    private final float[] u0 = new float[MAX_PARTICLES], v0 = new float[MAX_PARTICLES];
    private final float[] u1 = new float[MAX_PARTICLES], v1 = new float[MAX_PARTICLES];
    private final int[] layer = new int[MAX_PARTICLES], color = new int[MAX_PARTICLES];
    private final float[] alpha = new float[MAX_PARTICLES], light = new float[MAX_PARTICLES];
    private final short[] age = new short[MAX_PARTICLES], lifetime = new short[MAX_PARTICLES];
    private final byte[] type = new byte[MAX_PARTICLES], priority = new byte[MAX_PARTICLES];
    private final byte[] translucent = new byte[MAX_PARTICLES];
    private final byte[] onGround = new byte[MAX_PARTICLES];
    private int count;
    private int replacementCursor;
    private long spawned;
    private long rejected;

    public ParticleEngine(World world) {
        this.world = world;
        this.random = new Random();
    }

    ParticleEngine() {
        this.world = null;
        this.random = new Random();
    }

    ParticleEngine(Random random) {
        this.world = null;
        this.random = random;
    }

    public int count() { return this.count; }
    public long spawned() { return this.spawned; }
    public long rejected() { return this.rejected; }

    public void clear() {
        this.count = 0;
        this.replacementCursor = 0;
    }

    public void tick() {
        int cap = GameSettings.get().particleQuality.capacity;
        while (this.count > cap) this.remove(this.count - 1);
        for (int i = this.count - 1; i >= 0; i--) {
            this.prevX[i] = this.x[i];
            this.prevY[i] = this.y[i];
            this.prevZ[i] = this.z[i];
            ParticleType kind = TYPES[this.type[i]];
            int nextAge = (this.age[i] & 0xFFFF) + 1;
            this.age[i] = (short) nextAge;
            if (kind == ParticleType.DRIP_HANG && nextAge >= 20) {
                this.setKind(i, ParticleType.DRIP_FALL);
                this.vy[i] = -0.01F;
                continue;
            }
            if (nextAge >= (this.lifetime[i] & 0xFFFF)) {
                this.remove(i);
                continue;
            }
            if (kind == ParticleType.BUBBLE && this.world != null
                    && !this.isWater(this.x[i], this.y[i], this.z[i])) {
                this.setKind(i, ParticleType.BUBBLE_POP);
                this.vx[i] = this.vy[i] = this.vz[i] = 0F;
                continue;
            }

            this.vy[i] -= kind.gravity;
            if (kind.collision) {
                float oldVy = this.vy[i];
                this.moveColliding(i);
                if (kind == ParticleType.DRIP_FALL && oldVy < 0F && this.vy[i] == 0F) {
                    this.setKind(i, ParticleType.DRIP_LAND);
                    this.vx[i] = this.vy[i] = this.vz[i] = 0F;
                    continue;
                }
            }
            else {
                this.x[i] += this.vx[i];
                this.y[i] += this.vy[i];
                this.z[i] += this.vz[i];
            }
            this.vx[i] *= kind.drag;
            this.vy[i] *= kind.drag;
            this.vz[i] *= kind.drag;
            if (kind == ParticleType.BLOCK && this.onGround[i] != 0) {
                this.vx[i] *= 0.7F;
                this.vz[i] *= 0.7F;
            }
            if (kind == ParticleType.SMOKE || kind == ParticleType.LARGE_SMOKE) {
                this.size[i] *= 1.015F;
                this.alpha[i] = 1F - nextAge / (float) (this.lifetime[i] & 0xFFFF);
            }
            this.light[i] = this.sampleLight(this.x[i], this.y[i], this.z[i]);
        }
    }

    private void moveColliding(int i) {
        ParticleType kind = TYPES[this.type[i]];
        boolean block = kind == ParticleType.BLOCK;
        double collisionRadius = kind == ParticleType.FALLING_DUST ? this.size[i] : this.size[i] * 0.35;
        this.onGround[i] = 0;
        double nx = this.x[i] + this.vx[i];
        if (block ? this.blockParticleCollides(nx, this.y[i], this.z[i])
                : this.collides(nx, this.y[i], this.z[i], collisionRadius)) this.vx[i] = 0;
        else this.x[i] = nx;
        float requestedY = this.vy[i];
        double ny = this.y[i] + this.vy[i];
        if (block ? this.blockParticleCollides(this.x[i], ny, this.z[i])
                : this.collides(this.x[i], ny, this.z[i], collisionRadius)) {
            this.vy[i] = 0;
            if (requestedY < 0F) this.onGround[i] = 1;
        }
        else this.y[i] = ny;
        double nz = this.z[i] + this.vz[i];
        if (block ? this.blockParticleCollides(this.x[i], this.y[i], nz)
                : this.collides(this.x[i], this.y[i], nz, collisionRadius)) this.vz[i] = 0;
        else this.z[i] = nz;
    }

    /** Minecrafts TerrainParticle besitzt unabhaengig von seiner sichtbaren Groesse eine 0.2er Box. */
    private boolean blockParticleCollides(double px, double py, double pz) {
        if (this.world == null) return false;
        double minX = px - 0.1, maxX = px + 0.1;
        double minY = py, maxY = py + 0.2;
        double minZ = pz - 0.1, maxZ = pz + 0.1;
        int minBx = (int) Math.floor(minX), maxBx = (int) Math.floor(maxX);
        int minBy = (int) Math.floor(minY), maxBy = (int) Math.floor(maxY);
        int minBz = (int) Math.floor(minZ), maxBz = (int) Math.floor(maxZ);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    BlockShape shape = this.world.getCollisionShape(bx, by, bz);
                    for (AABB box : shape.boxes()) {
                        if (maxX > bx + box.minX && minX < bx + box.maxX
                                && maxY > by + box.minY && minY < by + box.maxY
                                && maxZ > bz + box.minZ && minZ < bz + box.maxZ) return true;
                    }
                }
            }
        }
        return false;
    }

    /** Punkt-/Radius-Test gegen die echte zusammengesetzte Blockform, ohne temporäre AABBs. */
    private boolean collides(double px, double py, double pz, double radius) {
        if (this.world == null) return false;
        int minBx = (int) Math.floor(px - radius), maxBx = (int) Math.floor(px + radius);
        int minBy = (int) Math.floor(py - radius), maxBy = (int) Math.floor(py + radius);
        int minBz = (int) Math.floor(pz - radius), maxBz = (int) Math.floor(pz + radius);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    BlockShape shape = this.world.getCollisionShape(bx, by, bz);
                    double lx = px - bx, ly = py - by, lz = pz - bz;
                    for (AABB box : shape.boxes()) {
                        if (lx + radius > box.minX && lx - radius < box.maxX
                                && ly + radius > box.minY && ly - radius < box.maxY
                                && lz + radius > box.minZ && lz - radius < box.maxZ) return true;
                    }
                }
            }
        }
        return false;
    }

    public void blockHit(BlockState state, double hitX, double hitY, double hitZ,
                         int faceX, int faceY, int faceZ) {
        BlockParticleSprite sprite = state.getParticleSprite();
        if (!sprite.isPresent()) return;
        float speed = 0.035F;
        this.spawnBlock(ParticlePriority.NORMAL, hitX + faceX * 0.02, hitY + faceY * 0.02,
                hitZ + faceZ * 0.02,
                faceX * speed + jitter(0.015F), faceY * speed + jitter(0.015F),
                faceZ * speed + jitter(0.015F), sprite, 0.09F,
                state.getRenderLayer() == de.skyengine.game.world.block.RenderLayer.TRANSLUCENT);
    }

    /** Vanilla-artiges 4x4x4-Gitter innerhalb des zerstörten Voxels. */
    public void blockBreak(int bx, int by, int bz, BlockState state) {
        BlockParticleSprite sprite = state.getParticleSprite();
        if (!sprite.isPresent()) return;
        var boxes = state.getOutlineShape().boxes();
        boolean isTranslucent = state.getRenderLayer()
                == de.skyengine.game.world.block.RenderLayer.TRANSLUCENT;
        for (AABB box : boxes) {
            double extentX = Math.min(1.0, box.maxX - box.minX);
            double extentY = Math.min(1.0, box.maxY - box.minY);
            double extentZ = Math.min(1.0, box.maxZ - box.minZ);
            int cellsX = Math.max(2, (int) Math.ceil(extentX / 0.25));
            int cellsY = Math.max(2, (int) Math.ceil(extentY / 0.25));
            int cellsZ = Math.max(2, (int) Math.ceil(extentZ / 0.25));
            for (int ix = 0; ix < cellsX; ix++) {
                double fractionX = (ix + 0.5) / cellsX;
                for (int iy = 0; iy < cellsY; iy++) {
                    double fractionY = (iy + 0.5) / cellsY;
                    for (int iz = 0; iz < cellsZ; iz++) {
                        double fractionZ = (iz + 0.5) / cellsZ;
                        this.spawnBreakBlock(bx + box.minX + fractionX * extentX,
                                by + box.minY + fractionY * extentY,
                                bz + box.minZ + fractionZ * extentZ,
                                (float) (fractionX - 0.5), (float) (fractionY - 0.5),
                                (float) (fractionZ - 0.5), sprite, isTranslucent);
                    }
                }
            }
        }
    }

    /** Minecrafts zufaellige TerrainParticle-Geschwindigkeit und -Groesse. */
    private void spawnBreakBlock(double px, double py, double pz, float mx, float my, float mz,
                                 BlockParticleSprite sprite, boolean translucent) {
        mx += this.jitter(0.4F);
        my += this.jitter(0.4F);
        mz += this.jitter(0.4F);
        double length = Math.sqrt(mx * mx + my * my + mz * mz);
        if (length < 1.0E-7) {
            mx = mz = 0F;
            my = 0.1F;
        } else {
            float speed = (this.random.nextFloat() + this.random.nextFloat() + 1F) * 0.15F * 0.4F;
            mx = (float) (mx / length) * speed;
            my = (float) (my / length) * speed + 0.1F;
            mz = (float) (mz / length) * speed;
        }
        float scale = 0.05F * (this.random.nextFloat() + 1F);
        this.spawnBlock(ParticlePriority.CRITICAL, px, py, pz, mx, my, mz, sprite, scale,
                translucent);
    }

    public void landing(double px, double py, double pz, BlockState ground, float fallDistance) {
        int amount = scaledAmount(Math.clamp((int) (fallDistance * 12F), 4, 80), false);
        BlockParticleSprite sprite = ground.getParticleSprite();
        if (!sprite.isPresent()) return;
        for (int i = 0; i < amount; i++) {
            double angle = this.random.nextDouble() * Math.PI * 2;
            double radius = this.random.nextDouble() * 0.45;
            this.spawnBlock(ParticlePriority.NORMAL,
                    px + Math.cos(angle) * radius, py + 0.02, pz + Math.sin(angle) * radius,
                    (float) Math.cos(angle) * 0.025F, 0.025F + this.random.nextFloat() * 0.025F,
                    (float) Math.sin(angle) * 0.025F, sprite, 0.08F,
                    ground.getRenderLayer() == de.skyengine.game.world.block.RenderLayer.TRANSLUCENT);
        }
    }

    public void sprint(double px, double py, double pz, BlockState ground, double motionX, double motionZ) {
        BlockParticleSprite sprite = ground.getParticleSprite();
        if (!sprite.isPresent()) return;
        this.spawnBlock(ParticlePriority.AMBIENT, px + jitter(0.35F), py + 0.03,
                pz + jitter(0.35F), (float) -motionX * 0.25F + jitter(0.01F),
                0.025F, (float) -motionZ * 0.25F + jitter(0.01F), sprite, 0.08F,
                ground.getRenderLayer() == de.skyengine.game.world.block.RenderLayer.TRANSLUCENT);
    }

    public void torch(double px, double py, double pz) {
        this.add(ParticleType.SMOKE, ParticlePriority.AMBIENT, px, py, pz,
                jitter(0.003F), 0.01F, jitter(0.003F), -1, 0x777777, 0.7F, 0.09F);
        this.add(ParticleType.FLAME, ParticlePriority.AMBIENT, px, py, pz,
                jitter(0.002F), 0.004F, jitter(0.002F), -1, 0xFFFFFF, 1F, 0.08F);
    }

    public void smoke(double px, double py, double pz, boolean large, ParticlePriority importance) {
        this.add(large ? ParticleType.LARGE_SMOKE : ParticleType.SMOKE, importance,
                px, py, pz, jitter(large ? 0.025F : 0.01F),
                0.02F + this.random.nextFloat() * 0.02F, jitter(large ? 0.025F : 0.01F),
                -1, large ? 0xEEEEEE : 0x888888, 0.9F, large ? 0.18F : 0.10F);
    }

    public void fluidReaction(double px, double py, double pz) {
        int amount = scaledAmount(8, false);
        for (int i = 0; i < amount; i++) this.smoke(px + this.random.nextDouble() - 0.5,
                py + 0.7 + this.random.nextDouble() * 0.3, pz + this.random.nextDouble() - 0.5,
                true, ParticlePriority.CRITICAL);
    }

    public void lavaPop(double px, double py, double pz) {
        this.add(ParticleType.LAVA, ParticlePriority.AMBIENT, px + jitter(0.35F), py,
                pz + jitter(0.35F), jitter(0.02F), 0.10F + this.random.nextFloat() * 0.08F,
                jitter(0.02F), -1, 0xFFFFFF, 1F, 0.10F);
    }

    public void underwater(double px, double py, double pz) {
        this.add(ParticleType.UNDERWATER, ParticlePriority.AMBIENT, px, py, pz,
                jitter(0.005F), jitter(0.005F), jitter(0.005F), -1, 0xFFFFFF, 0.55F, 0.04F);
    }

    public void drip(double px, double py, double pz, boolean lava) {
        this.add(ParticleType.DRIP_HANG, ParticlePriority.AMBIENT, px, py, pz,
                0F, 0F, 0F, -1, lava ? 0xFF6A00 : 0x3F76E4, 0.85F, 0.04F);
    }

    public void swim(double px, double py, double pz, double motionX, double motionY, double motionZ) {
        this.add(ParticleType.BUBBLE, ParticlePriority.AMBIENT,
                px + jitter(0.25F), py + jitter(0.45F), pz + jitter(0.25F),
                (float) -motionX * 0.15F + jitter(0.01F),
                (float) -motionY * 0.15F + 0.01F,
                (float) -motionZ * 0.15F + jitter(0.01F),
                -1, 0xFFFFFF, 0.75F, 0.05F);
    }

    public void fallingDust(double px, double py, double pz, BlockState state) {
        BlockParticleSprite sprite = state.getParticleSprite();
        if (!sprite.isPresent()) return;
        int index = this.add(ParticleType.FALLING_DUST, ParticlePriority.AMBIENT,
                px + jitter(0.3F), py, pz + jitter(0.3F), jitter(0.004F),
                -0.01F - this.random.nextFloat() * 0.015F, jitter(0.004F),
                sprite.textureLayer(), darken(this.tintAt(sprite, px, pz), 0.6F), 0.9F, 0.09F);
        if (index >= 0) {
            this.rotation[index] = 0F;
            this.u0[index] = this.random.nextInt(4) * 0.25F;
            this.v0[index] = this.random.nextInt(4) * 0.25F;
            this.u1[index] = this.u0[index] + 0.25F;
            this.v1[index] = this.v0[index] + 0.25F;
        }
    }

    public void splash(double px, double py, double pz, double speed) {
        int amount = scaledAmount(Math.clamp((int) (8 + speed * 30), 8, 40), false);
        for (int i = 0; i < amount; i++) {
            ParticleType kind = (i & 1) == 0 ? ParticleType.SPLASH : ParticleType.BUBBLE;
            this.add(kind, ParticlePriority.NORMAL, px + jitter(0.3F), py + jitter(0.15F),
                    pz + jitter(0.3F), jitter(0.06F), 0.04F + this.random.nextFloat() * 0.08F,
                    jitter(0.06F), -1, 0xFFFFFF, 0.8F, kind.size);
        }
    }

    public void explosion(double px, double py, double pz, float power) {
        int amount = scaledAmount(Math.clamp((int) (power * 12F), 16, 96), false);
        for (int i = 0; i < amount; i++) {
            double dx = this.random.nextDouble() * 2 - 1;
            double dy = this.random.nextDouble() * 2 - 1;
            double dz = this.random.nextDouble() * 2 - 1;
            double length = Math.max(0.001, Math.sqrt(dx * dx + dy * dy + dz * dz));
            float speed = 0.04F + this.random.nextFloat() * power * 0.02F;
            this.add(ParticleType.EXPLOSION, ParticlePriority.CRITICAL,
                    px + dx * 0.5, py + dy * 0.5, pz + dz * 0.5,
                    (float) (dx / length) * speed, (float) (dy / length) * speed,
                    (float) (dz / length) * speed, -1, 0xFFFFFF, 1F,
                    0.35F + this.random.nextFloat() * power * 0.12F);
        }
    }

    public void dispenser(double px, double py, double pz, int dx, int dy, int dz) {
        int amount = scaledAmount(10, false);
        for (int i = 0; i < amount; i++) this.add(ParticleType.SMOKE, ParticlePriority.NORMAL,
                px + dx * 0.55 + jitter(0.2F), py + dy * 0.55 + jitter(0.2F),
                pz + dz * 0.55 + jitter(0.2F), dx * 0.06F + jitter(0.02F),
                dy * 0.06F + jitter(0.02F), dz * 0.06F + jitter(0.02F),
                -1, 0x888888, 0.9F, 0.10F);
    }

    public void itemCrumb(int textureLayer, double px, double py, double pz,
                          double dirX, double dirY, double dirZ) {
        int amount = scaledAmount(5, false);
        for (int i = 0; i < amount; i++) this.add(ParticleType.BLOCK, ParticlePriority.NORMAL,
                px + jitter(0.12F), py + jitter(0.10F), pz + jitter(0.12F),
                (float) -dirX * 0.08F + jitter(0.03F),
                (float) -dirY * 0.08F + 0.03F + jitter(0.02F),
                (float) -dirZ * 0.08F + jitter(0.03F), textureLayer,
                0xFFFFFF, 1F, 0.06F);
    }

    private void spawnBlock(ParticlePriority importance, double px, double py, double pz,
                            float mx, float my, float mz, BlockParticleSprite sprite, float scale,
                            boolean translucent) {
        int index = this.add(ParticleType.BLOCK, importance, px, py, pz, mx, my, mz,
                sprite.textureLayer(), darken(this.tintAt(sprite, px, pz), 0.6F), 1F, scale);
        if (index < 0) return;
        this.translucent[index] = (byte) (translucent ? 1 : 0);
        this.rotation[index] = 0F;
        float uOffset = this.random.nextFloat() * 3F;
        float vOffset = this.random.nextFloat() * 3F;
        this.u0[index] = uOffset * 0.25F;
        this.v0[index] = vOffset * 0.25F;
        this.u1[index] = (uOffset + 1F) * 0.25F;
        this.v1[index] = (vOffset + 1F) * 0.25F;
    }

    private int add(ParticleType kind, ParticlePriority importance,
                    double px, double py, double pz, float mx, float my, float mz,
                    int textureLayer, int rgb, float opacity, float scale) {
        boolean ambient = importance == ParticlePriority.AMBIENT;
        if (!allow(ambient, importance, px, py, pz)) return -1;
        int index = this.allocate(importance);
        if (index < 0) return -1;
        this.x[index] = this.prevX[index] = px;
        this.y[index] = this.prevY[index] = py;
        this.z[index] = this.prevZ[index] = pz;
        this.vx[index] = mx;
        this.vy[index] = my;
        this.vz[index] = mz;
        this.type[index] = (byte) kind.ordinal();
        this.priority[index] = (byte) importance.ordinal();
        this.translucent[index] = (byte) (kind.translucent ? 1 : 0);
        this.onGround[index] = 0;
        this.size[index] = scale > 0 ? scale : kind.size;
        this.rotation[index] = this.random.nextFloat() * (float) (Math.PI * 2);
        this.layer[index] = textureLayer;
        this.color[index] = rgb;
        this.alpha[index] = opacity;
        this.u0[index] = this.v0[index] = 0F;
        this.u1[index] = this.v1[index] = 1F;
        this.age[index] = 0;
        int life = kind == ParticleType.BLOCK
                ? Math.max(4, (int) (4F / (this.random.nextFloat() * 0.9F + 0.1F)))
                : kind.minLifetime + this.random.nextInt(kind.maxLifetime - kind.minLifetime + 1);
        this.lifetime[index] = (short) life;
        this.light[index] = kind == ParticleType.FLAME || kind == ParticleType.EXPLOSION
                ? 1F : this.sampleLight(px, py, pz);
        this.spawned++;
        return index;
    }

    private int allocate(ParticlePriority importance) {
        int cap = GameSettings.get().particleQuality.capacity;
        if (this.count < cap) return this.count++;
        int start = this.replacementCursor;
        int attempts = Math.min(64, this.count);
        for (int n = 0; n < attempts; n++) {
            int index = (start + n) % this.count;
            if (this.priority[index] <= importance.ordinal()) {
                this.replacementCursor = (index + 1) % this.count;
                return index;
            }
        }
        this.rejected++;
        return -1;
    }

    private void setKind(int index, ParticleType kind) {
        this.type[index] = (byte) kind.ordinal();
        this.translucent[index] = (byte) (kind.translucent ? 1 : 0);
        this.age[index] = 0;
        this.lifetime[index] = (short) (kind.minLifetime
                + this.random.nextInt(kind.maxLifetime - kind.minLifetime + 1));
    }

    private boolean allow(boolean ambient, ParticlePriority importance, double px, double py, double pz) {
        GameSettings.ParticleQuality quality = GameSettings.get().particleQuality;
        float rate = ambient ? quality.ambientRate : 1F;
        if (rate <= 0F || (rate < 1F && this.random.nextFloat() >= rate)) return false;
        if (this.world == null) return true;
        var player = this.world.getPlayer();
        if (player == null || importance == ParticlePriority.CRITICAL) return true;
        double max = ambient ? quality.ambientDistance : quality.eventDistance;
        double dx = player.x - px, dy = player.y - py, dz = player.z - pz;
        return dx * dx + dy * dy + dz * dz <= max * max;
    }

    private int scaledAmount(int vanilla, boolean ambient) {
        float rate = ambient ? GameSettings.get().particleQuality.ambientRate
                : GameSettings.get().particleQuality.eventRate;
        if (rate <= 0F) return 0;
        return Math.max(1, Math.round(vanilla * rate));
    }

    private float jitter(float radius) {
        return (this.random.nextFloat() * 2F - 1F) * radius;
    }

    private boolean isWater(double px, double py, double pz) {
        BlockState state = Blocks.getState(this.world.getBlock((int) Math.floor(px),
                (int) Math.floor(py), (int) Math.floor(pz)));
        return state.isFluid() && !state.getBlock().getFluidInfo().lava;
    }

    private float sampleLight(double px, double py, double pz) {
        if (this.world == null) return 1F;
        int bx = (int) Math.floor(px), by = Math.clamp((int) Math.floor(py), 0, Chunk.HEIGHT - 1);
        int bz = (int) Math.floor(pz);
        Chunk chunk = this.world.getChunkManager().getChunk(bx >> ChunkSection.SHIFT, bz >> ChunkSection.SHIFT);
        if (chunk == null || !chunk.status.isAtLeast(ChunkStatus.LIT)) return 1F;
        return ChunkRenderer.lightFactor(chunk.light.get(bx & ChunkSection.MASK, by, bz & ChunkSection.MASK),
                chunk.blockLight.get(bx & ChunkSection.MASK, by, bz & ChunkSection.MASK));
    }

    private int tintAt(BlockParticleSprite sprite, double px, double pz) {
        if (this.world == null || sprite.tintType() == BakedQuad.TINT_NONE) return sprite.tint();
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        Chunk chunk = this.world.getChunkManager().getChunk(bx >> ChunkSection.SHIFT, bz >> ChunkSection.SHIFT);
        if (chunk == null) return sprite.tint();
        int[] grid = sprite.tintType() == BakedQuad.TINT_FOLIAGE
                ? chunk.foliageTintCorners : chunk.grassTintCorners;
        if (grid == null) return sprite.tint();
        int lx = bx & ChunkSection.MASK, lz = bz & ChunkSection.MASK;
        return grid[lx * (ChunkSection.SIZE + 1) + lz];
    }

    private static int darken(int rgb, float factor) {
        int r = Math.clamp(Math.round(((rgb >> 16) & 255) * factor), 0, 255);
        int g = Math.clamp(Math.round(((rgb >> 8) & 255) * factor), 0, 255);
        int b = Math.clamp(Math.round((rgb & 255) * factor), 0, 255);
        return r << 16 | g << 8 | b;
    }

    private void remove(int index) {
        int last = --this.count;
        if (index == last) return;
        this.x[index] = this.x[last]; this.y[index] = this.y[last]; this.z[index] = this.z[last];
        this.prevX[index] = this.prevX[last]; this.prevY[index] = this.prevY[last]; this.prevZ[index] = this.prevZ[last];
        this.vx[index] = this.vx[last]; this.vy[index] = this.vy[last]; this.vz[index] = this.vz[last];
        this.size[index] = this.size[last]; this.rotation[index] = this.rotation[last];
        this.u0[index] = this.u0[last]; this.v0[index] = this.v0[last]; this.u1[index] = this.u1[last]; this.v1[index] = this.v1[last];
        this.layer[index] = this.layer[last]; this.color[index] = this.color[last];
        this.alpha[index] = this.alpha[last]; this.light[index] = this.light[last];
        this.age[index] = this.age[last]; this.lifetime[index] = this.lifetime[last];
        this.type[index] = this.type[last]; this.priority[index] = this.priority[last];
        this.translucent[index] = this.translucent[last];
        this.onGround[index] = this.onGround[last];
    }

    /** Schreibt sichtbare Instanzen in das bereits gemappte Zielsegment. */
    public int writeInstances(FloatBuffer out, Camera camera, float partialTick, boolean translucent) {
        Vector3d cam = camera.getPosition();
        FrustumIntersection frustum = camera.getFrustum();
        int written = 0;
        for (int i = 0; i < this.count; i++) {
            ParticleType kind = TYPES[this.type[i]];
            if ((this.translucent[i] != 0) != translucent) continue;
            float px = (float) (this.prevX[i] + (this.x[i] - this.prevX[i]) * partialTick - cam.x);
            float py = (float) (this.prevY[i] + (this.y[i] - this.prevY[i]) * partialTick - cam.y);
            float pz = (float) (this.prevZ[i] + (this.z[i] - this.prevZ[i]) * partialTick - cam.z);
            float scale = this.size[i];
            if (!frustum.testSphere(px, py, pz, scale * 1.5F)) continue;
            int sprite = this.layer[i];
            if (sprite < 0) sprite = ParticleSprites.layer(kind, this.age[i] & 0xFFFF, this.lifetime[i] & 0xFFFF);
            int rgb = this.color[i];
            out.put(px).put(py).put(pz).put(scale).put(this.rotation[i]);
            out.put(this.u0[i]).put(this.v0[i]).put(this.u1[i]).put(this.v1[i]);
            out.put(sprite);
            out.put(((rgb >> 16) & 255) / 255F).put(((rgb >> 8) & 255) / 255F).put((rgb & 255) / 255F);
            out.put(this.alpha[i]).put(this.light[i]).put(0F);
            written++;
        }
        return written;
    }
}
