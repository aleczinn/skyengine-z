package de.skyengine.client.network;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.save.DataTagIO;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.chunk.palette.BitStorage;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.light.LightStorage;
import de.skyengine.shared.network.ProtocolException;
import de.skyengine.shared.world.ChunkColumnSnapshot;
import de.skyengine.shared.world.ChunkSectionSnapshot;
import de.skyengine.shared.world.LightPlane;

import java.util.Objects;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Rebuilds the existing L0 representation from a transport-neutral immutable snapshot. */
public final class LegacyChunkSnapshotDecoder {
    private LegacyChunkSnapshotDecoder() {
    }

    public static Chunk decode(ChunkColumnSnapshot snapshot) throws ProtocolException {
        return decode(snapshot, null);
    }

    public static Chunk decode(ChunkColumnSnapshot snapshot, Dimension world) throws ProtocolException {
        Objects.requireNonNull(snapshot, "snapshot");
        Chunk chunk = new Chunk(snapshot.chunkX(), snapshot.chunkZ());
        boolean[] receivedSections = new boolean[Chunk.SECTIONS];

        for (ChunkSectionSnapshot section : snapshot.sections()) {
            int[] palette = section.palette();
            for (int stateId : palette) {
                if (stateId < 0) throw new ProtocolException("Negative block-state ID in chunk palette");
            }
            BitStorage storage = null;
            if (section.bitsPerEntry() != 0) {
                storage = new BitStorage(section.bitsPerEntry(), ChunkSection.VOLUME,
                        section.packedPaletteIndices());
                validatePaletteIndices(section, storage, palette.length);
            }
            PalettedContainer blocks = new PalettedContainer(ChunkSection.VOLUME,
                    palette, palette.length, storage, section.nonAir());
            chunk.installSection(section.sectionY(), new ChunkSection(blocks));
            receivedSections[section.sectionY()] = true;
            installLight(chunk.light, section.sectionY(), section.skyLight());
            installLight(chunk.blockLight, section.sectionY(), section.blockLight());
        }

        chunk.grassTintCorners = snapshot.grassTintCorners();
        chunk.foliageTintCorners = snapshot.foliageTintCorners();
        chunk.biomeIds = snapshot.biomeIds();
        chunk.heightmap = snapshot.heightmap();
        installBlockEntities(chunk, snapshot, world);
        installOmittedSectionLight(chunk, receivedSections);
        // Network snapshots already contain final block, biome and light state.
        chunk.status = ChunkStatus.LIT;
        return chunk;
    }

    private static void installBlockEntities(Chunk chunk, ChunkColumnSnapshot snapshot,
                                             Dimension world) throws ProtocolException {
        for (var source : snapshot.blockEntities()) {
            installBlockEntity(chunk, source, world);
        }
    }

    public static void installBlockEntity(Chunk chunk,
                                          de.skyengine.shared.world.BlockEntitySnapshot source,
                                          Dimension world) throws ProtocolException {
            BlockEntityType<?> type;
            try {
                type = Registries.BLOCK_ENTITY.get(Identifier.of(source.typeId()));
            } catch (IllegalArgumentException invalidId) {
                throw new ProtocolException("Invalid block-entity type", invalidId);
            }
            if (type == null) return;
            int stateId = chunk.getBlock(source.localX(), source.y(), source.localZ());
            if (Blocks.getState(stateId).getBlock().getBlockEntityType() != type) return;
            BlockEntity entity = chunk.getBlockEntity(source.localX(), source.y(), source.localZ());
            if (entity == null || entity.getType() != type) {
                BlockPos pos = new BlockPos((chunk.chunkX << ChunkSection.SHIFT) + source.localX(),
                        source.y(), (chunk.chunkZ << ChunkSection.SHIFT) + source.localZ());
                entity = type.create(pos, Blocks.getState(stateId));
                if (world != null) entity.setWorld(world);
                chunk.removeBlockEntity(source.localX(), source.y(), source.localZ());
                chunk.setBlockEntity(source.localX(), source.y(), source.localZ(), entity);
            }
            try {
                entity.loadNetwork(DataTagIO.read(new DataInputStream(new ByteArrayInputStream(source.dataView()))));
            } catch (IOException malformed) {
                throw new ProtocolException("Invalid block-entity payload", malformed);
            }
    }

    /**
     * Empty sections are intentionally absent from the wire format. Sections completely above
     * the heightmap nevertheless contain full skylight, which matters when a surface lies on a
     * section boundary and the mesher samples its omitted upper neighbour.
     */
    private static void installOmittedSectionLight(Chunk chunk, boolean[] receivedSections) {
        int highestBlocker = 0;
        for (int height : chunk.heightmap) highestBlocker = Math.max(highestBlocker, height);
        for (int sectionY = 0; sectionY < Chunk.SECTIONS; sectionY++) {
            if (receivedSections[sectionY]) continue;
            int sectionBaseY = sectionY << ChunkSection.SHIFT;
            chunk.light.setUniform(sectionY, sectionBaseY >= highestBlocker ? 15 : 0);
            chunk.blockLight.setUniform(sectionY, 0);
        }
    }

    private static void validatePaletteIndices(ChunkSectionSnapshot section, BitStorage storage,
                                               int paletteSize) throws ProtocolException {
        for (int index = 0; index < ChunkSection.VOLUME; index++) {
            int paletteIndex = storage.get(index);
            if (paletteIndex >= paletteSize) {
                throw new ProtocolException("Chunk " + section.sectionY()
                        + " contains palette index " + paletteIndex
                        + " outside palette size " + paletteSize);
            }
        }
    }

    private static void installLight(LightStorage target, int sectionY, LightPlane plane) {
        switch (plane.mode()) {
            case UNIFORM_ZERO -> target.setUniform(sectionY, 0);
            case UNIFORM_FULL -> target.setUniform(sectionY, 15);
            case PACKED_NIBBLES -> target.installPackedSection(sectionY, plane.packedNibbles());
        }
    }
}
