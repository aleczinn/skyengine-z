package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.PistonReaction;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Löst auf, was ein Kolben-Schub bewegt — MCs {@code PistonStructureResolver} nachgebaut:
 * je Struktur-Block werden rückwärts angeklebte Ketten eingesammelt (Slime/Honig über
 * {@code sticky_group}; verschiedene Gruppen kleben NICHT aneinander), vorwärts läuft die
 * Linie bis Lücke (Luft/Fluid), destroy-Ende (zerbricht, nur beim Ausfahren) oder Blocker;
 * für jeden klebrigen Block kommen die 4 Querrichtungen dazu (Branching). Limit
 * {@link #MAX_PUSH} für die GESAMTE Struktur.
 *
 * <p><b>Vorab-Totalvalidierung:</b> jede betrachtete Zelle läuft über
 * {@code World.isPositionEditable} — ein halb fehlgeschlagener setBlock-Lauf an der
 * Ladefront hinterließe sonst Block-Duplikate. Die Writes selbst laufen im Behavior über
 * einen State-SNAPSHOT, damit die Schreib-Reihenfolge bei Verzweigungen irrelevant ist.
 */
public final class PistonResolver {

    /** MC-Schublimit (Gesamtstruktur). */
    public static final int MAX_PUSH = 12;

    private static final long NO_POS = Long.MIN_VALUE;

    /**
     * @param moves    Quellzellen der bewegten Struktur (deterministische Einfüge-Reihenfolge;
     *                 das Ziel jeder Quelle ist die Nachbarzelle in Bewegungsrichtung)
     * @param destroys zu zerbrechende Linien-Enden (Drop + Überschreiben, nur Ausfahren)
     * @param blockedByMoving Blocker war ein bewegter Block — der Aufrufer pollt dann
     */
    public record Result(long[] moves, long[] destroys, boolean blocked, boolean blockedByMoving) {

        static Result blocked(boolean byMoving) {
            return new Result(null, null, true, byMoving);
        }
    }

    /** Auflösung eines Ausfahrens ab der Basis (bx,by,bz) in Richtung {@code facing}. */
    public static Result resolveExtend(World world, int bx, int by, int bz, Direction facing) {
        Structure s = new Structure(world, facing, BlockPos.asLong(bx, by, bz), NO_POS, true);
        long start = offset(BlockPos.asLong(bx, by, bz), facing, 1);
        return s.resolve(start);
    }

    /**
     * Auflösung eines klebrigen Einzugs: Bewegung Richtung Piston (−facing), Start 2 vor der
     * Basis; die Kopf-Zelle zählt als frei, weil dort gleichzeitig der Kopf verschwindet bzw.
     * die erste Fracht-Moving-BE entsteht.
     * Blockiert/leer heißt hier nur „nichts ziehen" — das Einfahren selbst läuft immer.
     */
    public static Result resolveRetract(World world, int bx, int by, int bz, Direction facing) {
        long base = BlockPos.asLong(bx, by, bz);
        Structure s = new Structure(world, facing.opposite(), base, offset(base, facing, 1), false);
        return s.resolve(offset(base, facing, 2));
    }

    /** Traversierungs-Zustand einer Auflösung. */
    private static final class Structure {
        final World world;
        final Direction moveDir;
        final long basePos;
        /** Zelle, die als Luft zählt (Retract: die Kopf-Zelle). */
        final long ignoredPos;
        /** Ausfahren? Nur dann dürfen Linien-Enden zerbrechen (destroy). */
        final boolean allowDestroy;

        final List<Long> toPush = new ArrayList<>();
        final List<Long> toDestroy = new ArrayList<>();
        boolean blockedByMoving;

        Structure(World world, Direction moveDir, long basePos, long ignoredPos, boolean allowDestroy) {
            this.world = world;
            this.moveDir = moveDir;
            this.basePos = basePos;
            this.ignoredPos = ignoredPos;
            this.allowDestroy = allowDestroy;
        }

        Result resolve(long start) {
            /* Startzelle hart bewerten: Luft/Lücke = nichts zu bewegen; destroy nur beim
               Ausfahren (zerbricht); Blocker = blockiert (Extend) bzw. „nichts ziehen"
               (Retract — der Aufrufer wertet blocked dort als leere Menge). */
            Kind startKind = this.classify(start);
            switch (startKind) {
                case GAP -> {
                    return new Result(new long[0], new long[0], false, false);
                }
                case DESTROY -> {
                    if (!this.allowDestroy) return Result.blocked(false);
                    this.toDestroy.add(start);
                    return this.finish();
                }
                case BLOCKED -> {
                    return Result.blocked(false);
                }
                case MOVING -> {
                    return Result.blocked(true);
                }
                case MOVABLE -> { /* weiter unten */ }
            }
            if (!this.addBlockLine(start)) return Result.blocked(this.blockedByMoving);

            /* Vanilla iteriert die mutierbare toPush-Liste direkt. Collision-Reorders
               verzweigen ihren umgeordneten Prefix bereits rekursiv in addBlockLine. */
            for (int i = 0; i < this.toPush.size(); i++) {
                long pos = this.toPush.get(i);
                BlockState state = this.stateAt(pos);
                if (state.getBlock().getStickyGroup() != null && !this.addBranchingBlocks(pos)) {
                    return Result.blocked(this.blockedByMoving);
                }
            }
            return this.finish();
        }

        /** Vanillas addBranchingBlocks, einschließlich Direction.values-Reihenfolge. */
        boolean addBranchingBlocks(long pos) {
            String group = this.stateAt(pos).getBlock().getStickyGroup();
            if (group == null) return true;
            for (Direction direction : Direction.vanillaValues()) {
                if (direction.axis() == this.moveDir.axis()) continue;
                long neighbor = offset(pos, direction, 1);
                if (!sticksTo(group, this.stateAt(neighbor))) continue;
                if (!this.addBlockLine(neighbor)) return false;
            }
            return true;
        }

        /**
         * Nimmt eine Linie in die Struktur auf: erst rückwärts (entgegen der Bewegung) alle
         * angeklebten Vorgänger, dann vorwärts bis Lücke/destroy/Blocker. {@code false} =
         * die ganze Bewegung ist blockiert.
         */
        boolean addBlockLine(long pos) {
            if (this.toPush.contains(pos)) return true;
            Kind kind = this.classify(pos);
            if (kind == Kind.GAP) return true;
            if (kind == Kind.MOVING) {
                this.blockedByMoving = true;
                return false;
            }
            /* Als Branch-Einstieg blockieren unbewegliche Nachbarn nicht — sie hängen nur
               nicht an (der harte Start-Check läuft in resolve()). */
            if (kind != Kind.MOVABLE) return true;

            /* Rückwärts angeklebte Kette einsammeln (Slime zieht, was hinter ihm klebt). */
            int count = 1;
            BlockState current = this.stateAt(pos);
            while (current.getBlock().getStickyGroup() != null) {
                long prev = offset(pos, this.moveDir.opposite(), count);
                if (prev == this.basePos || this.toPush.contains(prev)) break;
                Kind prevKind = this.classify(prev);
                if (prevKind == Kind.MOVING) {
                    this.blockedByMoving = true;
                    return false;
                }
                if (prevKind != Kind.MOVABLE) break;
                BlockState prevState = this.stateAt(prev);
                if (!sticksTo(current.getBlock().getStickyGroup(), prevState)) break;
                count++;
                current = prevState;
            }
            for (int i = count - 1; i >= 0; i--) {
                this.toPush.add(offset(pos, this.moveDir.opposite(), i));
                if (this.toPush.size() > MAX_PUSH) return false;
            }

            /* Vorwärts bis zum Linien-Ende. */
            int addedCount = count;
            for (int j = 1; ; j++) {
                long next = offset(pos, this.moveDir, j);
                int collisionIndex = this.toPush.indexOf(next);
                if (collisionIndex >= 0) {
                    int branchingEnd = reorderListAtCollision(
                            this.toPush, addedCount, collisionIndex);
                    /* Historische Vanilla-Eigenheit: Den umgeordneten Prefix sofort erneut
                       verzweigen, bevor resolve seine äußere Schleife fortsetzt. */
                    for (int i = 0; i <= branchingEnd; i++) {
                        long reordered = this.toPush.get(i);
                        if (this.stateAt(reordered).getBlock().getStickyGroup() != null
                                && !this.addBranchingBlocks(reordered)) return false;
                    }
                    return true;
                }
                Kind nextKind = this.classify(next);
                switch (nextKind) {
                    case GAP -> {
                        return true;
                    }
                    case DESTROY -> {
                        if (!this.allowDestroy) return false;
                        this.toDestroy.add(next);
                        return true;
                    }
                    case MOVING -> {
                        this.blockedByMoving = true;
                        return false;
                    }
                    case BLOCKED -> {
                        return false;
                    }
                    case MOVABLE -> {
                        this.toPush.add(next);
                        addedCount++;
                        if (this.toPush.size() > MAX_PUSH) return false;
                    }
                }
            }
        }

        Result finish() {
            long[] moves = new long[this.toPush.size()];
            int i = 0;
            for (long pos : this.toPush) moves[i++] = pos;
            long[] destroys = new long[this.toDestroy.size()];
            i = 0;
            for (long pos : this.toDestroy) destroys[i++] = pos;
            return new Result(moves, destroys, false, false);
        }

        /** Bewegungs-Klasse einer Zelle. */
        enum Kind { GAP, MOVABLE, DESTROY, BLOCKED, MOVING }

        Kind classify(long pos) {
            if (pos == this.ignoredPos) return Kind.GAP;
            int x = BlockPos.unpackX(pos), y = BlockPos.unpackY(pos), z = BlockPos.unpackZ(pos);
            if (!this.world.isPositionEditable(x, y, z)) return Kind.BLOCKED;
            if (pos == this.basePos) return Kind.BLOCKED;
            BlockState state = this.stateAt(pos);
            /* Luft/Fluid = Lücke; Fluide werden vom Ziel-Write still überschrieben (kein Drop,
               Nachbarwasser fließt über die regulären Fluid-Ticks zurück). */
            if (state.isAir() || state.isFluid()) return Kind.GAP;
            if (state.getBlock().getBlockEntityType() == BlockEntities.PISTON_MOVING) return Kind.MOVING;
            PistonReaction reaction = state.getBlock().getPistonReaction();
            if (reaction == PistonReaction.BLOCK) return Kind.BLOCKED;
            /* Eine AUSGEFAHRENE Basis ist unverschiebbar (ihr Kopf bliebe zurück). */
            if (state.getValues().containsKey(Properties.EXTENDED) && state.get(Properties.EXTENDED)) {
                return Kind.BLOCKED;
            }
            if (reaction == PistonReaction.DESTROY) return Kind.DESTROY;
            return Kind.MOVABLE;
        }

        BlockState stateAt(long pos) {
            return Blocks.getState(this.world.getBlock(
                    BlockPos.unpackX(pos), BlockPos.unpackY(pos), BlockPos.unpackZ(pos)));
        }
    }

    /** Vanillas {@code reorderListAtCollision}: neue Linie vor den kollidierten Rest ziehen. */
    static int reorderListAtCollision(List<Long> positions, int addedCount, int collisionIndex) {
        int newLineStart = positions.size() - addedCount;
        List<Long> reordered = new ArrayList<>(positions.size());
        reordered.addAll(positions.subList(0, collisionIndex));
        reordered.addAll(positions.subList(newLineStart, positions.size()));
        reordered.addAll(positions.subList(collisionIndex, newLineStart));
        positions.clear();
        positions.addAll(reordered);
        return collisionIndex + addedCount;
    }

    /** MC-Kleberegel: klebrig zieht jeden beweglichen Nachbarn — außer eine FREMDE Klebe-Gruppe. */
    private static boolean sticksTo(String group, BlockState neighbor) {
        String other = neighbor.getBlock().getStickyGroup();
        return other == null || other.equals(group);
    }

    private static long offset(long pos, Direction d, int count) {
        return BlockPos.asLong(
                BlockPos.unpackX(pos) + d.offsetX() * count,
                BlockPos.unpackY(pos) + d.offsetY() * count,
                BlockPos.unpackZ(pos) + d.offsetZ() * count);
    }

    private PistonResolver() {}
}
