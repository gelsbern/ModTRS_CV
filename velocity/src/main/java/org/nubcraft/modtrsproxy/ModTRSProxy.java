package org.nubcraft.modtrsproxy;

import com.google.common.io.ByteArrayDataInput;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.UUID;

@Plugin(
        id = "modtrsproxy",
        name = "ModTRSProxy",
        version = "3.0.0-SNAPSHOT",
        description = "NubCraft ModTRS cross-server connection bridge",
        authors = {"Nubcraft"}
)
public final class ModTRSProxy {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("nubcraft", "modtrs");

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public ModTRSProxy(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("ModTRSProxy registered channel {}", CHANNEL.getId());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection source)) {
            logger.warn("Rejected ModTRS proxy message from non-server source");
            return;
        }

        try {
            ByteArrayDataInput input = event.dataAsDataStream();
            String action = input.readUTF();
            if (!"CONNECT".equals(action)) {
                logger.warn("Rejected unknown ModTRS proxy action {}", action);
                return;
            }

            UUID playerId = UUID.fromString(input.readUTF());
            String targetName = input.readUTF();

            Player player = source.getPlayer();
            if (!player.getUniqueId().equals(playerId)) {
                logger.warn(
                        "Rejected ModTRS proxy request for {} from backend connection owned by {}",
                        playerId,
                        player.getUniqueId()
                );
                return;
            }

            RegisteredServer target = server.getServer(targetName).orElse(null);
            if (target == null) {
                logger.warn(
                        "ModTRS requested unknown backend '{}' for {}",
                        targetName,
                        player.getUsername()
                );
                return;
            }

            player.createConnectionRequest(target)
                    .connect()
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            logger.warn(
                                    "Could not connect {} to {} for ModTRS",
                                    player.getUsername(),
                                    targetName,
                                    error
                            );
                            return;
                        }

                        logger.info(
                                "ModTRS moved {} from {} to {} ({})",
                                player.getUsername(),
                                source.getServerInfo().getName(),
                                targetName,
                                result.getStatus()
                        );
                    });

        } catch (Exception exception) {
            logger.warn("Could not process ModTRS proxy message", exception);
        }
    }
}
