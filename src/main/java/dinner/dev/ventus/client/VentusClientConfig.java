package dinner.dev.ventus.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The client config (config/ventus-client.toml): where the wind line sits. Registered only from
 * {@code Ventus}'s constructor behind a {@code Dist.CLIENT} guard, so a dedicated server never
 * builds it. The accessor falls back to its default before the file loads.
 */
public final class VentusClientConfig {
    private VentusClientConfig() {
    }

    /** Which screen corner the wind line is pinned to. */
    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.EnumValue<Corner> CORNER;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("windCompass");
        CORNER = builder.comment(
                        "Which screen corner the wind line sits in. Top left is the only one free",
                        "of vanilla HUD: status effects sit top right, the chat bottom left, and",
                        "boss bars run along the top centre. The line also hides itself while the",
                        "F3 debug screen is open.")
                .defineEnum("corner", Corner.TOP_LEFT);
        builder.pop();
        SPEC = builder.build();
    }

    /** The corner to pin to, or {@link Corner#TOP_LEFT} before the config has loaded. */
    public static Corner corner() {
        return SPEC.isLoaded() ? CORNER.get() : Corner.TOP_LEFT;
    }
}
