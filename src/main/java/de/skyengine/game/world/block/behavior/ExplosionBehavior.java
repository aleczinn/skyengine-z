package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;

/**
 * TNT-Verhalten: Der Block bleibt beim Platzieren inert (normaler, stapelbarer Block) und wird erst
 * per Rechtsklick gezündet — dann verschwindet der Block und an seiner Stelle spawnt eine {@link
 * de.skyengine.game.entity.PrimedTntEntity} (Fuse-Countdown + weißer Blink), die am Ende über {@link
 * de.skyengine.game.world.Explosion#explode} detoniert.
 *
 * <p>Sprengkraft ({@code power}) und Zünddauer ({@code fuse}, in Ticks) stammen aus der Block-JSON
 * (Felder {@code explosion_power} / {@code explosion_fuse}) und werden in {@link
 * de.skyengine.game.world.block.archetype.ArchetypeBlockFactory} verdrahtet. Die Getter liefern sie
 * an die Ketten­reaktion in {@code Explosion}.
 */
public final class ExplosionBehavior implements BlockBehavior {

    private final float power;
    private final int fuse;

    public ExplosionBehavior(float power, int fuse) {
        this.power = power;
        this.fuse = fuse;
    }

    public float power() {
        return this.power;
    }

    public int fuse() {
        return this.fuse;
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        /* Nachbar-Ring an (3-arg-setBlock): das TNT verschwindet wie bei einem normalen Abbau,
           also muss z.B. eine Fackel darauf mitfallen statt schweben zu bleiben. */
        world.setBlock(x, y, z, Blocks.AIR);                          // TNT wird zur Entity
        world.spawnPrimedTnt(x + 0.5, y, z + 0.5, this.power, this.fuse);
        return true;
    }
}
