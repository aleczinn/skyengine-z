package de.skyengine.graphics.camera;

import de.skyengine.game.entity.EntityPlayer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class Camera {

    /* Camera position uses doubles - at large world coordinates floats jitter */
    private final Vector3d position = new Vector3d();
    private float yaw, pitch;

    private float fov = 75.0F;
    /* Deckel für den waagerechten Blickwinkel (Grad); 180 = aus. S. fovYFor. */
    private float maxFovX = 180.0F;
    private float nearPlane = 0.05F;
    private float farPlane = 1500.0F;

    private boolean inverseDepth = false;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    /* View-Vorsatz (Bobbing/Hurt-Tilt): wirkt NUR auf die View-Matrix — Position, Raycast,
       Audio-Listener und getDirection bleiben unberührt. Identität = kein Effekt. */
    private final Matrix4f viewEffect = new Matrix4f();
    private final Matrix4f projectionView = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();

    /* --- TAA-Zustand (s. AntiAliasingPass) --- */
    /* Subpixel-Jitter als NDC-Offset (Post-NDC-Shift auf die Projektion); 0 = kein Jitter. */
    private float jitterX, jitterY;
    /* PV des VORHERIGEN Frames, UNGEJITTERT — Reprojektionsziel der History. */
    private final Matrix4f prevProjectionView = new Matrix4f();
    /* PV des aktuellen Frames ohne Jitter (wird im nächsten update() zu prev). */
    private final Matrix4f unjitteredProjectionView = new Matrix4f();
    /* Inverse der aktuellen UNGEJITTERTEN PV — Reprojektion rechnet komplett jitterfrei:
       im Stillstand ist prevUv == v_uv exakt (velPx == 0), sonst kräuselt Sub-Pixel-Geometrie
       in der Ferne (Residual-Velocity ≈ Jitter drückte das History-Gewicht). Der Halbpixel-
       Fehler gegen das gejitterte Depth ist subpixelig und gewollt (Standard-TAA-Praxis). */
    private final Matrix4f invProjectionView = new Matrix4f();
    private final Vector3d prevPosition = new Vector3d();
    /* camNow − camPrev (double-Differenz, dann float): P_relPrev = P_relNow + camDelta. */
    private final Vector3f camDelta = new Vector3f();
    private boolean prevValid = false;
    private final Matrix4f jitterScratch = new Matrix4f();

    /**
     * Call once per frame before rendering. Interpolates between the player's last and current tick position.
     */
    public void follow(EntityPlayer player, float partialTick) {
        this.position.set(
                player.lastX + (player.x - player.lastX) * partialTick,
                player.lastY + (player.y - player.lastY) * partialTick + player.getEyeHeight(partialTick),
                player.lastZ + (player.z - player.lastZ) * partialTick
        );
        this.yaw = player.yaw;
        this.pitch = player.pitch;
    }

    /**
     * Recompute matrices. Call after follow() and after resize().
     */
    public void update(double aspectRatio) {
        /* TAA: Vorframe-Zustand sichern, BEVOR die Matrizen überschrieben werden.
           (follow() hat position bereits aktualisiert; prevPosition stammt vom Ende
           des letzten update() — die Reihenfolge follow -> update ist tragend.) */
        if (this.prevValid) {
            this.prevProjectionView.set(this.unjitteredProjectionView);
            this.camDelta.set(
                    (float) (this.position.x - this.prevPosition.x),
                    (float) (this.position.y - this.prevPosition.y),
                    (float) (this.position.z - this.prevPosition.z));
        } else {
            this.camDelta.set(0F);
        }

        float fovY = fovYFor(this.fov, aspectRatio, this.maxFovX);
        if (this.inverseDepth) {
            /* Reversed-Z: far→0, near→1, Depth-Range [0,1] */
            this.projection.setPerspective(
                    (float) Math.toRadians(fovY),
                    (float) aspectRatio,
                    this.farPlane, this.nearPlane,   // bewusst getauscht!
                    true                              // zZeroToOne
            );
        } else {
            this.projection.setPerspective(
                    (float) Math.toRadians(fovY),
                    (float) aspectRatio,
                    this.nearPlane,
                    this.farPlane
            );
        }

        /* View matrix WITHOUT translation - chunks are rendered relative to the camera
           (camera-relative rendering avoids float precision issues far from origin).
           Der viewEffect-Vorsatz (Bobbing/Hurt) sitzt links und steckt damit auch in der
           ungejitterten PV — TAA-Reprojektion behandelt ihn wie normale Kamerabewegung. */
        this.view.set(this.viewEffect)
                .rotateX((float) Math.toRadians(this.pitch))
                .rotateY((float) Math.toRadians(this.yaw));

        this.projection.mul(this.view, this.unjitteredProjectionView);
        if (!this.prevValid) this.prevProjectionView.set(this.unjitteredProjectionView);

        /* Subpixel-Jitter (nur TAA): Post-NDC-Shift T(jx,jy,0) LINKS auf die PV —
           clip.xy += jitter*w, exakt und unabhängig von der Depth-Konvention. Frustum
           nutzt bewusst die gejitterte Matrix (Halbpixel-Versatz ist irrelevant). */
        if (this.jitterX != 0F || this.jitterY != 0F) {
            this.jitterScratch.translation(this.jitterX, this.jitterY, 0F)
                    .mul(this.unjitteredProjectionView, this.projectionView);
        } else {
            this.projectionView.set(this.unjitteredProjectionView);
        }
        this.unjitteredProjectionView.invert(this.invProjectionView);
        this.frustum.set(this.projectionView, false);

        this.prevPosition.set(this.position);
        this.prevValid = true;
    }

    /** NDC-Subpixel-Offset des Frames (TAA); (0,0) = kein Jitter. Vor update() setzen. */
    public void setJitter(float ndcX, float ndcY) {
        this.jitterX = ndcX;
        this.jitterY = ndcY;
    }

    /** Inverse der aktuellen UNGEJITTERTEN PV — rekonstruiert Depth jitterfrei zu kamerarelativen Positionen. */
    public Matrix4f getInvProjectionViewMatrix() {
        return invProjectionView;
    }

    /** UNGEJITTERTE PV des vorherigen Frames (Reprojektionsziel der TAA-History). */
    public Matrix4f getPrevProjectionViewMatrix() {
        return prevProjectionView;
    }

    /** camNow − camPrev (float aus double-Differenz): P_relPrev = P_relNow + camDelta. */
    public Vector3f getCamDelta() {
        return camDelta;
    }

    /**
     * Blickrichtung als normalisierter Vektor, konsistent zur View-Matrix.
     */
    public Vector3d getDirection(Vector3d dest) {
        double yawRad = Math.toRadians(this.yaw);
        double pitchRad = Math.toRadians(this.pitch);
        double cosPitch = Math.cos(pitchRad);

        return dest.set(
                cosPitch * Math.sin(yawRad),
                -Math.sin(pitchRad),
                -cosPitch * Math.cos(yawRad)
        );
    }

    public Vector3d getPosition() {
        return position;
    }

    public Matrix4f getProjectionViewMatrix() {
        return projectionView;
    }

    public FrustumIntersection getFrustum() {
        return frustum;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    /** Bob-/Hurt-Effektmatrix des Frames (kopiert); Identität = kein Effekt. Vor update() setzen. */
    public void setViewEffect(Matrix4f effect) {
        this.viewEffect.set(effect);
    }

    /** Blickwinkel direkt setzen (Third-Person-Front dreht die Kamera zum Spieler um). */
    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public void setFov(float fov) {
        this.fov = fov;
    }

    /** Deckel für den waagerechten Blickwinkel in Grad, 180 = aus — s. {@link #fovYFor}. */
    public void setMaxFovX(float maxFovXDeg) {
        this.maxFovX = maxFovXDeg;
    }

    /**
     * Senkrechter FOV für {@code setPerspective} (JOMLs {@code fovy}) unter dem Ultrawide-Deckel.
     *
     * <p>{@code fovDeg} ist und bleibt der senkrechte Blickwinkel (MC-Bedeutung des Reglers). Nur
     * wenn der daraus folgende waagerechte Blickwinkel {@code maxFovXDeg} überschreiten würde,
     * wird er so weit abgesenkt, dass der waagerechte genau dort liegt — auf 32:9 also z.B.
     * 90° → 73° für einen Deckel von 140° statt der sonst 150°.
     *
     * <p>Das {@code min(...)} ist tragend: der Deckel darf den FOV nur senken, nie anheben. Sonst
     * bliese ein schmales Fenster (4:3) den senkrechten FOV auf — dieselbe Verzerrung, nur um 90°
     * gedreht. Aus demselben Grund ist 180 = aus (ein rektilinearer FOV erreicht 180° nie).
     */
    public static float fovYFor(float fovDeg, double aspect, float maxFovXDeg) {
        if (maxFovXDeg >= 180F) return fovDeg;
        double tanHalfY = Math.tan(Math.toRadians(maxFovXDeg) * 0.5) / aspect;
        return Math.min(fovDeg, (float) Math.toDegrees(2.0 * Math.atan(tanHalfY)));
    }

    /** Sichtweite der Projektion (in Blöcken) — mit LOD hinter den äußersten Ring gelegt. */
    public void setFarPlane(float farPlane) {
        this.farPlane = farPlane;
    }

    public void setInverseDepth(boolean value) {
        this.inverseDepth = value;
    }
}