package de.skyengine.game.world.chunk;

import de.skyengine.game.world.chunk.palette.BitStorage;
import de.skyengine.game.world.chunk.palette.PalettedContainer;
import de.skyengine.game.world.light.LightStorage;
import de.skyengine.shared.world.ImmutableByteArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChunkSnapshotCopyOnWriteTest {
    @Test
    void frozenPaletteAndWordsRemainStableAfterSectionMutation() {
        PalettedContainer blocks = new PalettedContainer(ChunkSection.VOLUME, 1);
        blocks.set(0, 2);
        PalettedContainer.FrozenData revisionN = blocks.freezeData();

        blocks.set(1, 2);
        PalettedContainer.FrozenData revisionN1 = blocks.freezeData();

        assertSame(revisionN.palette(), revisionN1.palette(), "unchanged palette must be shared");
        assertNotSame(revisionN.packedIndices(), revisionN1.packedIndices(),
                "the changed packed component must use COW");
        BitStorage oldWords = BitStorage.adoptImmutable(revisionN.bitsPerEntry(),
                ChunkSection.VOLUME, revisionN.packedIndices());
        BitStorage newWords = BitStorage.adoptImmutable(revisionN1.bitsPerEntry(),
                ChunkSection.VOLUME, revisionN1.packedIndices());
        assertEquals(0, oldWords.get(1));
        assertEquals(1, newWords.get(1));
    }

    @Test
    void adoptedLightCopiesOnlyWhenMutated() {
        byte[] packed = new byte[16 * 1024];
        ImmutableByteArray revisionN = ImmutableByteArray.takeOwnership(packed);
        LightStorage light = new LightStorage();
        light.installImmutableSection(0, revisionN);

        light.set(0, 0, 0, 7);

        assertEquals(0, revisionN.get(0));
        assertEquals(7, light.get(0, 0, 0));
    }

    @Test
    void unchangedLightRevisionReusesOneImmutableFreeze() {
        LightStorage light = new LightStorage();
        light.set(1, 1, 1, 9);
        var revisionN = light.snapshotSection(0);
        var sameRevision = light.snapshotSection(0);
        assertSame(revisionN, sameRevision);

        light.set(2, 2, 2, 7);
        var revisionN1 = light.snapshotSection(0);
        assertNotSame(revisionN, revisionN1);
        int packed = revisionN.packedNibblesData().get((1 << 10 | 1 << 5 | 1) >> 1);
        assertEquals(9, packed >>> 4 & 0xF);
    }
}
