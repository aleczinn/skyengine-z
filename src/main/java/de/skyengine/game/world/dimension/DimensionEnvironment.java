package de.skyengine.game.world.dimension;

/** Render-, Licht- und Physikregeln einer Dimension. */
public record DimensionEnvironment(float backgroundRed, float backgroundGreen, float backgroundBlue,
                                   float fogRed, float fogGreen, float fogBlue,
                                   float fogStart, float fogEnd, boolean forceFog,
                                   float ambientLight, boolean hasSkylight,
                                   boolean ultrawarm, double coordinateScale) {

    public static final DimensionEnvironment OVERWORLD = new DimensionEnvironment(
            0.5059F, 0.6431F, 1.0F,
            0.5059F, 0.6431F, 1.0F,
            0F, 0F, false, 0.04F, true, false, 1.0);

    public static final DimensionEnvironment NETHER = new DimensionEnvironment(
            0.20F, 0.031F, 0.031F,
            0.20F, 0.031F, 0.031F,
            16F, 96F, true, 0.10F, false, true, 8.0);

    public DimensionEnvironment {
        if (fogStart < 0F || fogEnd <= fogStart) {
            if (forceFog) throw new IllegalArgumentException("Dimensionsnebel braucht eine positive Spanne");
        }
        if (ambientLight < 0F || ambientLight > 1F) {
            throw new IllegalArgumentException("Umgebungslicht muss zwischen 0 und 1 liegen");
        }
        if (!(coordinateScale > 0.0)) throw new IllegalArgumentException("Koordinatenskala muss positiv sein");
    }
}
