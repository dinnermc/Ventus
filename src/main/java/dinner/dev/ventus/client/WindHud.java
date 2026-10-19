package dinner.dev.ventus.client;

import com.mojang.math.Axis;
import dinner.dev.ventus.network.WindSyncState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The wind HUD: one line of text pinned to a screen corner — a needle that swings to the wind
 * direction relative to the player, the speed in m/s, and a climb marker once the vertical
 * component is worth showing. Only shows data that arrived from the server via
 * {@link WindSyncState}; with no Ventus on the server, or before the first packet, the HUD stays
 * hidden, and it steps aside for the F3 debug screen. The needle eases towards each new
 * direction ({@link WindHudFormat#approachAngle}); the text is rebuilt only when the figures
 * change.
 */
public final class WindHud {
    private WindHud() {
    }

    private static final int MARGIN = 4;
    private static final int FONT_HEIGHT = 9;
    /** Layout box of the needle: its head and tail together span ten pixels. */
    private static final int ARROW_SIZE = 10;
    private static final int ARROW_LENGTH = 6;
    private static final int ARROW_TAIL = 3;
    private static final int TEXT_GAP = 6;

    /** How long the needle takes to cover most of the gap to a new direction, in seconds. */
    private static final double TIME_CONSTANT = 0.15;

    private static final int TAIL_COLOR = 0x70000000;
    private static final int HUB_COLOR = 0xFFE8EEF6;

    private static double smoothedAngle;
    /** Whether the needle has ever had a real direction, so the first one snaps instead of sweeping. */
    private static boolean hasAngle;

    private static String line = "";
    private static int lineWidth;
    private static int cachedSpeedTenths = Integer.MIN_VALUE;
    private static int cachedClimbTenths = Integer.MIN_VALUE;

    public static void render(final RenderGuiEvent.Post event) {
        if (!VentusClient.windLineVisible() || !WindSyncState.isValid()) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        // F3 draws its own text over this corner; the wind line has nothing to add against it.
        if (minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }

        final float windX = WindSyncState.x();
        final float windY = WindSyncState.y();
        final float windZ = WindSyncState.z();
        final double speed = Math.sqrt((double) windX * windX + (double) windZ * windZ);
        final boolean calm = WindHudFormat.isCalm(speed);

        // Camera basis in the XZ plane: "look" is where the player faces, "right" is to their right.
        final double yaw = Math.toRadians(minecraft.player.getYRot());
        final double lookX = -Math.sin(yaw);
        final double lookZ = Math.cos(yaw);
        final double rightX = -Math.cos(yaw);
        final double rightZ = -Math.sin(yaw);
        // Still air has no direction to speak of, so leave the needle where it was rather than
        // easing it towards an arbitrary zero: it must not sweep across the screen while parked.
        if (!calm) {
            final double targetAngle = Math.atan2(windX * rightX + windZ * rightZ,
                    windX * lookX + windZ * lookZ);
            if (hasAngle) {
                final double deltaSeconds = minecraft.getTimer().getRealtimeDeltaTicks() / 20.0;
                smoothedAngle = WindHudFormat.approachAngle(smoothedAngle, targetAngle,
                        deltaSeconds, TIME_CONSTANT);
            } else {
                // the first direction after a join or a calm spell is taken as it is
                smoothedAngle = targetAngle;
                hasAngle = true;
            }
        }
        rebuildIfChanged(minecraft.font, speed, windY);

        final GuiGraphics graphics = event.getGuiGraphics();
        final VentusClientConfig.Corner corner = VentusClientConfig.corner();
        final boolean toTheRight = corner == VentusClientConfig.Corner.TOP_RIGHT
                || corner == VentusClientConfig.Corner.BOTTOM_RIGHT;
        final boolean atTheBottom = corner == VentusClientConfig.Corner.BOTTOM_LEFT
                || corner == VentusClientConfig.Corner.BOTTOM_RIGHT;

        final int textY = atTheBottom ? graphics.guiHeight() - MARGIN - FONT_HEIGHT : MARGIN;
        final int arrowX = toTheRight ? graphics.guiWidth() - MARGIN - ARROW_SIZE / 2
                : MARGIN + ARROW_SIZE / 2;
        final int arrowY = textY + FONT_HEIGHT / 2;
        final int textX = toTheRight
                ? graphics.guiWidth() - MARGIN - ARROW_SIZE - TEXT_GAP
                : MARGIN + ARROW_SIZE + TEXT_GAP;

        final int color = WindHudFormat.speedColor(speed);
        if (!calm) {
            drawNeedle(graphics, arrowX, arrowY, (float) smoothedAngle, color);
        }
        graphics.drawString(minecraft.font, line, toTheRight ? textX - lineWidth : textX, textY, color);
    }

    /** Forgets the cached line and the needle's angle; called when the player leaves a server. */
    public static void reset() {
        cachedSpeedTenths = Integer.MIN_VALUE;
        cachedClimbTenths = Integer.MIN_VALUE;
        line = "";
        lineWidth = 0;
        hasAngle = false;
    }

    /**
     * Rebuilds the text only when the figures it is made of have moved, measuring it in the same
     * pass so the width is not recomputed every frame. The inputs are rounded the way the string
     * is, so a difference too small to show up in the text cannot trigger a format call.
     */
    private static void rebuildIfChanged(final Font font, final double speed, final double climb) {
        final int speedTenths = (int) Math.round(speed * 10.0);
        final int climbTenths = (int) Math.round(climb * 10.0);
        if (speedTenths == cachedSpeedTenths && climbTenths == cachedClimbTenths) {
            return;
        }

        cachedSpeedTenths = speedTenths;
        cachedClimbTenths = climbTenths;
        line = WindHudFormat.line(speed, climb);
        lineWidth = font.width(line);
    }

    /** The needle: a lit head pointing downwind, a faint tail behind the hub, no dial around it. */
    private static void drawNeedle(final GuiGraphics graphics, final int centerX, final int centerY,
            final float angle, final int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0);
        graphics.pose().mulPose(Axis.ZP.rotation(angle));
        graphics.fill(-1, -ARROW_LENGTH, 1, -1, color);
        graphics.fill(-1, 1, 1, ARROW_TAIL, (color & 0x00FFFFFF) | TAIL_COLOR);
        graphics.pose().popPose();

        graphics.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 1, HUB_COLOR);
    }
}
