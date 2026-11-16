package com.example.piglincheck;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.debug.DebugHudEntries;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * When hitboxes are turned on (F3+B), you have 15 seconds to actually SEE at least one
 * zombified piglin (on screen + line of sight). Piglins that are merely loaded do not count.
 * If you don't, you get disconnected from the server.
 */
public class PiglinCheckClient implements ClientModInitializer {

    /** Seconds you get after enabling hitboxes. */
    private static final int TIMEOUT_SECONDS = 15;
    private static final int TIMEOUT_TICKS = TIMEOUT_SECONDS * 20;

    /** Piglins farther away than this (blocks) don't count as "seen". */
    private static final double MAX_DISTANCE = 64.0;

    /** Require an unobstructed line of sight (no blocks between camera and piglin). */
    private static final boolean REQUIRE_LINE_OF_SIGHT = true;

    /** If true, a new 15s window starts after a successful sighting while hitboxes stay on. */
    private static final boolean REARM_AFTER_SEEN = false;

    private boolean hitboxesWereOn = false;
    private boolean satisfied = false;
    private int ticksLeft = TIMEOUT_TICKS;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void reset() {
        hitboxesWereOn = false;
        satisfied = false;
        ticksLeft = TIMEOUT_TICKS;
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null || client.player == null || client.getNetworkHandler() == null) {
            reset();
            return;
        }

        boolean hitboxesOn = hitboxesVisible(client);

        if (!hitboxesOn) {
            if (hitboxesWereOn) {
                client.player.sendMessage(Text.literal("[PiglinCheck] Hitboxes off - check cancelled."), true);
            }
            reset();
            return;
        }

        if (!hitboxesWereOn) {
            // F3+B just turned hitboxes on: start the timer.
            hitboxesWereOn = true;
            satisfied = false;
            ticksLeft = TIMEOUT_TICKS;
        }

        if (satisfied) {
            return;
        }

        if (seesZombifiedPiglin(client)) {
            client.player.sendMessage(Text.literal("[PiglinCheck] Piglin spotted - you're good."), true);
            if (REARM_AFTER_SEEN) {
                ticksLeft = TIMEOUT_TICKS;
            } else {
                satisfied = true;
            }
            return;
        }

        ticksLeft--;
        if (ticksLeft % 20 == 0 && ticksLeft > 0) {
            client.player.sendMessage(
                    Text.literal("[PiglinCheck] No zombified piglin seen - disconnecting in " + (ticksLeft / 20) + "s"),
                    true);
        }

        if (ticksLeft <= 0) {
            reset();
            client.getNetworkHandler().getConnection().disconnect(
                    Text.literal("[PiglinCheck] No zombified piglin seen within " + TIMEOUT_SECONDS + " seconds."));
        }
    }

    /** In 1.21.11 F3+B toggles a debug HUD entry; find its id once and ask the debug profile. */
    private static Identifier hitboxEntryId = null;

    private static boolean hitboxesVisible(MinecraftClient client) {
        if (hitboxEntryId == null) {
            for (Object key : DebugHudEntries.getEntries().keySet()) {
                if (key instanceof Identifier id && id.getPath().contains("hitbox")) {
                    hitboxEntryId = id;
                    break;
                }
            }
            if (hitboxEntryId == null) {
                hitboxEntryId = Identifier.ofVanilla("entity_hitboxes");
            }
        }
        return client.debugHudEntryList.isEntryVisible(hitboxEntryId);
    }

    private boolean seesZombifiedPiglin(MinecraftClient client) {
        Camera camera = client.gameRenderer.getCamera();
        // Eye position of the player (same as the camera in first person).
        Vec3d camPos = client.player.getEyePos();

        // Camera basis vectors from yaw/pitch.
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
            // Several sample points so a partly visible piglin still counts.
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
