package dinner.dev.ventus;

import com.mojang.logging.LogUtils;
import dinner.dev.ventus.command.WindFieldCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(Ventus.MODID)
public class Ventus {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "ventus";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ventus(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> WindFieldCommand.register(event.getDispatcher()));

        LOGGER.info("Ventus loaded");
    }
}
