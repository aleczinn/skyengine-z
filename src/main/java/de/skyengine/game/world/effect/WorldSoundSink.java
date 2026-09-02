package de.skyengine.game.world.effect;

import de.skyengine.audio.BlockOpenSound;
import de.skyengine.audio.BlockSoundGroup;
import de.skyengine.game.entity.MinecartEntity;

/** Headless-safe destination for cosmetic world sounds. */
public interface WorldSoundSink {
    WorldSoundSink NONE = new WorldSoundSink() {};

    default void playStep(BlockSoundGroup group) {}
    default void playHit(BlockSoundGroup group, double x, double y, double z) {}
    default void playBreak(BlockSoundGroup group, double x, double y, double z) {}
    default void playPlace(BlockSoundGroup group, double x, double y, double z) {}
    default void playComparatorClick(boolean subtract, double x, double y, double z) {}
    default void playLeverClick(boolean powered, double x, double y, double z) {}
    default void playExplosion(double x, double y, double z) {}
    default void playFuse(double x, double y, double z) {}
    default void playPistonExtend(double x, double y, double z) {}
    default void playPistonContract(double x, double y, double z) {}
    default void playFizz(double x, double y, double z) {}
    default void playFluidExtinguish(double x, double y, double z) {}
    default void playWaterAmbient(double x, double y, double z) {}
    default void playLavaAmbient(double x, double y, double z) {}
    default void playLavaPop(double x, double y, double z) {}
    default void playIgnite(double x, double y, double z) {}
    default void playPortalAmbient(double x, double y, double z) {}
    default void playPortalTrigger(double x, double y, double z) {}
    default void playPortalTravel() {}
    default void playDispenserSuccess(double x, double y, double z) {}
    default void playDispenserFailure(double x, double y, double z) {}
    default void playBucketEmpty(boolean lava, double x, double y, double z) {}
    default void playBucketFill(boolean lava, double x, double y, double z) {}
    default void playItemFrameRemoveItem(double x, double y, double z) {}
    default void playItemFrameBreak(double x, double y, double z) {}
    default void playBlockOpen(BlockOpenSound sound, double x, double y, double z) {}
    default void playBlockClose(BlockOpenSound sound, double x, double y, double z) {}
    default void beginMinecartSounds() {}
    default void updateMinecartSound(MinecartEntity minecart, double x, double y, double z,
                                     double speed, boolean riddenByLocalPlayer) {}
    default void endMinecartSounds() {}
}
