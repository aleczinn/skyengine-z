package de.skyengine.graphics.player;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.core.io.IDisposable;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.PlayerAnimationState;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.GlState;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.Texture;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Zeichnet das Spielermodell mit Classic-Skin (64×64): Inventar-Vorschau (Ortho im GUI-Pass,
 * folgt der Maus wie MC), Third-Person in der Welt und den First-Person-Arm. Skin:
 * {@code skin.png} im Spielordner ({@code %APPDATA%\.skyengine}) überschreibt den
 * Default-Steve aus den Resources.
 *
 * <p>Pose-Berechnung VERBATIM Vanilla-HumanoidModel (y-down-Winkel, siehe {@link PlayerModel});
 * Welt-Ausrichtung: {@code rotateY(PI − toRadians(yaw))} + {@link PlayerModel#applyModelSpace}.
 * Shader flach ohne Richtungs-Shading (wie {@code EnchantingTableRenderer}).
 */
public final class PlayerRenderer implements IDisposable {

    private final Logger logger = LogManager.getLogger(PlayerRenderer.class.getName());

    private ShaderProgram shader;
    private Texture skin;
    private final PlayerModel model = new PlayerModel();
    private final PlayerModel.Pose pose = new PlayerModel.Pose();

    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f base = new Matrix4f();
    private final Matrix4f itemMatrix = new Matrix4f();

    /* Skin-Format (slim = 3px-Arme), automatisch aus dem PNG erkannt (loadSkin). */
    private boolean slim;

    /** Initialisiert Shader, Skin (setzt das slim-Flag!) und Meshes. Nur auf dem Render-Thread. */
    public void init() {
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        this.skin = this.loadSkin();   // VOR model.init — bestimmt das Arm-Layout
        this.model.init(this.slim);
    }

    /** Eigener Skin aus dem Spielordner, sonst Default-Steve; akzeptiert 64×64 und Legacy 64×32. */
    private Texture loadSkin() {
        File custom = GameDirectory.resolve("skin.png");
        if (custom.isFile()) {
            try {
                SkinTextureData data = SkinTextureData.load(new FileHandle(custom));
                this.slim = data.isSlim();
                this.logger.info("Eigener Skin geladen (" + skinFormat(data) + "): " + custom.getAbsolutePath());
                return uploadSkin(data);
            } catch (RuntimeException e) {
                this.logger.warning("skin.png konnte nicht geladen werden (" + e.getMessage()
                        + ") — nutze Default-Skin.");
            }
        }
        SkinTextureData fallback = SkinTextureData.load(
                new FileHandle("game/textures/entity/player/steve.png", FileType.RESOURCE));
        this.slim = fallback.isSlim();
        return uploadSkin(fallback);
    }

    private static String skinFormat(SkinTextureData data) {
        return data.isLegacy() ? "legacy 64x32, classic" : (data.isSlim() ? "64x64, slim" : "64x64, classic");
    }

    private static Texture uploadSkin(SkinTextureData data) {
        ByteBuffer pixels = MemoryUtil.memAlloc(data.rgba().length);
        try {
            pixels.put(data.rgba()).flip();
            return new Texture(SkinTextureData.WIDTH, SkinTextureData.HEIGHT, pixels, false);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /**
     * Spieler-Vorschau im GUI-Pass (Inventar): Modell steht bei ({@code centerX}, {@code feetY})
     * in virtuellen GUI-Koordinaten und schaut zur Maus. Formeln VERBATIM MCs
     * {@code renderEntityInInventoryFollowsMouse}. {@code scale} = virtuelle Pixel pro Block
     * (Modell wird 1.875·scale hoch).
     *
     * <p>Erwartet den Zustand nach {@code SpriteRenderer.end()} (Depth an, Blend aus) und lässt
     * ihn so zurück; eigener Depth-Clear, damit das Modell über dem GUI-Hintergrund liegt.
     */
    public void renderPreview(float centerX, float feetY, float scale,
                              double mouseX, double mouseY, float vW, float vH,
                              HeldItemMeshes items, ItemStack held) {
        /* Reversed-Z-Ortho wie ItemIconRenderer.begin (nah -> 1, fern -> 0). */
        if (SkyEngine.get().getWindow().getProperties().isUseInverseDepth()) {
            this.proj.identity().ortho(0, vW, 0, vH, 2000, -2000, true);
        } else {
            this.proj.identity().ortho(0, vW, 0, vH, -2000, 2000, true);
        }

        /* MC-Maus-Folge: Körper dreht 20°, Kopf 40°, Pitch 20° — atan-gedämpft. */
        float eyeY = feetY - scale * 1.62f;
        float f = (float) Math.atan((centerX - mouseX) / 40f);
        float f1 = (float) Math.atan((eyeY - mouseY) / 40f);
        float bodyYaw = 180f + f * 20f;

        this.pose.reset();
        this.pose.armY = this.model.getArmPivotY();
        this.pose.headYRot = (float) Math.toRadians(f * 20f);   // netHeadYaw = 40° − 20°
        this.pose.headXRot = (float) Math.toRadians(-f1 * 20f);
        /* Idle-Haltung wie MC: Arme leicht nach vorn und außen angewinkelt (getunt). */
        this.pose.rightArmXRot = -0.06F;
        this.pose.leftArmXRot = -0.06F;
        this.pose.rightArmZRot = 0.05F;
        this.pose.leftArmZRot = -0.05F;

        /* Ortho ist y-up, GUI y-down -> Fußpunkt spiegeln; yaw 180 = Front zum Betrachter. */
        PlayerModel.applyModelSpace(this.base.translation(centerX, vH - feetY, 0)
                .scale(scale)
                .rotateY((float) Math.PI - (float) Math.toRadians(bodyYaw)));

        boolean cull = GlState.isCullFaceEnabled();
        GlState.disableCullFace();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", this.proj);
        this.shader.setUniformi("u_Texture", 0);
        /* GUI: NIE abdunkeln. Der Shader ist derselbe wie im Welt-Pass, deshalb muss die
           Vorschau ihn explizit auf 1.0 zurücksetzen — sonst erbt sie das Licht des Frames
           davor und die Inventar-Figur wird in einer Höhle schwarz. */
        this.shader.setUniformf("u_Light", 1.0f);
        this.skin.bind(0);
        this.model.render(this.shader, this.base, this.pose);
        this.shader.unbind();
        this.drawHeldItem(items, held, this.proj, 1.0f);

        if (cull) GlState.enableCullFace();
    }

    /**
     * Third-Person-Spieler im Welt-Pass (zwischen Entities und Translucent): kamerarelativ,
     * erbt den globalen Depth-State (Reversed-Z — nichts umschalten, wie EntityRenderer).
     */
    public void renderThirdPerson(EntityPlayer player, PlayerAnimationState anim,
                                  Camera camera, float partialTick,
                                  HeldItemMeshes items, ItemStack held, float light) {
        Vector3d cam = camera.getPosition();
        float ox = (float) (player.lastX + (player.x - player.lastX) * partialTick - cam.x);
        float oy = (float) (player.lastY + (player.y - player.lastY) * partialTick - cam.y);
        float oz = (float) (player.lastZ + (player.z - player.lastZ) * partialTick - cam.z);

        float bodyYaw = anim.getBodyYaw(partialTick);
        this.computePose(player, anim, partialTick, bodyYaw);
        PlayerModel.applyModelSpace(this.base.translation(ox, oy, oz)
                .rotateY((float) Math.PI - (float) Math.toRadians(bodyYaw)));

        boolean cull = GlState.isCullFaceEnabled();
        GlState.disableCullFace();
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", camera.getProjectionViewMatrix());
        this.shader.setUniformi("u_Texture", 0);
        this.shader.setUniformf("u_Light", light);
        this.skin.bind(0);
        this.model.render(this.shader, this.base, this.pose);
        this.shader.unbind();
        this.drawHeldItem(items, held, camera.getProjectionViewMatrix(), light);
        if (cull) GlState.enableCullFace();
    }

    /** First-Person-Arm: bindet Skin-Shader + Skin und zeichnet nur den rechten Arm mit Ärmel. */
    public void renderFirstPersonArm(Matrix4f projectionView, Matrix4f armMatrix, float light) {
        boolean cull = GlState.isCullFaceEnabled();
        GlState.disableCullFace();
        this.shader.bind();
        this.shader.setUniformMatrix4f("u_ProjectionView", projectionView);
        this.shader.setUniformi("u_Texture", 0);
        this.shader.setUniformf("u_Light", light);
        this.skin.bind(0);
        this.model.renderRightArm(this.shader, armMatrix);
        this.shader.unbind();
        if (cull) GlState.enableCullFace();
    }

    /**
     * Vanilla-HumanoidModel-Pose (setupAnim/setupAttackAnimation/Crouch) VERBATIM — die
     * y-down-Konvention steckt komplett im Modell-Raum, hier keine Vorzeichen anpassen.
     */
    private void computePose(EntityPlayer player, PlayerAnimationState anim, float partialTick, float bodyYaw) {
        this.pose.reset();
        this.pose.armY = this.model.getArmPivotY();

        float amount = anim.getLimbSwingAmount(partialTick);
        float swing = anim.getLimbSwing(partialTick);
        this.pose.rightArmXRot = (float) (Math.cos(swing * 0.6662F + Math.PI) * 2.0 * amount * 0.5);
        this.pose.leftArmXRot = (float) (Math.cos(swing * 0.6662F) * 2.0 * amount * 0.5);
        this.pose.rightLegXRot = (float) (Math.cos(swing * 0.6662F) * 1.4 * amount);
        this.pose.leftLegXRot = (float) (Math.cos(swing * 0.6662F + Math.PI) * 1.4 * amount);

        this.pose.headYRot = (float) Math.toRadians(PlayerAnimationState.wrapDegrees(player.yaw - bodyYaw));
        this.pose.headXRot = (float) Math.toRadians(player.pitch);

        /* Essen: rechter Arm fährt weich vor den Mund (geglätteter Blend) — flacher Winkel
           (Hand auf Mund- statt Augenhöhe) + stärker zur Gesichtsmitte, dazu ein kleines
           Kau-Wippen mit derselben Zeitbasis wie die First-Person-Hand; kein Attack-Schwung. */
        float eatWeight = anim.getEatWeight(partialTick);
        if (eatWeight > 0F) {
            float chew = (float) (Math.cos(anim.getEatTime(partialTick) / 4.0 * Math.PI) * 0.1);
            this.pose.rightArmXRot = this.pose.rightArmXRot * (1F - eatWeight) + (-1.45F + chew) * eatWeight;
            this.pose.rightArmYRot = -0.6F * eatWeight;
        }

        /* Attack-Schwung (setupAttackAnimation, rechter Arm): Körper dreht mit, Arm-Pivots
           wandern auf dem Schulterkreis, Arm hackt nach vorn-unten. */
        float sp = anim.getSwingProgress(partialTick);
        if (eatWeight == 0F && sp > 0F) {
            this.pose.bodyYRot = (float) (Math.sin(Math.sqrt(sp) * 2 * Math.PI) * 0.2);
            this.pose.rightArmZ = (float) Math.sin(this.pose.bodyYRot) * 5F;
            this.pose.rightArmX = (float) -Math.cos(this.pose.bodyYRot) * 5F;
            this.pose.leftArmZ = (float) -Math.sin(this.pose.bodyYRot) * 5F;
            this.pose.leftArmX = (float) Math.cos(this.pose.bodyYRot) * 5F;
            this.pose.rightArmYRot += this.pose.bodyYRot;
            this.pose.leftArmYRot += this.pose.bodyYRot;
            this.pose.leftArmXRot += this.pose.bodyYRot;

            float f = 1F - sp;
            f = f * f;
            f = f * f;
            f = 1F - f;
            float f1 = (float) Math.sin(f * Math.PI);
            float f2 = (float) (Math.sin(sp * Math.PI) * -(this.pose.headXRot - 0.7F) * 0.75F);
            this.pose.rightArmXRot -= f1 * 1.2F + f2;
            this.pose.rightArmYRot += this.pose.bodyYRot * 2F;
            this.pose.rightArmZRot += (float) (Math.sin(sp * Math.PI) * -0.4);
        }

        if (player.isPassenger()) {
            this.applyRidingPose();
        } else if (player.isSneaking()) {
            this.applyCrouchPose();
        }
    }

    /** Vanilla-Humanoid-Sitzpose: angewinkelte Beine und leicht angehobene Arme. */
    private void applyRidingPose() {
        this.pose.rightArmXRot += -(float) Math.PI / 5F;
        this.pose.leftArmXRot += -(float) Math.PI / 5F;
        this.pose.rightLegXRot = -1.4137167F;
        this.pose.leftLegXRot = -1.4137167F;
        this.pose.rightLegYRot = (float) Math.PI / 10F;
        this.pose.leftLegYRot = -(float) Math.PI / 10F;
        this.pose.rightLegZRot = 0.07853982F;
        this.pose.leftLegZRot = -0.07853982F;
    }

    /** Crouch-Pose (Vanilla-Werte verbatim; Arm-Pivot slim-abhängig). */
    private void applyCrouchPose() {
        this.pose.bodyXRot = 0.5F;
        this.pose.bodyY = 3.2F;
        this.pose.headY = 4.2F;
        this.pose.armY = this.model.getArmPivotY() + 3.2F;
        this.pose.rightArmXRot += 0.4F;
        this.pose.leftArmXRot += 0.4F;
        this.pose.legY = 12.2F;
        this.pose.legZ = 4F;
    }

    /**
     * Item am rechten Arm (Third-Person + Vorschau), Vanilla-ItemInHandLayer-Anker:
     * Arm-Part-Matrix → Handgelenk → rotateX(−90°) → rotateY(180°) → Versatz — der
     * Display-Transform selbst steckt in {@link HeldItemMeshes#drawThirdPerson}.
     */
    private void drawHeldItem(HeldItemMeshes items, ItemStack held, Matrix4f projectionView, float light) {
        if (items == null || held == null || held.isEmpty()) return;
        /* Vanilla-Kette: das translate(1, 2, -10) px = (1/16, 0.125, -0.625) Blöcke wandert
           durch das rotateX(-90) effektiv ans Armende — KEIN zusätzlicher Handgelenk-Versatz.
           Slim: Anker 0.5px weiter innen (Vanilla PlayerModel.translateToHand). */
        float slimShift = this.model.isSlim() ? -0.5F : 0F;
        this.pose.rightArmX += slimShift;
        Matrix4f m = this.model.rightArmMatrix(this.base, this.pose, this.itemMatrix)
                .rotateX((float) Math.toRadians(-90))
                .rotateY((float) Math.toRadians(180))
                .translate(1F, 2F, -10F);
        this.pose.rightArmX -= slimShift;
        items.bind(projectionView, light);
        items.drawThirdPerson(held.getItem(), m);
        items.unbind();
    }

    @Override
    public void dispose() {
        this.model.dispose();
        if (this.skin != null) this.skin.dispose();
        if (this.shader != null) this.shader.dispose();
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec2 a_uv;
        uniform mat4 u_ProjectionView;
        uniform mat4 u_Model;
        out vec2 v_uv;
        void main() {
            v_uv = a_uv;
            gl_Position = u_ProjectionView * u_Model * vec4(a_position, 1.0);
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec2 v_uv;
        uniform sampler2D u_Texture;
        /* Himmelslicht der Spielerzelle, fertig durch die Kurve gerechnet
           (ChunkRenderer.lightFactor). 1.0 = voll hell, Fullbright ODER GUI-Vorschau. */
        uniform float u_Light;
        out vec4 fragColor;
        void main() {
            vec4 c = texture(u_Texture, v_uv);
            if (c.a < 0.5) discard;
            fragColor = vec4(c.rgb * u_Light, c.a);
        }
        """;
}
