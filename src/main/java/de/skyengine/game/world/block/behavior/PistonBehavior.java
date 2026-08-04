package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.PistonType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.redstone.RedstonePower;

/**
 * Kolben-Basis: Platzierung (schaut den Spieler an) und die Schub-Zustandsmaschine.
 *
 * <p><b>Ablauf Extend</b> (scheduledTick): {@link PistonResolver} liefert die STRUKTUR
 * (max. 12 — inkl. Slime-/Honig-Verkettung über {@code sticky_group}). Alle Quell-States
 * werden VOR den Writes ge-snapshottet — bei Verzweigungen können Ziele beliebige andere
 * Quellzellen überschreiben, mit dem Snapshot ist die Schreib-Reihenfolge irrelevant.
 * Jede Zielzelle wird ein {@code moving_piston} mit dem transportierten State, Quellzellen
 * ohne Ziel-Rolle werden geräumt, die Kopf-Zelle wird die Source-BE mit dem
 * {@code piston_head}-State, die Basis geht sofort auf {@code extended=true}. Danach
 * gezielte Nachbar-Ringe. Die BEs materialisieren nach 2 Ticks selbst
 * ({@link PistonMovingBlockEntity}).
 *
 * <p><b>Ablauf Retract:</b> die Source-BE sitzt an der KOPF-Zelle (transportiert den
 * Pull-Block des klebrigen Kolbens oder Luft; der zurückgleitende Arm ist reine
 * Renderer-Optik). Die Basis bleibt während der Animation ein echter
 * {@code piston[extended=true]}-Block im Chunk-Mesh — als BE-gerenderter Würfel (flaches
 * Zell-Licht ohne AO/Smooth-Lighting) blitzte sie sichtbar auf — und wird erst vom
 * finish der BE eingefahren.
 *
 * <p><b>Flicker-Regel:</b> laufende Bewegungen werden nie ABGEBROCHEN — die eigene laufende
 * Bewegung wird bei einer Gegenflanke aber sofort VOLLENDET (Fast-Forward, MCs
 * {@code clearPistonTileEntity}) und danach frisch entschieden; fremde Animationen werden
 * weiter gepollt. Signal wie MC über die 5 Seiten ohne die Blickrichtung, dazu
 * Quasi-Konnektivität über die Zelle darüber (s. {@link #hasSignal}).
 *
 * <p><b>Timing (MC-Parität):</b> Flanken laufen als Block-Event ({@code World.enqueueBlockEvent})
 * im SELBEN Game-Tick — 0 Ticks Reaktion wie MCs Block-Events — gefolgt von 2 Game-Ticks
 * Animation. Der finish der Source-BE reiht den Re-Check ebenfalls als Block-Event ein
 * (Drain B desselben Ticks): eine 2on/2off-Observer-Clock treibt den Kolben damit im
 * 4-Tick-Rhythmus wie MC. Der Tick-Scheduler bleibt nur Fallback für nicht simulierte
 * Chunks und fremde Animationen (persistiert im Save).
 */
public final class PistonBehavior implements BlockBehavior {

    private final boolean sticky;

    public PistonBehavior(boolean sticky) {
        this.sticky = sticky;
    }

    public boolean isSticky() {
        return this.sticky;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING_ALL, facingToPlayer(ctx))
                .with(Properties.EXTENDED, false);
    }

    /** 6-Richtungs-Facing zum Spieler (Engine-Konvention: positiver Pitch = runterschauen). */
    static Direction facingToPlayer(PlacementContext ctx) {
        if (ctx.playerPitch() > 45) return Direction.UP;
        if (ctx.playerPitch() < -45) return Direction.DOWN;
        return Direction.fromYaw(ctx.playerYaw()).opposite();
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        boolean want = hasSignal(world, x, y, z, f);
        /* Effektiver Zustand statt EXTENDED: der Basis-State bleibt beim Retract bis zum
           finish auf true und würde die AN-Flanke während des Einfahrens verschlucken. */
        PistonMovingBlockEntity own = ownSourceMoving(world, x, y, z, f);
        boolean effectiveExtended = own != null ? own.isExtending() : state.get(Properties.EXTENDED);
        if (want != effectiveExtended) {
            world.enqueueBlockEvent(x, y, z);
        }
        return state;
    }

    @Override
    public void onBlockEvent(World world, int x, int y, int z, BlockState state) {
        this.evaluate(world, x, y, z, state);
    }

    /** Fallback-Pfad (nicht simulierter Chunk, fremde Animation) — gleiche Logik wie das Event. */
    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        this.evaluate(world, x, y, z, state);
    }

    private void evaluate(World world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        boolean want = hasSignal(world, x, y, z, f);

        /* Fast-Forward NUR bei echter Gegenflanke: die EIGENE laufende Bewegung sofort
           vollenden (nie abbrechen), dann frisch entscheiden. Läuft sie schon in die
           gewünschte Richtung, ist nichts zu tun — der Re-Check aus dem finish (Drain B,
           selber Tick) würde sonst die gerade gestartete Gegenbewegung sofort vollenden:
           kein einziger gerenderter Frame, das Einfahren wäre ein Sprung. Beim sticky-Extend
           zusätzlich die Fracht-BE direkt vor dem Kopf mit-vollenden: sonst sähe
           resolveRetract dort noch MOVING und der Rückzug ließe den Block stehen. */
        PistonMovingBlockEntity own = ownSourceMoving(world, x, y, z, f);
        boolean dropCargo = false;
        if (own != null) {
            boolean wasExtending = own.isExtending();
            if (want == wasExtending) return;
            own.finishNow();
            if (this.sticky && wasExtending) {
                int cx = x + 2 * f.offsetX(), cy = y + 2 * f.offsetY(), cz = z + 2 * f.offsetZ();
                if (world.getBlockEntity(cx, cy, cz) instanceof PistonMovingBlockEntity cargo
                        && cargo.getFacing() == f && cargo.isExtending() && !cargo.isSource()) {
                    dropCargo = isTooEarlyToPull(world, cargo);
                    cargo.finishNow();
                }
            }
            /* finish flippt ggf. EXTENDED — frisch lesen (defensiv: Zelle könnte ersetzt sein). */
            state = Blocks.getState(world.getBlock(x, y, z));
            if (!state.getValues().containsKey(Properties.EXTENDED)) return;
        }

        boolean extended = state.get(Properties.EXTENDED);
        if (want && !extended) {
            this.extend(world, x, y, z, state, f);
        } else if (!want && extended) {
            this.retract(world, x, y, z, state, f, dropCargo);
        }
    }

    /**
     * MCs Drop-Regel: kam die Gegenflanke früh genug, lässt ein klebriger Kolben die Fracht
     * LIEGEN statt sie zurückzuziehen. Das ist der bekannte Block-Dropper aus kurzen Pulsen
     * (Beobachter, 1-Tick-Repeater-Puls) und Grundlage realer Maschinen.
     *
     * <p>Vanilla ({@code PistonBaseBlock.checkIfExtend}) schickt dafür statt TRIGGER_CONTRACT ein
     * TRIGGER_DROP, sobald {@code getProgress(0) < 0.5 || gameTime == lastTicked}; in
     * {@code triggerEvent} überspringt dieser Typ den ganzen Zieh-Zweig. Die dritte Klausel
     * {@code !isHandlingTick} hat hier keine Entsprechung — wir haben keinen Pfad, der Kolben
     * ausserhalb des Ticks schaltet.
     *
     * <p><b>Die Schwelle ist bei uns 1.0, nicht Vanillas 0.5 — das ist Absicht</b> und darf nicht
     * „zurück auf MC" korrigiert werden. Vanilla puffert frisch angelegte BlockEntities
     * ({@code pendingBlockEntityTickers}), sie ticken erst im Folge-Tick; unsere ticken schon im
     * Anlege-Tick (das „Off-by-one", das {@link PistonMovingBlockEntity} in seiner Animation
     * absorbiert). Unser Fortschritt steht beim Gegenflanken-Check deshalb genau eine Stufe
     * weiter als Vanillas {@code progressO}. Für einen Puls ab Tick T:
     *
     * <pre>
     * Puls-Ende  Vanilla progressO  unser lastProgress   Ergebnis
     * T+1        0                  0                    Drop
     * T+2        0                  0.5                  Drop     (Beobachter-Puls!)
     * T+3        0.5                BE ist schon fertig   Rückzug
     * </pre>
     *
     * Mit 0.5 fiel genau die Zeile T+2 heraus — und das ist die häufigste von allen, weil ein
     * Beobachter exakt 2 Ticks pulst ({@code ObserverBehavior}: {@code scheduleTick(…, 2)}).
     * Ab T+3 gibt es gar keine Fracht-BE mehr, der Rückzug läuft also ohnehin normal.
     *
     * <p>Die zweite Klausel ist nicht redundant: sie fängt die Gegenflanke, die uns über den
     * ZWEITEN Block-Event-Drain erreicht, also nach dem Animations-Tick desselben Ticks.
     */
    private static boolean isTooEarlyToPull(World world, PistonMovingBlockEntity cargo) {
        return cargo.getProgress(0.0f) < 1.0f || cargo.getLastTicked() == world.getGameTime();
    }

    /** Die eigene Source-Moving-BE an der Kopf-Zelle (Extend wie Retract sitzen dort), sonst null. */
    private static PistonMovingBlockEntity ownSourceMoving(World world, int x, int y, int z, Direction f) {
        int hx = x + f.offsetX(), hy = y + f.offsetY(), hz = z + f.offsetZ();
        BlockState head = Blocks.getState(world.getBlock(hx, hy, hz));
        if (head.getBlock().getBlockEntityType() != BlockEntities.PISTON_MOVING) return null;
        return world.getBlockEntity(hx, hy, hz) instanceof PistonMovingBlockEntity be
                && be.isSource() && be.getFacing() == f ? be : null;
    }

    private void extend(World world, int x, int y, int z, BlockState state, Direction f) {
        PistonResolver.Result result = PistonResolver.resolveExtend(world, x, y, z, f);
        if (result.blocked()) {
            /* Fremde Animation im Weg: pollen — ihr Ende erzeugt bei konstantem Signal
               kein Nachbar-Update mehr, das uns wecken würde. scheduleTickEarlier, damit
               der Poll nicht im First-wins-Dedup der Queue hängen bleibt. */
            if (result.blockedByMoving()) world.scheduleTickEarlier(x, y, z, 1);
            return;
        }
        /* Erst nach dem Blocked-Check: nur eine tatsächlich startende Bewegung klingt. */
        if (world.getSoundManager() != null) {
            world.getSoundManager().playPistonExtend(x + 0.5, y + 0.5, z + 0.5);
        }

        /* Destroy-Zelle: Drop + onBreak; überschrieben wird sie gleich vom äußersten Ziel-Write. */
        for (long pos : result.destroys()) {
            int dx = BlockPos.unpackX(pos), dy = BlockPos.unpackY(pos), dz = BlockPos.unpackZ(pos);
            BlockState broken = Blocks.getState(world.getBlock(dx, dy, dz));
            broken.getBlock().onBreak(world, dx, dy, dz, broken);
            Item drop = Items.forBlock(broken.getBlock());
            if (drop != null) world.spawnItem(dx + 0.5, dy + 0.5, dz + 0.5, new ItemStack(drop, 1));
        }

        /* Snapshot ALLER Quell-States VOR den Writes — bei verzweigten Slime-Strukturen
           können Ziele beliebige andere Quellzellen überschreiben, mit dem Snapshot ist
           die Schreib-Reihenfolge irrelevant. */
        long[] moves = result.moves();
        int[] movedIds = new int[moves.length];
        for (int i = 0; i < moves.length; i++) {
            movedIds[i] = world.getBlock(BlockPos.unpackX(moves[i]),
                    BlockPos.unpackY(moves[i]), BlockPos.unpackZ(moves[i]));
        }

        /* 1) Alle Ziele als Moving-BEs, 2) Quellzellen ohne Ziel-Rolle räumen (bei
           Verzweigungen gibt es mehrere Ketten-Enden). */
        java.util.HashSet<Long> targets = new java.util.HashSet<>();
        java.util.LinkedHashSet<Long> notify = new java.util.LinkedHashSet<>();
        for (int i = 0; i < moves.length; i++) {
            int tx = BlockPos.unpackX(moves[i]) + f.offsetX();
            int ty = BlockPos.unpackY(moves[i]) + f.offsetY();
            int tz = BlockPos.unpackZ(moves[i]) + f.offsetZ();
            spawnMoving(world, tx, ty, tz, movedIds[i], f, true, false, this.sticky);
            long t = BlockPos.asLong(tx, ty, tz);
            targets.add(t);
            notify.add(t);
        }
        for (long src : moves) {
            if (targets.contains(src)) continue;
            world.setBlock(BlockPos.unpackX(src), BlockPos.unpackY(src), BlockPos.unpackZ(src),
                    Blocks.AIR, false);
            notify.add(src);
        }

        /* Kopf-Zelle = Source-BE mit dem piston_head-State, Basis sofort ausgefahren.
           (H ist nie Ziel — Ziele liegen mindestens bei Basis+2f — und wurde als Quelle
           gerade geräumt.) */
        int hx = x + f.offsetX(), hy = y + f.offsetY(), hz = z + f.offsetZ();
        BlockState head = Blocks.getState(Blocks.PISTON_HEAD)
                .with(Properties.FACING_ALL, f)
                .with(Properties.PISTON_TYPE, this.sticky ? PistonType.STICKY : PistonType.NORMAL);
        spawnMoving(world, hx, hy, hz, head.getId(), f, true, true, this.sticky);
        world.setBlock(x, y, z, state.with(Properties.EXTENDED, true).getId(), false);

        world.updateNeighbors(x, y, z);
        world.updateNeighbors(hx, hy, hz);
        for (long pos : notify) {
            world.updateNeighbors(BlockPos.unpackX(pos), BlockPos.unpackY(pos), BlockPos.unpackZ(pos));
        }
    }

    private void retract(World world, int x, int y, int z, BlockState state, Direction f,
                         boolean dropCargo) {
        int hx = x + f.offsetX(), hy = y + f.offsetY(), hz = z + f.offsetZ();
        BlockState head = Blocks.getState(world.getBlock(hx, hy, hz));
        if (head.getBlock().getBlockEntityType() == BlockEntities.PISTON_MOVING) {
            /* Busy = FREMDER Schub über die Kopf-Zelle (die eigene Animation hat evaluate
               schon fast-geforwardet): pollen statt abbrechen. */
            world.scheduleTickEarlier(x, y, z, 1);
            return;
        }
        boolean validHead = head.getValues().containsKey(Properties.PISTON_TYPE)
                && head.get(Properties.FACING_ALL) == f;
        if (!validHead || !world.isPositionEditable(hx, hy, hz)) {
            /* Kopf verloren (weggesprengt/inkonsistent): heilen statt animieren. */
            world.setBlock(x, y, z, state.with(Properties.EXTENDED, false).getId(), true);
            return;
        }
        if (world.getSoundManager() != null) {
            world.getSoundManager().playPistonContract(x + 0.5, y + 0.5, z + 0.5);
        }

        /* Die Source-BE sitzt an der KOPF-Zelle; die Basis bleibt während der Animation ein
           echter piston[extended=true]-Block. Bewusst so: als BE-gerenderter Würfel (flaches
           Zell-Licht, kein AO/Smooth-Lighting) blitzte die Basis beim Einfahren sichtbar
           auf — im Chunk-Mesh bleibt sie durchgehend korrekt beleuchtet. Erst das finish
           der BE fährt sie ein.

           Klebriger Kolben: die GANZE angeklebte Struktur (MC-Resolver) wandert Richtung
           Piston — blockiert/leer heißt nur „nichts ziehen", der Arm fährt trotzdem ein.
           Dasselbe gilt bei dropCargo (zu kurzer Puls, s. isTooEarlyToPull): der Rückzug
           läuft ganz normal, nur eben ohne Fracht.
           Der Block direkt vor dem Kopf reist in der Source-BE (sein Ziel IST die
           Kopf-Zelle), der Rest als normale Moving-BEs; Snapshot wie beim Ausfahren. */
        PistonResolver.Result pull = this.sticky && !dropCargo
                ? PistonResolver.resolveRetract(world, x, y, z, f)
                : null;
        long[] moves = pull != null && !pull.blocked() ? pull.moves() : new long[0];
        int[] movedIds = new int[moves.length];
        for (int i = 0; i < moves.length; i++) {
            movedIds[i] = world.getBlock(BlockPos.unpackX(moves[i]),
                    BlockPos.unpackY(moves[i]), BlockPos.unpackZ(moves[i]));
        }

        long headPos = BlockPos.asLong(hx, hy, hz);
        long frontPos = BlockPos.asLong(hx + f.offsetX(), hy + f.offsetY(), hz + f.offsetZ());
        int sourceMoved = Blocks.AIR;
        for (int i = 0; i < moves.length; i++) {
            if (moves[i] == frontPos) sourceMoved = movedIds[i];
        }
        spawnMoving(world, hx, hy, hz, sourceMoved, f, false, true, this.sticky);

        java.util.HashSet<Long> targets = new java.util.HashSet<>();
        java.util.LinkedHashSet<Long> notify = new java.util.LinkedHashSet<>();
        targets.add(headPos);
        notify.add(headPos);
        Direction toPiston = f.opposite();
        for (int i = 0; i < moves.length; i++) {
            if (moves[i] == frontPos) continue;   // reist in der Source-BE
            int tx = BlockPos.unpackX(moves[i]) + toPiston.offsetX();
            int ty = BlockPos.unpackY(moves[i]) + toPiston.offsetY();
            int tz = BlockPos.unpackZ(moves[i]) + toPiston.offsetZ();
            spawnMoving(world, tx, ty, tz, movedIds[i], f, false, false, this.sticky);
            long t = BlockPos.asLong(tx, ty, tz);
            targets.add(t);
            notify.add(t);
        }
        for (long src : moves) {
            if (targets.contains(src)) continue;
            world.setBlock(BlockPos.unpackX(src), BlockPos.unpackY(src), BlockPos.unpackZ(src),
                    Blocks.AIR, false);
            notify.add(src);
        }
        /* Die BASIS-Zelle gehört mit in den Ring — wie beim Ausfahren (s. dort). Sie ändert
           beim Einfahren zwar erst im finish ihren State, aber die Nachbarn müssen JETZT
           erfahren, dass hier etwas passiert: in einer Kolbentür hängt der untere Kolben über
           Quasi-Konnektivität an der Zelle des oberen und bekäme sonst gar keinen Weckruf —
           er fuhr dadurch zwei Ticks später ein als der obere. MC benachrichtigt an dieser
           Stelle ebenfalls (die Basis wird dort sogar selbst zum moving_piston). */
        notify.add(BlockPos.asLong(x, y, z));
        for (long pos : notify) {
            world.updateNeighbors(BlockPos.unpackX(pos), BlockPos.unpackY(pos), BlockPos.unpackZ(pos));
        }
    }

    /** Setzt einen moving_piston und konfiguriert die frisch angelegte BE. */
    private static void spawnMoving(World world, int x, int y, int z, int movedStateId,
                                    Direction facing, boolean extending, boolean source, boolean sticky) {
        world.setBlock(x, y, z, Blocks.MOVING_PISTON, false);
        if (world.getBlockEntity(x, y, z) instanceof PistonMovingBlockEntity be) {
            be.configure(movedStateId, facing, extending, source, sticky);
        }
    }

    /**
     * Beim Abbau einer AUSGEFAHRENEN Basis verschwindet der Arm mit — der fertige Kopf
     * genauso wie eine noch laufende eigene Extend-Animation. Bereits geschobene Moving-BEs
     * weiter außen laufen zu Ende (MC-Verhalten).
     */
    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        if (!state.get(Properties.EXTENDED)) return;
        Direction f = state.get(Properties.FACING_ALL);
        int hx = x + f.offsetX(), hy = y + f.offsetY(), hz = z + f.offsetZ();
        BlockState head = Blocks.getState(world.getBlock(hx, hy, hz));
        boolean matchingHead = head.getValues().containsKey(Properties.PISTON_TYPE)
                && head.get(Properties.FACING_ALL) == f;
        boolean matchingMoving = head.getBlock().getBlockEntityType() == BlockEntities.PISTON_MOVING
                && world.getBlockEntity(hx, hy, hz) instanceof PistonMovingBlockEntity mb
                && mb.isSource() && mb.getFacing() == f;
        if (matchingHead || matchingMoving) {
            /* Bei einer laufenden Animation transportiert die BE ggf. einen Pull-Block —
               dessen onBreak (MovingPistonBehavior) droppt ihn, statt ihn zu verschlucken. */
            if (matchingMoving) head.getBlock().onBreak(world, hx, hy, hz, head);
            world.setBlock(hx, hy, hz, Blocks.AIR, true);
        }
    }

    /**
     * Signal aus einer der 5 Seiten ohne die Blickrichtung — PLUS Quasi-Konnektivitaet
     * (MCs {@code PistonBaseBlock.getNeighborSignal}): der Kolben gilt auch dann als gespeist,
     * wenn die Zelle DARUEBER Signal bekommt, unabhaengig davon, ob dort ein Block oder Luft
     * steht. Darauf beruhen BUD-Powering, 2-hohe Kolbentueren und Flugmaschinen.
     *
     * <p>In der oberen Runde bleibt DOWN ausgespart: dieser Nachbar der oberen Zelle ist der
     * Kolben selbst, er wuerde sich sonst ueber seinen eigenen Ausgang selbst speisen.
     */
    private static boolean hasSignal(World world, int x, int y, int z, Direction facing) {
        for (Direction d : Direction.values()) {
            if (d == facing) continue;
            if (emitsInto(world, x, y, z, d)) return true;
        }
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            if (emitsInto(world, x, y + 1, z, d)) return true;
        }
        return false;
    }

    /** Speist der Nachbar in Richtung {@code d} Signal in die Zelle (x,y,z)? */
    private static boolean emitsInto(World world, int x, int y, int z, Direction d) {
        return RedstonePower.emittedSignal(world, x + d.offsetX(), y + d.offsetY(), z + d.offsetZ(),
                d.opposite(), false) > 0;
    }
}
