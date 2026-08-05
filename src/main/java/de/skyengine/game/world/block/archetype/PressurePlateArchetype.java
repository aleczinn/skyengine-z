package de.skyengine.game.world.block.archetype;

import de.skyengine.game.entity.EntityFilters;
import de.skyengine.game.world.block.behavior.PressurePlateBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

/**
 * Druckplatte: binär {@code powered} (2 States) oder als Wägeplatte {@code power} 0..15
 * (16 States) — die {@code sensor}-Sektion der Block-JSON entscheidet (Filter, Modus,
 * Skalierung). Keine Kollision (man steht auf dem Block darunter), Umriss aus dem Modell.
 *
 * <p>Der Träger kommt über {@code "place_on_full_top": true} in der Block-JSON, dieselbe Regel
 * wie bei der Tür — das erledigt das generische {@code SupportBehavior}.
 */
public final class PressurePlateArchetype implements Archetype {

    private static final Logger LOGGER = LogManager.getLogger(PressurePlateArchetype.class.getName());

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        BlockDefinition.SensorDef sensor = def.sensor != null ? def.sensor : new BlockDefinition.SensorDef();
        boolean counting = switch (sensor.signal_mode) {
            case "count" -> true;
            case "binary" -> false;
            default -> {
                LOGGER.warning("Unbekannter signal_mode '" + sensor.signal_mode + "' bei "
                        + def.id + " — fallback binary");
                yield false;
            }
        };
        cfg.property(counting ? Properties.POWER : Properties.POWERED)
                .behavior(new PressurePlateBehavior(def.press_ticks,
                        EntityFilters.combine(sensor.entity_filter),
                        counting, sensor.min_count, sensor.entities_per_signal))
                .shapes(Shapes.outlineOnly())
                .opaque(state -> false);
    }
}
