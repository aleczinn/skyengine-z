package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registry;
import de.skyengine.game.world.generator.OreGeneratingWorldGenerator;
import de.skyengine.game.world.generator.OreProfile;
import de.skyengine.game.world.generator.feature.trees.BiomeTreeFeature;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.generator.generators.FlatMiningWorldGenerator;
import de.skyengine.game.world.generator.generators.NetherWorldGenerator;
import de.skyengine.game.world.generator.generators.VoidWorldGenerator;

import java.util.List;
import java.util.Map;

/** Eingebaute und erweiterbare Dimension-/Generatorregistries. */
public final class WorldgenRegistries {

    public static final Identifier OVERWORLD = Identifier.of("skyengine:overworld");
    public static final Identifier MINING = Identifier.of("skyengine:mining");
    public static final Identifier NETHER = Identifier.of("skyengine:nether");
    public static final Identifier ALPHA_V2 = Identifier.of("skyengine:alpha_v2");
    public static final Identifier MINING_FLAT_V1 = Identifier.of("skyengine:mining_flat_v1");
    public static final Identifier NETHER_V1 = Identifier.of("skyengine:nether_v1");
    public static final Identifier MINECRAFT_IMPORT = Identifier.of("skyengine:minecraft_import");

    public static final Registry<GeneratorDefinition> GENERATORS = new Registry<>("world_generator");
    public static final Registry<DimensionDefinition> DIMENSIONS = new Registry<>("dimension");
    public static final Registry<PortalDefinition> PORTALS = new Registry<>("portal");

    private static boolean bootstrapped;

    /** Nach dem Block-Bake aufrufen: die Factorys duerfen danach Blocks/Biomes verwenden. */
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        registerGenerator(new GeneratorDefinition(ALPHA_V2, 2, seed ->
                new GenerationSetup(new OreGeneratingWorldGenerator(
                        new AlphaWorldGeneratorV2(seed), OreProfile.normal()),
                        List.of(new BiomeTreeFeature()), GenerationSetup.StorageMode.GENERATED)));
        registerGenerator(new GeneratorDefinition(MINING_FLAT_V1, FlatMiningWorldGenerator.VERSION, seed ->
                new GenerationSetup(new OreGeneratingWorldGenerator(
                        new FlatMiningWorldGenerator(seed), OreProfile.rich()),
                        List.of(), GenerationSetup.StorageMode.GENERATED)));
        registerGenerator(new GeneratorDefinition(MINECRAFT_IMPORT, 1, seed ->
                new GenerationSetup(new VoidWorldGenerator(seed), List.of(),
                        GenerationSetup.StorageMode.IMPORTED)));
        registerGenerator(new GeneratorDefinition(NETHER_V1, NetherWorldGenerator.VERSION, seed ->
                new GenerationSetup(new NetherWorldGenerator(seed), List.of(),
                        GenerationSetup.StorageMode.GENERATED)));

        registerDimension(new DimensionDefinition(OVERWORLD, ALPHA_V2, 0, true));
        registerDimension(new DimensionDefinition(MINING, MINING_FLAT_V1, 0x4D494E45, true));
        registerDimension(new DimensionDefinition(NETHER, NETHER_V1, 0x4E455448, false,
                DimensionEnvironment.NETHER));
        PortalDefinition miningPortal = new PortalDefinition(Identifier.of("skyengine:mining_portal"),
                Identifier.of("skyengine:mining_portal"), Map.of(OVERWORLD, MINING, MINING, OVERWORLD),
                PortalDefinition.Activation.USE);
        registerPortal(miningPortal);
        PortalDefinition netherPortal = new PortalDefinition(Identifier.of("skyengine:nether_portal"),
                Identifier.of("skyengine:nether_portal"), Map.of(OVERWORLD, NETHER, NETHER, OVERWORLD),
                PortalDefinition.Activation.CONTACT, 80, 1, PortalDefinition.LinkPolicy.NETHER);
        registerPortal(netherPortal);
    }

    public static void registerGenerator(GeneratorDefinition definition) {
        validate(definition.id());
        GENERATORS.register(definition.id(), definition);
    }

    public static void registerDimension(DimensionDefinition definition) {
        validate(definition.id());
        DIMENSIONS.register(definition.id(), definition);
    }

    /** Erweiterungspunkt fuer weitere Portaltypen; waehrend des Content-Bootstraps registrieren. */
    public static void registerPortal(PortalDefinition definition) {
        validate(definition.id());
        validate(definition.block());
        PORTALS.register(definition.id(), definition);
    }

    public static void validate(Identifier id) {
        if (id == null || !id.namespace().matches("[a-z0-9._-]+")
                || !id.path().matches("[a-z0-9._/-]+")
                || id.path().startsWith("/") || id.path().endsWith("/")
                || id.path().contains("..") || id.path().contains("//")) {
            throw new IllegalArgumentException("Ungueltiger Dimensions-Identifier: " + id);
        }
    }

    /** Stabile, persistierte Ableitung fuer neue Dimensionen. */
    public static int dimensionSeed(int worldSeed, DimensionDefinition dimension) {
        if (dimension.id().equals(OVERWORLD)) return worldSeed;
        int value = worldSeed ^ dimension.id().toString().hashCode() ^ dimension.seedSalt();
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        return value ^ (value >>> 16);
    }

    private WorldgenRegistries() {}
}
