package com.espsand;

// Mod Fabric (client-only) RIÊNG cho Minecraft Java 1.21.11, mappings Yarn.
// /espsand: bật/tắt. Khi bật, mod chạy LIÊN TỤC: mọi item cát đang rơi trong phạm vi phát sáng viền
// (nhìn xuyên tường) để bạn tự đi nhặt. Hết cát thì mod vẫn chờ, có cát mới rơi là sáng lại ngay.
// Mod chỉ tắt khi bạn gõ /espsand lần nữa. Mod chỉ quét 1 lần mỗi 0.5 giây nên rất nhẹ.
// CHƯA ĐƯỢC BIÊN DỊCH/CHẠY THỬ trong game.

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class EspSandClient implements ClientModInitializer {

    // ===== CẤU HÌNH =====
    static final double RANGE = 64.0;             // chỉ làm sáng cát trong phạm vi này (block)
    static final int SCAN_EVERY = 10;             // quét mỗi 10 tick (0.5 giây), không quét mỗi tick cho đỡ lag
    static final boolean INCLUDE_RED_SAND = true; // true = sáng cả cát đỏ, false = chỉ cát thường
    // =====================

    private boolean active = false;
    private int tickCount = 0;
    private int lastCount = -1;
    private ClientWorld lastWorld;
    private final Set<ItemEntity> glowing = new HashSet<>(); // các item đang được mod làm sáng
    private final Set<ItemEntity> seen = new HashSet<>();    // dùng lại giữa các lần quét để khỏi tạo đối tượng mới

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("espsand").executes(ctx -> {
                    toggle(MinecraftClient.getInstance());
                    return 1;
                })));
    }

    private static boolean isSand(ItemStack s) {
        return s.isOf(Items.SAND) || (INCLUDE_RED_SAND && s.isOf(Items.RED_SAND));
    }

    private void log(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[ESP Sand] " + msg), false);
    }

    private void toggle(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        active = !active;
        if (active) {
            tickCount = 0;
            lastCount = -1;
            lastWorld = mc.world;
            log(mc, "BẬT: cát rơi trong " + (int) RANGE + " ô sẽ phát sáng, chạy liên tục. Gõ /espsand để tắt");
            scan(mc);
        } else {
            clearGlow();
            log(mc, "TẮT");
        }
    }

    /** Trả các item về trạng thái bình thường (không sáng). */
    private void clearGlow() {
        for (ItemEntity e : glowing) {
            if (!e.isRemoved()) e.setGlowing(false);
        }
        glowing.clear();
        seen.clear();
    }

    private void scan(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        double r2 = RANGE * RANGE;
        Box box = p.getBoundingBox().expand(RANGE);
        List<ItemEntity> items = mc.world.getEntitiesByClass(ItemEntity.class, box,
                e -> isSand(e.getStack()) && e.squaredDistanceTo(p) <= r2);

        seen.clear();
        double nearest = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            seen.add(e);
            if (glowing.add(e)) e.setGlowing(true); // chỉ bật khi item mới xuất hiện
            double d = e.squaredDistanceTo(p);
            if (d < nearest) nearest = d;
        }

        // Tắt sáng cho item đã bị nhặt, đã đi ra khỏi phạm vi hoặc không còn là cát
        Iterator<ItemEntity> it = glowing.iterator();
        while (it.hasNext()) {
            ItemEntity e = it.next();
            if (!seen.contains(e)) {
                if (!e.isRemoved()) e.setGlowing(false);
                it.remove();
            }
        }

        // Hiện số lượng và khoảng cách gần nhất trên thanh phía trên vật phẩm (không spam khung chat)
        int n = items.size();
        if (n > 0) {
            p.sendMessage(Text.literal("Cát: " + n + " (gần nhất " + (int) Math.sqrt(nearest) + " ô)"), true);
        } else if (lastCount != 0) {
            p.sendMessage(Text.literal("Cát: không có trong " + (int) RANGE + " ô, đang chờ"), true);
        }
        lastCount = n;
    }

    private void tick(MinecraftClient mc) {
        if (!active) return;
        // Đang chuyển server/thế giới, hoặc chết và hồi sinh: tạm dừng nhưng KHÔNG tắt, vào lại là chạy tiếp
        if (mc.player == null || mc.world == null) {
            glowing.clear();
            seen.clear();
            lastWorld = null;
            return;
        }
        if (mc.world != lastWorld) { // đổi thế giới: danh sách cũ không còn dùng được
            glowing.clear();
            lastWorld = mc.world;
        }
        if (++tickCount % SCAN_EVERY == 0) scan(mc);
    }
}
