package dinner.dev.ventus.network;

import dinner.dev.ventus.Ventus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The wind at one player's eye position, sent from the server to the client every tick so the client
 * HUD can show it. The field itself is server-owned (it is a server config plus server-level terrain
 * queries), so the client only ever displays a copy.
 *
 * <p>Registered as an <em>optional</em> payload (see {@link VentusNetwork}), so a client without
 * Ventus simply ignores it.
 */
public record WindSyncPayload(float x, float y, float z) implements CustomPacketPayload {

    public static final Type<WindSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Ventus.MODID, "wind_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WindSyncPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeFloat(payload.x());
                buffer.writeFloat(payload.y());
                buffer.writeFloat(payload.z());
            },
            buffer -> new WindSyncPayload(buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
