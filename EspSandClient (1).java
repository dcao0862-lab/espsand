package com.espsand;

// Mod Fabric (client-only) RIÊNG cho Minecraft Java 1.21.11, dùng MOJANG MAPPINGS.
// /espsand: bật/tắt. Khi bật, mod chạy LIÊN TỤC: mỗi item cát rơi trong phạm vi được vẽ một KHUNG HIGHLIGHT
// (hộp màu vàng trong suốt, nhìn xuyên tường) để bạn tự đi nhặt. Hết cát thì mod vẫn chờ, có cát mới là hiện khung ngay.
// Mod chỉ tắt khi bạn gõ /espsand lần nữa.
// Tối ưu: quét item 1 lần mỗi 0.5 giây; mỗi khung hình chỉ vẽ lại các hộp đã có trong 1 lần vẽ duy nhất.
// Phần vẽ làm theo ví dụ chính thức "Rendering in the World" của Fabric cho 1.21.11.
// CHƯA ĐƯỢC BIÊN DỊCH/CHẠY THỬ trong game.

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

public class EspSandClient implements ClientModInitializer {

    // ===== CẤU HÌNH =====
    static final double RANGE = 64.0;             // chỉ vẽ khung cho cát trong phạm vi này (block)
    static final int SCAN_EVERY = 10;             // quét mỗi 10 tick (0.5 giây), không quét mỗi tick cho đỡ lag
    static final boolean INCLUDE_RED_SAND = true; // true = vẽ cả cát đỏ, false = chỉ cát thường
    static final int MAX_BOXES = 400;             // tối đa số khung vẽ cùng lúc (ưu tiên cái gần nhất)
    static final float BOX_HALF = 0.28f;          // nửa bề rộng khung quanh item
    static final float RED = 1.0f, GREEN = 0.85f, BLUE = 0.1f, ALPHA = 0.45f; // màu khung (vàng, hơi trong suốt)
    // =====================

    private static final String MOD_ID = "espsand";

    // Pipeline vẽ hộp đặc không kiểm tra độ sâu = nhìn xuyên tường (theo ví dụ của Fabric)
    private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/sand_box_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    private static final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private MappableRingBuffer vertexBuffer;

    private boolean active = false;
    private int tickCount = 0;
    private int lastCount = -1;
    private ClientLevel lastLevel;
    private final List<ItemEntity> tracked = new ArrayList<>(); // item cát đang được vẽ khung

    // ---------- khởi tạo ----------

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::onWorldRender);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("espsand").executes(ctx -> {
                    toggle(Minecraft.getInstance());
                    return 1;
                })));
    }

    private static boolean isSand(ItemStack s) {
        return s.is(Items.SAND) || (INCLUDE_RED_SAND && s.is(Items.RED_SAND));
    }

    private void log(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[ESP Sand] " + msg), false);
    }

    private void toggle(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        active = !active;
        if (active) {
            tickCount = 0;
            lastCount = -1;
            lastLevel = mc.level;
            log(mc, "BẬT: cát rơi trong " + (int) RANGE + " ô sẽ có khung vàng, chạy liên tục. Gõ /espsand để tắt");
            scan(mc);
        } else {
            tracked.clear();
            log(mc, "TẮT");
        }
    }

    // ---------- quét item (nhẹ: 1 lần mỗi 0.5 giây) ----------

    private void scan(Minecraft mc) {
        LocalPlayer p = mc.player;
        double r2 = RANGE * RANGE;
        AABB area = p.getBoundingBox().inflate(RANGE);
        List<ItemEntity> found = mc.level.getEntitiesOfClass(ItemEntity.class, area,
                e -> isSand(e.getItem()) && e.distanceToSqr(p) <= r2);

        int n = found.size();
        double nearest = Double.MAX_VALUE;
        for (ItemEntity e : found) {
            double d = e.distanceToSqr(p);
            if (d < nearest) nearest = d;
        }
        if (n > MAX_BOXES) {
            found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
            found = found.subList(0, MAX_BOXES);
        }
        tracked.clear();
        tracked.addAll(found);

        // Hiện số lượng và khoảng cách gần nhất trên thanh phía trên vật phẩm (không spam khung chat)
        if (n > 0) {
            p.displayClientMessage(Component.literal("Cát: " + n + " (gần nhất " + (int) Math.sqrt(nearest) + " ô)"), true);
        } else if (lastCount != 0) {
            p.displayClientMessage(Component.literal("Cát: không có trong " + (int) RANGE + " ô, đang chờ"), true);
        }
        lastCount = n;
    }

    private void tick(Minecraft mc) {
        if (!active) return;
        // Đang chuyển server/thế giới, hoặc chết và hồi sinh: tạm dừng nhưng KHÔNG tắt, vào lại là chạy tiếp
        if (mc.player == null || mc.level == null) {
            tracked.clear();
            lastLevel = null;
            return;
        }
        if (mc.level != lastLevel) { // đổi thế giới: danh sách cũ không còn dùng được
            tracked.clear();
            lastLevel = mc.level;
        }
        if (++tickCount % SCAN_EVERY == 0) scan(mc);
    }

    // ---------- vẽ khung (mỗi khung hình) ----------

    private void onWorldRender(WorldRenderContext context) {
        if (!active || tracked.isEmpty()) return;

        PoseStack matrices = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        BufferBuilder buffer = null;
        for (ItemEntity e : tracked) {
            if (e.isRemoved()) continue;
            if (buffer == null) {
                buffer = new BufferBuilder(allocator, FILLED_THROUGH_WALLS.getVertexFormatMode(),
                        FILLED_THROUGH_WALLS.getVertexFormat());
            }
            float x = (float) e.getX();
            float y = (float) e.getY();
            float z = (float) e.getZ();
            addBox(matrices.last().pose(), buffer,
                    x - BOX_HALF, y - 0.05f, z - BOX_HALF,
                    x + BOX_HALF, y + 0.45f, z + BOX_HALF,
                    RED, GREEN, BLUE, ALPHA);
        }
        matrices.popPose();

        if (buffer != null) drawFilledThroughWalls(Minecraft.getInstance(), FILLED_THROUGH_WALLS, buffer);
    }

    private void addBox(Matrix4fc positionMatrix, BufferBuilder buffer, float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float red, float green, float blue, float alpha) {
        // Front Face
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);

        // Back face
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);

        // Left face
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

        // Right face
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);

        // Top face
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

        // Bottom face
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
    }

    private void drawFilledThroughWalls(Minecraft client, RenderPipeline pipeline, BufferBuilder buffer) {
        // Build the buffer
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);

        draw(client, pipeline, builtBuffer, drawParameters, vertices, format);

        // Rotate the vertex buffer so we are less likely to use buffers that the GPU is using
        vertexBuffer.rotate();
    }

    private GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        // Calculate the size needed for the vertex buffer
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        // Initialize or resize the vertex buffer as needed
        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }

            vertexBuffer = new MappableRingBuffer(() -> MOD_ID + " esp sand render pipeline",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        // Copy vertex data into the vertex buffer
        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();

        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }

        return vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer,
                             MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            // Sort the quads if there is translucency
            builtBuffer.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
            // Upload the index buffer
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            // Use the general shape index buffer for non-quad draw modes
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        // Actually execute the draw
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> MOD_ID + " esp sand render pipeline rendering",
                        client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                        client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);

            // The base vertex is the starting index when we copied the data into the vertex buffer divided by vertex size
            //noinspection ConstantValue
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
    }

    /** Giải phóng bộ nhớ GPU khi tắt game. */
    public void close() {
        allocator.close();

        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
