package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.AttachBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.Shapes;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.Properties;

import java.util.EnumSet;
import java.util.Set;

/**
 * Block, der an einer Fläche hängt (Fackel; später Hebel, Knopf, Leiter). Ein Archetyp für die
 * ganze Klasse — welche Flächen erlaubt sind, steht in der Block-JSON
 * ({@code "attach_faces": ["floor","wall"]}, Default alle drei).
 *
 * <p>Properties wie in Vanilla: {@code face} (floor/wall/ceiling) × {@code facing} (4 horizontal)
 * = 12 States. Eine sechswertige FACING-Property wäre kompakter, würde aber mit der bestehenden
 * vierwertigen {@link Properties#FACING} im Namen kollidieren — und beim Laden einer Welt
 * entscheidet {@code BlockStateCodec} nur über den Namen.
 *
 * <p>Keine Kollision (man läuft durch die Fackel); die Umriss-Box kommt zustandsabhängig aus
 * {@link Shapes#attached()} — eine Wandfackel steht nicht in der Blockmitte, sonst ließe sie sich
 * dort, wo sie gezeichnet wird, nicht anvisieren. Eine {@code collision}-Sektion in der Block-JSON
 * würde das wieder überschreiben (siehe {@code ArchetypeBlockFactory}).
 */
public final class AttachedArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        cfg.property(Properties.ATTACH)
                .property(Properties.FACING)
                .behavior(new AttachBehavior(parseFaces(def.attach_faces)))
                .shapes(Shapes.attached())
                .opaque(state -> false);
    }

    private static Set<AttachFace> parseFaces(String[] names) {
        if (names == null || names.length == 0) return EnumSet.allOf(AttachFace.class);
        Set<AttachFace> out = EnumSet.noneOf(AttachFace.class);
        for (String n : names) out.add(AttachFace.valueOf(n.toUpperCase()));
        return out;
    }
}
