package dinner.dev.ventus.client;

import com.mojang.blaze3d.platform.InputConstants;
import dinner.dev.ventus.network.WindSyncState;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Everything Ventus does on the physical client: the keybind and the HUD.
 *
 * <p>Loaded only from {@code Ventus}'s constructor behind a {@code Dist.CLIENT} guard, so a dedicated
 * server never touches it (the class references {@code Minecraft} types throughout).
 */
public final class VentusClient {
    private VentusClient() {
    }

    /** Default F8; rebindable in Options → Controls → Ventus. */
    private static final String KEY_DESCRIPTION = "key.ventus.toggle_compass";
    private static final String KEY_CATEGORY = "key.categories.ventus";

    private static KeyMapping toggleCompass;

    private static boolean compassVisible = true;

    /** Registers the keybind, the HUD and the input handling. Client-only. */
    public static void init(final IEventBus modEventBus) {
        modEventBus.addListener(RegisterKeyMappingsEvent.class, VentusClient::registerKeyMapping);

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, VentusClient::clientTick);
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, WindHud::render);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
            WindSyncState.invalidate();
            WindHud.reset();
        });
    }

    /** Whether the compass is currently showing (toggled with {@link #toggleCompass}). */
    public static boolean compassVisible() {
        return compassVisible;
    }

    private static void registerKeyMapping(final RegisterKeyMappingsEvent event) {
        toggleCompass = new KeyMapping(KEY_DESCRIPTION, InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8, KEY_CATEGORY);
        event.register(toggleCompass);
    }

    private static void clientTick(final ClientTickEvent.Post event) {
        if (toggleCompass == null) {
            return;
        }
        // consumeClick() so a single press toggles exactly once even if the frame lags.
        while (toggleCompass.consumeClick()) {
            compassVisible = !compassVisible;
        }
    }
}
