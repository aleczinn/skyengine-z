package de.skyengine.graphics.player;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.GameContainer;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.PlayerAnimationState;
import de.skyengine.game.world.item.Item;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * First-Person-Hand: rechter Skin-Arm (leere Hand) bzw. gehaltenes Item, gezeichnet am Ende
 * von renderWorld ins HDR-Szene-Target (läuft durch die Post-Kette wie die Welt). Eigene
 * Projektion mit fixem FOV {@value #HAND_FOV} (wie MC, unabhängig vom Kamera-FOV-Setting;
 * Reversed-Z: near/far getauscht wie Camera) + eigener Depth-Clear — die Hand liegt immer
 * über der Welt. Die übergebene View-Matrix ist die Bob-/Hurt-Effektmatrix, so wackelt die
 * Hand mit der Kamera mit.
 *
 * <p>Transformketten VERBATIM Vanilla-{@code ItemInHandRenderer} (renderPlayerArm bzw.
 * renderArmWithItem + applyItemArm*Transform, side = rechts) — Werte nicht „vereinfachen", jede
 * Abweichung war in Runde 1 ein sichtbarer Fehler. Der equippedProgress (Hand fährt bei jeder
 * Stapel-Änderung nach unten aus dem Bild) kommt aus {@link PlayerAnimationState}; gezeichnet wird
 * dessen gemerktes Item, damit beim Wechsel erst das alte herunter- und dann das neue hochfährt.
 */
public final class FirstPersonHandRenderer {

    private static final float HAND_FOV = 70F;

    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f pv = new Matrix4f();
    private final Matrix4f model = new Matrix4f();

    public void render(PlayerRenderer playerRenderer, HeldItemMeshes items, EntityPlayer player,
                       PlayerAnimationState anim, float aspect, float partialTick, Matrix4f viewEffect) {
        if (SkyEngine.get().getWindow().getProperties().isUseInverseDepth()) {
            this.proj.setPerspective((float) Math.toRadians(HAND_FOV), aspect, 20F, 0.05F, true);
        } else {
            this.proj.setPerspective((float) Math.toRadians(HAND_FOV), aspect, 0.05F, 20F);
        }
        this.proj.mul(viewEffect, this.pv);

        /* Kamera-Nachlauf (Vanilla submitHandsWithItems): die ganze Hand dreht um 10 % dessen,
           was der nachziehende Blickwinkel gegenüber dem echten hinterherhinkt — nach Bob/Hurt
           und vor den Arm-/Item-Transforms. */
        float lagX = PlayerAnimationState.wrapDegrees(player.pitch - anim.getXBob(partialTick)) * 0.1F;
        float lagY = PlayerAnimationState.wrapDegrees(player.yaw - anim.getYBob(partialTick)) * 0.1F;
        this.pv.rotateX((float) Math.toRadians(lagX)).rotateY((float) Math.toRadians(lagY));

        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT); // Hand nie von Welt-Geometrie verdeckt

        float sp = anim.getSwingProgress(partialTick);
        float sqrtSp = (float) Math.sqrt(sp);
        /* Vanilla: equippedProgress schiebt Arm/Item um bis zu 0.6 nach unten aus dem Bild. */
        float equipDrop = anim.getEquippedProgress(partialTick) * -0.6F;
        Item handItem = anim.getHandItem();

        if (handItem == null) {
            /* Vanilla renderPlayerArm (side=1): Arm ragt von rechts unten in den Blick. */
            float f2 = -0.3F * (float) Math.sin(sqrtSp * Math.PI);
            float f3 = 0.4F * (float) Math.sin(sqrtSp * 2 * Math.PI);
            float f4 = -0.4F * (float) Math.sin(sp * Math.PI);
            float f5 = (float) Math.sin(sp * sp * Math.PI);
            float f6 = (float) Math.sin(sqrtSp * Math.PI);
            this.model.translation(f2 + 0.64000005F, f3 - 0.6F + equipDrop, f4 - 0.71999997F)
                    .rotateY((float) Math.toRadians(45F))
                    .rotateY((float) Math.toRadians(f6 * 70F))
                    .rotateZ((float) Math.toRadians(f5 * -20F))
                    .translate(-1F, 3.6F, 3.5F)
                    .rotateZ((float) Math.toRadians(120F))
                    .rotateX((float) Math.toRadians(200F))
                    .rotateY((float) Math.toRadians(-135F))
                    .translate(5.6F, 0F, 0F)
                    .scale(1F / 16F)
                    .translate(-5F, 2F, 0F); // Arm-Pivot (Vanilla ModelPart rightArm)
            playerRenderer.renderFirstPersonArm(this.pv, this.model);
            return;
        }

        if (anim.isEating()) {
            /* Vanilla-Reihenfolge: ERST applyEatTransform, DANN applyItemArmTransform —
               die Eat-Rotationen drehen den Arm-Versatz mit, dadurch schwenkt das Item
               zur Bildmitte ans Gesicht (f1 1 -> 0 bis zum Schlucken) und nickt im
               Kau-Takt. Umgekehrt bleibt es riesig/flach rechts unten liegen. */
            this.model.identity();
            float f = anim.getEatTime(partialTick);
            float f1 = f / GameContainer.EAT_TICKS;
            if (f1 < 0.8F) {
                this.model.translate(0F, (float) Math.abs(Math.cos(f / 4.0 * Math.PI) * 0.1), 0F);
            }
            float f3 = 1F - (float) Math.pow(f1, 27.0);
            this.model.translate(f3 * 0.6F, f3 * -0.5F, 0F)
                    .rotateY((float) Math.toRadians(f3 * 90F))
                    .rotateX((float) Math.toRadians(f3 * 10F))
                    .rotateZ((float) Math.toRadians(f3 * 30F))
                    .translate(0.56F, -0.52F + equipDrop, -0.72F);
        } else {
            /* Vanilla renderArmWithItem: Swing-Versatz + Arm-Transform + Attack-Rotationen. */
            float f5 = (float) Math.sin(sp * sp * Math.PI);
            float f6 = (float) Math.sin(sqrtSp * Math.PI);
            float fx = -0.4F * f6;
            float fy = 0.2F * (float) Math.sin(sqrtSp * 2 * Math.PI);
            float fz = -0.2F * (float) Math.sin(sp * Math.PI);
            this.model.translation(fx + 0.56F, fy - 0.52F + equipDrop, fz - 0.72F)
                    .rotateY((float) Math.toRadians(45F + f5 * -20F))
                    .rotateZ((float) Math.toRadians(f6 * -20F))
                    .rotateX((float) Math.toRadians(f6 * -80F))
                    .rotateY((float) Math.toRadians(-45F));
        }
        items.bind(this.pv);
        items.drawFirstPerson(handItem, this.model);
        items.unbind();
    }
}
