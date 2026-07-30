package de.skyengine.game.world.light;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.chunk.palette.PalettedContainer;

import java.util.Arrays;

/**
 * Himmelslicht-Engine: Spalten-Initialisierung über die Heightmap + Flood-Fill (Increase-/
 * Decrease-BFS) ins Dunkle (Höhlen, Überhänge), inkrementelle Updates bei Block-Edits.
 *
 * <p><b>Arbeitsraum:</b> das 3×3-Chunk-Umfeld um einen Zentrums-Chunk; Koordinaten sind
 * chunk-lokal zum Zentrum mit x/z ∈ [-32, 63] (Lichtradius 15 &lt; Chunkbreite 32 ⇒ ein
 * BFS erreicht höchstens die direkten Nachbarn — kein transitives Gating nötig).</p>
 *
 * <p><b>Threading:</b> Initial-Lighting läuft als Worker-Job (Aufrufer hält Read-Locks für
 * die Block-Reads); Edit-Updates laufen synchron auf dem Render-Thread (der einzige
 * Block-Schreiber — keine Locks nötig). Licht-Writes sind bewusst lock-frei
 * (siehe {@link LightStorage}); geänderte Sections werden dirty markiert, Remesh konvergiert.
 * Eine Instanz ist NICHT threadsicher — pro Worker-Thread (ThreadLocal) bzw. eine für den
 * Render-Thread verwenden.</p>
 *
 * <p><b>Zwei Ebenen:</b> Himmelslicht ({@code chunk.light}) und Blocklicht ({@code chunk.blockLight},
 * Fackeln/Lava). Beide teilen sich BFS, Dirty-Verwaltung und das 3×3-Umfeld; welche gerade
 * bearbeitet wird, sagt {@link #skyLayer}. Nur drei Regeln sind ebenenspezifisch: die verlustfreie
 * Direkt-Säule samt ihrem Decrease-Gegenstück, die Quellen (Heightmap-Spalten vs. Emitter) und der
 * Randfall über der Welt. Blocklicht ist <b>monochrom</b> (0..15) — farbiges Licht käme als weitere
 * Ebenen daneben.</p>
 */
public final class LightEngine {

    /* Richtungs-Offsets: 0=up, 1=down, 2=north(-z), 3=south(+z), 4=west(-x), 5=east(+x) */
    private static final int[] DIR_X = {0, 0, 0, 0, -1, 1};
    private static final int[] DIR_Y = {1, -1, 0, 0, 0, 0};
    private static final int[] DIR_Z = {0, 0, -1, 1, 0, 0};
    private static final int DIR_DOWN = 1;

    /* Kontext des laufenden Aufrufs: 3×3-Chunks, Index = (zRel+1)*3 + (xRel+1) */
    private final Chunk[] chunks = new Chunk[9];
    /* true = Writes nur in den Zentrums-Chunk (Initial-Lighting vor LIT) */
    private boolean writeCenterOnly;
    /* true = geänderte Zellen dirty markieren (Edits/Exchange; beim Initial-Pass unnötig) */
    private boolean markDirty;
    /* Dirty-Section-Masken pro 3×3-Chunk (16 Bits) */
    private final int[] dirtyMasks = new int[9];
    /* Aktive Licht-Ebene: true = Himmelslicht (chunk.light), false = Blocklicht (chunk.blockLight).
       Zurückgesetzt wird in setContext() und NICHT bloß in einem finally: die Instanz lebt im
       ThreadLocal weiter, eine geworfene Exception darf die Ebene nicht in den nächsten Job
       lecken — sonst landete Blocklicht im Himmelslicht-Array. */
    private boolean skyLayer = true;

    private final IntQueue increase = new IntQueue();
    private final IntQueue decrease = new IntQueue();
    /* Emitter, die eine Unlight-Welle gelöscht hat und die sich selbst wieder speisen. Erst NACH
       der Welle anzuwenden — Begründung in removeLight. */
    private final IntQueue reseed = new IntQueue();

    /* ------------------------- Öffentliche Einstiege ------------------------- */

    /**
     * Initial-Lighting eines Chunks (Worker-Job, Aufrufer hält Read-Locks der 9 Chunks):
     * Heightmap berechnen, Spalten füllen, Flood ins Dunkle — und anschließend dasselbe für die
     * Blocklicht-Ebene, dort mit den Leuchtblöcken als Quellen. Schreibt NUR den eigenen Chunk;
     * was über die Chunkgrenze reichen müsste, holt {@link #exchangeBorders} nach.
     */
    public void lightInitial(Chunk center, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals) {
        this.setContext(center, north, south, west, east, diagonals);
        this.writeCenterOnly = true;
        this.markDirty = false;
        try {
            int[] heightmap = this.computeHeightmap(center);
            this.initColumns(center, heightmap);
            this.seedColumnEdges(heightmap);
            this.seedWaterColumns(center, heightmap);
            this.runIncrease();

            /* Zweite Ebene. Kein Gegenstück zu initColumns: Blocklicht startet überall bei 0
               (Uniform-Default des LightStorage), Quellen sind allein die Emitter. */
            this.skyLayer = false;
            this.seedEmitters(center);
            this.runIncrease();
        } finally {
            this.clearContext();
        }
    }

    /**
     * Rand-Austausch nach dem Initial-Lighting (Aufrufer setzt vorher {@code status = LIT}!):
     * seedet Licht in beide Richtungen über alle Kanten zu bereits-LIT-Nachbarn. Wer von zwei
     * benachbarten Jobs später fertig wird, sieht den anderen als LIT und tauscht beide
     * Richtungen aus — die Reihenfolge der Jobs ist damit egal. Ohne diese Regel könnten sich
     * zwei gleichzeitig fertige Nachbarn gegenseitig verpassen: dauerhaft dunkle Naht.
     * Geänderte Sections werden dirty markiert (Remesh konvergiert sichtbar). Gilt für beide
     * Ebenen — eine Fackel dicht an der Chunkgrenze leuchtet erst dadurch in den Nachbarn hinein.
     */
    public void exchangeBorders(Chunk center, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals) {
        this.setContext(center, north, south, west, east, diagonals);
        this.writeCenterOnly = false;
        this.markDirty = true;
        try {
            this.exchangeLayer(north, south, west, east);
            this.skyLayer = false;
            this.exchangeLayer(north, south, west, east);
            /* applyDirty einmal am Ende: die dirtyMasks sammeln über beide Ebenen. */
            this.applyDirty();
        } finally {
            this.clearContext();
        }
    }

    /** Ein Rand-Durchgang für die gerade aktive Ebene. */
    private void exchangeLayer(Chunk north, Chunk south, Chunk west, Chunk east) {
        if (isLit(north)) this.seedBorder(0, -1);
        if (isLit(south)) this.seedBorder(0, 1);
        if (isLit(west)) this.seedBorder(-1, 0);
        if (isLit(east)) this.seedBorder(1, 0);
        this.runIncrease();
    }

    /**
     * Inkrementelles Licht-Update nach einem Block-Edit (Render-Thread, Chunk ist READY).
     * Pflegt die Heightmap-Säule und lässt Licht kollabieren/fluten. Koordinaten chunk-lokal.
     */
    public void onBlockChanged(Chunk center, Chunk north, Chunk south, Chunk west, Chunk east,
                               Chunk[] diagonals, int lx, int y, int lz, int oldId, int newId) {
        if (center.heightmap == null) return;
        int oldOpacity = BlockRegistry.getState(oldId).getLightOpacity();
        int newOpacity = BlockRegistry.getState(newId).getLightOpacity();
        int oldLuminance = BlockRegistry.getState(oldId).getLuminance();
        int newLuminance = BlockRegistry.getState(newId).getLuminance();
        boolean opacityChanged = oldOpacity != newOpacity;
        /* Die Luminanz-Bedingung ist nicht optional: eine gesetzte Fackel ändert die Opazität
           NICHT (0 -> 0). Ohne sie löste kein einziger Leuchtblock je ein Update aus. */
        if (!opacityChanged && oldLuminance == newLuminance) return;

        this.setContext(center, north, south, west, east, diagonals);
        this.writeCenterOnly = false;
        this.markDirty = true;
        try {
            /* Himmelslicht interessiert sich nur für die Opazität. */
            if (opacityChanged) this.updateSkyAt(center, lx, y, lz, newOpacity > oldOpacity);
            /* Blocklicht für beides: eine neu gesetzte Wand blockt auch Fackellicht. */
            this.skyLayer = false;
            this.updateBlockAt(lx, y, lz, newLuminance);
            this.applyDirty();
        } finally {
            this.clearContext();
        }
    }

    /**
     * Batch-Variante von {@link #onBlockChanged} für Massen-Edits (Explosionen): setContext,
     * BFS-Durchläufe und applyDirty laufen EINMAL für alle Edits eines Zentrums-Chunks statt
     * pro Block — der Einzel-Pfad flutete dasselbe Kratervolumen n-fach neu. Der Aufrufer hat
     * die Blöcke bereits geschrieben (neue IDs stehen im Chunk) und liefert je Edit die
     * chunk-lokale Position (gepackt lx | lz&lt;&lt;5 | y&lt;&lt;10) plus die alte Block-ID.
     *
     * <p>Äquivalenz zum Einzel-Pfad: das BFS konvergiert gegen den eindeutigen Fixpunkt der
     * Licht-Gleichung (Quellen = Heightmap-Säulen, Ränder, Emitter) — Reihenfolge und
     * Gruppierung der Edits ändern den Endzustand nicht. Multi-Seed ist durch den
     * Staleness-Check in runIncrease und die reseed-Queue in removeLight bereits abgedeckt.
     * Nachgewiesen wird die Äquivalenz im LightProbe-Batch-Test (sequenziell == Batch).</p>
     *
     * <p>Ablauf je Ebene wie im Einzel-Pfad, nur zusammengefasst: Himmel = je Edit removeLight
     * + Nachbar-Quellen, danach je EINDEUTIGER Säule die Heightmap EINMAL neu bestimmen
     * (scanColumnDown) und die Direkt-Säule fluten bzw. kappen, dann EIN runIncrease.
     * Block = je Edit removeLight + Emitter-/Nachbar-Seeds, dann EIN runIncrease.</p>
     */
    public void onBlocksChanged(Chunk center, Chunk north, Chunk south, Chunk west, Chunk east,
                                Chunk[] diagonals, int[] packedPos, int[] oldIds, int count) {
        if (center.heightmap == null || count == 0) return;

        this.setContext(center, north, south, west, east, diagonals);
        this.writeCenterOnly = false;
        this.markDirty = true;
        try {
            int[] heightmap = center.heightmap;

            /* --- Himmelslicht: erst alle Abrisse, dann die Säulen, dann EIN Flood. --- */
            boolean anySky = false;
            for (int i = 0; i < count; i++) {
                int pos = packedPos[i];
                int lx = pos & 31, lz = (pos >> 5) & 31, y = (pos >> 10) & 511;
                int oldOpacity = BlockRegistry.getState(oldIds[i]).getLightOpacity();
                int newOpacity = this.opacityAt(lx, y, lz); // neue ID steht schon im Chunk
                if (oldOpacity == newOpacity) continue;
                anySky = true;
                this.removeLight(lx, y, lz);
                for (int d = 0; d < 6; d++) {
                    int nx = lx + DIR_X[d], ny = y + DIR_Y[d], nz = lz + DIR_Z[d];
                    if (ny < 0 || ny >= Chunk.HEIGHT) continue;
                    int level = this.getLight(nx, ny, nz);
                    if (level > 1) this.increase.push(encode(nx, ny, nz, level));
                }
            }
            if (anySky) {
                /* Heightmap je Säule EINMAL neu (Dedup über ein 32x32-Bitfeld): geöffnete
                   Säulen bekommen ihre 15er-Direktzellen NACH den removeLights geseedet —
                   sonst würde der Staleness-Check die frischen 15er gleich wieder verwerfen. */
                long[] columnSeen = new long[ChunkSection.SIZE];
                for (int i = 0; i < count; i++) {
                    int pos = packedPos[i];
                    int lx = pos & 31, lz = (pos >> 5) & 31;
                    int oldOpacity = BlockRegistry.getState(oldIds[i]).getLightOpacity();
                    int newOpacity = this.opacityAt(lx, (pos >> 10) & 511, lz);
                    if (oldOpacity == newOpacity) continue;
                    if ((columnSeen[lz] & (1L << lx)) != 0) continue;
                    columnSeen[lz] |= 1L << lx;

                    int hIdx = (lz << ChunkSection.SHIFT) | lx;
                    int oldHeight = heightmap[hIdx];
                    int newHeight = scanColumnDown(center, lx, lz, Chunk.HEIGHT - 1);
                    if (newHeight == oldHeight) continue;
                    heightmap[hIdx] = newHeight;
                    if (newHeight < oldHeight) {
                        /* Säule geöffnet: Direkt-Himmel bis zum neuen Blocker fluten. */
                        for (int yy = oldHeight - 1; yy >= newHeight; yy--) {
                            this.setLight(lx, yy, lz, 15);
                            this.markCell(lx, yy, lz);
                            this.increase.push(encode(lx, yy, lz, 15));
                        }
                    } else if (newHeight >= 2) {
                        /* Neuer höchster Blocker: die Zelle darunter kollabiert die alte
                           15er-Säule über die Down-Regel (wie der Einzel-Pfad). */
                        this.removeLight(lx, newHeight - 2, lz);
                    }
                }
                this.runIncrease();
            }

            /* --- Blocklicht: derselbe Ablauf wie updateBlockAt, nur gesammelt. --- */
            this.skyLayer = false;
            boolean anyBlock = false;
            for (int i = 0; i < count; i++) {
                int pos = packedPos[i];
                int lx = pos & 31, lz = (pos >> 5) & 31, y = (pos >> 10) & 511;
                int oldId = oldIds[i];
                int newId = center.getBlock(lx, y, lz);
                int oldOpacity = BlockRegistry.getState(oldId).getLightOpacity();
                int newOpacity = BlockRegistry.getState(newId).getLightOpacity();
                int oldLuminance = BlockRegistry.getState(oldId).getLuminance();
                int newLuminance = BlockRegistry.getState(newId).getLuminance();
                if (oldOpacity == newOpacity && oldLuminance == newLuminance) continue;
                anyBlock = true;
                this.removeLight(lx, y, lz);
                if (newLuminance > 0) {
                    this.setLight(lx, y, lz, newLuminance);
                    this.markCell(lx, y, lz);
                    this.increase.push(encode(lx, y, lz, newLuminance));
                }
                for (int d = 0; d < 6; d++) {
                    int nx = lx + DIR_X[d], ny = y + DIR_Y[d], nz = lz + DIR_Z[d];
                    if (ny < 0 || ny >= Chunk.HEIGHT) continue;
                    int level = this.getLight(nx, ny, nz);
                    if (level > 1) this.increase.push(encode(nx, ny, nz, level));
                }
            }
            if (anyBlock) this.runIncrease();

            this.applyDirty();
        } finally {
            this.clearContext();
        }
    }

    /**
     * Blocklicht-Flood nach einem Edit. Braucht — anders als {@link #updateSkyAt} — kein
     * dunkler/heller-Zweigpaar: es gibt keine Heightmap zu pflegen, und derselbe Ablauf deckt alle
     * vier Fälle ab (Leuchtblock setzen/abbauen, undurchsichtigen Block setzen/abbauen).
     */
    private void updateBlockAt(int lx, int y, int lz, int newLuminance) {
        /* Alten Beitrag der Zelle abräumen; fremd gespeiste Nachbarn landen als Re-Seeds
           in der Increase-Queue. */
        this.removeLight(lx, y, lz);

        if (newLuminance > 0) {
            this.setLight(lx, y, lz, newLuminance);
            this.markCell(lx, y, lz);
            this.increase.push(encode(lx, y, lz, newLuminance));
        }
        /* Nachbarn als Quellen anbieten — deckt „Opazität gesunken, Licht darf jetzt herein" ab. */
        for (int d = 0; d < 6; d++) {
            int nx = lx + DIR_X[d], ny = y + DIR_Y[d], nz = lz + DIR_Z[d];
            int level = this.getLight(nx, ny, nz);
            if (level > 1) this.increase.push(encode(nx, ny, nz, level));
        }
        this.runIncrease();
    }

    /** Heightmap-Säule + Flood nach einem Edit, siehe {@link #onBlockChanged}. */
    private void updateSkyAt(Chunk center, int lx, int y, int lz, boolean darker) {
        int[] heightmap = center.heightmap;
        int hIdx = (lz << ChunkSection.SHIFT) | lx;

        if (darker) {
            /* Dunkler geworden. Neuer höchster Blocker? -> Säule darunter verliert den
               Direkt-Himmel (ein removeLight an der Spitze kaskadiert die 15er-Säule
               über die Down-Regel selbst nach unten). */
            if (y + 1 > heightmap[hIdx]) {
                heightmap[hIdx] = y + 1;
                if (y > 0) this.removeLight(lx, y - 1, lz);
            }
            this.removeLight(lx, y, lz);
            this.runIncrease(); // Re-Seeds aus dem Decrease anwenden
        } else {
            /* Durchlässiger geworden. War das der oberste Blocker, wird die Säule bis zum
               nächsten Blocker wieder Direkt-Himmel; sonst fließt Nachbarlicht ein. */
            if (y + 1 == heightmap[hIdx]) {
                int newHeight = scanColumnDown(center, lx, lz, y);
                heightmap[hIdx] = newHeight;
                for (int yy = y; yy >= newHeight; yy--) {
                    this.setLight(lx, yy, lz, 15);
                    this.markCell(lx, yy, lz);
                    this.increase.push(encode(lx, yy, lz, 15));
                }
            } else {
                /* Zelle neu berechnen lassen: Nachbarn als Quellen einreihen */
                for (int d = 0; d < 6; d++) {
                    int nx = lx + DIR_X[d], ny = y + DIR_Y[d], nz = lz + DIR_Z[d];
                    int level = this.getLight(nx, ny, nz);
                    if (level > 1) this.increase.push(encode(nx, ny, nz, level));
                }
                /* Opazität gesunken, aber > 0 (z.B. Stein -> Wasser): alten Wert der Zelle
                   entfernen, damit der neue, höhere einfließen kann. */
                this.removeLight(lx, y, lz);
            }
            this.runIncrease();
        }
    }

    /* ------------------------- Initial-Lighting ------------------------- */

    /** Heightmap: Y des höchsten Blocks mit Licht-Opazität &gt; 0, +1 (0 = Säule frei). */
    private int[] computeHeightmap(Chunk chunk) {
        int[] heightmap = new int[ChunkSection.SIZE * ChunkSection.SIZE];
        for (int lz = 0; lz < ChunkSection.SIZE; lz++) {
            for (int lx = 0; lx < ChunkSection.SIZE; lx++) {
                heightmap[(lz << ChunkSection.SHIFT) | lx] = scanColumnDown(chunk, lx, lz, Chunk.HEIGHT - 1);
            }
        }
        chunk.heightmap = heightmap;
        return heightmap;
    }

    /** Höchster Blocker (Opazität &gt; 0) von {@code fromY} abwärts, Rückgabe y+1 (0 = keiner). */
    private static int scanColumnDown(Chunk chunk, int lx, int lz, int fromY) {
        for (int s = fromY >> ChunkSection.SHIFT; s >= 0; s--) {
            ChunkSection section = chunk.getSection(s);
            if (section == null || section.isEmpty()) continue;
            int base = s << ChunkSection.SHIFT;
            for (int y = Math.min(fromY, base + ChunkSection.MASK); y >= base; y--) {
                int id = chunk.getBlock(lx, y, lz);
                if (id != 0 && BlockRegistry.getState(id).getLightOpacity() > 0) return y + 1;
            }
        }
        return 0;
    }

    /** Füllt alle Zellen über der Heightmap mit 15 (Sections komplett darüber als Uniform). */
    private void initColumns(Chunk chunk, int[] heightmap) {
        int maxHeight = 0;
        for (int h : heightmap) maxHeight = Math.max(maxHeight, h);

        for (int s = 0; s < Chunk.SECTIONS; s++) {
            int base = s << ChunkSection.SHIFT;
            if (base >= maxHeight) {
                /* Section komplett über dem Terrain -> uniform 15, kostet kein Byte. */
                chunk.light.setUniform(s, 15);
                continue;
            }
            int top = base + ChunkSection.MASK;
            for (int lz = 0; lz < ChunkSection.SIZE; lz++) {
                for (int lx = 0; lx < ChunkSection.SIZE; lx++) {
                    int h = heightmap[(lz << ChunkSection.SHIFT) | lx];
                    for (int y = Math.max(h, base); y <= top; y++) {
                        chunk.light.set(lx, y, lz, 15);
                    }
                }
            }
        }
    }

    /**
     * Seedet den Flood ins Dunkle: an jeder Säulenkante (innerhalb des Chunks), wo die
     * Nachbarsäule höher aufragt, liegen eigene 15er-Zellen neben unbeleuchteten — sie sind
     * die BFS-Quellen für Höhlen/Überhänge. Randsäulen zu Nachbar-Chunks übernimmt der
     * spätere {@link #exchangeBorders}-Schritt.
     */
    private void seedColumnEdges(int[] heightmap) {
        int size = ChunkSection.SIZE;
        for (int lz = 0; lz < size; lz++) {
            for (int lx = 0; lx < size; lx++) {
                int own = heightmap[(lz << ChunkSection.SHIFT) | lx];
                for (int d = 2; d < 6; d++) { // nur horizontale Richtungen
                    int nx = lx + DIR_X[d], nz = lz + DIR_Z[d];
                    if (nx < 0 || nx >= size || nz < 0 || nz >= size) continue;
                    int neighbor = heightmap[(nz << ChunkSection.SHIFT) | nx];
                    for (int y = own; y < neighbor; y++) {
                        this.increase.push(encode(lx, y, lz, 15));
                    }
                }
            }
        }
    }

    /**
     * Seedet Wasser-Säulen: {@link #seedColumnEdges} greift nur bei Höhen-DIFFERENZEN —
     * eine flache Ozeanfläche (uniforme Heightmap, Wasser ist Blocker) hätte sonst NULL
     * BFS-Quellen und bliebe samt Meeresboden komplett unbeleuchtet (Licht käme nur
     * ~15 Blöcke seitlich vom Ufer). Daher: über jeder Säule, deren oberster Blocker ein
     * Fluid ist, die 15er-Luftzelle auf Heightmap-Höhe pushen — das Skylight fällt dann
     * mit der normalen Down-Regel (−1/Block, Vanilla) durch die eigene Wassersäule.
     * <b>Nicht wegoptimieren</b> — ohne diesen Schritt ist jeder Meeresboden schwarz.
     */
    private void seedWaterColumns(Chunk chunk, int[] heightmap) {
        int size = ChunkSection.SIZE;
        for (int lz = 0; lz < size; lz++) {
            for (int lx = 0; lx < size; lx++) {
                int h = heightmap[(lz << ChunkSection.SHIFT) | lx];
                if (h <= 0 || h >= Chunk.HEIGHT) continue;
                int id = chunk.getBlock(lx, h - 1, lz);
                if (id == 0 || !BlockRegistry.getState(id).isFluid()) continue;
                this.increase.push(encode(lx, h, lz, 15));
            }
        }
    }

    /**
     * Seedet alle Leuchtblöcke des Chunks in den Blocklicht-BFS — das Gegenstück zu
     * {@link #initColumns} und {@link #seedColumnEdges}, nur eben ohne Heightmap: Quellen sind
     * die Blöcke selbst.
     *
     * <p>Ein Zell-Scan wäre teuer (32768 Zellen je Section). Deshalb zuerst der
     * <b>Paletten-Vorfilter</b>: kommt in der Palette einer Section gar keine leuchtende State-ID
     * vor, kann auch keine ihrer Zellen leuchten. In typischem Terrain fallen damit alle 16
     * Sections heraus, ohne dass eine einzige Zelle gelesen wird.</p>
     *
     * <p>Die Palette schrumpft nie — eine gesetzte und wieder abgebaute Fackel hinterlässt also
     * einen Treffer, der keiner mehr ist. Das kostet einen überflüssigen Scan, sonst nichts.</p>
     */
    private void seedEmitters(Chunk chunk) {
        for (int s = 0; s < Chunk.SECTIONS; s++) {
            ChunkSection section = chunk.getSection(s);
            if (section == null || section.isEmpty()) continue;
            PalettedContainer container = section.container();
            if (container == null) continue;

            boolean anyEmitter = false;
            for (int stateId : container.paletteEntries()) {
                if (stateId != 0 && BlockRegistry.getState(stateId).getLuminance() > 0) {
                    anyEmitter = true;
                    break;
                }
            }
            if (!anyEmitter) continue;

            int base = s << ChunkSection.SHIFT;
            for (int ly = 0; ly < ChunkSection.SIZE; ly++) {
                for (int lz = 0; lz < ChunkSection.SIZE; lz++) {
                    for (int lx = 0; lx < ChunkSection.SIZE; lx++) {
                        int id = section.getBlock(lx, ly, lz);
                        if (id == 0) continue;
                        int luminance = BlockRegistry.getState(id).getLuminance();
                        if (luminance == 0) continue;
                        this.setLight(lx, base + ly, lz, luminance);
                        this.increase.push(encode(lx, base + ly, lz, luminance));
                    }
                }
            }
        }
    }

    /** Seedet beide Seiten einer Chunk-Kante (dx/dz = Richtung zum Nachbarn) in den BFS. */
    private void seedBorder(int dx, int dz) {
        int size = ChunkSection.SIZE;
        /* eigene Randspalte + Nachbar-Randspalte */
        int ownX0 = dx < 0 ? 0 : (dx > 0 ? size - 1 : 0);
        int ownZ0 = dz < 0 ? 0 : (dz > 0 ? size - 1 : 0);

        for (int s = 0; s < Chunk.SECTIONS; s++) {
            /* Uniform-Kurzschluss: sind beide Sections uniform gleich, ist an dieser Kante
               nichts auszutauschen (Standardfall Himmel 15/15 bzw. Untergrund 0/0). Fürs
               Blocklicht greift er noch häufiger — ohne Leuchtblock in der Nähe sind beide
               Seiten uniform 0, der ganze Block-Durchgang kostet dann nichts. */
            Chunk neighborChunk = this.chunkAt(ownX0 + dx * size, ownZ0 + dz * size);
            int ownUniform = this.storageOf(this.chunks[4]).uniformValue(s);
            int neighborUniform = neighborChunk == null ? 0 : this.storageOf(neighborChunk).uniformValue(s);
            if (ownUniform >= 0 && ownUniform == neighborUniform) continue;

            int yBase = s << ChunkSection.SHIFT;
            for (int y = yBase; y <= yBase + ChunkSection.MASK; y++) {
                for (int i = 0; i < size; i++) {
                    int ox = dx != 0 ? ownX0 : i;
                    int oz = dz != 0 ? ownZ0 : i;
                    int nx = ox + dx, nz = oz + dz;
                    int own = this.getLight(ox, y, oz);
                    int other = this.getLight(nx, y, nz);
                    /* Nur seeden, wenn eine Seite die andere verbessern könnte */
                    if (own > other + 1) {
                        this.increase.push(encode(ox, y, oz, own));
                    } else if (other > own + 1) {
                        this.increase.push(encode(nx, y, nz, other));
                    }
                }
            }
        }
    }

    /* ------------------------- BFS ------------------------- */

    private void runIncrease() {
        while (!this.increase.isEmpty()) {
            int entry = this.increase.poll();
            int x = (entry & 0x7F) - 32;
            int z = ((entry >> 7) & 0x7F) - 32;
            int y = (entry >> 14) & 0x1FF;
            int level = (entry >>> 23) & 0xF;
            if (this.getLight(x, y, z) != level) continue; // überholt

            for (int d = 0; d < 6; d++) {
                int nx = x + DIR_X[d], ny = y + DIR_Y[d], nz = z + DIR_Z[d];
                if (ny < 0 || ny >= Chunk.HEIGHT) continue;
                int opacity = this.opacityAt(nx, ny, nz);
                if (opacity >= 15) continue;
                /* Verlustfreie Direkt-Säule: volles Himmelslicht fällt ohne Abschwächung
                   nach unten, solange nichts dämpft (Vanilla). Der Gegenpart steht in
                   removeLight — beide Hälften gehören zusammen. Blocklicht kennt die Regel
                   nicht: eine Fackel leuchtet nach unten genauso weit wie zur Seite. */
                int next = (this.skyLayer && d == DIR_DOWN && level == 15 && opacity == 0)
                        ? 15 : level - Math.max(1, opacity);
                if (next <= 0 || next <= this.getLight(nx, ny, nz)) continue;
                if (!this.writeAllowed(nx, nz)) continue;
                this.setLight(nx, ny, nz, next);
                this.markCell(nx, ny, nz);
                this.increase.push(encode(nx, ny, nz, next));
            }
        }
    }

    /**
     * Entfernt das Licht einer Zelle und lässt alles kollabieren, was von ihr gespeist wurde
     * (Unlight-Wavefront); Zellen mit fremden Quellen landen als Re-Seeds im Increase-BFS,
     * den der Aufrufer anschließend laufen lässt.
     */
    private void removeLight(int x, int y, int z) {
        int old = this.getLight(x, y, z);
        if (old == 0) return;
        this.setLight(x, y, z, 0);
        this.markCell(x, y, z);
        this.decrease.push(encode(x, y, z, old));

        while (!this.decrease.isEmpty()) {
            int entry = this.decrease.poll();
            int cx = (entry & 0x7F) - 32;
            int cz = ((entry >> 7) & 0x7F) - 32;
            int cy = (entry >> 14) & 0x1FF;
            int level = (entry >>> 23) & 0xF;

            for (int d = 0; d < 6; d++) {
                int nx = cx + DIR_X[d], ny = cy + DIR_Y[d], nz = cz + DIR_Z[d];
                if (ny < 0 || ny >= Chunk.HEIGHT) continue;
                int neighborLevel = this.getLight(nx, ny, nz);
                if (neighborLevel == 0) continue;
                /* Von uns gespeist: schwächer als wir, oder die 15er-Säule direkt darunter
                   (Gegenstück zur verlustfreien Down-Regel in runIncrease). */
                boolean fedByUs = neighborLevel < level
                        || (this.skyLayer && d == DIR_DOWN && level == 15 && neighborLevel == 15);
                if (fedByUs && this.writeAllowed(nx, nz)) {
                    this.setLight(nx, ny, nz, 0);
                    this.markCell(nx, ny, nz);
                    this.decrease.push(encode(nx, ny, nz, neighborLevel));
                    /* Eine gelöschte Zelle, die selbst leuchtet, ist ihre eigene Quelle und muss
                       zurückkommen — sonst löschen zwei benachbarte Fackeln einander aus. */
                    if (!this.skyLayer) {
                        int lum = this.luminanceAt(nx, ny, nz);
                        if (lum > 0) this.reseed.push(encode(nx, ny, nz, lum));
                    }
                } else {
                    this.increase.push(encode(nx, ny, nz, neighborLevel));
                }
            }
        }

        /* Erst JETZT die Emitter zurückholen, nicht schon in der Schleife: sonst kann die Welle
           dieselbe Zelle ein zweites Mal aus einer stärkeren Richtung erreichen (Fackel 7 neben
           Lava 15), sie erneut auf 0 setzen und den bereits eingereihten Increase-Eintrag an
           „getLight != level" scheitern lassen — die Fackel bliebe dauerhaft aus. */
        while (!this.reseed.isEmpty()) {
            int entry = this.reseed.poll();
            int rx = (entry & 0x7F) - 32;
            int rz = ((entry >> 7) & 0x7F) - 32;
            int ry = (entry >> 14) & 0x1FF;
            int lum = (entry >>> 23) & 0xF;
            this.setLight(rx, ry, rz, lum);
            this.markCell(rx, ry, rz);
            this.increase.push(entry);
        }
    }

    /* ------------------------- Zugriff aufs 3×3-Umfeld ------------------------- */

    private void setContext(Chunk center, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals) {
        Chunk[] c = this.chunks;
        /* diagonals-Konvention NW, NE, SW, SE (wie ChunkManager.getDiagonalsAtLeast) */
        c[0] = diagonals[0]; c[1] = north;  c[2] = diagonals[1];
        c[3] = west;         c[4] = center; c[5] = east;
        c[6] = diagonals[2]; c[7] = south;  c[8] = diagonals[3];
        Arrays.fill(this.dirtyMasks, 0);
        this.increase.clear();
        this.decrease.clear();
        this.reseed.clear();
        this.skyLayer = true;
    }

    private void clearContext() {
        Arrays.fill(this.chunks, null);
    }

    private Chunk chunkAt(int x, int z) {
        return this.chunks[((z >> ChunkSection.SHIFT) + 1) * 3 + ((x >> ChunkSection.SHIFT) + 1)];
    }

    private static boolean isLit(Chunk chunk) {
        return chunk != null && chunk.status.isAtLeast(ChunkStatus.LIT);
    }

    private boolean writeAllowed(int x, int z) {
        Chunk chunk = this.chunkAt(x, z);
        if (chunk == null) return false;
        if (this.writeCenterOnly) return chunk == this.chunks[4];
        return chunk == this.chunks[4] || chunk.status.isAtLeast(ChunkStatus.LIT);
    }

    /** Die Speicher-Ebene, auf der der laufende Aufruf arbeitet. */
    private LightStorage storageOf(Chunk chunk) {
        return this.skyLayer ? chunk.light : chunk.blockLight;
    }

    private int getLight(int x, int y, int z) {
        /* Über der Welt ist immer voller Himmel — aber kein Blocklicht, sonst strahlte von dort
           oben Fackellicht herein. */
        if (y >= Chunk.HEIGHT) return this.skyLayer ? 15 : 0;
        if (y < 0) return 0;
        Chunk chunk = this.chunkAt(x, z);
        if (chunk == null) return 0;
        return this.storageOf(chunk).get(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
    }

    private void setLight(int x, int y, int z, int value) {
        Chunk chunk = this.chunkAt(x, z);
        if (chunk == null) return;
        this.storageOf(chunk).set(x & ChunkSection.MASK, y, z & ChunkSection.MASK, value);
    }

    /** Eigenleuchten des Blocks in einer Zelle — Zwilling zu {@link #opacityAt}. */
    private int luminanceAt(int x, int y, int z) {
        Chunk chunk = this.chunkAt(x, z);
        if (chunk == null) return 0;
        int id = chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
        return id == 0 ? 0 : BlockRegistry.getState(id).getLuminance();
    }

    private int opacityAt(int x, int y, int z) {
        Chunk chunk = this.chunkAt(x, z);
        if (chunk == null) return 15; // ungeladen: dicht (kein Seeding ins Nichts)
        int id = chunk.getBlock(x & ChunkSection.MASK, y, z & ChunkSection.MASK);
        return id == 0 ? 0 : BlockRegistry.getState(id).getLightOpacity();
    }

    /* ------------------------- Dirty-Verwaltung ------------------------- */

    /**
     * Merkt die Section der geänderten Zelle als dirty vor — inkl. angrenzender Sections/
     * Chunks bei Zellen an Grenzen (das Corner-Smoothing des Meshers liest 1 Zelle hinein).
     *
     * <p>Der ±1-Ring kann rechnerisch aus dem 3×3-Umfeld hinausgreifen (Zelle in der äußersten
     * Ecke eines Diagonal-Chunks). Praktisch unerreichbar — der Lichtradius 15 ist kleiner als
     * die halbe Chunkbreite —, deshalb wird der Fall hier schlicht übersprungen statt einen
     * falschen Chunk zu markieren.</p>
     */
    private void markCell(int x, int y, int z) {
        if (!this.markDirty) return;
        int sy = y >> ChunkSection.SHIFT, ly = y & ChunkSection.MASK;
        int lx = x & ChunkSection.MASK, lz = z & ChunkSection.MASK;
        int fromX = lx == 0 ? -1 : 0, toX = lx == ChunkSection.MASK ? 1 : 0;
        int fromZ = lz == 0 ? -1 : 0, toZ = lz == ChunkSection.MASK ? 1 : 0;
        int mask = 1 << sy;
        if (ly == 0 && sy > 0) mask |= 1 << (sy - 1);
        if (ly == ChunkSection.MASK && sy < Chunk.SECTIONS - 1) mask |= 1 << (sy + 1);

        for (int dz = fromZ; dz <= toZ; dz++) {
            int cz = ((z + dz) >> ChunkSection.SHIFT) + 1;
            if (cz < 0 || cz > 2) continue;
            for (int dx = fromX; dx <= toX; dx++) {
                int cx = ((x + dx) >> ChunkSection.SHIFT) + 1;
                if (cx < 0 || cx > 2) continue;
                this.dirtyMasks[cz * 3 + cx] |= mask;
            }
        }
    }

    private void applyDirty() {
        for (int i = 0; i < 9; i++) {
            int mask = this.dirtyMasks[i];
            Chunk chunk = this.chunks[i];
            if (mask == 0 || chunk == null) continue;
            for (int s = 0; s < Chunk.SECTIONS; s++) {
                if ((mask & (1 << s)) != 0) chunk.markSectionDirty(s);
            }
        }
    }

    /* ------------------------- Hilfen ------------------------- */

    /**
     * Packt eine BFS-Zelle: x/z ∈ [-32,63] (je 7 Bit mit Bias 32), y ∈ [0,511] (9 Bit),
     * Level 0..15 (4 Bit). Passt exakt auf {@code Chunk.HEIGHT = 512} — wer die Welthöhe
     * ändert, muss hier mitziehen.
     */
    private static int encode(int x, int y, int z, int level) {
        return (x + 32) | ((z + 32) << 7) | (y << 14) | (level << 23);
    }

    /** Wachsende FIFO-Queue für gepackte BFS-Einträge (keine Boxing-Allokationen). */
    private static final class IntQueue {
        private int[] data = new int[4096];
        private int head, tail;

        void clear() {
            this.head = this.tail = 0;
        }

        boolean isEmpty() {
            return this.head == this.tail;
        }

        void push(int value) {
            if (this.tail == this.data.length) {
                if (this.head > this.data.length / 2) {
                    /* vorne Platz: zusammenschieben statt wachsen */
                    System.arraycopy(this.data, this.head, this.data, 0, this.tail - this.head);
                } else {
                    int[] bigger = new int[this.data.length * 2];
                    System.arraycopy(this.data, this.head, bigger, 0, this.tail - this.head);
                    this.data = bigger;
                }
                this.tail -= this.head;
                this.head = 0;
            }
            this.data[this.tail++] = value;
        }

        int poll() {
            return this.data[this.head++];
        }
    }
}
