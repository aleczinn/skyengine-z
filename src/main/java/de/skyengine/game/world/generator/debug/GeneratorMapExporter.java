package de.skyengine.game.world.generator.debug;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.WorldGenerator;
import de.skyengine.game.world.generator.biome.BiomeWeights;
import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.game.world.generator.climate.ClimateSampler;
import de.skyengine.game.world.generator.climate.ClimateSamplerV3;
import de.skyengine.game.world.generator.climate.ClimateV3;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone-Werkzeug (eigene main, kein GL/Engine-Start): sampelt den Weltgenerator und
 * schreibt Falschfarben-Karten als PNG nach ./debug-maps/. Damit lassen sich Klima, Biome
 * und Hoehenmodell in Sekunden pruefen und tunen, ohne die Engine zu starten.
 */
public final class GeneratorMapExporter {

    /* Muss zum SEA_LEVEL des untersuchten Generators passen (V1/V2: 64) */
    private static final int SEA_LEVEL = 64;

    /* Kartengroesse in Pixeln; step Bloecke pro Pixel, zentriert um (centerX, centerZ) —
     * per main-Args uebersteuerbar: <step> <centerX> <centerZ> */
    private static final int SIZE = 1024;
    private static int step = 4;
    private static int centerX = 0;
    private static int centerZ = 0;

    private static final File OUTPUT_DIR = new File("debug-maps");

    /** Farbfunktion einer Karte: Weltkoordinaten -> 0xRRGGBB. */
    @FunctionalInterface
    public interface MapFunction {
        int color(int wx, int wz);
    }

    public static void main(String[] args) throws IOException {
        if (args.length >= 1) step = Integer.parseInt(args[0]);
        if (args.length >= 3) {
            centerX = Integer.parseInt(args[1]);
            centerZ = Integer.parseInt(args[2]);
        }

        /* Block-Registry GL-frei laden, damit Blocks.*-IDs fuer die Materialkarte stimmen */
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        int seed = 123; // gleicher Seed wie World
        AlphaWorldGeneratorV2 v2 = new AlphaWorldGeneratorV2(seed);
        AlphaWorldGeneratorV3 v3 = new AlphaWorldGeneratorV3(seed);
        ClimateSampler climate = new ClimateSampler(seed);
        ClimateSamplerV3 climateV3 = new ClimateSamplerV3(seed);

        long start = System.currentTimeMillis();

        /* Klimakarten: reine Felder (smooth) — Felder sind fuer V2 und V3 identisch,
         * nur variant gibt es ausschliesslich in V3 */
        writeMap("temperature", (wx, wz) -> valueColor(climate.sampleSmooth(wx, wz).temperature()));
        writeMap("humidity", (wx, wz) -> valueColor(climate.sampleSmooth(wx, wz).humidity()));
        writeMap("continentalness", (wx, wz) -> valueColor(climate.sampleSmooth(wx, wz).continentalness()));
        writeMap("erosion", (wx, wz) -> valueColor(climate.sampleSmooth(wx, wz).erosion()));
        writeMap("variant", (wx, wz) -> valueColor(climateV3.sampleSmooth(wx, wz).variant()));

        Map<Integer, Integer> materialColors = materialColors();

        /* --- V2: bit-stabile Referenz (Vergleichsanker fuer die V3-Portierung) --- */
        writeMap("v2_height", (wx, wz) -> heightColor(v2.sampleHeight(wx, wz)));
        writeMap("v2_biomes", (wx, wz) -> Biomes.lookup(climate.sample(wx, wz)).debugColor);
        writeMap("v2_surface", (wx, wz) -> {
            int color = materialColors.getOrDefault(v2.debugSurfaceTop(wx, wz), 0xFF00FF);
            return (v2.sampleHeight(wx, wz) < v2.waterLevelAt(wx, wz)) ? mixBlue(color) : color;
        });
        writeMap("v2_water", (wx, wz) -> {
            int height = v2.sampleHeight(wx, wz);
            int waterLevel = v2.waterLevelAt(wx, wz);
            if (height >= waterLevel) return heightColor(height);
            return (waterLevel > SEA_LEVEL) ? 0x55CCEE : 0x2244CC;
        });
        writeCrossSection(v2, materialColors, centerX - 512, centerZ, "v2_section");

        /* --- V3: Biome-Parameter-Blending --- */
        writeMap("v3_height", (wx, wz) -> heightColor(v3.sampleHeight(wx, wz)));
        writeMap("v3_biomes", (wx, wz) -> v3.biomeAt(wx, wz).debugColor);
        /* Blend-Zonen der TERRAIN-Gewichte (smooth, ohne Warp — so blendet auch columnFor):
         * Biomfarbe x Dominanz des staerksten Profils (dunkel = starke Mischung) */
        writeMap("v3_blend", (wx, wz) -> {
            ClimateV3 c = climateV3.sampleSmooth(wx, wz);
            return scale(BiomeWeights.pick(c).debugColor, 0.35F + 0.65F * BiomeWeights.dominance(c));
        });
        writeMap("v3_surface", (wx, wz) -> {
            int color = materialColors.getOrDefault(v3.debugSurfaceTop(wx, wz), 0xFF00FF);
            return (v3.sampleHeight(wx, wz) < v3.waterLevelAt(wx, wz)) ? mixBlue(color) : color;
        });
        writeMap("v3_water", (wx, wz) -> {
            int height = v3.sampleHeight(wx, wz);
            int waterLevel = v3.waterLevelAt(wx, wz);
            if (height >= waterLevel) return heightColor(height);
            return (waterLevel > SEA_LEVEL) ? 0x55CCEE : 0x2244CC;
        });
        writeCrossSection(v3, materialColors, centerX - 512, centerZ, "v3_section");

        System.out.println("Fertig in " + (System.currentTimeMillis() - start) + " ms -> " + OUTPUT_DIR.getAbsolutePath());
    }

    /** Sampelt die Karte pixelweise (step Bloecke pro Pixel, zentriert um (centerX, centerZ)) und schreibt sie als PNG. */
    public static void writeMap(String name, MapFunction function) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        int half = (SIZE / 2) * step;

        for (int px = 0; px < SIZE; px++) {
            for (int pz = 0; pz < SIZE; pz++) {
                image.setRGB(px, pz, function.color(centerX + px * step - half, centerZ + pz * step - half));
            }
        }

        if (!OUTPUT_DIR.isDirectory() && !OUTPUT_DIR.mkdirs()) {
            throw new IOException("Konnte Ausgabeverzeichnis nicht anlegen: " + OUTPUT_DIR);
        }
        File file = new File(OUTPUT_DIR, name + ".png");
        ImageIO.write(image, "png", file);
        System.out.println("Karte geschrieben: " + file.getPath());
    }

    /**
     * Schneidet 32 echte generate()-Chunks entlang X bei festem Z auf (x-y-Bild, y 0..319):
     * Luft = schwarz (Hoehlen!), Wasser = blau, Materialien wie in der Materialkarte.
     */
    public static void writeCrossSection(WorldGenerator generator, Map<Integer, Integer> colors,
                                         int startX, int wz, String name) throws IOException {
        int chunkCount = 32;
        int imageHeight = 320;
        BufferedImage image = new BufferedImage(chunkCount * ChunkSection.SIZE, imageHeight, BufferedImage.TYPE_INT_RGB);

        int firstChunkX = startX >> ChunkSection.SHIFT;
        int chunkZ = wz >> ChunkSection.SHIFT;
        int localZ = wz & ChunkSection.MASK;

        for (int c = 0; c < chunkCount; c++) {
            Chunk chunk = new Chunk(firstChunkX + c, chunkZ);
            generator.generate(chunk);
            for (int lx = 0; lx < ChunkSection.SIZE; lx++) {
                for (int y = 0; y < imageHeight; y++) {
                    int block = chunk.getBlock(lx, y, localZ);
                    int color;
                    if (block == Blocks.AIR) color = 0x000000;
                    else if (block == Blocks.WATER) color = 0x3355CC;
                    else if (block == Blocks.BEDROCK) color = 0x1A1A1A;
                    else if (block == Blocks.SANDSTONE) color = 0xC8BE87;
                    else color = colors.getOrDefault(block, 0xFF00FF);
                    image.setRGB(c * ChunkSection.SIZE + lx, imageHeight - 1 - y, color);
                }
            }
        }

        File file = new File(OUTPUT_DIR, name + ".png");
        ImageIO.write(image, "png", file);
        System.out.println("Karte geschrieben: " + file.getPath());
    }

    /** Anzeigefarben der Oberflaechenmaterialien (Block-ID -> 0xRRGGBB); unbekannt = Magenta. */
    private static Map<Integer, Integer> materialColors() {
        Map<Integer, Integer> colors = new HashMap<>();
        colors.put(Blocks.GRASS_BLOCK, 0x6FA341);
        colors.put(Blocks.SAND, 0xDBD3A0);
        colors.put(Blocks.GRAVEL, 0x84807B);
        colors.put(Blocks.CLAY, 0x9CA2AE);
        colors.put(Blocks.SNOW, 0xF8FCFC);
        colors.put(Blocks.STONE, 0x707070);
        colors.put(Blocks.DIRT, 0x8B5A2B);
        /* Gesteinsvarianten kontrastreich, damit Adern im Querschnitt auffallen */
        colors.put(Blocks.GRANITE, 0xB06050);
        colors.put(Blocks.DIORITE, 0xE8E8E8);
        colors.put(Blocks.ANDESITE, 0x4A6A4A);
        /* Canyon-Strata (V3): Terracotta-Baender + Mesa-Deckflaeche */
        colors.put(Blocks.RED_SANDSTONE, 0xA6541F);
        colors.put(Blocks.TERRACOTTA, 0x985E43);
        colors.put(Blocks.ORANGE_TERRACOTTA, 0xA05325);
        colors.put(Blocks.RED_TERRACOTTA, 0x8F3D2E);
        colors.put(Blocks.YELLOW_TERRACOTTA, 0xBA8523);
        colors.put(Blocks.WHITE_TERRACOTTA, 0xD1B2A1);
        colors.put(Blocks.LIGHT_GRAY_TERRACOTTA, 0xB7A18F);
        colors.put(Blocks.BROWN_TERRACOTTA, 0x4D3323);
        return colors;
    }

    /** Skaliert die RGB-Kanaele einer Farbe mit f (0..1) — fuer die Blend-Dominanz-Karte. */
    private static int scale(int color, float f) {
        int r = (int) (((color >> 16) & 0xFF) * f);
        int g = (int) (((color >> 8) & 0xFF) * f);
        int b = (int) ((color & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** Mischt die Farbe zu 45% mit Blau (Unterwasser-Kennzeichnung). */
    private static int mixBlue(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = (int) (r * 0.55F);
        g = (int) (g * 0.55F + 0x40 * 0.45F);
        b = (int) (b * 0.55F + 0xFF * 0.45F);
        return (r << 16) | (g << 8) | b;
    }

    /** Klimawert ca. -1..1 -> Graustufe (schwarz = -1, weiss = +1). */
    public static int valueColor(float value) {
        int gray = (int) ((Math.clamp(value, -1F, 1F) + 1F) * 0.5F * 255F);
        return (gray << 16) | (gray << 8) | gray;
    }

    /** Hoehe -> Farbe: unter dem Meeresspiegel Blau (dunkler = tiefer), sonst Graustufen (heller = hoeher). */
    public static int heightColor(int height) {
        if (height < SEA_LEVEL) {
            /* Tiefe 0..SEA_LEVEL auf Blau 40..255 abbilden */
            int blue = 255 - (SEA_LEVEL - height) * 215 / SEA_LEVEL;
            return Math.max(40, blue);
        }
        /* Land: SEA_LEVEL..280 auf Grau 70..255 abbilden (Gipfel V1/V2 ~260-270) */
        int gray = 70 + (height - SEA_LEVEL) * 185 / (280 - SEA_LEVEL);
        gray = Math.min(255, gray);
        return (gray << 16) | (gray << 8) | gray;
    }

    private GeneratorMapExporter() {
    }
}
