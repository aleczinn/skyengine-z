package de.skyengine.shared.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ImmutableSnapshotOwnershipTest {
    @Test
    void publicBlockEntityConstructorDefensivelyCopiesBothDirections() {
        byte[] mutable = {1, 2, 3};
        BlockEntitySnapshot snapshot = new BlockEntitySnapshot(1, 2, 3,
                "voxelstories:test", mutable);
        mutable[0] = 9;
        byte[] exposed = snapshot.data();
        exposed[1] = 9;

        assertEquals(1, snapshot.dataPayload().get(0));
        assertEquals(2, snapshot.dataPayload().get(1));
    }

    @Test
    void immutableSlicesShareStorageButNeverMutableCursorState() {
        ImmutableByteArray payload = ImmutableByteArray.takeOwnership(new byte[]{1, 2, 3, 4});
        ImmutableByteArray slice = payload.slice(1, 2);
        assertEquals(2, slice.get(0));
        assertNotSame(payload.readOnlyView(), slice.readOnlyView());
        var first = slice.readOnlyView();
        first.get();
        assertEquals(2, slice.readOnlyView().get());
    }
}
