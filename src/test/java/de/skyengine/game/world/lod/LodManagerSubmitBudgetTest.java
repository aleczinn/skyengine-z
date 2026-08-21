package de.skyengine.game.world.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sichert die Aufteilung des Submit-Budgets in {@code LodManager.submitPass}.
 *
 * <p>Hintergrund: dringende Clip-/Handoff-Jobs gehen an der Admission des ChunkManagers vorbei
 * ({@code submitLodTask(..., clip=true, ...)} reiht direkt ein) und duerfen deshalb nicht am
 * Budget der normalen LOD-Jobs haengen. Genau das war einmal der Fall — nicht in
 * {@code submitLodTask} selbst, sondern eine Ebene darueber: {@code submitPass} rief die Methode
 * bei erschoepftem Normalbudget gar nicht erst auf. Diese Auswahl-Entscheidung wird hier geprueft.
 */
final class LodManagerSubmitBudgetTest {

    /* Gesaettigte Worker-Queue (normales Budget 0) darf dringende Arbeit nicht blockieren. */
    @Test
    void urgentSubmitsEvenWhenNormalBudgetIsExhausted() {
        assertEquals(5, LodManager.urgentSubmitCount(5));
        assertEquals(0, LodManager.normalSubmitCount(5, 40, 0));
    }

    /* Bleibt ein dringender Ueberhang liegen, belegen normale Jobs in DIESEM Tick keine Worker. */
    @Test
    void urgentOverhangSuppressesNormalWorkInTheSameTick() {
        assertEquals(32, LodManager.urgentSubmitCount(33));
        assertEquals(0, LodManager.normalSubmitCount(33, 40, 64));
    }

    /* Grenzfall gegen Off-by-one: 32 sind noch KEIN Ueberhang, erst 33. */
    @Test
    void exactlyFullUrgentBudgetStillAllowsNormalWork() {
        assertEquals(32, LodManager.urgentSubmitCount(32));
        assertEquals(40, LodManager.normalSubmitCount(32, 40, 64));
    }

    /* Ohne Ueberhang zaehlt fuer die normalen Jobs das Budget des ChunkManagers. */
    @Test
    void normalWorkUsesTheRemainingChunkManagerBudget() {
        assertEquals(3, LodManager.urgentSubmitCount(3));
        assertEquals(17, LodManager.normalSubmitCount(3, 40, 17));
        assertEquals(12, LodManager.normalSubmitCount(3, 12, 17)); // weniger Kandidaten als Budget
        assertEquals(0, LodManager.normalSubmitCount(0, 40, -1));  // negatives Budget = nichts
    }
}
