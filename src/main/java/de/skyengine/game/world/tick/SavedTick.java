package de.skyengine.game.world.tick;

/**
 * Ein persistierter Scheduled-Tick: Typ-Kennung (s. {@link ScheduledTickTypes}) + absolute
 * Weltposition + Rest-Delay in Ticks. Absolute Koordinaten mit Absicht — ein Tick ist eine
 * Welt-Aktion; das erspart lokale Umrechnungen und trägt spätere chunk-übergreifende
 * Systeme (Redstone, Maschinen, Entity-Ticks).
 *
 * <p>Bewusst NUR der verbleibende Delay, nie eine absolute Weltzeit: Savegames und
 * Simulations-Ticks sollen unabhängig von der aktuellen Weltzeit reproduzierbar sein
 * (gilt auch, falls gameTime später selbst persistiert wird).
 */
public record SavedTick(String type, int x, int y, int z, int remainingTicks) {}
