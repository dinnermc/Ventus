package dinner.dev.ventus.client;

import com.mojang.math.Axis;
import dinner.dev.ventus.network.WindSyncState;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The wind compass HUD: a small dial at the top centre of the screen whose needle points downwind,
 * with the world's N/E/S/W marking the rim so you can read the bearing, plus the speed and heading
 * underneath.
 *
 * <p>Only ever shows data that arrived from the server via {@link WindSyncState}; with no Ventus on
 * the server, or before the first packet, the HUD stays hidden.
 */
public final class WindHud {
    private WindHud() {
    }

    private static final String[] HEADINGS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] RIM_LABELS = {"N", "E", "S", "W"};
    /** World directions for N, E, S, W in (x, z): north is -Z, east is +X. */
    private static final double[] RIM_X = {0.0, 1.0, 0.0, -1.0};
    private static final double[] RIM_Z = {-1.0, 0.0, 1.0, 0.0};

    private static final int CENTER_Y = 22;
    private static final int RADIUS = 16;
    private static final int LETTER_RADIUS = 10;
    private static final int NEEDLE_LENGTH = 12;

    private static final int DIAL_FILL = 0x96090D13;
    private static final int RIM_COLOR = 0xFF2C3743;
    private static final int LETTER_COLOR = 0xFFD5DDE8;
    private static final int NORTH_COLOR = 0xFFFF7B74;
    private static final int LABEL_COLOR = 0xFFC0C9D6;

    public static void render(final RenderGuiEvent.Post event) {
        if (!VentusClient.compassVisible() || !WindSyncState.isValid()) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        final float windX = WindSyncState.x();
        final float windZ = WindSyncState.z();
        final double speed = Math.sqrt((double) windX * windX + (double) windZ * windZ);

        final GuiGraphics graphics = event.getGuiGraphics();
        final int centerX = graphics.guiWidth() / 2;

        // Camera basis in the XZ plane: "look" is where the player faces, "right" is to their right.
        final double yaw = Math.toRadians(minecraft.player.getYRot());
        final double lookX = -Math.sin(yaw);
        final double lookZ = Math.cos(yaw);
        final double rightX = -Math.cos(yaw);
        final double rightZ = -Math.sin(yaw);

        fillCircle(graphics, centerX, CENTER_Y, RADIUS, DIAL_FILL);
        fillRing(graphics, centerX, CENTER_Y, RADIUS, 2, RIM_COLOR);
        drawRimLabels(graphics, minecraft, centerX, lookX, lookZ, rightX, rightZ);

        if (speed > 1.0e-4) {
            // Angle of the wind in camera space: 0 = straight ahead (needle points up),
            // positive = to the right (needle points right).
            final double across = windX * rightX + windZ * rightZ;
            final double along = windX * lookX + windZ * lookZ;
            drawNeedle(graphics, centerX, (float) Math.atan2(across, along), speedColor(speed));
        }

        final int color = speedColor(speed);
        graphics.drawCenteredString(minecraft.font,
                String.format(Locale.ROOT, "%.1f m/s", speed),
                centerX, CENTER_Y + RADIUS + 5, color);
        graphics.drawCenteredString(minecraft.font,
                speed > 1.0e-4 ? headingLabel(windX, windZ) : "CALM",
                centerX, CENTER_Y + RADIUS + 15, LABEL_COLOR);
    }

    /** The rim's world cardinal letters, positioned by their camera-relative direction. */
    private static void drawRimLabels(final GuiGraphics graphics, final Minecraft minecraft,
            final int centerX, final double lookX, final double lookZ,
            final double rightX, final double rightZ) {
        for (int i = 0; i < RIM_LABELS.length; i++) {
            final double along = RIM_X[i] * lookX + RIM_Z[i] * lookZ;
            final double across = RIM_X[i] * rightX + RIM_Z[i] * rightZ;
            final int x = (int) Math.round(centerX + LETTER_RADIUS * across);
            final int y = (int) Math.round(CENTER_Y - LETTER_RADIUS * along);
            graphics.drawCenteredString(minecraft.font, RIM_LABELS[i], x, y - 4,
                    i == 0 ? NORTH_COLOR : LETTER_COLOR);
        }
    }

    private static void drawNeedle(final GuiGraphics graphics, final int centerX, final float angle,
            final int color) {
        final int tailColor = (color & 0x00FFFFFF) | 0x70000000;

        graphics.pose().pushPose();
        graphics.pose().translate(centerX, CENTER_Y, 0.0);
        graphics.pose().mulPose(Axis.ZP.rotation(angle));
        graphics.fill(-1, -NEEDLE_LENGTH, 1, -1, color);
        graphics.fill(-1, 1, 1, 5, tailColor);
        graphics.pose().popPose();

        graphics.fill(centerX - 1, CENTER_Y - 1, centerX + 1, CENTER_Y + 1, 0xFFE8EEF6);
    }

    /**
     * Compass bearing (0 = north, 90 = east) of the direction the wind is blowing <em>towards</em>,
     * e.g. {@code 090° E} for a wind heading east.
     */
    private static String headingLabel(final float windX, final float windZ) {
        final double degrees = (Math.toDegrees(Math.atan2(windX, -windZ)) + 360.0) % 360.0;
        final int rounded = (int) Math.round(degrees) % 360;
        final int index = (int) Math.round(degrees / 45.0) % HEADINGS.length;
        return String.format(Locale.ROOT, "%03d\u00B0 %s", rounded, HEADINGS[index]);
    }

    private static int speedColor(final double speed) {
        if (speed < 1.0) {
            return 0xFF8C96A6;
        }
        if (speed < 4.0) {
            return 0xFF5FD9C9;
        }
        if (speed < 8.0) {
            return 0xFFF2C14E;
        }
        if (speed < 16.0) {
            return 0xFFF2792E;
        }
        return 0xFFF0483C;
    }

    private static void fillCircle(final GuiGraphics graphics, final int centerX, final int centerY,
            final int radius, final int color) {
        final int radiusSquared = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            final int half = (int) Math.floor(Math.sqrt(radiusSquared - dy * dy));
            graphics.fill(centerX - half, centerY + dy, centerX + half + 1, centerY + dy + 1, color);
        }
    }

    private static void fillRing(final GuiGraphics graphics, final int centerX, final int centerY,
            final int radius, final int thickness, final int color) {
        final int radiusSquared = radius * radius;
        final int inner = radius - thickness;
        final int innerSquared = inner * inner;
        for (int dy = -radius; dy <= radius; dy++) {
            final int outerHalf = (int) Math.ceil(Math.sqrt(radiusSquared - dy * dy));
            final int innerHalf = (dy * dy < innerSquared)
                    ? (int) Math.floor(Math.sqrt(innerSquared - dy * dy))
                    : 0;
            if (outerHalf <= innerHalf) {
                continue;
            }
            graphics.fill(centerX - outerHalf, centerY + dy, centerX - innerHalf, centerY + dy + 1, color);
            graphics.fill(centerX + innerHalf, centerY + dy, centerX + outerHalf, centerY + dy + 1, color);
        }
    }
}
