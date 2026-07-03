package de.skyengine.game.world.block.entity;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.BlockPos;

import java.util.Random;

/**
 * Zaubertisch: hält den Animationszustand des schwebenden Buches (Dreh-, Öffnungs- und
 * Seiten-Flip-Werte) - 1:1 nach Minecrafts {@code EnchantmentTableBlockEntity}. Das Buch dreht sich
 * frei und ist geschlossen; kommt ein Spieler in die Nähe (≤ 3 Blöcke), öffnet es sich, dreht sich
 * zum Spieler und blättert zufällig Seiten um. Gezeichnet wird es vom {@code EnchantingTableRenderer}.
 *
 * <p>Kein Inventar/GUI (Phase: nur Optik). Tickend - die Animationswerte werden pro Tick fortgeführt,
 * der Renderer interpoliert mit {@code partialTick} (Muster wie {@link ChestBlockEntity}).
 */
public final class EnchantingTableBlockEntity extends BlockEntity {

    private static final float PI = (float) Math.PI;

    private final Random random = new Random();

    private int tickCount;
    private float open, oOpen;       // Öffnungsgrad 0..1
    private float rot, oRot, tRot;   // aktuelle / vorige / Ziel-Drehung (rad)
    private float flip, oFlip;       // Seiten-Flip-Akkumulator
    private float flipA;             // Flip-Geschwindigkeit
    private float flipT;             // Flip-Ziel (zufällige Seitenzahl)

    public EnchantingTableBlockEntity(BlockEntityType<?> type, BlockPos pos) {
        super(type, pos);
    }

    @Override
    public void tick() {
        this.oOpen = this.open;
        this.oRot = this.rot;

        EntityPlayer player = this.world == null ? null
                : this.world.getNearestPlayer(this.pos.x() + 0.5, this.pos.y() + 0.5, this.pos.z() + 0.5, 3.0);

        if (player != null) {
            double dx = player.x - (this.pos.x() + 0.5);
            double dz = player.z - (this.pos.z() + 0.5);
            this.tRot = (float) Math.atan2(dz, dx);
            this.open += 0.1f;

            /* Beim Öffnen und ab und zu eine neue Seite anpeilen (zufälliges Blättern). */
            if (this.open < 0.5f || this.random.nextInt(40) == 0) {
                float old = this.flipT;
                do {
                    this.flipT += this.random.nextInt(4) - this.random.nextInt(4);
                } while (old == this.flipT);
            }
        } else {
            this.tRot += 0.02f;
            this.open -= 0.1f;
        }

        /* rot/tRot in [-PI, PI) halten und rot auf kürzestem Weg Richtung tRot ziehen. */
        this.rot = wrap(this.rot);
        this.tRot = wrap(this.tRot);
        float diff = wrap(this.tRot - this.rot);
        this.rot += diff * 0.4f;

        this.open = clamp(this.open, 0f, 1f);
        this.tickCount++;

        this.oFlip = this.flip;
        float fd = clamp((this.flipT - this.flip) * 0.4f, -0.2f, 0.2f);
        this.flipA += (fd - this.flipA) * 0.9f;
        this.flip += this.flipA;
    }

    /* --- interpolierte Werte für den Renderer --- */

    public float getOpen(float partialTick) {
        return this.oOpen + (this.open - this.oOpen) * partialTick;
    }

    public float getFlip(float partialTick) {
        return this.oFlip + (this.flip - this.oFlip) * partialTick;
    }

    /** Interpolierte Buch-Drehung auf kürzestem Winkelweg (vermeidet Sprung bei -PI/PI). */
    public float getRot(float partialTick) {
        return this.oRot + wrap(this.rot - this.oRot) * partialTick;
    }

    public float getTime(float partialTick) {
        return this.tickCount + partialTick;
    }

    private static float wrap(float angle) {
        while (angle >= PI) angle -= 2f * PI;
        while (angle < -PI) angle += 2f * PI;
        return angle;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }
}
