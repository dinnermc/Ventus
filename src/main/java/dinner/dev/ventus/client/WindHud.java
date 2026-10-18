package dinner.dev.ventus.client;

import dinner.dev.ventus.network.WindSyncState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The wind HUD: one line of text pinned to a screen corner — an eight position arrow for the way
 * the wind is blowing relative to the player, the speed in m/s, and the climb marker once the
 * vertical component is worth showing.
 *
 * <p>Only ever shows data that arrived from the server via {@link WindSyncState}; with no Ventus on
 * the server, or before the first packet, the HUD stays hidden. It also steps aside for the F3
 * debug screen, which owns the same corner.
 *
 * <p>The line is rebuilt only when the figures it is made of change (formatting lives in
 * {@link WindHudFormat}, cached here), so a still player costs one string comparison and a fill.
 */
public final class WindHud {
    private WindHud() {
    }

    private static final int MARGIN = 4;
    private static final int FONT_HEIGHT = 9;

    private static String line = "";
    private static int cachedSpeedTenths = Integer.MIN_VALUE;
    private static int cachedClimbTenths = Integer.MIN_VALUE;
    private static int cachedArrow = Integer.MIN_VALUE;

    public static void render(final RenderGuiEvent.Post event) {
        if (!VentusClient.compassVisible() || !WindSyncState.isValid()) {
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

        // Camera basis in the XZ plane: "look" is where the player faces, "right" is to their right.
        final double yaw = Math.toRadians(minecraft.player.getYRot());
        final double lookX = -Math.sin(yaw);
        final double lookZ = Math.cos(yaw);
        final double rightX = -Math.cos(yaw);
        final double rightZ = -Math.sin(yaw);
        final int arrowIndex = WindHudFormat.isCalm(speed) ? -1
                : WindHudFormat.arrowIndex(windX * rightX + windZ * rightZ,
                        windX * lookX + windZ * lookZ);
        rebuildIfChanged(speed, windY, arrowIndex);

        final GuiGraphics graphics = event.getGuiGraphics();
        final VentusClientConfig.Corner corner = VentusClientConfig.corner();
        final boolean toTheRight = corner == VentusClientConfig.Corner.TOP_RIGHT
                || corner == VentusClientConfig.Corner.BOTTOM_RIGHT;
        final boolean atTheBottom = corner == VentusClientConfig.Corner.BOTTOM_LEFT
                || corner == VentusClientConfig.Corner.BOTTOM_RIGHT;

        final int x = toTheRight ? graphics.guiWidth() - MARGIN - minecraft.font.width(line) : MARGIN;
        final int y = atTheBottom ? graphics.guiHeight() - MARGIN - FONT_HEIGHT : MARGIN;
        graphics.drawString(minecraft.font, line, x, y, WindHudFormat.speedColor(speed));
    }

    /** Forgets the cached line; called when the player leaves a server. */
    public static void reset() {
        cachedSpeedTenths = Integer.MIN_VALUE;
        cachedClimbTenths = Integer.MIN_VALUE;
        cachedArrow = Integer.MIN_VALUE;
        line = "";
    }

    /**
     * Rebuilds the line only when the figures it is made of have moved. The inputs are rounded the
     * way the string is, so a difference too small to show up in the text cannot trigger a format
     * call.
     */
    private static void rebuildIfChanged(final double speed, final double climb,
            final int arrowIndex) {
        final int speedTenths = (int) Math.round(speed * 10.0);
        final int climbTenths = (int) Math.round(climb * 10.0);
        if (speedTenths == cachedSpeedTenths && climbTenths == cachedClimbTenths
                && arrowIndex == cachedArrow) {
            return;
        }

        cachedSpeedTenths = speedTenths;
        cachedClimbTenths = climbTenths;
        cachedArrow = arrowIndex;
        line = WindHudFormat.line(speed, climb, arrowIndex);
    }
}
