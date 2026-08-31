package de.skyengine.game.world.light.debug;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.PackedTerrainQuad;
import de.skyengine.game.world.chunk.VertexLight;
import de.skyengine.game.world.light.LightEngine;

import java.io.File;

/**
 * Standalone-Werkzeug (eigene main, kein GL/Engine-Start, Muster {@code SaveRoundTripTest}):
 * baut künstliche Chunks mit bekannter Geometrie, lässt die {@link LightEngine} darüber laufen
 * und prüft nachrechenbare Invarianten des Himmelslichts. Exit-Code 0 = alles korrekt.
 *
 * <p>Der Rest des Lichtsystems (Mesher-Ecken, Shader-Kurve, Nähte im Bild) bleibt ausschließlich
 * visuell prüfbar — hier geht es um die Ausbreitungslogik, die man ohne Fenster festnageln kann.</p>
 */
public final class LightProbe {

    private static final int SIZE = ChunkSection.SIZE;
    private static int errors = 0;

    private static int STONE, WATER, GLASS, LEAVES, AIR, TORCH;

    public static void main(String[] args) {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        AIR = Blocks.AIR;
        STONE = id("stone");
        WATER = id("water[falling=false,level=0]");
        GLASS = id("glass");
        LEAVES = id("oak_leaves");
        TORCH = id("torch");

        System.out.println("== Licht-Opazitaeten ==");
        check(Blocks.getState(STONE).getLightOpacity() == 15, "Stein blockt (15)");
        check(Blocks.getState(GLASS).getLightOpacity() == 0, "Glas laesst durch (0)");
        check(Blocks.getState(WATER).getLightOpacity() == 1, "Wasser daempft (1)");
        check(Blocks.getState(LEAVES).getLightOpacity() == 1, "Laub daempft (1)");

        System.out.println("== Eigenleuchten ==");
        check(Blocks.getState(TORCH).getLuminance() == 14, "Fackel leuchtet mit 14");
        check(Blocks.getState(STONE).getLuminance() == 0, "Stein leuchtet nicht");
        check(Blocks.getState(TORCH).getLightOpacity() == 0, "Fackel blockt kein Licht");

        testHeightmapAndColumn();
        testDeepSkyShaft();
        testMinecraftAxisContour();
        testTunnelGradient();
        testSealAndBreak();
        testWaterColumn();
        testChunkSeam();
        testGeneratedTerrain();
        testTorchGradient();
        testTorchPlaceBreak();
        testTwoTorches();
        testTorchSeam();
        testWalledInEmitter();
        testVertexLightPrecision();
        testMesherVertices();
        testSmoothVertexPrecision();
        testBatchEquivalence();
        testMultiChunkBatchOrder();

        System.out.println(errors == 0 ? "LICHT OK" : "LICHT FEHLGESCHLAGEN: " + errors + " Fehler");
        System.exit(errors == 0 ? 0 : 1);
    }

    /* ------------------------------------------------------------------ */

    /**
     * Aequivalenz-Beweis fuer den Batch-Pfad (onBlocksChanged, Explosionen): dieselbe
     * Edit-Liste einmal sequenziell ueber onBlockChanged, einmal als Batch — der komplette
     * Licht-Endzustand BEIDER Ebenen muss identisch sein (das BFS konvergiert gegen den
     * eindeutigen Fixpunkt, Reihenfolge/Gruppierung duerfen nichts aendern).
     */
    private static void testBatchEquivalence() {
        System.out.println("== Batch-Aequivalenz (onBlocksChanged) ==");
        Chunk seq = buildBatchScenario();
        Chunk batch = buildBatchScenario();

        /* Edit-Liste: Krater (Kugel r=4 in die Oberflaeche), neue Wand in der Luft,
           Fackel abbauen, Fackel neu setzen — deckt alle vier updateBlockAt-Faelle
           plus beide Heightmap-Zweige ab. */
        java.util.List<int[]> edits = new java.util.ArrayList<>(); // {lx, y, lz, newId}
        for (int dy = -4; dy <= 4; dy++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    if (dx * dx + dy * dy + dz * dz > 16) continue;
                    edits.add(new int[]{16 + dx, 99 + dy, 16 + dz, AIR});
                }
            }
        }
        for (int y = 101; y <= 106; y++) edits.add(new int[]{8, y, 8, STONE}); // neue Wand
        edits.add(new int[]{24, 50, 24, AIR});   // Fackel im Stollen abbauen
        edits.add(new int[]{20, 50, 24, TORCH}); // neue Fackel weiter hinten

        /* Sequenziell: exakt der setBlockRaw-Ablauf (alte ID lesen, schreiben, updaten). */
        LightEngine seqEngine = new LightEngine();
        Chunk[] none = new Chunk[4];
        for (int[] e : edits) {
            int old = seq.getBlock(e[0], e[1], e[2]);
            seq.setBlock(e[0], e[1], e[2], e[3]);
            seqEngine.onBlockChanged(seq, null, null, null, null, none, e[0], e[1], e[2], old, e[3]);
        }

        /* Batch: erst ALLE Writes (Alt-IDs snapshotten), dann ein onBlocksChanged. */
        int[] packed = new int[edits.size()];
        int[] olds = new int[edits.size()];
        for (int i = 0; i < edits.size(); i++) {
            int[] e = edits.get(i);
            olds[i] = batch.getBlock(e[0], e[1], e[2]);
            batch.setBlock(e[0], e[1], e[2], e[3]);
            packed[i] = (e[0] & 31) | ((e[2] & 31) << 5) | ((e[1] & 511) << 10);
        }
        new LightEngine().onBlocksChanged(batch, null, null, null, null, none, packed, olds, packed.length);

        int skyDiff = 0, blockDiff = 0, heightDiff = 0;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                if (seq.heightmap[idx(x, z)] != batch.heightmap[idx(x, z)]) heightDiff++;
                for (int y = 0; y < 140; y++) {
                    if (seq.light.get(x, y, z) != batch.light.get(x, y, z)) skyDiff++;
                    if (seq.blockLight.get(x, y, z) != batch.blockLight.get(x, y, z)) blockDiff++;
                }
            }
        }
        System.out.println("  " + edits.size() + " Edits, Abweichungen: Himmel " + skyDiff
                + ", Block " + blockDiff + ", Heightmap " + heightDiff);
        check(heightDiff == 0, "Heightmap: Batch == sequenziell");
        check(skyDiff == 0, "Himmelslicht: Batch == sequenziell");
        check(blockDiff == 0, "Blocklicht: Batch == sequenziell");
    }

    /** Deterministisches Szenario fuer den Batch-Test: Terrain + Stollen + zwei Fackeln. */
    private static Chunk buildBatchScenario() {
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 100, STONE);
        /* Stollen bei y = 50 mit zwei Fackeln (eine wird im Test abgebaut). */
        for (int x = 6; x <= 26; x++) chunk.setBlock(x, 50, 24, AIR);
        chunk.setBlock(24, 50, 24, TORCH);
        chunk.setBlock(10, 50, 24, TORCH);
        light(chunk);
        chunk.status = ChunkStatus.READY;
        return chunk;
    }

    /** Heightmap-Regel + verlustfreie 15er-Saeule + harter Schnitt unter einem Blocker. */
    private static void testHeightmapAndColumn() {
        System.out.println("== Heightmap und Direkt-Saeule ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 100, STONE);          // Boden y = 0..100
        for (int y = 101; y <= 110; y++) chunk.setBlock(5, y, 5, STONE);   // Saeule
        /* Freischwebendes Dach ueber (20,20), zwei Bloecke ueber dem Boden. */
        chunk.setBlock(20, 103, 20, STONE);

        light(chunk);

        check(chunk.heightmap[idx(0, 0)] == 101, "freie Saeule: Heightmap = hoechster Blocker + 1");
        check(chunk.heightmap[idx(5, 5)] == 111, "Saeule: Heightmap folgt dem hoechsten Blocker");
        check(chunk.heightmap[idx(20, 20)] == 104, "Dach: Heightmap sitzt auf dem Dach");

        check(chunk.light.get(0, 101, 0) == 15 && chunk.light.get(0, 400, 0) == 15,
                "ueber dem Boden faellt Himmelslicht verlustfrei bis auf die Oberflaeche");
        check(chunk.light.get(0, 100, 0) == 0, "im Stein ist es dunkel");
        check(chunk.light.get(5, 111, 5) == 15, "Saeulenkopf ist voll belichtet");
        /* Unter dem Dach: kein Direkt-Himmel; seitlich sickert Licht ein (14 an der Kante). */
        check(chunk.light.get(20, 102, 20) < 15, "unter dem Dach kein voller Himmel");
        check(chunk.light.get(20, 102, 20) > 0, "unter dem Dach nicht schwarz (seitliches Einsickern)");
    }

    /** Tiefer 1x1-Schacht: Direkt-Himmel bleibt 15, nur der waagerechte Abzweig verliert Licht. */
    private static void testDeepSkyShaft() {
        System.out.println("== Tiefer Direkt-Schacht ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 300, STONE);
        for (int y = 10; y <= 300; y++) chunk.setBlock(16, y, 16, AIR);
        for (int x = 16; x < SIZE; x++) chunk.setBlock(x, 10, 16, AIR);

        light(chunk);

        boolean directFull = true;
        for (int y = 10; y <= 300; y++) {
            if (chunk.light.get(16, y, 16) != 15) directFull = false;
        }
        check(directFull, "freier 1x1-Schacht bleibt ueber die gesamte Tiefe auf 15");
        check(chunk.light.get(17, 10, 16) == 14, "seitlich ein Block entfernt: 14");
        check(chunk.light.get(30, 10, 16) == 1, "seitlich 14 Bloecke entfernt: 1");
        check(chunk.light.get(31, 10, 16) == 0, "seitlich ab 15 Bloecken: dunkel");
    }

    /**
     * Das Minecraft-artige Lichtfeld nutzt sechs Achsen-Nachbarn. Auf einer freien Ebene ist
     * die Distanz deshalb |dx|+|dz|: Die sichtbare Kontur darf blockig sein, aber niemals einen
     * isolierten Wert oder einen Sprung entgegen dieser monotonen 15..0-Folge enthalten.
     */
    private static void testMinecraftAxisContour() {
        System.out.println("== Minecraft-Achsenkontur ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 40, STONE);
        for (int z = 3; z <= 29; z++) {
            for (int x = 3; x <= 29; x++) chunk.setBlock(x, 11, z, AIR);
        }
        for (int y = 12; y <= 40; y++) chunk.setBlock(16, y, 16, AIR);

        light(chunk);

        boolean contour = true;
        for (int dz = -6; dz <= 6; dz++) {
            for (int dx = -6; dx <= 6; dx++) {
                int expected = Math.max(0, 15 - Math.abs(dx) - Math.abs(dz));
                contour &= chunk.light.get(16 + dx, 11, 16 + dz) == expected;
            }
        }
        check(contour, "freie Ebene folgt exakt der monotonen |dx|+|dz|-Kontur");
    }

    /** Waagerechter Tunnel in einen Berg: Gradient faellt monoton, Reichweite hoechstens 15. */
    private static void testTunnelGradient() {
        System.out.println("== Tunnel-Gradient ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 120, STONE);
        /* Tunnel auf y = 118 von x = 31 (offene Chunkkante... nein: von der Oberflaeche) nach innen.
           Damit der Tunnel eine Lichtquelle hat, wird ueber x = 30 ein Schacht bis nach oben gegraben. */
        for (int y = 118; y <= 120; y++) chunk.setBlock(30, y, 16, AIR);
        for (int x = 29; x >= 5; x--) chunk.setBlock(x, 118, 16, AIR);

        light(chunk);

        check(chunk.light.get(30, 118, 16) == 15, "Schachtgrund hat vollen Himmel");
        boolean monoton = true, reichweite = true;
        int prev = 15;
        for (int x = 29; x >= 5; x--) {
            int v = chunk.light.get(x, 118, 16);
            if (v > prev) monoton = false;
            if (v != Math.max(0, 15 - (30 - x))) reichweite = false;
            prev = v;
        }
        check(monoton, "Tunnel: Licht faellt monoton nach innen");
        check(reichweite, "Tunnel: genau -1 pro Block, ab 15 Bloecken dunkel");
        check(chunk.light.get(14, 118, 16) == 0, "16 Bloecke tief ist es schwarz");
    }

    /** onBlockChanged in beide Richtungen: Hoehle versiegeln und wieder aufbrechen. */
    private static void testSealAndBreak() {
        System.out.println("== Versiegeln und Aufbrechen ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 100, STONE);
        /* Hohlraum bei y = 98, mit einem Loch nach oben bei (16,16). */
        for (int x = 12; x <= 20; x++) {
            for (int z = 12; z <= 20; z++) chunk.setBlock(x, 98, z, AIR);
        }
        for (int y = 99; y <= 100; y++) chunk.setBlock(16, y, 16, AIR);

        light(chunk);
        int before = chunk.light.get(16, 98, 16);
        check(before == 15, "durch das Loch faellt volles Licht in die Hoehle");
        check(chunk.light.get(12, 98, 12) > 0, "Hoehlenrand bekommt Restlicht");

        /* Versiegeln: Loch bei y = 100 zumauern. */
        Chunk[] none = new Chunk[4];
        LightEngine engine = new LightEngine();
        chunk.setBlock(16, 100, 16, STONE);
        engine.onBlockChanged(chunk, null, null, null, null, none, 16, 100, 16, AIR, STONE);
        check(chunk.light.get(16, 98, 16) == 0, "versiegelt: Hoehle wird komplett dunkel");
        check(chunk.heightmap[idx(16, 16)] == 101, "versiegelt: Heightmap steigt wieder auf das Dach");

        /* Wieder aufbrechen. */
        chunk.setBlock(16, 100, 16, AIR);
        engine.onBlockChanged(chunk, null, null, null, null, none, 16, 100, 16, STONE, AIR);
        check(chunk.light.get(16, 98, 16) == before, "aufgebrochen: Licht kehrt exakt zurueck");
        /* Oberster Blocker ist wieder y = 97 — darueber liegen Hohlraum (98) und Loch (99/100). */
        check(chunk.heightmap[idx(16, 16)] == 98, "aufgebrochen: Heightmap faellt auf den Hoehlenboden");
    }

    /** seedWaterColumns: eine flache Wasserflaeche hat keine Hoehendifferenz — ohne den Seed schwarz. */
    private static void testWaterColumn() {
        System.out.println("== Wassersaeule ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 100, STONE);
        fillLayer(chunk, 101, 120, WATER); // 20 Bloecke tief, ueberall gleich hoch

        light(chunk);

        check(chunk.heightmap[idx(16, 16)] == 121, "Wasser zaehlt als Blocker (Heightmap ueber der Oberflaeche)");
        boolean stufig = true;
        for (int y = 120; y >= 106; y--) {
            if (chunk.light.get(16, y, 16) != 14 - (120 - y)) stufig = false;
        }
        check(chunk.light.get(16, 120, 16) == 14, "oberster Wasserblock: 15 - 1");
        check(stufig, "Wasser: genau -1 pro Block nach unten");
        check(chunk.light.get(16, 105, 16) == 0, "ab 15 Bloecken Tiefe ist es schwarz");

        /* Flaches Wasser: hier zahlt sich seedWaterColumns aus. Der Meeresbodenblock selbst
           ist Stein (immer 0) — entscheidend ist die Wasserzelle direkt darueber, die der
           Mesher fuer die Bodenoberseite liest. */
        Chunk flach = new Chunk(0, 0);
        fillLayer(flach, 0, 100, STONE);
        fillLayer(flach, 101, 105, WATER);
        light(flach);
        check(flach.light.get(16, 101, 16) == 10, "flaches Wasser: Zelle ueber dem Grund ist belichtet (15 - 5)");
    }

    /**
     * Naht zwischen zwei Chunks: eine hohe Wand an der Grenze im Westchunk beschattet den
     * Ostchunk. Ohne {@code exchangeBorders} bliebe der Streifen hinter der Wand falsch hell
     * bzw. die Wand wuerfe keinen Schatten ueber die Grenze.
     */
    private static void testChunkSeam() {
        System.out.println("== Chunk-Naht ==");
        Chunk west = new Chunk(-1, 0);
        Chunk east = new Chunk(0, 0);
        for (Chunk c : new Chunk[]{west, east}) fillLayer(c, 0, 100, STONE);
        /* Ueberdachung im Ostchunk ab x = 0 nach Osten, Hoehe 104 — der Bereich darunter ist
           nur ueber den Westchunk (offener Himmel) erreichbar. */
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z < SIZE; z++) east.setBlock(x, 104, z, STONE);
        }

        LightEngine engine = new LightEngine();
        Chunk[] none = new Chunk[4];
        engine.lightInitial(west, null, null, null, east, none);
        west.status = ChunkStatus.LIT;
        engine.lightInitial(east, null, null, west, null, none);
        east.status = ChunkStatus.LIT;

        int isolated = east.light.get(0, 103, 16);
        engine.exchangeBorders(east, null, null, west, null, none);
        int exchanged = east.light.get(0, 103, 16);

        check(west.light.get(31, 103, 16) == 15, "Westchunk unter freiem Himmel: voll hell");
        check(isolated == 0, "vor dem Randaustausch ist der ueberdachte Streifen dunkel");
        check(exchanged == 14, "nach dem Randaustausch sickert Licht ueber die Chunkgrenze (15 - 1)");
        check(east.light.get(3, 103, 16) == 11, "Gradient laeuft im Nachbarchunk weiter");
    }

    /**
     * Echtes Terrain aus dem Weltgenerator statt Kunstgeometrie: faengt Faelle ab, die
     * synthetische Chunks nicht haben (Ueberhaenge, Hoehlen, Wasser, Baeume) — vor allem die
     * Frage, ob die OBERFLAECHE ueberhaupt vollstaendig belichtet wird. Waere sie es nicht,
     * waere die ganze Welt dunkel.
     */
    private static void testGeneratedTerrain() {
        System.out.println("== Echtes Terrain (Generator, Seed 123) ==");
        Chunk chunk = new Chunk(3, -7);
        new de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2(123).generate(chunk);
        light(chunk);

        int columns = 0, litAbove = 0, minSurface = 15;
        long sum = 0;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int h = chunk.heightmap[idx(x, z)];
                if (h <= 0 || h >= Chunk.HEIGHT) continue;
                columns++;
                int v = chunk.light.get(x, h, z); // Zelle direkt ueber dem obersten Blocker
                sum += v;
                if (v == 15) litAbove++;
                minSurface = Math.min(minSurface, v);
            }
        }
        System.out.println("  " + columns + " Saeulen, Oberflaechenzelle: Schnitt "
                + String.format(java.util.Locale.ROOT, "%.2f", sum / (double) columns)
                + ", Minimum " + minSurface + ", davon voll belichtet " + litAbove);
        check(columns > 900, "Chunk hat Terrain in nahezu allen Saeulen");
        check(litAbove == columns, "JEDE Oberflaechenzelle hat vollen Himmel (15)");
        check(chunk.light.get(16, 5, 16) == 0, "tief unter Tage ist es dunkel");
    }

    /**
     * Blocklicht-Grundfall: eine Fackel in einem versiegelten Hohlraum. Prueft zugleich, dass die
     * beiden Ebenen wirklich unabhaengig sind — dort unten ist das Himmelslicht ueberall 0.
     */
    private static void testTorchGradient() {
        System.out.println("== Fackel-Gradient ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 100, STONE);
        /* Waagerechter Stollen bei y = 50, damit sich das Licht ausbreiten kann. */
        for (int x = 5; x <= 28; x++) chunk.setBlock(x, 50, 16, AIR);
        chunk.setBlock(5, 50, 16, TORCH);

        light(chunk);

        check(chunk.blockLight.get(5, 50, 16) == 14, "Fackelzelle traegt 14");
        check(chunk.blockLight.get(6, 50, 16) == 13, "ein Block weiter: 13");
        check(chunk.blockLight.get(8, 50, 16) == 11, "drei Bloecke weiter: 11");
        check(chunk.blockLight.get(19, 50, 16) == 0, "ab 14 Bloecken ist das Fackellicht aus");
        check(chunk.light.get(6, 50, 16) == 0, "Himmelslicht bleibt davon unberuehrt (0)");
        check(chunk.blockLight.get(6, 51, 16) == 0, "durch Stein kommt kein Fackellicht");
    }

    /**
     * Der Test fuer den erweiterten Early-Out in {@code onBlockChanged}: eine Fackel aendert die
     * Opazitaet NICHT (0 -> 0). Wer nur auf die Opazitaet prueft, sieht hier ueberall Null.
     */
    private static void testTorchPlaceBreak() {
        System.out.println("== Fackel setzen und abbauen ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 100, STONE);
        for (int x = 5; x <= 28; x++) chunk.setBlock(x, 50, 16, AIR);
        light(chunk);
        check(chunk.blockLight.get(10, 50, 16) == 0, "vorher ist der Stollen finster");

        LightEngine engine = new LightEngine();
        Chunk[] none = new Chunk[4];
        chunk.setBlock(10, 50, 16, TORCH);
        engine.onBlockChanged(chunk, null, null, null, null, none, 10, 50, 16, AIR, TORCH);
        check(chunk.blockLight.get(10, 50, 16) == 14, "gesetzt: Fackelzelle traegt 14");
        check(chunk.blockLight.get(13, 50, 16) == 11, "gesetzt: Gradient steht");

        chunk.setBlock(10, 50, 16, AIR);
        engine.onBlockChanged(chunk, null, null, null, null, none, 10, 50, 16, TORCH, AIR);
        check(chunk.blockLight.get(10, 50, 16) == 0, "abgebaut: Fackelzelle wieder dunkel");
        check(chunk.blockLight.get(13, 50, 16) == 0, "abgebaut: der ganze Gradient ist weg");
    }

    /**
     * Regressionstest fuer den aufgeschobenen Emitter-Re-Seed: baut man eine von zwei benachbarten
     * Fackeln ab, darf die Unlight-Welle die verbliebene nicht mitloeschen.
     */
    private static void testTwoTorches() {
        System.out.println("== Zwei Fackeln ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 100, STONE);
        for (int x = 5; x <= 28; x++) chunk.setBlock(x, 50, 16, AIR);
        chunk.setBlock(10, 50, 16, TORCH);
        chunk.setBlock(13, 50, 16, TORCH);
        light(chunk);
        check(chunk.blockLight.get(10, 50, 16) == 14, "beide Fackeln tragen 14");
        check(chunk.blockLight.get(13, 50, 16) == 14, "beide Fackeln tragen 14 (zweite)");

        LightEngine engine = new LightEngine();
        Chunk[] none = new Chunk[4];
        chunk.setBlock(13, 50, 16, AIR);
        engine.onBlockChanged(chunk, null, null, null, null, none, 13, 50, 16, TORCH, AIR);

        check(chunk.blockLight.get(10, 50, 16) == 14, "verbliebene Fackel leuchtet weiter (14)");
        check(chunk.blockLight.get(11, 50, 16) == 13, "ihr Gradient ist intakt (13)");
        check(chunk.blockLight.get(13, 50, 16) == 11, "an der abgebauten Stelle bleibt nur ihr Restlicht (11)");
    }

    /** Wie {@link #testChunkSeam()}, nur fuer die Blocklicht-Ebene. */
    private static void testTorchSeam() {
        System.out.println("== Chunk-Naht (Blocklicht) ==");
        Chunk west = new Chunk(-1, 0);
        Chunk east = new Chunk(0, 0);
        for (Chunk c : new Chunk[]{west, east}) fillLayer(c, 0, 100, STONE);
        /* Stollen quer ueber die Grenze bei y = 50, Fackel direkt an der Ostkante des Westchunks. */
        for (int x = 20; x < SIZE; x++) west.setBlock(x, 50, 16, AIR);
        for (int x = 0; x <= 10; x++) east.setBlock(x, 50, 16, AIR);
        west.setBlock(31, 50, 16, TORCH);

        LightEngine engine = new LightEngine();
        Chunk[] none = new Chunk[4];
        engine.lightInitial(west, null, null, null, east, none);
        west.status = ChunkStatus.LIT;
        engine.lightInitial(east, null, null, west, null, none);
        east.status = ChunkStatus.LIT;

        int isolated = east.blockLight.get(0, 50, 16);
        engine.exchangeBorders(east, null, null, west, null, none);

        check(west.blockLight.get(31, 50, 16) == 14, "Fackel im Westchunk traegt 14");
        check(isolated == 0, "vor dem Randaustausch ist der Ostchunk dunkel");
        check(east.blockLight.get(0, 50, 16) == 13, "nach dem Randaustausch sickert Fackellicht ueber die Grenze");
        check(east.blockLight.get(3, 50, 16) == 10, "Gradient laeuft im Nachbarchunk weiter");
    }

    /**
     * Eine allseitig EINGEMAUERTE Lichtquelle darf im Bild nirgends ankommen. Der Fall ist der
     * Grund fuer den Diagonal-Guard in {@code ChunkMesher.computeCornerLight}: das Smooth Lighting
     * mittelt pro Quad-Ecke vier Zellen inklusive der Diagonalen, und der Okklusions-Filter prueft
     * nur die Zelle selbst, nie den Weg dorthin. Da JEDER Leuchtblock selbst nicht-okkludierend
     * ist ({@code "opaque": false} bei Fackel und Lava), fiel er nie aus der Mittelung heraus und
     * leckte ueber Eck ins Bild.
     *
     * <p>Der Test trennt bewusst Engine von Mesher: die LightEngine leckt nachweislich nicht
     * (Vorbedingungen), das Leck sass allein im Mesher.
     */
    private static void testWalledInEmitter() {
        System.out.println("== Eingemauerte Lichtquelle ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 96, 110, STONE);
        /* Raum ab x/z = 16; die Fackel sitzt bei 15/15, also mit ALLEN sechs orthogonalen
           Nachbarn im Stein. Die Luftzelle (16,100,16) beruehrt sie nur DIAGONAL. */
        for (int y = 100; y <= 102; y++) {
            for (int z = 16; z <= 18; z++) {
                for (int x = 16; x <= 18; x++) chunk.setBlock(x, y, z, AIR);
            }
        }
        chunk.setBlock(15, 100, 15, TORCH);
        light(chunk);

        check(chunk.blockLight.get(15, 100, 15) == 14, "eingemauerte Fackel traegt selbst 14");
        check(chunk.blockLight.get(16, 100, 16) == 0,
                "die diagonale Luftzelle bleibt dunkel (LightEngine leckt nicht)");

        de.skyengine.game.world.chunk.ChunkMesher.MeshData mesh =
                new de.skyengine.game.world.chunk.ChunkMesher()
                        .mesh(chunk, 3, null, null, null, null, new Chunk[4]); // Section 3 = y 96..127
        check(hasOpaqueGeometry(mesh), "Mesher liefert Geometrie");
        if (!hasOpaqueGeometry(mesh)) return;

        /* Die sechs Steinflaechen, die IN die Nische hineinschauen, tragen legitim das Licht der
           Fackelzelle (14) — sie sind eingemauert und nie sichtbar. Sie muessen deshalb aus der
           Messung heraus: ein Quad zaehlt nur, wenn nicht ALLE vier Ecken in der Huelle der
           Fackelzelle liegen. Pro QUAD statt pro Vertex, weil das Leck genau an der gemeinsamen
           Ecke (16,100,16) sitzt — die gehoert beiden Seiten, nur die uebrigen Ecken des
           leckenden Bodenquads liegen ausserhalb.
           Nur mesh.opaque: die Fackel emittiert Cross-Quads (face() < 0) in den CUTOUT-Layer, die
           per Definition flach das Licht ihrer eigenen Zelle tragen (dokumentierter Fallback fuer
           richtungslose Quads). testMesherVertices prueft aus demselben Grund nur opaque. */
        final int stride = de.skyengine.game.world.chunk.ChunkMesher.VERTEX_SIZE;
        final int yBase = 3 << ChunkSection.SHIFT; // Section 3 -> y 96; Vertex-y ist section-lokal
        int blockMax = 0;
        int[] buf = mesh.opaque;
        for (int q = 0; buf != null && q + 4 * stride <= buf.length; q += 4 * stride) {
            boolean allInPocket = true;
            int quadMax = 0;
            for (int v = 0; v < 4; v++) {
                int b = q + v * stride;
                float px = (buf[b] & 0xFFFF) / 1024F - 1F;
                float py = (buf[b] >>> 16) / 1024F - 1F;
                float pz = (buf[b + 1] & 0xFFFF) / 1024F - 1F;
                if (px < 14.99F || px > 16.01F || pz < 14.99F || pz > 16.01F
                        || py < (100 - yBase) - 0.01F || py > (101 - yBase) + 0.01F) {
                    allInPocket = false;
                }
                quadMax = Math.max(quadMax, VertexLight.genericRed(buf[b + 4]));
            }
            if (!allInPocket) blockMax = Math.max(blockMax, quadMax);
        }
        blockMax = Math.max(blockMax, compactBlockMaxOutsidePocket(mesh, yBase));
        System.out.println("  Blocklicht ausserhalb der Nische: max " + blockMax + " (erwartet 0)");
        check(blockMax == 0, "eingemauerte Fackel erreicht KEINEN sichtbaren Vertex (kein Diagonal-Leck)");
    }

    /**
     * Letztes fensterloses Glied der Kette: landet das Licht auch im gepackten VERTEX? Der
     * Mesher laeuft GL-frei, also laesst sich der 5. Int direkt nachrechnen. Was danach kommt
     * (VAO-Attribut 1, Vertex-/Fragment-Shader) ist nur noch im Fenster pruefbar.
     */
    private static void testMesherVertices() {
        System.out.println("== Licht im Vertex-Puffer ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 96, 110, STONE);
        /* Versiegelter Hohlraum mitten im Stein — muss dunkle Vertices erzeugen. */
        for (int y = 100; y <= 102; y++) {
            for (int z = 15; z <= 17; z++) {
                for (int x = 15; x <= 17; x++) chunk.setBlock(x, y, z, AIR);
            }
        }
        light(chunk);

        de.skyengine.game.world.chunk.ChunkMesher mesher = new de.skyengine.game.world.chunk.ChunkMesher();
        de.skyengine.game.world.chunk.ChunkMesher.MeshData mesh =
                mesher.mesh(chunk, 3, null, null, null, null, new Chunk[4]); // Section 3 = y 96..127
        check(hasOpaqueGeometry(mesh), "Mesher liefert Geometrie");
        if (!hasOpaqueGeometry(mesh)) return;

        int min = VertexLight.CHANNEL_MAX, max = 0, count = 0, freeBits = 0;
        if (mesh.opaque != null) {
            for (int i = 4; i < mesh.opaque.length; i += ChunkMesher.VERTEX_SIZE) {
                int v = VertexLight.genericSky(mesh.opaque[i]);
                min = Math.min(min, v);
                max = Math.max(max, v);
                freeBits |= mesh.opaque[i] & ~VertexLight.GENERIC_CHANNELS_MASK;
                count++;
            }
        }
        int[] compact = compactLightStats(mesh);
        min = Math.min(min, compact[0]);
        max = Math.max(max, compact[1]);
        count += compact[3];
        System.out.println("  " + count + " opake Vertices, Himmelslicht min " + min + " / max " + max);
        check(max == VertexLight.CHANNEL_MAX,
                "belichtete Oberflaeche erreicht den Vertex mit Licht 255");
        check(min == 0, "versiegelter Hohlraum erreicht den Vertex mit Licht 0");
        check(freeBits == 0, "Bits 24-31 des generischen Licht-/Flag-Ints bleiben reserviert");

        /* Zweite Runde mit einer Fackel im Hohlraum: landet auch das BLOCKlicht im Vertex? */
        Chunk lit = new Chunk(0, 0);
        fillLayer(lit, 96, 110, STONE);
        for (int y = 100; y <= 102; y++) {
            for (int z = 15; z <= 17; z++) {
                for (int x = 15; x <= 17; x++) lit.setBlock(x, y, z, AIR);
            }
        }
        lit.setBlock(16, 101, 16, TORCH);
        light(lit);

        de.skyengine.game.world.chunk.ChunkMesher.MeshData litMesh =
                new de.skyengine.game.world.chunk.ChunkMesher().mesh(lit, 3, null, null, null, null, new Chunk[4]);
        int blockMax = 0;
        if (litMesh != null && litMesh.opaque != null) {
            for (int i = 4; i < litMesh.opaque.length; i += de.skyengine.game.world.chunk.ChunkMesher.VERTEX_SIZE) {
                blockMax = Math.max(blockMax, VertexLight.genericRed(litMesh.opaque[i]));
            }
        }
        if (litMesh != null) blockMax = Math.max(blockMax, compactLightStats(litMesh)[2]);
        System.out.println("  Blocklicht im Vertex, max " + blockMax);
        check(blockMax > 0, "die Fackel erreicht den Vertex ueber die Blocklicht-Bits");
    }

    private static void testVertexLightPrecision() {
        int direct = VertexLight.fromLevels(15, 14);
        check(VertexLight.sky(direct) == 255, "Vertex-Himmelslicht nutzt den vollen Byte-Bereich");
        check(VertexLight.block(direct) == 238, "Vertex-Blocklicht nutzt den vollen Byte-Bereich");
        check((direct & ~VertexLight.CHANNELS_MASK) == 0,
                "reines Vertex-Licht setzt keine Renderer-Flags");

        int averaged = VertexLight.average(58, 0, 4);
        check(VertexLight.sky(averaged) == 247,
                "Eckenlicht bewahrt halbe Lichtstufen statt auf 14 oder 15 zu runden");
        check(VertexLight.sky(averaged) % 17 != 0,
                "gemitteltes Eckenlicht bewahrt Bruchteile der 0..15-Lichtstufen");
    }

    private static void testSmoothVertexPrecision() {
        System.out.println("== Praezises Eckenlicht im Mesh ==");
        Chunk chunk = new Chunk(0, 0);
        fillLayer(chunk, 0, 120, STONE);

        /* Ein horizontaler Tunnel mit offenem Schacht erzeugt einen reproduzierbaren
           Himmelslicht-Verlauf mit halben Werten an gemeinsam genutzten Ecken. */
        for (int x = 2; x <= 30; x++) {
            for (int y = 118; y <= 119; y++) {
                for (int z = 15; z <= 17; z++) chunk.setBlock(x, y, z, AIR);
            }
        }
        for (int x = 29; x <= 31; x++) {
            for (int y = 118; y <= 120; y++) {
                for (int z = 15; z <= 17; z++) chunk.setBlock(x, y, z, AIR);
            }
        }

        light(chunk);
        de.skyengine.game.world.chunk.ChunkMesher.MeshData mesh =
                new de.skyengine.game.world.chunk.ChunkMesher()
                        .mesh(chunk, 3, null, null, null, null, new Chunk[4]);
        check(hasOpaqueGeometry(mesh), "Gradient-Mesher liefert Geometrie");
        if (!hasOpaqueGeometry(mesh)) return;

        boolean foundFractionalVertex = false;
        for (int i = 4; mesh.opaque != null && i < mesh.opaque.length; i += ChunkMesher.VERTEX_SIZE) {
            int sky = VertexLight.genericSky(mesh.opaque[i]);
            if (sky > 0 && sky < VertexLight.CHANNEL_MAX && sky % 17 != 0) {
                foundFractionalVertex = true;
                break;
            }
        }
        if (!foundFractionalVertex) foundFractionalVertex = compactHasFractionalSky(mesh);
        check(foundFractionalVertex,
                "Mesh bewahrt gemittelte Lichtstufen ohne Ganzzahl-Banding");
    }

    private static boolean hasOpaqueGeometry(ChunkMesher.MeshData mesh) {
        if (mesh == null) return false;
        if (mesh.opaque != null) return true;
        if (mesh.compactGeometry == null) return false;
        for (int[] geometry : mesh.compactGeometry) if (geometry != null) return true;
        return false;
    }

    /** minSky, maxSky, maxBlock, cornerCount ueber alle Compact-Shading-Streams. */
    private static int[] compactLightStats(ChunkMesher.MeshData mesh) {
        int min = VertexLight.CHANNEL_MAX, max = 0, blockMax = 0, count = 0;
        for (int mode = 0; mode < 3; mode++) {
            int[] geometry = mesh.compactGeometry == null ? null : mesh.compactGeometry[mode];
            if (geometry == null) continue;
            int quads = geometry.length / 2;
            int[] shading = mesh.compactShading[mode];
            for (int quad = 0; quad < quads; quad++) for (int corner = 0; corner < 4; corner++) {
                int sky = 255, block = 0;
                if (mode != PackedTerrainQuad.SHADING_STANDARD) {
                    int word = shading[mode == PackedTerrainQuad.SHADING_UNIFORM ? quad : quad * 4 + corner];
                    sky = PackedTerrainQuad.sampleSumToByteLight(PackedTerrainQuad.skySum(word));
                    block = PackedTerrainQuad.sampleSumToByteLight(PackedTerrainQuad.redSum(word));
                }
                min = Math.min(min, sky);
                max = Math.max(max, sky);
                blockMax = Math.max(blockMax, block);
                count++;
            }
        }
        return new int[]{min, max, blockMax, count};
    }

    private static boolean compactHasFractionalSky(ChunkMesher.MeshData mesh) {
        for (int mode = PackedTerrainQuad.SHADING_UNIFORM; mode <= PackedTerrainQuad.SHADING_CORNER; mode++) {
            int[] shading = mesh.compactShading[mode];
            if (shading == null) continue;
            for (int word : shading) {
                int sky = PackedTerrainQuad.sampleSumToByteLight(PackedTerrainQuad.skySum(word));
                if (sky > 0 && sky < 255 && sky % 17 != 0) return true;
            }
        }
        return false;
    }

    private static int compactBlockMaxOutsidePocket(ChunkMesher.MeshData mesh, int yBase) {
        int result = 0;
        for (int mode = 0; mode < 3; mode++) {
            int[] geometry = mesh.compactGeometry[mode];
            if (geometry == null) continue;
            int[] shading = mesh.compactShading[mode];
            for (int quad = 0; quad < geometry.length / 2; quad++) {
                int g0 = geometry[quad * 2];
                int axis = PackedTerrainQuad.axis(g0);
                boolean positive = PackedTerrainQuad.positive(g0);
                int x = PackedTerrainQuad.x(g0), y = PackedTerrainQuad.y(g0);
                int z = PackedTerrainQuad.z(g0);
                int minX = x, maxX = x, minY = y, maxY = y, minZ = z, maxZ = z;
                if (axis == 0) {
                    minX = maxX = x + (positive ? 1 : 0);
                    maxY += PackedTerrainQuad.width(g0); maxZ += PackedTerrainQuad.height(g0);
                } else if (axis == 1) {
                    minY = maxY = y + (positive ? 1 : 0);
                    maxX += PackedTerrainQuad.width(g0); maxZ += PackedTerrainQuad.height(g0);
                } else {
                    minZ = maxZ = z + (positive ? 1 : 0);
                    maxX += PackedTerrainQuad.width(g0); maxY += PackedTerrainQuad.height(g0);
                }
                boolean allInPocket = minX >= 15 && maxX <= 16 && minZ >= 15 && maxZ <= 16
                        && minY >= 100 - yBase && maxY <= 101 - yBase;
                if (allInPocket || mode == PackedTerrainQuad.SHADING_STANDARD) continue;
                int corners = mode == PackedTerrainQuad.SHADING_UNIFORM ? 1 : 4;
                for (int corner = 0; corner < corners; corner++) {
                    int word = shading[mode == PackedTerrainQuad.SHADING_UNIFORM ? quad : quad * 4 + corner];
                    result = Math.max(result, PackedTerrainQuad.sampleSumToByteLight(
                            PackedTerrainQuad.redSum(word)));
                }
            }
        }
        return result;
    }

    /**
     * Grosser Editor-Aushub ueber 3x3 Chunks: vorwaerts und rueckwaerts aktualisierte Batches
     * muessen exakt dieselben Heightmaps/Lichtwerte wie ein frisches Initial-Lighting liefern.
     */
    private static void testMultiChunkBatchOrder() {
        System.out.println("== Mehrchunk-Batch gegen Neubeleuchtung ==");
        Chunk[][] forward = buildSolidGrid();
        Chunk[][] reverse = buildSolidGrid();
        Chunk[][] fresh = buildSolidGrid();
        lightGrid(forward);
        lightGrid(reverse);

        LightBatch[][] forwardBatches = carveGrid(forward);
        LightBatch[][] reverseBatches = carveGrid(reverse);
        carveGrid(fresh);

        updateGridBatches(forward, forwardBatches, false);
        updateGridBatches(reverse, reverseBatches, true);
        lightGrid(fresh);

        int[] forwardDiff = compareGridLight(forward, fresh);
        int[] reverseDiff = compareGridLight(reverse, fresh);
        System.out.println("  vorwaerts: Himmel " + forwardDiff[0] + ", Block " + forwardDiff[1]
                + ", Heightmap " + forwardDiff[2]);
        System.out.println("  rueckwaerts: Himmel " + reverseDiff[0] + ", Block " + reverseDiff[1]
                + ", Heightmap " + reverseDiff[2]);
        check(forwardDiff[0] == 0 && forwardDiff[1] == 0 && forwardDiff[2] == 0,
                "Mehrchunk-Batch vorwaerts == frisches Lighting");
        check(reverseDiff[0] == 0 && reverseDiff[1] == 0 && reverseDiff[2] == 0,
                "Mehrchunk-Batch rueckwaerts == frisches Lighting");
    }

    private static Chunk[][] buildSolidGrid() {
        Chunk[][] grid = new Chunk[3][3];
        for (int gz = 0; gz < 3; gz++) {
            for (int gx = 0; gx < 3; gx++) {
                Chunk chunk = new Chunk(gx - 1, gz - 1);
                fillLayer(chunk, 0, 40, STONE);
                grid[gz][gx] = chunk;
            }
        }
        return grid;
    }

    /**
     * Hohlraum x/z -16..47, y 10..39 unter einem Steindach; vier Schaechte an Chunk-Kanten
     * oeffnen das Dach. Alle neun Chunks tragen Edits, das Licht kreuzt mehrere ihrer Raender.
     */
    private static LightBatch[][] carveGrid(Chunk[][] grid) {
        LightBatch[][] batches = new LightBatch[3][3];
        for (int gz = 0; gz < 3; gz++) {
            for (int gx = 0; gx < 3; gx++) {
                Chunk chunk = grid[gz][gx];
                LightBatch batch = new LightBatch();
                batches[gz][gx] = batch;
                int baseX = chunk.chunkX << ChunkSection.SHIFT;
                int baseZ = chunk.chunkZ << ChunkSection.SHIFT;
                for (int lz = 0; lz < SIZE; lz++) {
                    int wz = baseZ + lz;
                    if (wz < -16 || wz > 47) continue;
                    for (int lx = 0; lx < SIZE; lx++) {
                        int wx = baseX + lx;
                        if (wx < -16 || wx > 47) continue;
                        for (int y = 10; y <= 39; y++) {
                            batch.add(lx | (lz << ChunkSection.SHIFT) | (y << 10), STONE);
                            chunk.setBlock(lx, y, lz, AIR);
                        }
                        if (isTestShaft(wx, wz)) {
                            batch.add(lx | (lz << ChunkSection.SHIFT) | (40 << 10), STONE);
                            chunk.setBlock(lx, 40, lz, AIR);
                        }
                    }
                }
            }
        }
        return batches;
    }

    private static boolean isTestShaft(int x, int z) {
        return (x == -1 || x == 31) && (z == -1 || z == 31);
    }

    private static void updateGridBatches(Chunk[][] grid, LightBatch[][] batches, boolean reverse) {
        LightEngine engine = new LightEngine();
        for (int step = 0; step < 9; step++) {
            int index = reverse ? 8 - step : step;
            int gx = index % 3, gz = index / 3;
            Chunk center = grid[gz][gx];
            LightBatch batch = batches[gz][gx];
            engine.onBlocksChanged(center,
                    at(grid, gx, gz - 1), at(grid, gx, gz + 1),
                    at(grid, gx - 1, gz), at(grid, gx + 1, gz),
                    diagonals(grid, gx, gz), batch.packed, batch.oldIds, batch.count);
        }
        stabilizeGrid(engine, grid, reverse);
    }

    private static void lightGrid(Chunk[][] grid) {
        LightEngine engine = new LightEngine();
        for (int gz = 0; gz < 3; gz++) {
            for (int gx = 0; gx < 3; gx++) {
                engine.lightInitial(grid[gz][gx],
                        at(grid, gx, gz - 1), at(grid, gx, gz + 1),
                        at(grid, gx - 1, gz), at(grid, gx + 1, gz), diagonals(grid, gx, gz));
            }
        }
        for (Chunk[] row : grid) {
            for (Chunk chunk : row) chunk.status = ChunkStatus.LIT;
        }
        stabilizeGrid(engine, grid, false);
    }

    private static void stabilizeGrid(LightEngine engine, Chunk[][] grid, boolean reverse) {
        boolean changed;
        do {
            changed = false;
            for (int step = 0; step < 9; step++) {
                int index = reverse ? 8 - step : step;
                int gx = index % 3, gz = index / 3;
                changed |= engine.exchangeBorders(grid[gz][gx],
                        at(grid, gx, gz - 1), at(grid, gx, gz + 1),
                        at(grid, gx - 1, gz), at(grid, gx + 1, gz), diagonals(grid, gx, gz));
            }
        } while (changed);
    }

    private static Chunk at(Chunk[][] grid, int gx, int gz) {
        return gx >= 0 && gx < 3 && gz >= 0 && gz < 3 ? grid[gz][gx] : null;
    }

    private static Chunk[] diagonals(Chunk[][] grid, int gx, int gz) {
        return new Chunk[]{at(grid, gx - 1, gz - 1), at(grid, gx + 1, gz - 1),
                at(grid, gx - 1, gz + 1), at(grid, gx + 1, gz + 1)};
    }

    /** Rueckgabe: Abweichungen Himmelslicht, Blocklicht, Heightmap. */
    private static int[] compareGridLight(Chunk[][] actual, Chunk[][] expected) {
        int sky = 0, block = 0, height = 0;
        for (int gz = 0; gz < 3; gz++) {
            for (int gx = 0; gx < 3; gx++) {
                Chunk a = actual[gz][gx], e = expected[gz][gx];
                for (int z = 0; z < SIZE; z++) {
                    for (int x = 0; x < SIZE; x++) {
                        if (a.heightmap[idx(x, z)] != e.heightmap[idx(x, z)]) height++;
                        for (int y = 0; y <= 50; y++) {
                            if (a.light.get(x, y, z) != e.light.get(x, y, z)) sky++;
                            if (a.blockLight.get(x, y, z) != e.blockLight.get(x, y, z)) block++;
                        }
                    }
                }
            }
        }
        return new int[]{sky, block, height};
    }

    private static final class LightBatch {
        int[] packed = new int[4096];
        int[] oldIds = new int[4096];
        int count;

        void add(int position, int oldId) {
            if (this.count == this.packed.length) {
                this.packed = java.util.Arrays.copyOf(this.packed, this.count * 2);
                this.oldIds = java.util.Arrays.copyOf(this.oldIds, this.count * 2);
            }
            this.packed[this.count] = position;
            this.oldIds[this.count] = oldId;
            this.count++;
        }
    }

    /* ------------------------------------------------------------------ */

    /** Initial-Lighting ohne Nachbarn (isolierter Chunk) — reicht fuer die Einzeltests. */
    private static void light(Chunk chunk) {
        new LightEngine().lightInitial(chunk, null, null, null, null, new Chunk[4]);
        chunk.status = ChunkStatus.LIT;
    }

    private static void fillLayer(Chunk chunk, int fromY, int toY, int block) {
        for (int y = fromY; y <= toY; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) chunk.setBlock(x, y, z, block);
            }
        }
    }

    private static int idx(int x, int z) {
        return (z << ChunkSection.SHIFT) | x;
    }

    private static int id(String encoded) {
        BlockState state = BlockStateCodec.decode(encoded);
        if (state == null) throw new IllegalStateException("Testblock nicht gefunden: " + encoded);
        return state.getId();
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  [OK] " : "  [FEHLER] ") + what);
        if (!ok) errors++;
    }

    private LightProbe() {}
}
