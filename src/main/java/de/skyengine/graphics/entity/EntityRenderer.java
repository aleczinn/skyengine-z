package de.skyengine.graphics.entity;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.entity.PrimedTntEntity;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.GlState;
import de.skyengine.graphics.ItemSpriteBuilder;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.shader.Shader;
import de.skyengine.graphics.shader.ShaderProgram;
import de.skyengine.graphics.shader.ShaderType;
import de.skyengine.graphics.texture.TextureArray;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.graphics.texture.Texture;
import de.skyengine.graphics.world.ChunkRenderer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Zeichnet die Welt-Entities ({@link FallingBlockEntity}, {@link ItemEntity}) im Welt-Pass nach dem
 * Chunk-Mesh. Wiederverwendet die bereits gebackenen Block-Quads ({@code BlockState.getModel()}) und
 * das Block-{@link TextureArray}; gerendert wird kamerarelativ (Offset = Weltpos − Kamerapos, wie
 * {@code ChestRenderer}), damit es zur kamerarelativen Chunk-Darstellung passt.
 *
 * <p>Erbt den globalen Welt-GL-State (Reversed-Z, Depth-Test, Back-Face-Culling) und schaltet ihn
 * NICHT um; Alpha-Cutout via {@code discard} im Shader.
 */
public final class EntityRenderer {

    private static final int FLOATS_PER_VERTEX = 9;   // pos3 + texCoord3(u,v,layer) + rgb3 (brightness × fester Tint)
    private static final float ITEM_SCALE = 0.25f;
    /** MC {@code item/generated} ground-Scale — flache Sprites liegen doppelt so groß da wie Blöcke. */
    private static final float FLAT_ITEM_SCALE = 0.5f;
    /* Face-Helligkeiten des extrudierten Sprites nach Block-Konvention (oben hell, unten dunkel). */
    private static final float FLAT_FACE_FRONT = 0.8f, FLAT_FACE_SIDE = 0.6f;
    private static final float FLAT_FACE_TOP = 1.0f, FLAT_FACE_BOTTOM = 0.5f;
    /* Stapel-Optik wie MC: Streuung je Kopie, dazu der feste z-Schritt der Sprite-Schichten. */
    private static final float COPY_SPREAD = 0.15f;
    private static final float COPY_LAYER_STEP = 0.09375f;

    /* Boden-Animation, Werte aus MCs ItemEntityRenderer. */
    private static final float BOB_SPEED = 0.1f;       // MC: (age + partialTick) / 10
    private static final float BOB_AMPLITUDE = 0.1f;   // MC: sin(...) * 0.1 + 0.1
    private static final float SPIN_SPEED = 0.05f;     // MC: (age + partialTick) / 20
    /**
     * Höhe der Modellmitte über dem Fußpunkt der Entity, ohne die Wippe. Setzt sich in MC aus dem
     * Entity-Versatz {@code 0.25 · scale} und der {@code ground}-Translation des Item-Modells
     * zusammen — und ergibt für BEIDE Fälle denselben Wert, weil MCs Transforms so gebaut sind:
     * {@code item/block} 3/16 + 0.25·0.25, {@code item/generated} 2/16 + 0.25·0.5. Ohne diesen
     * Versatz steckt das Modell im Boden, weil es um seine Mitte zentriert gezeichnet wird.
     */
    private static final float GROUND_LIFT = 0.25f;
    /** Konservativer Rand fürs Frustum-Culling (deckt Würfel, Item-Wippe, Interpolation ab). */
    private static final float CULL_MARGIN = 1.0f;

    private ShaderProgram shader;
    private TextureArray textures;
    /** Die Vanilla-Minecart-Textur ist 64x32 und passt deshalb nicht in das 16x16-Block-Array. */
    private Texture minecartTexture;

    /** Würfel-Mesh je Block-State-ID; NO_MESH = bekannt-leeres Modell (LongObjMap verbietet
     *  null-Werte, und der Sentinel erspart das frühere containsKey+get-Doppel mit Boxing). */
    private final de.skyengine.utils.collect.LongObjMap<Object> cache =
            new de.skyengine.utils.collect.LongObjMap<>(64);
    private static final Object NO_MESH = new Object();

    /** Extrudierte Sprites der Nicht-Block-Items (Apfel, Werkzeug, Eimer, Material-Items). */
    private final Map<Item, Object> sprites = new HashMap<>();
    /** Rahmenmodell je Anhefterichtung, weil die Engine die Vanilla-Flächenhelligkeit in die
     * Vertexfarben backt und keine Normalen im Entity-Shader auswertet. */
    private final Mesh[] itemFrameMeshes = new Mesh[6];
    private Mesh minecartMesh;

    private final Matrix4f model = new Matrix4f();
    /** Wiederverwendet, vor jeder Kopien-Schleife neu geseedet — die Versätze sind deterministisch. */
    private final Random copyRandom = new Random();

    private int locProjectionView, locWhiteFlash, locLight, locModel, locAlphaCutoff;
    private int locUseEntityTexture;
    /** Alpha-Test-Schwellen wie im ChunkRenderer: harter Cutout bzw. praktisch aus fürs Blending. */
    private static final float CUTOUT_ALPHA = 0.5f;
    private static final float TRANSLUCENT_ALPHA = 0.001f;

    public void init(TextureArray textures) {
        this.textures = textures;
        for (Direction direction : Direction.sharedValues()) {
            this.itemFrameMeshes[direction.faceIndex()] = new Mesh(buildItemFrameMesh(direction));
        }
        this.minecartTexture = new Texture(
                new FileHandle("game/textures/entity/minecart.png", FileType.RESOURCE), false);
        this.minecartMesh = new Mesh(buildMinecartMesh());
        this.shader = new ShaderProgram(
                new Shader(VERTEX, ShaderType.VERTEX),
                new Shader(FRAGMENT, ShaderType.FRAGMENT));
        /* Uniform-Locations cachen — die String-Overloads liefen pro Entity pro Frame über
           die HashMap; u_Textures ist konstant Unit 0. */
        this.locProjectionView = this.shader.getUniformLocation("u_ProjectionView");
        this.locWhiteFlash = this.shader.getUniformLocation("u_WhiteFlash");
        this.locLight = this.shader.getUniformLocation("u_Light");
        this.locModel = this.shader.getUniformLocation("u_Model");
        this.locAlphaCutoff = this.shader.getUniformLocation("u_AlphaCutoff");
        this.locUseEntityTexture = this.shader.getUniformLocation("u_UseEntityTexture");
        this.shader.bind();
        this.shader.setUniformi("u_Textures", 0);
        this.shader.setUniformi("u_NormalTextures", 1);
        this.shader.setUniformi("u_MaterialTextures", 2);
        this.shader.setUniformi("u_EntityTexture", 3);
        this.shader.setUniformi(this.locUseEntityTexture, 0);
        this.shader.unbind();
    }

    /**
     * Zeichnet die Entities der übergebenen Chunks. Der Aufrufer reicht nur die Chunks mit
     * mindestens einer Entity durch (World#chunksWithEntities) — kein Iterieren über ALLE
     * geladenen Chunks pro Frame. Der READY-Guard bleibt: zwischen zwei Ticks kann ein Chunk
     * bereits entladen sein, bevor das Reconcile ihn aus der Menge nimmt.
     */
    public void render(World world, Iterable<Chunk> chunks, Camera camera, float partialTick) {
        this.shader.bind();
        this.shader.setUniformMatrix4f(this.locProjectionView, camera.getProjectionViewMatrix());
        this.shader.setUniformf(this.locWhiteFlash, 0f); // Default: kein Blink (Falling/Item unverändert)
        this.shader.setUniformf(this.locLight, 1.0f);    // Default hell; drawEntity setzt den echten Wert
        this.shader.setUniformf(this.locAlphaCutoff, CUTOUT_ALPHA); // drawMesh senkt ihn für Transluzentes
        this.textures.bind(0);
        BlockTextureAtlas.bindOptionalMaterials(this.shader);

        Vector3d cam = camera.getPosition();
        FrustumIntersection frustum = camera.getFrustum();
        for (Chunk chunk : chunks) {
            if (chunk.status != ChunkStatus.READY) continue;
            List<Entity> entities = chunk.entities();
            for (int i = 0; i < entities.size(); i++) {
                this.drawEntity(world, entities.get(i), chunk, cam, frustum, partialTick);
            }
        }
        this.shader.unbind();
    }

    private void drawEntity(World world, Entity e, Chunk chunk, Vector3d cam,
                            FrustumIntersection frustum, float partialTick) {
        if (e.isRemoved()) return;

        float ox = (float) (e.lastX + (e.x - e.lastX) * partialTick - cam.x);
        float oy = (float) (e.lastY + (e.y - e.lastY) * partialTick - cam.y);
        float oz = (float) (e.lastZ + (e.z - e.lastZ) * partialTick - cam.z);

        /* Frustum-Culling (kamerarelativ wie der ChunkRenderer): konservative Box deckt Würfel (0..1),
           Item-Wippe und Tick-Interpolation ab - lieber zu großzügig als sichtbare Entity verlieren. */
        if (!frustum.testAab(ox - CULL_MARGIN, oy - CULL_MARGIN, oz - CULL_MARGIN,
                ox + CULL_MARGIN, oy + 1f + CULL_MARGIN, oz + CULL_MARGIN)) return;

        /* Licht der eigenen Zelle (Himmel + Block). Der Chunk der Schleife IST der Chunk der
           Entity (Entities hängen an ihrem Chunk, s. World.tickEntities) — kein World-Lookup. */
        int lx = (int) Math.floor(e.x) & ChunkSection.MASK;
        int ly = Math.clamp((int) Math.floor(e.y), 0, Chunk.HEIGHT - 1);
        int lz = (int) Math.floor(e.z) & ChunkSection.MASK;
        this.shader.setUniformf(this.locLight, ChunkRenderer.lightFactor(
                chunk.light.get(lx, ly, lz), chunk.blockLight.get(lx, ly, lz),
                world.getEnvironment().ambientLight()));

        if (e instanceof FallingBlockEntity fb) {
            Mesh mesh = this.meshFor(fb.getBlockId());
            if (mesh == null) return;
            /* Voller Würfel: Modell liegt in 0..1, Entity-x/z sind Zentrum, y der Fußpunkt. */
            this.model.translation(ox - 0.5f, oy, oz - 0.5f);
            this.shader.setUniformMatrix4f(this.locModel, this.model);
            this.drawMesh(mesh, fb.getBlockId());
        } else if (e instanceof PrimedTntEntity tnt) {
            Mesh mesh = this.meshFor(Blocks.TNT);
            if (mesh == null) return;
            /* Voller TNT-Würfel wie FallingBlock, zusätzlich weißer Blink über u_WhiteFlash. */
            this.shader.setUniformf(this.locWhiteFlash, tnt.whiteFlash(partialTick));
            this.model.translation(ox - 0.5f, oy, oz - 0.5f);
            this.shader.setUniformMatrix4f(this.locModel, this.model);
            this.drawMesh(mesh, Blocks.TNT);
            this.shader.setUniformf(this.locWhiteFlash, 0f); // zurücksetzen für folgende Entities
        } else if (e instanceof ItemFrameEntity frame) {
            this.drawItemFrame(frame, ox, oy, oz);
        } else if (e instanceof MinecartEntity minecart) {
            MinecartEntity.RenderPose pose = minecart.renderPose(world, partialTick);
            this.model.translation(ox + (float) pose.offsetX(), oy + (float) pose.offsetY(),
                            oz + (float) pose.offsetZ())
                    /* Das Vanilla-Modell zeigt lokal entlang +X; yaw=0 zeigt in der Engine -Z. */
                    .rotateY((float) Math.toRadians(90 - pose.yaw()))
                    .rotateZ((float) Math.toRadians(pose.pitch()));
            float hurt = Math.max(0, minecart.getHurtTime() - partialTick);
            float damage = Math.max(0, minecart.getDamage() - partialTick);
            if (hurt > 0) {
                this.model.rotateZ((float) Math.toRadians(
                        Math.sin(hurt) * hurt * damage / 10.0 * minecart.getHurtDirection()));
            }
            this.shader.setUniformMatrix4f(this.locModel, this.model);
            this.minecartTexture.bind(3);
            this.shader.setUniformi(this.locUseEntityTexture, 1);
            GlState.disableCullFace();
            this.minecartMesh.render();
            GlState.enableCullFace();
            this.shader.setUniformi(this.locUseEntityTexture, 0);
            this.textures.bind(0);
            BlockTextureAtlas.bindOptionalMaterials(this.shader);
        } else if (e instanceof ItemEntity item) {
            this.drawItem(item, ox, oy, oz, partialTick);
        }
    }

    /** Hanging-Modell plus der enthaltene Stack, in 45-Grad-Schritten um die Flaechennormale. */
    private void drawItemFrame(ItemFrameEntity frame, float ox, float oy, float oz) {
        GlState.disableCullFace();
        itemFramePose(this.model, frame.getDirection(), ox, oy, oz);
        this.model.translate(-0.5f, -0.5f, -0.5f);
        this.shader.setUniformMatrix4f(this.locModel, this.model);
        this.itemFrameMeshes[frame.getDirection().faceIndex()].render();
        GlState.enableCullFace();

        ItemStack stack = frame.getItem();
        if (stack == null || stack.isEmpty()) return;
        int stateId = blockStateId(stack);
        Mesh content = stateId >= 0 ? this.meshFor(stateId) : this.spriteFor(stack.getItem());
        if (content == null) return;

        itemFramePose(this.model, frame.getDirection(), ox, oy, oz);
        this.model.translate(0f, 0f, 0.4375f)
                .rotateZ((float) Math.toRadians(45.0 * frame.getRotation()));
        if (stateId >= 0) {
            /* Vanilla: ItemFrameRenderer 0,5 * block/block FIXED 0,5 = effektiv 0,25. */
            this.model.scale(0.25f);
        } else {
            /* item/generated FIXED dreht das Sprite um Y, skaliert aber nicht zusaetzlich. */
            this.model.rotateY((float) Math.PI).scale(0.5f);
        }
        this.model.translate(-0.5f, -0.5f, -0.5f);
        this.shader.setUniformMatrix4f(this.locModel, this.model);
        if (stateId >= 0) {
            this.drawMesh(content, stateId);
        } else {
            GlState.disableCullFace();
            content.render();
            GlState.enableCullFace();
        }
    }

    /** Exakte Pose aus Vanilla 26.2 ItemFrameRenderer.submit. */
    private static void itemFramePose(Matrix4f matrix, Direction direction,
                                      float x, float y, float z) {
        matrix.identity().translate(x, y, z).translate(
                direction.offsetX() * 0.46875f,
                direction.offsetY() * 0.46875f,
                direction.offsetZ() * 0.46875f);
        switch (direction) {
            case SOUTH -> matrix.rotateY((float) Math.PI);
            case NORTH -> { }
            case EAST -> matrix.rotateY((float) (-Math.PI * 0.5));
            case WEST -> matrix.rotateY((float) (Math.PI * 0.5));
            case UP -> matrix.rotateX((float) (-Math.PI * 0.5)).rotateY((float) Math.PI);
            case DOWN -> matrix.rotateX((float) (Math.PI * 0.5)).rotateY((float) Math.PI);
        }
    }

    /** Vanillas template_item_frame inklusive seiner Face-spezifischen Pixel-UVs. */
    private static float[] buildItemFrameMesh(Direction direction) {
        float[] data = new float[22 * 6 * FLOATS_PER_VERTEX];
        int[] cursor = {0};
        float[] shade = itemFrameFaceBrightness(direction);
        float back = BlockTextures.layerOf("game/textures/block/item_frame.png");
        float wood = BlockTextures.layerOf("game/textures/block/birch_planks.png");
        float p = 1/16f;

        north(data,cursor,shade[2],3*p,3*p,15.5f*p,13*p,13*p,back,3,3,13,13);
        south(data,cursor,shade[3],3*p,3*p,16*p,13*p,13*p,back,3,3,13,13);

        boxAll(data,cursor,shade,2*p,2*p,15*p,14*p,3*p,16*p,wood,
                2,0,14,1, 2,15,14,16, 2,13,14,14, 2,13,14,14,
                15,13,16,14, 0,13,1,14);
        boxAll(data,cursor,shade,2*p,13*p,15*p,14*p,14*p,16*p,wood,
                2,0,14,1, 2,15,14,16, 2,2,14,3, 2,2,14,3,
                15,2,16,3, 0,2,1,3);

        north(data,cursor,shade[2],2*p,3*p,15*p,3*p,13*p,wood,13,3,14,13);
        south(data,cursor,shade[3],2*p,3*p,16*p,3*p,13*p,wood,2,3,3,13);
        west(data,cursor,shade[4],2*p,3*p,15*p,13*p,16*p,wood,15,3,16,13);
        east(data,cursor,shade[5],3*p,3*p,15*p,13*p,16*p,wood,0,3,1,13);

        north(data,cursor,shade[2],13*p,3*p,15*p,14*p,13*p,wood,2,3,3,13);
        south(data,cursor,shade[3],13*p,3*p,16*p,14*p,13*p,wood,13,3,14,13);
        west(data,cursor,shade[4],13*p,3*p,15*p,13*p,16*p,wood,15,3,16,13);
        east(data,cursor,shade[5],14*p,3*p,15*p,13*p,16*p,wood,0,3,1,13);
        return data;
    }

    /** Vanillas fünf ModelPart-Würfel inklusive der originalen 64x32-Box-UV-Belegung. */
    private static float[] buildMinecartMesh() {
        float[] data = new float[5 * 6 * 6 * FLOATS_PER_VERTEX];
        int[] cursor = {0};
        minecartCube(data, cursor, -10,-8,-1, 20,16,2, 0,10, 0,4,0, 90,0);
        /* Exakte PartPoses aus MinecartModel.createBodyLayer in Java 26.2. */
        minecartCube(data, cursor, -8,-9,-1, 16,8,2, 0,0, -9,4,0, 0,-90);
        minecartCube(data, cursor, -8,-9,-1, 16,8,2, 0,0, 9,4,0, 0,90);
        minecartCube(data, cursor, -8,-9,-1, 16,8,2, 0,0, 0,4,-7, 0,180);
        minecartCube(data, cursor, -8,-9,-1, 16,8,2, 0,0, 0,4,7, 0,0);
        return data;
    }

    private static void minecartCube(float[] out, int[] at,
                                     float x, float y, float z, float w, float h, float d,
                                     float u, float v, float px, float py, float pz,
                                     float rotateX, float rotateY) {
        float x1=x+w, y1=y+h, z1=z+d;
        float u1=u+d, u2=u+d+w, u3=u+d+w+w, u4=u+d+w+d, u5=u+d+w+d+w;
        float v1=v+d, v2=v+d+h;
        minecartFace(out,at,px,py,pz,rotateX,rotateY,x,y,z,x1,y,z,x1,y,z1,x,y,z1,u1,v,u2,v1);
        minecartFace(out,at,px,py,pz,rotateX,rotateY,x,y1,z1,x1,y1,z1,x1,y1,z,x,y1,z,u2,v,u3,v1);
        minecartFace(out,at,px,py,pz,rotateX,rotateY,x,y,z,x,y,z1,x,y1,z1,x,y1,z,u,v1,u1,v2);
        minecartFace(out,at,px,py,pz,rotateX,rotateY,x1,y,z1,x1,y,z,x1,y1,z,x1,y1,z1,u2,v1,u4,v2);
        minecartFace(out,at,px,py,pz,rotateX,rotateY,x1,y,z,x,y,z,x,y1,z,x1,y1,z,u1,v1,u2,v2);
        minecartFace(out,at,px,py,pz,rotateX,rotateY,x,y,z1,x1,y,z1,x1,y1,z1,x,y1,z1,u4,v1,u5,v2);
    }

    private static void minecartFace(float[] out, int[] at,
                                     float px,float py,float pz,float rotateX,float rotateY,
                                     float ax,float ay,float az,float bx,float by,float bz,
                                     float cx,float cy,float cz,float dx,float dy,float dz,
                                     float u0,float v0,float u1,float v1) {
        u0/=64; u1/=64; v0/=32; v1/=32;
        /* ModelPart.Polygon ordnet die vier UV-Ecken in genau dieser Reihenfolge zu. */
        minecartVertex(out,at,px,py,pz,rotateX,rotateY,ax,ay,az,u1,v0);
        minecartVertex(out,at,px,py,pz,rotateX,rotateY,bx,by,bz,u0,v0);
        minecartVertex(out,at,px,py,pz,rotateX,rotateY,cx,cy,cz,u0,v1);
        minecartVertex(out,at,px,py,pz,rotateX,rotateY,ax,ay,az,u1,v0);
        minecartVertex(out,at,px,py,pz,rotateX,rotateY,cx,cy,cz,u0,v1);
        minecartVertex(out,at,px,py,pz,rotateX,rotateY,dx,dy,dz,u1,v1);
    }

    private static void minecartVertex(float[] out, int[] at,
                                       float px,float py,float pz,float rotateX,float rotateY,
                                       float x,float y,float z,float u,float v) {
        double rx=Math.toRadians(rotateX), ry=Math.toRadians(rotateY);
        double cx=Math.cos(rx), sx=Math.sin(rx), cy=Math.cos(ry), sy=Math.sin(ry);
        double yx=y*cx-z*sx, zx=y*sx+z*cx;
        double xx=x*cy+zx*sy, zy=-x*sy+zx*cy;
        /* Vanilla-Renderer: translateY(0,375) vor scale(-1/16). Das sind exakt 6/16;
           der frühere Offset 5/16 legte den Cart-Boden direkt auf die Rail-Oberkante. */
        vertex(out,at,(float)((xx+px)/16.0),(float)((6-(yx+py))/16.0),
                (float)((zy+pz)/16.0),u,v,0f,1f);
    }

    /**
     * Uebersetzt die lokalen Modellflaechen nach der Item-Frame-Pose in Weltrichtungen. Dadurch
     * folgen die gebackenen Vertexfarben exakt der Block-Schattierung (oben 1,0, unten 0,5,
     * Nord/Sued 0,8, West/Ost 0,6), auch bei Boden- und Deckenrahmen.
     */
    private static float[] itemFrameFaceBrightness(Direction frameDirection) {
        Direction localUp;
        Direction localDown;
        Direction localWest;
        Direction localEast;
        if (frameDirection.axis() == Direction.Axis.Y) {
            localUp = frameDirection == Direction.UP ? Direction.NORTH : Direction.SOUTH;
            localDown = localUp.opposite();
            localWest = Direction.EAST;
            localEast = Direction.WEST;
        } else {
            localUp = Direction.UP;
            localDown = Direction.DOWN;
            localWest = frameDirection.rotateYCCW();
            localEast = frameDirection.rotateYCW();
        }
        return new float[] {
                faceBrightness(localUp), faceBrightness(localDown),
                faceBrightness(frameDirection), faceBrightness(frameDirection.opposite()),
                faceBrightness(localWest), faceBrightness(localEast)
        };
    }

    private static float faceBrightness(Direction direction) {
        return BlockModels.FACE_BRIGHTNESS[direction.faceIndex()];
    }

    private static void boxAll(float[] o,int[] a,float[] s,float x0,float y0,float z0,float x1,float y1,float z1,float l,
                               float du0,float dv0,float du1,float dv1,float uu0,float uv0,float uu1,float uv1,
                               float nu0,float nv0,float nu1,float nv1,float su0,float sv0,float su1,float sv1,
                               float wu0,float wv0,float wu1,float wv1,float eu0,float ev0,float eu1,float ev1) {
        down(o,a,s[1],x0,y0,z0,x1,z1,l,du0,dv0,du1,dv1); up(o,a,s[0],x0,y1,z0,x1,z1,l,uu0,uv0,uu1,uv1);
        north(o,a,s[2],x0,y0,z0,x1,y1,l,nu0,nv0,nu1,nv1); south(o,a,s[3],x0,y0,z1,x1,y1,l,su0,sv0,su1,sv1);
        west(o,a,s[4],x0,y0,z0,y1,z1,l,wu0,wv0,wu1,wv1); east(o,a,s[5],x1,y0,z0,y1,z1,l,eu0,ev0,eu1,ev1);
    }

    private static void north(float[]o,int[]a,float b,float x0,float y0,float z,float x1,float y1,float l,float u0,float v0,float u1,float v1){quad(o,a,l,b,x1,y0,z,x0,y0,z,x0,y1,z,x1,y1,z,u0,v0,u1,v1);}
    private static void south(float[]o,int[]a,float b,float x0,float y0,float z,float x1,float y1,float l,float u0,float v0,float u1,float v1){quad(o,a,l,b,x0,y0,z,x1,y0,z,x1,y1,z,x0,y1,z,u0,v0,u1,v1);}
    private static void up(float[]o,int[]a,float b,float x0,float y,float z0,float x1,float z1,float l,float u0,float v0,float u1,float v1){quad(o,a,l,b,x0,y,z1,x1,y,z1,x1,y,z0,x0,y,z0,u0,v0,u1,v1);}
    private static void down(float[]o,int[]a,float b,float x0,float y,float z0,float x1,float z1,float l,float u0,float v0,float u1,float v1){quad(o,a,l,b,x0,y,z0,x1,y,z0,x1,y,z1,x0,y,z1,u0,v0,u1,v1);}
    private static void west(float[]o,int[]a,float b,float x,float y0,float z0,float y1,float z1,float l,float u0,float v0,float u1,float v1){quad(o,a,l,b,x,y0,z0,x,y0,z1,x,y1,z1,x,y1,z0,u0,v0,u1,v1);}
    private static void east(float[]o,int[]a,float b,float x,float y0,float z0,float y1,float z1,float l,float u0,float v0,float u1,float v1){quad(o,a,l,b,x,y0,z1,x,y0,z0,x,y1,z0,x,y1,z1,u0,v0,u1,v1);}

    private static void quad(float[] out, int[] at, float layer, float brightness,
                             float ax,float ay,float az,float bx,float by,float bz,
                             float cx,float cy,float cz,float dx,float dy,float dz,
                             float u0,float v0,float u1,float v1) {
        u0/=16;v0/=16;u1/=16;v1/=16;
        vertex(out,at,ax,ay,az,u0,v1,layer,brightness); vertex(out,at,bx,by,bz,u1,v1,layer,brightness);
        vertex(out,at,cx,cy,cz,u1,v0,layer,brightness); vertex(out,at,ax,ay,az,u0,v1,layer,brightness);
        vertex(out,at,cx,cy,cz,u1,v0,layer,brightness); vertex(out,at,dx,dy,dz,u0,v0,layer,brightness);
    }

    private static void vertex(float[] out, int[] at, float x,float y,float z,
                               float u,float v,float layer,float brightness) {
        int i = at[0];
        out[i++] = x; out[i++] = y; out[i++] = z;
        out[i++] = u; out[i++] = v; out[i++] = layer;
        out[i++] = brightness; out[i++] = brightness; out[i++] = brightness;
        at[0] = i;
    }

    /**
     * Gedropptes Item: kleiner, um Y rotierender und sanft wippender Körper über dem Boden.
     * Block-Items sind Mini-Blockmodelle (Maßstab wie MCs {@code item/block}-ground), alles
     * andere ein extrudiertes Sprite wie in der Hand (MC {@code item/generated}, ground-Maßstab
     * 0,5) — ohne diesen zweiten Zweig lägen Apfel, Werkzeug und Eimer unsichtbar am Boden.
     *
     * <p>{@code oy} ist der FUSSPUNKT der Entity ({@code Entity.updateBoundingBox} setzt
     * {@code minY = y}), das Modell wird aber um seine Mitte zentriert — die Höhe muss das
     * ausgleichen, sonst steckt es im Boden. Siehe {@link #GROUND_LIFT}.
     */
    private void drawItem(ItemEntity item, float ox, float oy, float oz, float partialTick) {
        ItemStack stack = item.getStack();
        if (stack == null || stack.isEmpty()) return;

        float a = item.getAge() + partialTick;
        /* Wippe schwingt um GROUND_LIFT nach oben, nie darunter — sonst taucht das Modell in den
           Boden (es wird um seine Mitte zentriert gezeichnet, s. GROUND_LIFT). */
        float lift = GROUND_LIFT + (float) Math.sin(a * BOB_SPEED) * BOB_AMPLITUDE + BOB_AMPLITUDE;
        float spin = a * SPIN_SPEED;

        int copies = renderedCopies(stack.getCount());
        int id = blockStateId(stack);
        if (id >= 0) {
            Mesh mesh = this.meshFor(id);
            if (mesh == null) return;
            /* Seed = State-ID: derselbe Block sieht überall gleich aus (wie MC, dort Item-ID). */
            this.copyRandom.setSeed(id);
            this.drawCopies(mesh, id, copies, ox, oy + lift, oz, spin, ITEM_SCALE, false);
            return;
        }

        Mesh sprite = this.spriteFor(stack.getItem());
        if (sprite == null) return;
        this.copyRandom.setSeed(stack.getItem().getId().hashCode());
        /* Wie in HeldItemMeshes ohne Culling: das Sprite ist 1 px dick und dreht sich frei,
           da darf keine Wand wegen ihrer Winding-Richtung verschwinden. */
        GlState.disableCullFace();
        this.drawCopies(sprite, -1, copies, ox, oy + lift, oz, spin, FLAT_ITEM_SCALE, true);
        GlState.enableCullFace();
    }

    /**
     * Wie viele Kopien ein Stapel dieser Größe zeigt (MC {@code ItemEntityRenderer
     * .getRenderedAmount}) — daher springt die Optik bei 2, 17, 33 und 49.
     */
    private static int renderedCopies(int count) {
        if (count <= 1) return 1;
        if (count <= 16) return 2;
        if (count <= 32) return 3;
        if (count <= 48) return 4;
        return 5;
    }

    /**
     * Zeichnet {@code copies} Exemplare mit MCs Versätzen. Die Versätze sitzen in der Matrix-Kette
     * NACH der Drehung und VOR der Skalierung — sie sind Welt-Einheiten und dürfen nicht
     * mitverkleinert werden. Flache Sprites versetzen nur in x/y und schichten sich zusätzlich in
     * z, Würfel streuen in alle drei Achsen.
     *
     * <p>{@code stateId} < 0 heißt „flaches Sprite": dann entfällt die RenderLayer-Abfrage, und
     * gezeichnet wird ohne den Blend-Umschalter von {@link #drawMesh}.
     */
    private void drawCopies(Mesh mesh, int stateId, int copies, float ox, float oy, float oz,
                            float spin, float scale, boolean flat) {
        boolean translucent = stateId >= 0
                && Blocks.getState(stateId).getRenderLayer() == RenderLayer.TRANSLUCENT;
        /* Blend EINMAL um die ganze Schleife, nicht je Kopie. */
        if (translucent) {
            GL11.glEnable(GL11.GL_BLEND);
            this.shader.setUniformf(this.locAlphaCutoff, TRANSLUCENT_ALPHA);
        }
        /* Der Sprite-Stapel wächst nach hinten; die Vorab-Verschiebung hält ihn mittig. */
        float layerZ = flat ? -COPY_LAYER_STEP * (copies - 1) * 0.5f : 0f;
        for (int i = 0; i < copies; i++) {
            float dx = 0f, dy = 0f, dz = layerZ;
            if (i > 0) {
                float spread = flat ? COPY_SPREAD * 0.5f : COPY_SPREAD;
                dx += (this.copyRandom.nextFloat() * 2f - 1f) * spread;
                dy += (this.copyRandom.nextFloat() * 2f - 1f) * spread;
                if (!flat) dz += (this.copyRandom.nextFloat() * 2f - 1f) * spread;
            }
            this.model.identity()
                    .translate(ox, oy, oz)
                    .rotateY(spin)
                    .translate(dx, dy, dz)
                    .scale(scale)
                    .translate(-0.5f, -0.5f, -0.5f);
            this.shader.setUniformMatrix4f(this.locModel, this.model);
            mesh.render();
            layerZ += flat ? COPY_LAYER_STEP : 0f;
        }
        if (translucent) {
            GL11.glDisable(GL11.GL_BLEND);
            this.shader.setUniformf(this.locAlphaCutoff, CUTOUT_ALPHA);
        }
    }

    /**
     * Zeichnet das Würfel-Mesh mit dem Zustand, den sein RenderLayer braucht: transluzente Blöcke
     * (Slime, Honig, Eis, Glas) mit Blending und praktisch ohne Alpha-Test, alles andere als
     * harter Cutout — dieselbe Aufteilung wie die drei Passes des {@code ChunkRenderer}. Ohne das
     * läge ein gedroppter Slimeblock deckend in der Welt, weil seine Textur mit Alpha ≈ 0,7 am
     * 0,5-Test vorbeikommt.
     *
     * <p>Die Pass-Reihenfolge stimmt bereits: Entities zeichnet {@code World.render} VOR
     * {@code ChunkRenderer.renderTranslucent}, ein durchscheinender Drop blendet also gegen die
     * fertige opake Welt, und Wasser kommt danach korrekt darüber.
     */
    private void drawMesh(Mesh mesh, int stateId) {
        boolean translucent = Blocks.getState(stateId).getRenderLayer() == RenderLayer.TRANSLUCENT;
        if (translucent) {
            GL11.glEnable(GL11.GL_BLEND);
            this.shader.setUniformf(this.locAlphaCutoff, TRANSLUCENT_ALPHA);
        }
        mesh.render();
        if (translucent) {
            GL11.glDisable(GL11.GL_BLEND);
            this.shader.setUniformf(this.locAlphaCutoff, CUTOUT_ALPHA);
        }
    }

    /** Block-State-ID für ein (Block-)Item, oder -1 wenn das Item keinen Würfel hat. */
    private static int blockStateId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return -1;
        return bi.getBlock().getDefaultState().getId();
    }

    /**
     * Extrudiertes Sprite eines Nicht-Block-Items (lazy gebacken), oder null ohne Icon-Textur.
     * Helligkeiten nach Block-Konvention statt der First-Person-Werte aus {@code HeldItemMeshes} —
     * das Item liegt hier am Boden und dreht sich, die große Fläche darf nicht die dunkelste sein.
     */
    private Mesh spriteFor(Item item) {
        Object cached = this.sprites.get(item);
        if (cached != null) return cached == NO_MESH ? null : (Mesh) cached;
        String path = item.getIconTexture();
        Mesh mesh = path == null ? null : new Mesh(ItemSpriteBuilder.extrude(path, 0xFFFFFF,
                FLAT_FACE_FRONT, FLAT_FACE_SIDE, FLAT_FACE_TOP, FLAT_FACE_BOTTOM));
        this.sprites.put(item, mesh == null ? NO_MESH : mesh);
        return mesh;
    }

    /** Liefert das gecachte Würfel-Mesh (lazy gebacken) oder null bei leerem Modell. */
    private Mesh meshFor(int stateId) {
        Object cached = this.cache.get(stateId);
        if (cached != null) return cached == NO_MESH ? null : (Mesh) cached;
        Mesh mesh = build(stateId);
        this.cache.put(stateId, mesh == null ? NO_MESH : mesh);
        return mesh;
    }

    /** Backt die Quads des States in ein interleaved Mesh (Daten-Bau geteilt in {@code BlockStateMesh}). */
    private static Mesh build(int stateId) {
        float[] data = de.skyengine.graphics.BlockStateMesh.interleave(stateId);
        return data == null ? null : new Mesh(data);
    }

    public void dispose() {
        for (int i = 0, n = this.cache.tableSize(); i < n; i++) {
            Object cached = this.cache.valueAt(i);
            if (cached != null && cached != NO_MESH) ((Mesh) cached).dispose();
        }
        this.cache.clear();
        for (Object cached : this.sprites.values()) {
            if (cached != NO_MESH) ((Mesh) cached).dispose();
        }
        this.sprites.clear();
        for (Mesh itemFrameMesh : this.itemFrameMeshes) {
            if (itemFrameMesh != null) itemFrameMesh.dispose();
        }
        if (this.minecartMesh != null) this.minecartMesh.dispose();
        if (this.minecartTexture != null) this.minecartTexture.dispose();
        if (this.shader != null) this.shader.dispose();
    }

    /* --- kleine VAO/VBO-Hülle (identisches Layout wie ItemIconRenderer) --- */
    private static final class Mesh {
        private final int vao, vbo, count;

        Mesh(float[] data) {
            this.count = data.length / FLOATS_PER_VERTEX;
            this.vao = GL30.glGenVertexArrays();
            this.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            de.skyengine.graphics.GlDebug.labelBuffer(this.vbo, "EntityRenderer Block-Mesh-VBO");
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL30.glBindVertexArray(0);
        }

        void render() {
            GL30.glBindVertexArray(this.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.count);
        }

        void dispose() {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.vbo);
        }
    }

    private static final String VERTEX = """
        #version 460 core
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_texCoord;
        layout(location = 2) in vec3 a_color;
        uniform mat4 u_ProjectionView;
        uniform mat4 u_Model;
        out vec3 v_texCoord;
        out vec3 v_color;
        out vec3 v_pos;
        void main() {
            v_texCoord = a_texCoord;
            v_color = a_color;
            vec4 p=u_Model*vec4(a_position,1.0); v_pos=p.xyz;
            gl_Position = u_ProjectionView * p;
        }
        """;

    private static final String FRAGMENT = """
        #version 460 core
        in vec3 v_texCoord;
        in vec3 v_color;
        in vec3 v_pos;
        uniform sampler2DArray u_Textures;
        uniform sampler2DArray u_NormalTextures;
        uniform sampler2DArray u_MaterialTextures;
        uniform int u_PbrEnabled;
        uniform sampler2D u_EntityTexture;
        uniform int u_UseEntityTexture;
        uniform float u_WhiteFlash;   // 0..1: mischt das Fragment Richtung Weiß (TNT-Blink)
        /* Himmelslicht der Entity-Zelle, fertig durch die Kurve gerechnet
           (ChunkRenderer.lightFactor). 1.0 = voll hell bzw. Fullbright. */
        uniform float u_Light;
        /* Wie im ChunkRenderer: 0.5 = harter Cutout, 0.001 = praktisch aus, damit ein
           transluzenter Block (Slime, Honig, Eis, Glas) sein Alpha ins Blending bringt. */
        uniform float u_AlphaCutoff;
        out vec4 fragColor;
        vec3 materialLight(vec3 albedo) {
            if(u_PbrEnabled==0||u_UseEntityTexture!=0)return albedo;
            vec4 nt=texture(u_NormalTextures,v_texCoord),m=texture(u_MaterialTextures,v_texCoord);
            if(nt.a<=0.0&&m.a<=0.0)return albedo;
            vec3 gn=normalize(cross(dFdx(v_pos),dFdy(v_pos)));if(!gl_FrontFacing)gn=-gn;
            vec3 p1=dFdx(v_pos),p2=dFdy(v_pos);vec2 d1=dFdx(v_texCoord.xy),d2=dFdy(v_texCoord.xy);
            vec3 t=cross(p2,gn)*d1.x+cross(gn,p1)*d2.x,b=cross(p2,gn)*d1.y+cross(gn,p1)*d2.y;
            float s=inversesqrt(max(max(dot(t,t),dot(b,b)),1e-8));vec3 n=nt.a>0.0?normalize(mat3(t*s,b*s,gn)*(nt.rgb*2.0-1.0)):gn;
            vec3 l=normalize(vec3(-0.35,0.80,0.45)),h=normalize(l+normalize(-v_pos));
            float r=m.a>0.0?m.r:1.0,metal=m.a>0.0?m.g:0.0,e=m.a>0.0?m.b:0.0;
            float diff=0.35+0.65*max(dot(n,l),0.0),spec=pow(max(dot(n,h),0.0),mix(96.0,2.0,r))*(1.0-r);
            return albedo*(1.0-metal)*diff+mix(vec3(0.04),albedo,metal)*spec+albedo*e;
        }
        void main() {
            vec4 c = u_UseEntityTexture != 0
                    ? texture(u_EntityTexture, v_texCoord.xy)
                    : texture(u_Textures, v_texCoord);
            if (c.a < u_AlphaCutoff) discard;
            /* Licht VOR dem Blink: eine TNT-Zuendung soll auch in einer finsteren Hoehle
               rein weiss aufblitzen und nicht mit abgedunkelt werden. */
            vec3 rgb = mix(materialLight(c.rgb) * v_color * u_Light, vec3(1.0), u_WhiteFlash);
            fragColor = vec4(rgb, c.a);
        }
        """;
}
