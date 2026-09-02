package de.skyengine.game.world.effect;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.particle.ParticlePriority;

import java.util.Random;

/** Headless-safe destination for cosmetic particles emitted by gameplay simulation. */
public interface ParticleSink {
    ParticleSink NONE = new ParticleSink() {};

    default int count() { return 0; }
    default long rejected() { return 0; }
    default void tick() {}
    default void clear() {}
    default void blockHit(BlockState state, double hitX, double hitY, double hitZ,
                          int faceX, int faceY, int faceZ) {}
    default void blockBreak(int x, int y, int z, BlockState state) {}
    default void landing(double x, double y, double z, BlockState ground, float fallDistance) {}
    default void sprint(double x, double y, double z, BlockState ground, double motionX, double motionZ) {}
    default void torch(double x, double y, double z) {}
    default void smoke(double x, double y, double z, boolean large, ParticlePriority priority) {}
    default void tntFuseSmoke(double x, double y, double z) {}
    default void redstoneBurnout(int x, int y, int z) {}
    default void fluidReaction(double x, double y, double z) {}
    default void lavaPop(double x, double y, double z) {}
    default void underwater(double x, double y, double z) {}
    default void portal(int x, int y, int z, Direction.Axis axis, Random random) {}
    default void portalCollapse(double x, double y, double z, Direction.Axis axis, int width, int height) {}
    default void drip(double x, double y, double z, boolean lava) {}
    default void swim(double x, double y, double z, double motionX, double motionY, double motionZ) {}
    default void fallingDust(double x, double y, double z, BlockState state) {}
    default void splash(double x, double y, double z, double motionX, double motionY, double motionZ) {}
    default void explosion(double x, double y, double z, float power, int affectedBlocks) {}
    default void dispenser(double x, double y, double z, int dx, int dy, int dz) {}
    default void redstoneDust(double x, double y, double z, int rgb, ParticlePriority priority) {}
    default void redstoneWire(int x, int y, int z, BlockState state) {}
    default void fallingLeaf(double x, double y, double z, BlockState state, boolean paleOak) {}
    default void itemCrumb(int textureLayer, double x, double y, double z,
                           double directionX, double directionY, double directionZ) {}
}
