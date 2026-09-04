package de.skyengine.game.world;

import de.skyengine.game.Gamemode;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.item.BucketItem;
import de.skyengine.game.world.item.FlintAndSteelItem;
import de.skyengine.game.world.item.ItemFrameItem;
import de.skyengine.game.world.item.MinecartItem;
import de.skyengine.game.world.item.ShearsItem;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.game.world.item.ToolType;
import de.skyengine.game.world.loot.LootContext;

import java.util.ArrayList;

/**
 * Authoritative, renderer-independent block rules shared by integrated and dedicated play.
 * Sound, particles and GUI opening stay client presentation concerns; all world/inventory
 * mutations live here so multiplayer cannot drift from the former singleplayer rules.
 */
public final class PlayerBlockActions {
    public record UseResult(boolean accepted, int x, int y, int z) {
        public static UseResult rejected() { return new UseResult(false, 0, 0, 0); }
    }

    public static float destroyProgress(EntityPlayer player, BlockState state) {
        float hardness = state.getBlock().getHardness();
        if (hardness < 0F) return 0F;
        if (hardness == 0F || player.getGamemode().isInstantBreak()) return 1F;
        ItemStack held = player.getInventory().get(player.getSelectedSlot());
        float speed = 1F;
        if (held.getItem() instanceof ToolItem tool && tool.getType() == state.getBlock().getToolType()) {
            speed = tool.getTier().speed();
        }
        float progress = speed / hardness / (isHarvestable(state, held) ? 30F : 100F);
        return player.onGround ? progress : progress / 5F;
    }

    public static boolean isHarvestable(BlockState state, ItemStack held) {
        ToolType required = state.getBlock().getToolType();
        if (required == null) return true;
        if (!(held.getItem() instanceof ToolItem tool) || tool.getType() != required) return false;
        return tool.getTier().level() >= state.getBlock().getHarvestLevel();
    }

    public static boolean breakBlock(Dimension world, EntityPlayer player, int x, int y, int z,
                                     BlockState expected, boolean applyDurability) {
        if (world.getBlock(x, y, z) != expected.getId()) return false;
        ItemStack held = player.getInventory().get(player.getSelectedSlot());
        ArrayList<ItemStack> drops = new ArrayList<>(2);
        if (player.getGamemode().dropsItems() && isHarvestable(expected, held)) {
            LootContext context = new LootContext(world, x, y, z, expected, held,
                    LootContext.Cause.PLAYER, 0F, world.random());
            expected.getBlock().appendDrops(context, (stack, dropX, dropY, dropZ) -> drops.add(stack));
        }
        boolean removed = world.runPlayerBlockChange(() -> {
            expected.getBlock().onBreak(world, x, y, z, expected);
            return world.setBlock(x, y, z, Blocks.AIR);
        });
        if (!removed) return false;
        for (ItemStack drop : drops) world.spawnItem(x + 0.5, y + 0.5, z + 0.5, drop);
        if (applyDurability) damageHeldTool(player, held, expected);
        return true;
    }

    private static void damageHeldTool(EntityPlayer player, ItemStack held, BlockState broken) {
        int durability = 0;
        if (held.getItem() instanceof ToolItem tool && broken.getBlock().getHardness() > 0) {
            durability = tool.getTier().durability();
        } else if (held.getItem() instanceof ShearsItem
                && (broken.isLeaves() || switch (broken.getBlock().getIdentifier().path()) {
                    case "short_grass", "fern", "tall_grass", "dead_bush" -> true;
                    default -> false;
                })) {
            durability = ShearsItem.DURABILITY;
        }
        if (durability <= 0) return;
        held.setDamage(held.getDamage() + 1);
        if (held.getDamage() >= durability) {
            player.getInventory().set(player.getSelectedSlot(), ItemStack.EMPTY);
        } else {
            player.getInventory().setChanged();
        }
    }

    public static UseResult useOrPlace(Dimension world, EntityPlayer player, int hitX, int hitY, int hitZ,
                                       Direction face, double relativeHitX, double relativeHitY,
                                       double relativeHitZ, ItemStack held) {
        BlockState hitState = Blocks.getState(world.getBlock(hitX, hitY, hitZ));
        boolean placingWhileSneaking = player.isSecondaryUseActive() && !held.isEmpty()
                && held.getItem().getPlacedBlock() != null;

        /* Item-specific actions belong to authoritative gameplay, not to the client GUI. */
        if (held.getItem() instanceof MinecartItem && placeMinecart(world, player, hitX, hitY, hitZ,
                hitState)) return new UseResult(true, hitX, hitY, hitZ);
        if (held.getItem() instanceof FlintAndSteelItem
                && ignite(world, player, hitX, hitY, hitZ, face, hitState, held)) {
            return new UseResult(true, hitX, hitY, hitZ);
        }
        if (!placingWhileSneaking && hitState.getBlock().onUse(world, hitX, hitY, hitZ,
                hitState, player.yaw)) {
            return new UseResult(true, hitX, hitY, hitZ);
        }
        if (held.isEmpty()) return UseResult.rejected();
        if (held.getItem() instanceof BucketItem bucket
                && useBucket(world, player, hitX, hitY, hitZ, face, hitState, bucket)) {
            return new UseResult(true, hitX, hitY, hitZ);
        }
        if (held.getItem() instanceof ItemFrameItem
                && placeItemFrame(world, player, hitX, hitY, hitZ, face)) {
            return new UseResult(true, hitX, hitY, hitZ);
        }
        Block block = held.getItem().getPlacedBlock();
        if (block == null) return UseResult.rejected();

        if (block.getDefaultState().getValues().containsKey(Properties.SLAB_TYPE)
                && hitState.getBlock() == block) {
            SlabType type = hitState.get(Properties.SLAB_TYPE);
            boolean merge = (type == SlabType.BOTTOM && face == Direction.UP)
                    || (type == SlabType.TOP && face == Direction.DOWN);
            if (merge && world.runPlayerBlockChange(() -> world.setBlock(hitX, hitY, hitZ,
                    hitState.with(Properties.SLAB_TYPE, SlabType.DOUBLE).getId()))) {
                consumeHeld(player);
                return new UseResult(true, hitX, hitY, hitZ);
            }
        }

        int x = hitX, y = hitY, z = hitZ;
        if (!hitState.getBlock().isReplaceable()) {
            x += face.offsetX(); y += face.offsetY(); z += face.offsetZ();
        }
        if (y < 0 || y >= 512 || !isReplaceable(world.getBlock(x, y, z))) return UseResult.rejected();
        BlockState place = block.getPlacementState(world, x, y, z, face.offsetX(), face.offsetY(),
                face.offsetZ(), relativeHitX, relativeHitY, relativeHitZ, player.yaw, player.pitch,
                player.isSecondaryUseActive());
        if (place == null || collides(world, player, place, x, y, z)) return UseResult.rejected();
        int targetX = x, targetY = y, targetZ = z;
        if (!world.runPlayerBlockChange(() -> world.placeBlock(targetX, targetY, targetZ, place, held))) {
            return UseResult.rejected();
        }
        consumeHeld(player);
        return new UseResult(true, x, y, z);
    }

    private static boolean isReplaceable(int stateId) {
        BlockState state = Blocks.getState(stateId);
        return stateId == Blocks.AIR || state.isFluid() || state.getBlock().isReplaceable();
    }

    private static boolean collides(Dimension world, EntityPlayer player, BlockState state,
                                    int x, int y, int z) {
        for (AABB local : state.getCollisionShape().boxes()) {
            AABB box = local.copy().move(x, y, z);
            if (box.intersects(player.getBoundingBox()) || world.intersectsCollidableEntity(box)) return true;
        }
        return false;
    }

    private static void consumeHeld(EntityPlayer player) {
        if (player.getGamemode() != Gamemode.SURVIVAL) return;
        int slot = player.getSelectedSlot();
        ItemStack held = player.getInventory().get(slot);
        held.setCount(held.getCount() - 1);
        if (held.isEmpty()) player.getInventory().set(slot, ItemStack.EMPTY);
        else player.getInventory().setChanged();
    }

    private static boolean placeMinecart(Dimension world, EntityPlayer player, int x, int y, int z,
                                         BlockState rail) {
        if (!rail.getValues().containsKey(Properties.RAIL_SHAPE)
                && !rail.getValues().containsKey(Properties.STRAIGHT_RAIL_SHAPE)) return false;
        double yOffset = de.skyengine.game.world.block.behavior.RailBehavior.shape(rail).isAscending()
                ? 0.5625 : 0.0625;
        world.spawnMinecart(x + 0.5, y + yOffset, z + 0.5);
        consumeHeld(player);
        return true;
    }

    private static boolean placeItemFrame(Dimension world, EntityPlayer player, int hitX, int hitY,
                                          int hitZ, Direction face) {
        int x = hitX + face.offsetX(), y = hitY + face.offsetY(), z = hitZ + face.offsetZ();
        if (!world.isPlayerInteractionReady(x, y, z) || !world.placeItemFrame(x, y, z, face)) return false;
        consumeHeld(player);
        return true;
    }

    private static boolean ignite(Dimension world, EntityPlayer player, int hitX, int hitY, int hitZ,
                                  Direction face, BlockState hitState, ItemStack held) {
        int[] target = placementTarget(world, hitX, hitY, hitZ, face, hitState);
        if (target != null && world.runPlayerBlockChange(() ->
                de.skyengine.game.world.dimension.NetherPortalShape.activateNear(
                        world, target[0], target[1], target[2]))) {
            if (world.getSoundManager() != null) {
                world.getSoundManager().playIgnite(target[0] + 0.5, target[1] + 0.5, target[2] + 0.5);
                world.getSoundManager().playPortalTrigger(target[0] + 0.5, target[1] + 1.5, target[2] + 0.5);
            }
            damageFlintAndSteel(player, held);
            return true;
        }
        var explosive = hitState.getBlock().getBehavior(
                de.skyengine.game.world.block.behavior.ExplosionBehavior.class);
        if (explosive == null) return false;
        explosive.prime(world, hitX, hitY, hitZ);
        damageFlintAndSteel(player, held);
        return true;
    }

    private static void damageFlintAndSteel(EntityPlayer player, ItemStack held) {
        if (player.getGamemode() != Gamemode.SURVIVAL) return;
        held.setDamage(held.getDamage() + 1);
        if (held.getDamage() >= FlintAndSteelItem.DURABILITY) {
            player.getInventory().set(player.getSelectedSlot(), ItemStack.EMPTY);
        } else player.getInventory().setChanged();
    }

    private static boolean useBucket(Dimension world, EntityPlayer player, int hitX, int hitY, int hitZ,
                                     Direction face, BlockState hitState, BucketItem bucket) {
        if (bucket.isEmpty()) {
            if (!hitState.isFluid() || hitState.get(Properties.FALLING)
                    || hitState.get(Properties.LEVEL) != 0) return false;
            if (!world.runPlayerBlockChange(() -> world.setBlock(hitX, hitY, hitZ, Blocks.AIR))) return false;
            boolean lava = hitState.getBlock().getFluidInfo().lava;
            world.playBucketFill(hitX, hitY, hitZ, lava);
            consumeHeld(player, Items.get(Identifier.of(lava ? "lava_bucket" : "water_bucket")));
            return true;
        }
        int[] target = placementTarget(world, hitX, hitY, hitZ, face, hitState);
        if (target == null) return false;
        Block fluid = bucket.getFluid();
        if (world.getEnvironment().ultrawarm() && fluid.getFluidInfo() != null
                && !fluid.getFluidInfo().lava) {
            world.playFluidExtinguish(target[0], target[1], target[2]);
            consumeHeld(player, Items.get(Identifier.of("bucket")));
            return true;
        }
        int source = fluid.getDefaultState().with(Properties.LEVEL, 0)
                .with(Properties.FALLING, false).getId();
        if (!world.runPlayerBlockChange(() -> world.setBlock(target[0], target[1], target[2], source))) {
            return false;
        }
        world.scheduleTick(target[0], target[1], target[2], 1);
        world.playBucketEmpty(target[0], target[1], target[2], fluid.getFluidInfo().lava);
        consumeHeld(player, Items.get(Identifier.of("bucket")));
        return true;
    }

    private static int[] placementTarget(Dimension world, int hitX, int hitY, int hitZ,
                                         Direction face, BlockState hitState) {
        if (hitState.getBlock().isReplaceable()) return new int[]{hitX, hitY, hitZ};
        int x = hitX + face.offsetX(), y = hitY + face.offsetY(), z = hitZ + face.offsetZ();
        if (y < 0 || y >= 512 || !world.isPlayerInteractionReady(x, y, z)
                || !isReplaceable(world.getBlock(x, y, z))) return null;
        return new int[]{x, y, z};
    }

    private static void consumeHeld(EntityPlayer player, Item result) {
        if (player.getGamemode() != Gamemode.SURVIVAL) return;
        int slot = player.getSelectedSlot();
        ItemStack held = player.getInventory().get(slot);
        if (held.getCount() > 1) {
            held.setCount(held.getCount() - 1);
            if (result != null) player.getInventory().insert(new ItemStack(result, 1));
            else player.getInventory().setChanged();
        } else {
            player.getInventory().set(slot, result == null ? ItemStack.EMPTY : new ItemStack(result, 1));
        }
    }

    public static Direction directionFromFace(int face) {
        for (Direction direction : Direction.sharedValues()) if (direction.faceIndex() == face) return direction;
        throw new IllegalArgumentException("Unknown face " + face);
    }

    private PlayerBlockActions() { }
}
