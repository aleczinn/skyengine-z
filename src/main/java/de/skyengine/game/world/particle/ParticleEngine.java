package de.skyengine.game.world.particle;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockParticleSprite;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RedstoneSide;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.redstone.RedstoneColors;
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
    private static final int MAX_EXPLOSION_BURSTS = 256;
    private static final int MAX_EXPLOSION_BLOCK_PARTICLES = 512;
    private static final ParticleType[] TYPES = ParticleType.values();

    private final Dimension world;
    private final Random random;
    private final double[] x = new double[MAX_PARTICLES], y = new double[MAX_PARTICLES], z = new double[MAX_PARTICLES];
    private final double[] prevX = new double[MAX_PARTICLES], prevY = new double[MAX_PARTICLES], prevZ = new double[MAX_PARTICLES];
    private final double[] originX = new double[MAX_PARTICLES], originY = new double[MAX_PARTICLES], originZ = new double[MAX_PARTICLES];
    private final float[] vx = new float[MAX_PARTICLES], vy = new float[MAX_PARTICLES], vz = new float[MAX_PARTICLES];
    private final float[] size = new float[MAX_PARTICLES], rotation = new float[MAX_PARTICLES];
    private final float[] rotationVelocity = new float[MAX_PARTICLES];
    private final float[] aux0 = new float[MAX_PARTICLES], aux1 = new float[MAX_PARTICLES];
    private final float[] u0 = new float[MAX_PARTICLES], v0 = new float[MAX_PARTICLES];
    private final float[] u1 = new float[MAX_PARTICLES], v1 = new float[MAX_PARTICLES];
    private final int[] layer = new int[MAX_PARTICLES], color = new int[MAX_PARTICLES];
    private final float[] alpha = new float[MAX_PARTICLES], light = new float[MAX_PARTICLES];
    private final short[] age = new short[MAX_PARTICLES], lifetime = new short[MAX_PARTICLES];
    private final byte[] type = new byte[MAX_PARTICLES], priority = new byte[MAX_PARTICLES];
    private final byte[] translucent = new byte[MAX_PARTICLES];
    private final byte[] onGround = new byte[MAX_PARTICLES];
    /* 26.2 ClientExplosionTracker: feste Burst-Queue statt Objekte pro Explosion. */
    private final double[] explosionX = new double[MAX_EXPLOSION_BURSTS];
    private final double[] explosionY = new double[MAX_EXPLOSION_BURSTS];
    private final double[] explosionZ = new double[MAX_EXPLOSION_BURSTS];
    private final float[] explosionRadius = new float[MAX_EXPLOSION_BURSTS];
    private final int[] explosionWeight = new int[MAX_EXPLOSION_BURSTS];
    private int explosionBurstCount;
    private int count;
    private int replacementCursor;
    private long spawned;
    private long rejected;

    public ParticleEngine(Dimension world) {
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
        this.explosionBurstCount = 0;
    }

    public void tick() {
        int cap = GameSettings.get().particleQuality.capacity;
        while (this.count > cap) this.remove(this.count - 1);
        for (int i = this.count - 1; i >= 0; i--) {
            this.prevX[i] = this.x[i];
            this.prevY[i] = this.y[i];
            this.prevZ[i] = this.z[i];
            ParticleType kind = TYPES[this.type[i]];
            if (kind == ParticleType.EXPLOSION_EMITTER) {
                this.tickExplosionEmitter(i);
                continue;
            }
            if (kind == ParticleType.FALLING_LEAF) {
                this.tickFallingLeaf(i);
                continue;
            }
            if (kind == ParticleType.PORTAL) {
                this.tickPortal(i);
                continue;
            }
            int nextAge = (this.age[i] & 0xFFFF) + 1;
            this.age[i] = (short) nextAge;
            if (kind == ParticleType.DRIP_HANG) {
                this.updateHangingDripColor(i, nextAge);
                if (nextAge >= 40) {
                    this.startFallingDrip(i);
                    continue;
                }
            }
            if (nextAge >= (this.lifetime[i] & 0xFFFF)) {
                this.remove(i);
                continue;
            }
            if (kind == ParticleType.BUBBLE && this.world != null
                    && !this.isWater(this.x[i], this.y[i], this.z[i])) {
                this.remove(i);
                continue;
            }

            this.vy[i] -= kind.gravity;
            if (kind.collision) {
                float oldVy = this.vy[i];
                this.moveColliding(i);
                if (kind == ParticleType.DRIP_FALL && oldVy < 0F && this.vy[i] == 0F) {
                    this.landDrip(i);
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
            if (kind == ParticleType.FALLING_DUST) {
                this.vy[i] = Math.max(this.vy[i], -0.14F);
                this.rotation[i] = this.onGround[i] != 0 ? 0F
                        : this.rotation[i] + this.rotationVelocity[i];
            }
            if (kind == ParticleType.LAVA
                    && this.random.nextFloat() > nextAge / (float) (this.lifetime[i] & 0xFFFF)) {
                this.spawnVanillaSmoke(this.x[i], this.y[i], this.z[i],
                        this.vx[i], this.vy[i], this.vz[i], 1F, ParticlePriority.AMBIENT);
            }
            if (kind == ParticleType.PORTAL_BURST) {
                this.rotation[i] += this.rotationVelocity[i];
                this.alpha[i] = Math.max(0F,
                        1F - nextAge / (float) (this.lifetime[i] & 0xFFFF));
            }
            if (kind != ParticleType.LAVA && kind != ParticleType.EXPLOSION) {
                this.light[i] = this.sampleLight(this.x[i], this.y[i], this.z[i]);
            }
        }
        this.flushExplosionClouds();
    }

    /** Exakte 26.2-Bahnkurve: erst aus der Portalebene heraus, dann wieder zu ihr zurueck. */
    private void tickPortal(int index) {
        int nextAge = (this.age[index] & 0xFFFF) + 1;
        this.age[index] = (short) nextAge;
        int life = this.lifetime[index] & 0xFFFF;
        if (nextAge >= life) {
            this.remove(index);
            return;
        }
        float progress = nextAge / (float) life;
        float path = 1F + progress - 2F * progress * progress;
        this.x[index] = this.originX[index] + this.vx[index] * path;
        this.y[index] = this.originY[index] + this.vy[index] * path + 1F - progress;
        this.z[index] = this.originZ[index] + this.vz[index] * path;
        float glow = progress * progress;
        glow *= glow;
        this.light[index] = Math.max(this.sampleLight(this.x[index], this.y[index], this.z[index]), glow);
    }

    /** Vanillas EXPLOSION_EMITTER: acht Ticks lang je sechs ortsfeste Explosion-Sprites. */
    private void tickExplosionEmitter(int index) {
        int emitterAge = this.age[index] & 0xFFFF;
        float progress = emitterAge / 8F;
        double px = this.x[index], py = this.y[index], pz = this.z[index];
        for (int n = 0; n < 6; n++) {
            double sx = px + (this.random.nextDouble() - this.random.nextDouble()) * 4.0;
            double sy = py + (this.random.nextDouble() - this.random.nextDouble()) * 4.0;
            double sz = pz + (this.random.nextDouble() - this.random.nextDouble()) * 4.0;
            int gray = Math.clamp((int) ((0.4F + this.random.nextFloat() * 0.6F) * 255F), 0, 255);
            int child = this.add(ParticleType.EXPLOSION, ParticlePriority.CRITICAL,
                    sx, sy, sz, 0F, 0F, 0F, -1, gray << 16 | gray << 8 | gray,
                    1F, 2F * (1F - progress * 0.5F));
            if (child >= 0) {
                this.lifetime[child] = (short) (6 + this.random.nextInt(4));
                this.light[child] = 1F;
            }
        }
        this.age[index] = (short) (emitterAge + 1);
        if (emitterAge + 1 >= 8) this.remove(index);
    }

    /** Gemeinsames Verhalten der TINTED_LEAVES- und PALE_OAK_LEAVES-Partikel. */
    private void tickFallingLeaf(int index) {
        int nextAge = (this.age[index] & 0xFFFF) + 1;
        this.age[index] = (short) nextAge;
        if (nextAge >= 300) {
            this.remove(index);
            return;
        }
        float progress = Math.min(nextAge / 300F, 1F);
        float curve = progress * progress;
        this.vx[index] += (float) (curve * Math.cos(curve * this.aux1[index]) * 10F * 0.0025F);
        this.vz[index] += (float) (curve * Math.sin(curve * this.aux1[index]) * 10F * 0.0025F);
        this.vy[index] -= 0.00021F;
        this.rotationVelocity[index] += this.aux0[index] / 20F;
        this.rotation[index] += this.rotationVelocity[index] / 20F;
        float requestedX = this.vx[index], requestedZ = this.vz[index];
        this.moveColliding(index);
        if (this.onGround[index] != 0
                || (nextAge > 1 && requestedX != 0F && this.vx[index] == 0F)
                || (nextAge > 1 && requestedZ != 0F && this.vz[index] == 0F)) {
            this.remove(index);
            return;
        }
        this.light[index] = this.sampleLight(this.x[index], this.y[index], this.z[index]);
    }

    private void moveColliding(int i) {
        ParticleType kind = TYPES[this.type[i]];
        boolean block = kind == ParticleType.BLOCK;
        double collisionRadius = kind == ParticleType.FALLING_DUST ? this.size[i]
                : kind == ParticleType.POOF ? 0.1 : this.size[i] * 0.35;
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
        int fallDamage = Math.max(0, (int) Math.ceil(fallDistance - 3F));
        if (fallDamage == 0) return;
        double spread = Math.min(0.2 + fallDamage / 15.0, 2.5);
        int amount = scaledAmount((int) (150.0 * spread), false);
        BlockParticleSprite sprite = ground.getParticleSprite();
        if (!sprite.isPresent()) return;
        for (int i = 0; i < amount; i++) {
            this.spawnBlock(ParticlePriority.NORMAL,
                    px, py, pz,
                    (float) (this.random.nextGaussian() * 0.15),
                    (float) (this.random.nextGaussian() * 0.15),
                    (float) (this.random.nextGaussian() * 0.15),
                    sprite, vanillaQuadSize() * 0.5F,
                    ground.getRenderLayer() == de.skyengine.game.world.block.RenderLayer.TRANSLUCENT);
        }
    }

    public void sprint(double px, double py, double pz, BlockState ground, double motionX, double motionZ) {
        BlockParticleSprite sprite = ground.getParticleSprite();
        if (!sprite.isPresent()) return;
        this.spawnBlock(ParticlePriority.AMBIENT, px + jitter(0.3F), py + 0.1,
                pz + jitter(0.3F), (float) (-motionX * 4.0),
                1.5F, (float) (-motionZ * 4.0), sprite, vanillaQuadSize() * 0.5F,
                ground.getRenderLayer() == de.skyengine.game.world.block.RenderLayer.TRANSLUCENT);
    }

    public void torch(double px, double py, double pz) {
        this.spawnVanillaSmoke(px, py, pz, 0F, 0F, 0F, 1F, ParticlePriority.AMBIENT);
        this.spawnVanillaFlame(px, py, pz, 0F, 0F, 0F, ParticlePriority.AMBIENT);
    }

    public void smoke(double px, double py, double pz, boolean large, ParticlePriority importance) {
        this.spawnVanillaSmoke(px, py, pz, 0F, 0F, 0F,
                large ? 2.5F : 1F, importance);
    }

    /** Der dunkle SMOKE-Partikel, den PrimedTnt pro Fuse-Tick mit Nullgeschwindigkeit erzeugt. */
    public void tntFuseSmoke(double px, double py, double pz) {
        this.spawnVanillaSmoke(px, py, pz, 0F, 0F, 0F, 1F, ParticlePriority.NORMAL);
    }

    public void redstoneBurnout(int x, int y, int z) {
        for (int i = 0; i < 5; i++) {
            this.spawnVanillaSmoke(x + this.random.nextDouble(), y + this.random.nextDouble(),
                    z + this.random.nextDouble(), 0F, 0F, 0F, 1F, ParticlePriority.NORMAL);
        }
    }

    public void fluidReaction(double px, double py, double pz) {
        int amount = scaledAmount(8, false);
        for (int i = 0; i < amount; i++) {
            this.spawnVanillaSmoke(px + this.random.nextDouble() - 0.5,
                    py + 0.7, pz + this.random.nextDouble() - 0.5,
                    0F, 0F, 0F, 2.5F, ParticlePriority.CRITICAL);
        }
    }

    public void lavaPop(double px, double py, double pz) {
        int index = this.add(ParticleType.LAVA, ParticlePriority.AMBIENT, px, py, pz,
                0F, 0F, 0F, -1, 0xFFFFFF, 1F, 0.10F);
        if (index < 0) return;
        this.setVanillaBaseVelocity(index, 0.8F);
        this.vy[index] = this.random.nextFloat() * 0.4F + 0.05F;
        this.size[index] = vanillaQuadSize() * (this.random.nextFloat() * 2F + 0.2F);
        this.lifetime[index] = (short) Math.max(1,
                (int) (16F / (this.random.nextFloat() * 0.8F + 0.2F)));
        this.light[index] = 1F;
    }

    public void underwater(double px, double py, double pz) {
        int index = this.add(ParticleType.UNDERWATER, ParticlePriority.AMBIENT,
                px, py - 0.125, pz, 0F, 0F, 0F, -1, 0xFFFFFF, 1F,
                vanillaQuadSize() * (this.random.nextFloat() * 0.6F + 0.2F));
        if (index >= 0) this.lifetime[index] = (short) Math.max(1,
                (int) (16F / (this.random.nextFloat() * 0.8F + 0.2F)));
    }

    /** Minecraft 26.2: vier Partikel pro Animate-Tick mit identischer Position und Bewegung. */
    public void portal(int blockX, int blockY, int blockZ, Direction.Axis axis, Random source) {
        for (int n = 0; n < 4; n++) {
            double px = blockX + source.nextDouble();
            double py = blockY + source.nextDouble();
            double pz = blockZ + source.nextDouble();
            float mx = (source.nextFloat() - 0.5F) * 0.5F;
            float my = (source.nextFloat() - 0.5F) * 0.5F;
            float mz = (source.nextFloat() - 0.5F) * 0.5F;
            int sign = source.nextInt(2) * 2 - 1;
            if (axis == Direction.Axis.X) {
                pz = blockZ + 0.5 + 0.25 * sign;
                mz = source.nextFloat() * 2F * sign;
            } else {
                px = blockX + 0.5 + 0.25 * sign;
                mx = source.nextFloat() * 2F * sign;
            }

            float shade = source.nextFloat() * 0.6F + 0.4F;
            int red = Math.clamp(Math.round(shade * 0.9F * 255F), 0, 255);
            int green = Math.clamp(Math.round(shade * 0.3F * 255F), 0, 255);
            int blue = Math.clamp(Math.round(shade * 255F), 0, 255);
            float particleSize = 0.1F * (source.nextFloat() * 0.2F + 0.5F);
            int index = this.add(ParticleType.PORTAL, ParticlePriority.AMBIENT,
                    px, py, pz, mx, my, mz, -1, red << 16 | green << 8 | blue,
                    1F, particleSize);
            if (index < 0) continue;
            this.originX[index] = px;
            this.originY[index] = py;
            this.originZ[index] = pz;
            this.lifetime[index] = (short) ((int) (source.nextFloat() * 10F) + 40);
            this.layer[index] = ParticleSprites.randomPortal(source.nextInt(8));
            this.rotation[index] = 0F;
        }
    }

    /** Ein einzelner, begrenzter Burst fuer den Kollaps der gesamten Portaloberflaeche. */
    public void portalCollapse(double centerX, double centerY, double centerZ,
                               Direction.Axis axis, int width, int height) {
        int amount = scaledAmount(Math.min(40, 18 + width * height), false);
        for (int i = 0; i < amount; i++) {
            double tangent = (this.random.nextDouble() - 0.5) * width;
            double vertical = (this.random.nextDouble() - 0.5) * height;
            double normal = (this.random.nextDouble() - 0.5) * 0.3;
            double px = centerX + (axis == Direction.Axis.X ? tangent : normal);
            double pz = centerZ + (axis == Direction.Axis.Z ? tangent : normal);
            int index = this.add(ParticleType.PORTAL_BURST, ParticlePriority.CRITICAL,
                    px, centerY + vertical, pz,
                    jitter(0.11F), jitter(0.11F), jitter(0.11F), -1,
                    this.random.nextBoolean() ? 0xC65CFF : 0x7621BC,
                    0.95F, 0.16F + this.random.nextFloat() * 0.12F);
            if (index >= 0) {
                this.lifetime[index] = (short) (18 + this.random.nextInt(20));
                this.rotationVelocity[index] = jitter(0.12F);
            }
        }
    }

    public void drip(double px, double py, double pz, boolean lava) {
        int index = this.add(ParticleType.DRIP_HANG, ParticlePriority.AMBIENT, px, py, pz,
                0F, 0F, 0F, -1, lava ? 0xFFFF80 : 0x334DFF, 1F,
                vanillaQuadSize() * 0.02F);
        if (index >= 0) {
            this.lifetime[index] = 40;
            this.aux0[index] = lava ? 1F : 0F;
        }
    }

    private void updateHangingDripColor(int index, int dripAge) {
        if (this.aux0[index] == 0F) {
            this.color[index] = 0x334DFF;
            return;
        }
        int green = Math.clamp(Math.round(255F * 16F / (dripAge + 16F)), 0, 255);
        int blue = Math.clamp(Math.round(255F * 4F / (dripAge + 8F)), 0, 255);
        this.color[index] = 0xFF0000 | green << 8 | blue;
    }

    private void startFallingDrip(int index) {
        boolean lava = this.aux0[index] != 0F;
        this.setKind(index, ParticleType.DRIP_FALL);
        this.vx[index] = this.vy[index] = this.vz[index] = 0F;
        this.size[index] = vanillaQuadSize();
        this.color[index] = lava ? 0xFF4915 : 0x334DFF;
        this.lifetime[index] = (short) Math.max(1,
                (int) (64F / (this.random.nextFloat() * 0.8F + 0.2F)));
    }

    private void landDrip(int index) {
        boolean lava = this.aux0[index] != 0F;
        this.setKind(index, ParticleType.DRIP_LAND);
        this.vx[index] = this.vy[index] = this.vz[index] = 0F;
        this.size[index] = vanillaQuadSize();
        this.color[index] = lava ? 0xFF4915 : 0x334DFF;
        this.lifetime[index] = (short) Math.max(1,
                (int) (16F / (this.random.nextFloat() * 0.8F + 0.2F)));
    }

    public void swim(double px, double py, double pz, double motionX, double motionY, double motionZ) {
        int index = this.add(ParticleType.BUBBLE, ParticlePriority.AMBIENT,
                px + jitter(0.25F), py + jitter(0.45F), pz + jitter(0.25F),
                (float) -motionX * 0.2F + jitter(0.02F),
                (float) -motionY * 0.2F + jitter(0.02F),
                (float) -motionZ * 0.2F + jitter(0.02F),
                -1, 0xFFFFFF, 1F,
                vanillaQuadSize() * (this.random.nextFloat() * 0.6F + 0.2F));
        if (index >= 0) this.lifetime[index] = (short) Math.max(1,
                (int) (8F / (this.random.nextFloat() * 0.8F + 0.2F)));
    }

    public void fallingDust(double px, double py, double pz, BlockState state) {
        BlockParticleSprite sprite = state.getParticleSprite();
        if (!sprite.isPresent()) return;
        int index = this.add(ParticleType.FALLING_DUST, ParticlePriority.AMBIENT,
                px, py, pz, 0F, 0F, 0F,
                -1, this.tintAt(sprite, px, pz), 1F,
                vanillaQuadSize() * 0.67499995F);
        if (index >= 0) {
            this.lifetime[index] = (short) Math.max(1,
                    (int) (32F / (this.random.nextFloat() * 0.8F + 0.2F) * 0.9F));
            this.rotationVelocity[index] = (this.random.nextFloat() - 0.5F)
                    * 0.1F * (float) (Math.PI * 2);
        }
    }

    /** Vanillas Wassereintritt: fuer Spielerbreite 0.6 je 13 Bubble und Splash. */
    public void splash(double px, double py, double pz,
                       double motionX, double motionY, double motionZ) {
        int amount = scaledAmount(13, false);
        double surfaceY = Math.floor(py) + 1.0;
        for (int i = 0; i < amount; i++) {
            double ox = (this.random.nextDouble() * 2.0 - 1.0) * 0.6;
            double oz = (this.random.nextDouble() * 2.0 - 1.0) * 0.6;
            this.spawnBubble(px + ox, surfaceY, pz + oz, motionX,
                    motionY - this.random.nextDouble() * 0.2, motionZ,
                    ParticlePriority.NORMAL);
            this.spawnSplash(px + ox, surfaceY, pz + oz,
                    motionX, motionY, motionZ, ParticlePriority.NORMAL);
        }
    }

    private void spawnBubble(double px, double py, double pz,
                             double motionX, double motionY, double motionZ,
                             ParticlePriority importance) {
        int index = this.add(ParticleType.BUBBLE, importance, px, py, pz,
                (float) (motionX * 0.2 + jitter(0.02F)),
                (float) (motionY * 0.2 + jitter(0.02F)),
                (float) (motionZ * 0.2 + jitter(0.02F)),
                -1, 0xFFFFFF, 1F,
                vanillaQuadSize() * (this.random.nextFloat() * 0.6F + 0.2F));
        if (index >= 0) this.lifetime[index] = (short) Math.max(1,
                (int) (8F / (this.random.nextFloat() * 0.8F + 0.2F)));
    }

    private void spawnSplash(double px, double py, double pz,
                             double motionX, double motionY, double motionZ,
                             ParticlePriority importance) {
        int index = this.add(ParticleType.SPLASH, importance, px, py, pz,
                0F, 0F, 0F, -1, 0xFFFFFF, 1F, vanillaQuadSize());
        if (index < 0) return;
        this.setVanillaBaseVelocity(index, 1F);
        if (motionY == 0.0 && (motionX != 0.0 || motionZ != 0.0)) {
            this.vx[index] = (float) motionX;
            this.vy[index] = 0.1F;
            this.vz[index] = (float) motionZ;
        }
        this.lifetime[index] = (short) Math.max(1,
                (int) (8F / (this.random.nextFloat() * 0.8F + 0.2F)));
    }

    public void explosion(double px, double py, double pz, float power, int affectedBlocks) {
        int emitter = this.add(ParticleType.EXPLOSION_EMITTER, ParticlePriority.CRITICAL,
                px, py, pz, 0F, 0F, 0F, -1, 0xFFFFFF, 0F, 0F);
        if (emitter >= 0) this.lifetime[emitter] = 8;
        if (affectedBlocks <= 0
                || GameSettings.get().particleQuality != GameSettings.ParticleQuality.ALL) return;
        if (this.explosionBurstCount >= MAX_EXPLOSION_BURSTS) {
            this.rejected += affectedBlocks;
            return;
        }
        int index = this.explosionBurstCount++;
        this.explosionX[index] = px;
        this.explosionY[index] = py;
        this.explosionZ[index] = pz;
        this.explosionRadius[index] = Math.max(0F, power);
        this.explosionWeight[index] = affectedBlocks;
    }

    /**
     * Minecraft 26.2s blockzahlgewichtete POOF/SMOKE-Wolke. Pro Tick werden global hoechstens
     * 512 Kandidaten erzeugt; feste Arrays und lineare Auswahl halten den Hotpath allokationsfrei.
     */
    private void flushExplosionClouds() {
        if (this.explosionBurstCount == 0) return;
        if (GameSettings.get().particleQuality != GameSettings.ParticleQuality.ALL) {
            this.explosionBurstCount = 0;
            return;
        }
        long totalWeight = 0;
        for (int i = 0; i < this.explosionBurstCount; i++) {
            totalWeight += this.explosionWeight[i];
        }
        int attempts = (int) Math.min(totalWeight, MAX_EXPLOSION_BLOCK_PARTICLES);
        for (int attempt = 0; attempt < attempts; attempt++) {
            long selected = this.random.nextLong(totalWeight);
            int burst = 0;
            while (selected >= this.explosionWeight[burst]) {
                selected -= this.explosionWeight[burst++];
            }
            this.spawnExplosionCloudParticle(burst);
        }
        this.explosionBurstCount = 0;
    }

    private void spawnExplosionCloudParticle(int burst) {
        double dx = this.random.nextDouble() * 2.0 - 1.0;
        double dy = this.random.nextDouble() * 2.0 - 1.0;
        double dz = this.random.nextDouble() * 2.0 - 1.0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-7) return;
        dx /= length;
        dy /= length;
        dz /= length;
        double radius = this.explosionRadius[burst];
        if (radius <= 0.0) return;
        double distance = Math.cbrt(this.random.nextDouble()) * radius;
        double ox = dx * distance, oy = dy * distance, oz = dz * distance;
        double px = this.explosionX[burst] + ox;
        double py = this.explosionY[burst] + oy;
        double pz = this.explosionZ[burst] + oz;
        if (this.world != null && this.world.getBlock((int) Math.floor(px),
                (int) Math.floor(py), (int) Math.floor(pz)) != Blocks.AIR) return;
        double speed = (0.5 / (distance / radius + 0.1))
                * this.random.nextDouble() * this.random.nextDouble() + 0.3;
        float mx = (float) (dx * speed), my = (float) (dy * speed), mz = (float) (dz * speed);
        if (this.random.nextBoolean()) {
            this.spawnExplosionPoof(this.explosionX[burst] + ox * 0.5,
                    this.explosionY[burst] + oy * 0.5, this.explosionZ[burst] + oz * 0.5,
                    mx, my, mz);
        } else {
            this.spawnVanillaSmoke(px, py, pz, mx, my, mz, 1F, ParticlePriority.CRITICAL);
        }
    }

    private void spawnExplosionPoof(double px, double py, double pz,
                                    float requestedX, float requestedY, float requestedZ) {
        int gray = Math.clamp((int) ((0.7F + this.random.nextFloat() * 0.3F) * 255F), 0, 255);
        float quadSize = 0.1F * (this.random.nextFloat() * this.random.nextFloat() * 6F + 1F);
        int index = this.add(ParticleType.POOF, ParticlePriority.CRITICAL, px, py, pz,
                requestedX + jitter(0.05F), requestedY + jitter(0.05F),
                requestedZ + jitter(0.05F), -1, gray << 16 | gray << 8 | gray,
                1F, quadSize);
        if (index >= 0) {
            this.lifetime[index] = (short) ((int) (16F
                    / (this.random.nextFloat() * 0.8F + 0.2F)) + 2);
        }
    }

    public void dispenser(double px, double py, double pz, int dx, int dy, int dz) {
        int amount = scaledAmount(10, false);
        for (int i = 0; i < amount; i++) {
            this.spawnVanillaSmoke(px + dx * 0.55 + jitter(0.2F),
                    py + dy * 0.55 + jitter(0.2F), pz + dz * 0.55 + jitter(0.2F),
                    dx * 0.06F + jitter(0.02F), dy * 0.06F + jitter(0.02F),
                    dz * 0.06F + jitter(0.02F), 1F, ParticlePriority.NORMAL);
        }
    }

    /** Vanillas DUST-Partikel inklusive zufaelliger Helligkeitsvariation und schneller Groessenrampe. */
    public void redstoneDust(double px, double py, double pz, int rgb, ParticlePriority importance) {
        int index = this.add(ParticleType.DUST, importance, px, py, pz,
                0F, 0F, 0F, -1, rgb, 1F, 0.10F);
        if (index < 0) return;
        this.setVanillaBaseVelocity(index, 0.1F);
        float common = this.random.nextFloat() * 0.4F + 0.6F;
        int r = Math.clamp((int) (((rgb >> 16) & 255) * common
                * (this.random.nextFloat() * 0.2F + 0.8F)), 0, 255);
        int g = Math.clamp((int) (((rgb >> 8) & 255) * common
                * (this.random.nextFloat() * 0.2F + 0.8F)), 0, 255);
        int b = Math.clamp((int) ((rgb & 255) * common
                * (this.random.nextFloat() * 0.2F + 0.8F)), 0, 255);
        this.color[index] = r << 16 | g << 8 | b;
        this.size[index] = vanillaQuadSize() * 0.75F;
        this.lifetime[index] = (short) Math.max(1,
                (int) (8F / (this.random.nextFloat() * 0.8F + 0.2F)));
    }

    public void redstoneWire(int x, int y, int z, BlockState state) {
        int power = state.get(Properties.POWER);
        if (power == 0) return;
        int rgb = RedstoneColors.forPower(power);
        for (Direction direction : Direction.horizontalValues()) {
            RedstoneSide side = state.get(Properties.wireSide(direction));
            if (side == RedstoneSide.UP) {
                this.spawnDustAlong(x, y, z, rgb, direction, Direction.UP, -0.5F, 0.5F);
                this.spawnDustAlong(x, y, z, rgb, Direction.DOWN, direction, 0F, 0.5F);
            } else if (side == RedstoneSide.SIDE) {
                this.spawnDustAlong(x, y, z, rgb, Direction.DOWN, direction, 0F, 0.5F);
            } else {
                this.spawnDustAlong(x, y, z, rgb, Direction.DOWN, direction, 0F, 0.3F);
            }
        }
    }

    private void spawnDustAlong(int x, int y, int z, int rgb,
                                Direction fixed, Direction along, float start, float end) {
        float range = end - start;
        if (this.random.nextFloat() >= 0.2F * range) return;
        float distance = start + range * this.random.nextFloat();
        double px = x + 0.5 + fixed.offsetX() * 0.4375 + along.offsetX() * distance;
        double py = y + 0.5 + fixed.offsetY() * 0.4375 + along.offsetY() * distance;
        double pz = z + 0.5 + fixed.offsetZ() * 0.4375 + along.offsetZ() * distance;
        this.redstoneDust(px, py, pz, rgb, ParticlePriority.AMBIENT);
    }

    public void fallingLeaf(double px, double py, double pz, BlockState state, boolean paleOak) {
        BlockParticleSprite blockSprite = state.getParticleSprite();
        int tint = paleOak || !blockSprite.isPresent() ? 0xFFFFFF
                : this.tintAt(blockSprite, px, pz);
        int index = this.add(ParticleType.FALLING_LEAF, ParticlePriority.AMBIENT,
                px, py, pz, 0F, -0.021F, 0F,
                ParticleSprites.randomLeaf(paleOak, this.random.nextInt(12)), tint, 1F,
                this.random.nextBoolean() ? 0.10F : 0.15F);
        if (index < 0) return;
        this.lifetime[index] = 300;
        this.rotation[index] = 0F;
        this.rotationVelocity[index] = (float) Math.toRadians(this.random.nextBoolean() ? -30 : 30);
        this.aux0[index] = (float) Math.toRadians(this.random.nextBoolean() ? -5 : 5);
        this.aux1[index] = (float) Math.toRadians(1000F + this.random.nextFloat() * 3000F);
    }

    private void spawnVanillaSmoke(double px, double py, double pz,
                                   float requestedX, float requestedY, float requestedZ,
                                   float scale, ParticlePriority importance) {
        ParticleType kind = scale > 1F ? ParticleType.LARGE_SMOKE : ParticleType.SMOKE;
        int index = this.add(kind, importance, px, py, pz,
                0F, 0F, 0F, -1, 0, 1F, 0.10F);
        if (index < 0) return;
        this.setVanillaBaseVelocity(index, 0.1F);
        this.vx[index] += requestedX;
        this.vy[index] += requestedY;
        this.vz[index] += requestedZ;
        int gray = Math.clamp((int) (this.random.nextFloat() * 0.3F * 255F), 0, 255);
        this.color[index] = gray << 16 | gray << 8 | gray;
        this.size[index] = vanillaQuadSize() * 0.75F * scale;
        this.lifetime[index] = (short) Math.max(1,
                (int) (8F / (this.random.nextFloat() * 0.8F + 0.2F) * scale));
    }

    private void spawnVanillaFlame(double px, double py, double pz,
                                   float requestedX, float requestedY, float requestedZ,
                                   ParticlePriority importance) {
        int index = this.add(ParticleType.FLAME, importance,
                px + (this.random.nextFloat() - this.random.nextFloat()) * 0.05F,
                py + (this.random.nextFloat() - this.random.nextFloat()) * 0.05F,
                pz + (this.random.nextFloat() - this.random.nextFloat()) * 0.05F,
                0F, 0F, 0F, -1, 0xFFFFFF, 1F, vanillaQuadSize());
        if (index < 0) return;
        this.setVanillaBaseVelocity(index, 0.01F);
        this.vx[index] += requestedX;
        this.vy[index] += requestedY;
        this.vz[index] += requestedZ;
        this.lifetime[index] = (short) (Math.max(1,
                (int) (8F / (this.random.nextFloat() * 0.8F + 0.2F))) + 4);
        this.light[index] = 1F;
    }

    private void setVanillaBaseVelocity(int index, float multiplier) {
        double mx = (this.random.nextFloat() * 2F - 1F) * 0.4F;
        double my = (this.random.nextFloat() * 2F - 1F) * 0.4F;
        double mz = (this.random.nextFloat() * 2F - 1F) * 0.4F;
        double speed = (this.random.nextFloat() + this.random.nextFloat() + 1F) * 0.15F;
        double length = Math.max(1.0E-7, Math.sqrt(mx * mx + my * my + mz * mz));
        this.vx[index] = (float) (mx / length * speed * 0.4F) * multiplier;
        this.vy[index] = ((float) (my / length * speed * 0.4F) + 0.1F) * multiplier;
        this.vz[index] = (float) (mz / length * speed * 0.4F) * multiplier;
    }

    private float vanillaQuadSize() {
        return 0.1F * (this.random.nextFloat() * 0.5F + 0.5F) * 2F;
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
        this.originX[index] = px;
        this.originY[index] = py;
        this.originZ[index] = pz;
        this.vx[index] = mx;
        this.vy[index] = my;
        this.vz[index] = mz;
        this.type[index] = (byte) kind.ordinal();
        this.priority[index] = (byte) importance.ordinal();
        this.translucent[index] = (byte) (kind.translucent ? 1 : 0);
        this.onGround[index] = 0;
        this.size[index] = scale > 0 ? scale : kind.size;
        this.rotation[index] = this.random.nextFloat() * (float) (Math.PI * 2);
        this.rotationVelocity[index] = 0F;
        this.aux0[index] = this.aux1[index] = 0F;
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
                || kind == ParticleType.LAVA
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
            if (TYPES[this.type[index]] == ParticleType.EXPLOSION_EMITTER) continue;
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
                chunk.blockLight.get(bx & ChunkSection.MASK, by, bz & ChunkSection.MASK),
                this.world.getEnvironment().ambientLight());
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
        this.originX[index] = this.originX[last]; this.originY[index] = this.originY[last]; this.originZ[index] = this.originZ[last];
        this.vx[index] = this.vx[last]; this.vy[index] = this.vy[last]; this.vz[index] = this.vz[last];
        this.size[index] = this.size[last]; this.rotation[index] = this.rotation[last];
        this.rotationVelocity[index] = this.rotationVelocity[last];
        this.aux0[index] = this.aux0[last]; this.aux1[index] = this.aux1[last];
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
            if (kind == ParticleType.EXPLOSION_EMITTER) continue;
            if ((this.translucent[i] != 0) != translucent) continue;
            float px = (float) (this.prevX[i] + (this.x[i] - this.prevX[i]) * partialTick - cam.x);
            float py = (float) (this.prevY[i] + (this.y[i] - this.prevY[i]) * partialTick - cam.y);
            float pz = (float) (this.prevZ[i] + (this.z[i] - this.prevZ[i]) * partialTick - cam.z);
            float scale = this.renderSize(i, kind, partialTick);
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

    private float renderSize(int index, ParticleType kind, float partialTick) {
        float base = this.size[index];
        float progress = ((this.age[index] & 0xFFFF) + partialTick)
                / Math.max(1F, this.lifetime[index] & 0xFFFF);
        if (kind == ParticleType.SMOKE || kind == ParticleType.LARGE_SMOKE
                || kind == ParticleType.DUST || kind == ParticleType.PORTAL_BURST
                || kind == ParticleType.FALLING_DUST) {
            return base * Math.clamp(progress * 32F, 0F, 1F);
        }
        if (kind == ParticleType.PORTAL) {
            float inverse = 1F - progress;
            return base * (1F - inverse * inverse);
        }
        if (kind == ParticleType.LAVA) return base * (1F - progress * progress);
        if (kind == ParticleType.FLAME) return base * (1F - progress * progress * 0.5F);
        return base;
    }
}
