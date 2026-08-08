package de.skyengine.game.world.block.behavior;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.entity.PrimedTntEntity;
import de.skyengine.game.world.Explosion;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.archetype.ArchetypeBlockFactory;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.TooltipContext;
import de.skyengine.game.world.redstone.RedstonePower;

import java.text.NumberFormat;
import java.util.Map;
import java.util.Locale;

/**
 * TNT-Verhalten: Der Block bleibt beim Platzieren inert (normaler, stapelbarer Block) und zündet
 * erst auf ein <b>Redstone-Signal</b> — dann verschwindet er und an seiner Stelle spawnt eine
 * {@link PrimedTntEntity} (Fuse-Countdown + weißer Blink), die am Ende
 * über {@link Explosion#explode} detoniert.
 *
 * <p>Die Zündung folgt MCs {@code TntBlock}: {@code neighborChanged} und {@code onPlace} prüfen
 * beide {@code hasNeighborSignal} und zünden <b>sofort</b>, ohne geplanten Tick. Der Platzier-Fall
 * ist kein Luxus — er deckt ab, dass frisch gesetztes TNT neben einer bereits brennenden Leitung
 * losgeht, statt auf eine Signaländerung zu warten, die nie kommt.
 *
 * <p><b>Rechtsklick zündet nicht.</b> In Vanilla reagiert {@code useItemOn} ausschließlich auf
 * Feuerzeug und Feuerkugel; jede andere Hand fällt durch. Das Feuerzeug ist bei uns ein
 * Sonderfall im {@code GameContainer} (Muster Eimer), weil {@code Item} keinen Interaktions-Hook
 * hat. MCs dritter Zündweg — Abbauen mit {@code unstable=true} — entfällt, die Property gibt es
 * hier nicht.
 *
 * <p>Sprengkraft ({@code power}) und Zünddauer ({@code fuse}, in Ticks) stammen aus der Block-JSON
 * (Felder {@code explosion_power} / {@code explosion_fuse}) und werden in {@link
 * ArchetypeBlockFactory} verdrahtet. Die Getter liefern sie
 * an die Ketten­reaktion in {@code Explosion}.
 */
public final class ExplosionBehavior implements BlockBehavior {

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

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
    public void appendTooltipVariables(ItemStack stack, TooltipContext context, Map<String, String> variables) {
        variables.put("fuse_ticks", Integer.toString(this.fuse));
        variables.put("fuse_seconds", formatSeconds(this.fuse / 20.0));
    }

    private static String formatSeconds(double seconds) {
        Locale locale = Locale.forLanguageTag(I18n.code().replace('_', '-'));
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return format.format(seconds);
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (RedstonePower.isReceiving(world, x, y, z)) this.prime(world, x, y, z);
        return state;
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        if (RedstonePower.isReceiving(world, x, y, z)) this.prime(world, x, y, z);
    }

    /**
     * Block weg, Entity hin — MCs {@code TntBlock.prime}. Public, damit das Feuerzeug denselben
     * Weg nimmt statt einen zweiten zu erfinden.
     *
     * <p>Der Nachbar-Ring bleibt an (3-arg-setBlock): das TNT verschwindet wie bei einem normalen
     * Abbau, also muss z.B. eine Fackel darauf mitfallen statt schweben zu bleiben. Aufgerufen
     * wird das aus {@code onNeighborUpdate} heraus, also mitten im State-Update dieser Zelle —
     * das ist unkritisch, weil der Aufrufer danach nur noch den unveränderten Rückgabe-State
     * gegen den alten vergleicht und nichts mehr schreibt.
     */
    public void prime(World world, int x, int y, int z) {
        world.setBlock(x, y, z, Blocks.AIR);
        world.spawnPrimedTnt(x + 0.5, y, z + 0.5, this.power, this.fuse);
    }
}
