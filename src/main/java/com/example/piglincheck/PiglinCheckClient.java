package com.example.piglincheck;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/**
 * Press F3+B (hitboxes). You then have 15 seconds to SEE a zombified piglin
 * (on screen + line of sight). Loaded-but-unseen piglins don't count.
 * If you don't see one, you are disconnected.
 *
 * The F3+B press is detected directly from the keyboard, so it does not depend
 * on any Minecraft internals that change between versions.
 * Pressing F3+B again turns the check off.
 */
public class PiglinCheckClient implements ClientModInitializer {

    private static final int TIMEOUT_SECONDS = 15;
    private static final int TIMEOUT_TICKS = TIMEOUT_SECONDS * 20;
    private static final double MAX_DISTANCE = 64.0;
    private static final boolean REQUIRE_LINE_OF_SIGHT = true;

    /** Our own copy of the hitbox on/off state, flipped on every F3+B press. */
    private boolean hitboxesOn = false;
    private boolean bWasDown = false;
    private boolean satisfied = false;
    private int ticksLeft = TIMEOUT_TICKS;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void resetAll() {
        hitboxesOn = false;
        bWasDown = false;
        satisfied = false;
        ticksLeft = TIMEOUT_TICKS;
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null || client.player == null || client.getNetworkHandler() == null) {
            resetAll();
            return;
        }

        // --- detect F3+B ---
        long window = client.getWindow().getHandle();
        boolean f3Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F3) == GLFW.GLFW_PRESS;
        boolean bDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_B) == GLFW.GLFW_PRESS;
        if (f3Down && bDown && !bWasDown) {
            hitboxesOn = !hitboxesOn;
            satisfied = false;
            ticksLeft = TIMEOUT_TICKS;
            if (hitboxesOn) {
                msg(client, "Hitboxes ON - you have " + TIMEOUT_SECONDS + "s to see a zombified piglin.");
            } else {
                msg(client, "Hitboxes OFF - check cancelled.");
            }
        }
        bWasDown = bDown;

        if (!hitboxesOn || satisfied) {
            return;
        }

        // --- the actual check ---
        if (seesZombifiedPiglin(client)) {
            msg(client, "Zombified piglin seen - you're good.");
            satisfied = true;
            return;
        }

        ticksLeft--;
        if (ticksLeft > 0 && ticksLeft % 20 == 0) {
            msg(client, "No zombified piglin seen - disconnecting in " + (ticksLeft / 20) + "s");
        }
        if (ticksLeft <= 0) {
            resetAll();
            client.getNetworkHandler().getConnection().disconnect(
                    Text.literal("[PiglinCheck] No zombified piglin seen within " + TIMEOUT_SECONDS + " seconds."));
        }
    }

    private static void msg(MinecraftClient client, String text) {
        client.player.sendMessage(Text.literal("[PiglinCheck] " + text), true);
    }

    private boolean seesZombifiedPiglin(MinecraftClient client) {
        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = client.player.getEyePos();

        Vec3d forward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw()).normalize();
        Vec3d up = Vec3d.fromPolar(camera.getPitch() - 90.0f, camera.getYaw()).normalize();
        Vec3d right = forward.crossProduct(up).normalize();

        double vFov = Math.toRadians(client.options.getFov().getValue());
        double aspect = (double) client.getWindow().getFramebufferWidth()
                / Math.max(1, client.getWindow().getFramebufferHeight());
        double tanV = Math.tan(vFov / 2.0);
        double tanH = tanV * aspect;

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ZombifiedPiglinEntity) || !entity.isAlive()) {
                continue;
            }
            Vec3d base = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            if (base.distanceTo(camPos) > MAX_DISTANCE) {
                continue;
            }

            double w = entity.getWidth() / 2.0;
            double h = entity.getHeight();
            Vec3d[] points = new Vec3d[] {
                    base.add(0, h * 0.5, 0),
                    base.add(0, h * 0.9, 0),
                    base.add(0, 0.1, 0),
                    base.add(w, h * 0.5, 0),
                    base.add(-w, h * 0.5, 0),
                    base.add(0, h * 0.5, w),
                    base.add(0, h * 0.5, -w)
            };

            for (Vec3d p : points) {
                if (inFrustum(camPos, forward, up, right, tanH, tanV, p)
                        && (!REQUIRE_LINE_OF_SIGHT || hasLineOfSight(client, camPos, p, entity))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inFrustum(Vec3d camPos, Vec3d forward, Vec3d up, Vec3d right,
                                     double tanH, double tanV, Vec3d p) {
        Vec3d d = p.subtract(camPos);
        double z = d.dotProduct(forward);
        if (z <= 0.05) {
            return false;
        }
        double x = d.dotProduct(right);
        double y = d.dotProduct(up);
        return Math.abs(x) <= z * tanH && Math.abs(y) <= z * tanV;
    }

    private static boolean hasLineOfSight(MinecraftClient client, Vec3d from, Vec3d to, Entity entity) {
        HitResult hit = client.world.raycast(new RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                entity));
        return hit.getType() == HitResult.Type.MISS;
    }
}
