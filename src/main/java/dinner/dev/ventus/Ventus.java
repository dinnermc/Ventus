package dinner.dev.ventus;

import com.mojang.logging.LogUtils;
import dinner.dev.ventus.client.VentusClient;
import dinner.dev.ventus.command.WindFieldCommand;
import dinner.dev.ventus.network.VentusNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(Ventus.MODID)
public class Ventus {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "ventus";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ventus(IEventBus modEventBus, ModContainer modContainer) {
        // Server-side config (config/ventus-server.toml): wind scale + per-dimension base wind.
        modContainer.registerConfig(ModConfig.Type.SERVER, VentusConfig.SPEC);

        // Sample the wind for every sub-level once per game tick, before physics runs.
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, WindSampler::tick);

        // Send each player the wind at their own position, for the client HUD.
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, WindHudSync::tick);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
                event -> WindHudSync.forget(event.getEntity()));

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> WindFieldCommand.register(event.getDispatcher()));

        // Registered on both sides: the server needs the payload to be able to send it.
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, VentusNetwork::register);

        // The keybind, the HUD and the packet consumer only exist on the physical client. Guarded
        // here so a dedicated server never links against Minecraft's client classes.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            VentusClient.init(modEventBus);
        }

        LOGGER.info("Ventus loaded");
    }
}
