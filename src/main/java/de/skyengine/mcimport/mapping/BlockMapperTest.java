package de.skyengine.mcimport.mapping;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.mcimport.mca.McBlockState;

import java.io.File;
import java.util.Map;

/**
 * Headless-Selbsttest des {@link BlockMapper} (Muster SaveRoundTripTest): prüft genau
 * die stillen Fallen — half-Werte-Remap (Tür/Doppelpflanzen), Fluid-falling-Kodierung,
 * Property-Verwerfen, Identität/Alias/Unbekannt. Exit-Code 0 = alles korrekt.
 */
public final class BlockMapperTest {

    private static int errors = 0;

    public static void main(String[] args) throws Exception {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        BlockMapper mapper = BlockMapper.loadDefault();

        /* half-Werte: MC lower/upper -> Engine bottom/top (Codec fiele sonst LAUTLOS auf Default). */
        check(mapper, "minecraft:oak_door", Map.of("facing", "east", "half", "upper", "hinge", "left", "open", "false"),
                "oak_door[facing=east,half=top,hinge=left,open=false]");
        check(mapper, "minecraft:oak_door", Map.of("facing", "north", "half", "lower", "hinge", "right", "open", "true"),
                "oak_door[facing=north,half=bottom,hinge=right,open=true]");
        check(mapper, "minecraft:tall_grass", Map.of("half", "upper"), "tall_grass[half=top]");
        /* Treppen nutzen in BEIDEN Welten top/bottom — dürfen vom Remap unberührt bleiben. */
        check(mapper, "minecraft:stone_stairs", Map.of("facing", "east", "half", "top", "shape", "straight"),
                "stone_stairs[facing=east,half=top,shape=straight]");

        /* Fluide: MC level>=8 = fallend -> Engine FALLING-Property. */
        check(mapper, "minecraft:water", Map.of("level", "0"), "water[falling=false,level=0]");
        check(mapper, "minecraft:water", Map.of("level", "3"), "water[falling=false,level=3]");
        check(mapper, "minecraft:water", Map.of("level", "9"), "water[falling=true,level=0]");
        check(mapper, "minecraft:lava", Map.of("level", "10"), "lava[falling=true,level=0]");

        /* Unbekannte Property-NAMEN verwirft der Codec (snowy/waterlogged/lit/distance). */
        check(mapper, "minecraft:grass_block", Map.of("snowy", "true"), "grass_block");
        check(mapper, "minecraft:redstone_ore", Map.of("lit", "false"), "redstone_ore");

        /* Alias, Umbenennung, Identität, Luft-Familie. */
        check(mapper, "minecraft:deepslate", Map.of("axis", "y"), "stone");
        check(mapper, "minecraft:grass", Map.of(), "short_grass");
        check(mapper, "minecraft:seagrass", Map.of(), "water[falling=false,level=0]");
        check(mapper, "minecraft:stone", Map.of(), "stone");
        checkAir(mapper, "minecraft:cave_air", true);

        /* Unbekannt -> Luft + isKnown false (Basis des Unknown-Block-Reports). */
        checkAir(mapper, "minecraft:amethyst_block", false);
        checkAir(mapper, "minecraft:oak_stairs", false); // Engine-Lücke: kein oak_stairs/oak_slab

        System.out.println(errors == 0 ? "MAPPER-SELBSTTEST OK" : errors + " FEHLER");
        System.exit(errors == 0 ? 0 : 1);
    }

    private static void check(BlockMapper mapper, String name, Map<String, String> props, String expected) {
        int stateId = mapper.map(new McBlockState(name, props));
        String actual = BlockStateCodec.encode(Blocks.getState(stateId));
        boolean ok = actual.equals(expected);
        System.out.println((ok ? "  [OK] " : "  [FEHLER] ") + name + props + " -> " + actual
                + (ok ? "" : " (erwartet: " + expected + ")"));
        if (!ok) errors++;
    }

    private static void checkAir(BlockMapper mapper, String name, boolean expectKnown) {
        McBlockState state = new McBlockState(name, Map.of());
        boolean ok = mapper.map(state) == 0 && mapper.isKnown(state) == expectKnown;
        System.out.println((ok ? "  [OK] " : "  [FEHLER] ") + name + " -> Luft, bekannt=" + expectKnown);
        if (!ok) errors++;
    }

    private BlockMapperTest() {}
}
