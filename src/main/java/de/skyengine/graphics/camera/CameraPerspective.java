package de.skyengine.graphics.camera;

/** Kamera-Perspektive wie Minecraft (F5-Zyklus): Ego → hinter dem Spieler → vor dem Spieler. */
public enum CameraPerspective {
    FIRST_PERSON,
    THIRD_PERSON_BACK,
    THIRD_PERSON_FRONT;

    public CameraPerspective next() {
        CameraPerspective[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public boolean isFirstPerson() {
        return this == FIRST_PERSON;
    }
}
