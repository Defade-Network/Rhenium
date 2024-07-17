package net.defade.rhenium.servers;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.redis.RedisGameServer;
import net.defade.rhenium.servers.template.ServerTemplate;
import net.defade.rhenium.servers.template.ServerTemplateManager;
import net.defade.rhenium.utils.RedisConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redis.clients.jedis.JedisPubSub;
import java.util.HashMap;
import java.util.Map;

public class PlayerServerDispatcher {
    private static final Logger LOGGER = LogManager.getLogger(PlayerServerDispatcher.class);
    private static final int MAX_WAIT_TIME = 15000; // If the player is not moved within 5 seconds, cancel the request

    private final Rhenium rhenium;
    private final ServerTemplateManager serverTemplateManager;
    private final ServerManager serverManager;

    private final Map<String, ServerMoveRequest> playerServerRequests = new HashMap<>();

    public PlayerServerDispatcher(Rhenium rhenium, ServerManager serverManager) {
        this.rhenium = rhenium;
        this.serverTemplateManager = rhenium.getServerTemplateManager();
        this.serverManager = serverManager;
    }

    public void start() {
        Thread.ofVirtual().start(() -> {
            rhenium.getJedisPool().subscribe(new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    if (!rhenium.isLeader()) return;

                    String[] parts = message.split(",");
                    String uuid = parts[0];
                    String serverTemplateName = parts[1];

                    ServerTemplate serverTemplate = serverTemplateManager.getServerTemplate(serverTemplateName);
                    RedisGameServer targetServer = findBestServer(serverTemplate);

                    if (targetServer == null) {
                        playerServerRequests.put(uuid, new ServerMoveRequest(serverTemplateName, System.currentTimeMillis())); // Store the request for later retry
                    } else {
                        rhenium.getJedisPool().publish(RedisConstants.CHANNEL_PLAYER_MOVE_PROXY, uuid + "," + targetServer.getServerId());
                    }
                }
            }, RedisConstants.CHANNEL_PLAYER_MOVE_REQUEST);
        });
    }

    public void checkRequests() {
        playerServerRequests.entrySet().removeIf(entry -> {
            String uuid = entry.getKey();
            ServerMoveRequest request = entry.getValue();

            if (System.currentTimeMillis() - request.time > MAX_WAIT_TIME) {
                LOGGER.warn("Failed to move player {} to server {}.", uuid, request.serverTemplateName);
                return true;
            }

            ServerTemplate serverTemplate = serverTemplateManager.getServerTemplate(request.serverTemplateName);
            RedisGameServer targetServer = findBestServer(serverTemplate);
            if (targetServer == null) {
                return false;
            }

            rhenium.getJedisPool().publish(RedisConstants.CHANNEL_PLAYER_MOVE_PROXY, uuid + "," + targetServer.getServerId());
            return true;
        });
    }

    private RedisGameServer findBestServer(ServerTemplate serverTemplate) {
        if (serverTemplate == null) return null;

        RedisGameServer bestServer = null;
        int highestPlayers = -1;

        for (RedisGameServer gameServer : serverManager.getAllRedisGameServers(serverTemplate)) {
            if (gameServer.isScheduledForStop() || !gameServer.hasStarted()) continue;

            if (gameServer.getPlayerCount() < gameServer.getMaxPlayers() && gameServer.getPlayerCount() > highestPlayers) {
                bestServer = gameServer;
                highestPlayers = gameServer.getPlayerCount();
            }
        }

        return bestServer;
    }

    private record ServerMoveRequest(String serverTemplateName, long time) { }
}
