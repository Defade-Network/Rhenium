package net.defade.rhenium.servers;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.redis.RedisGameServer;
import net.defade.rhenium.redis.RedisMiniGameInstance;
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
    private static final int MAX_WAIT_TIME = 15000; // If the player is not moved within 15 seconds, cancel the request

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

                    if (channel.equals(RedisConstants.CHANNEL_PLAYER_SERVER_JOIN_REQUEST)) {
                        String[] parts = message.split(",");
                        String playerUUID = parts[0];
                        String requestedServerTemplateName = parts.length == 1 ? "hub" : parts[1];

                        // Find if the player was previously in a mini-game instance and if the mini-game instance is still running/requires players to rejoin
                        String miniGameInstanceId = rhenium.getJedisPool().hget(RedisConstants.PLAYERS_LAST_MINI_GAME_INSTANCE_KEY, playerUUID);
                        if (miniGameInstanceId != null) {
                            RedisMiniGameInstance miniGameInstance = serverManager.getRedisMiniGameInstance(miniGameInstanceId);
                            if (miniGameInstance != null && miniGameInstance.requirePlayingPlayersToRejoin()) {
                                sendPlayerToMiniGameInstance(playerUUID, miniGameInstance);
                                return;
                            }
                        }

                        movePlayerToServerTemplate(playerUUID, serverTemplateManager.getServerTemplate(requestedServerTemplateName));
                        return;
                    }

                    String[] parts = message.split(",");
                    String playerUUID = parts[0];
                    String serverTemplateName = parts[1];

                    ServerTemplate serverTemplate = serverTemplateManager.getServerTemplate(serverTemplateName);
                    movePlayerToServerTemplate(playerUUID, serverTemplate);
                }
            }, RedisConstants.CHANNEL_PLAYER_SERVER_JOIN_REQUEST, RedisConstants.CHANNEL_PLAYER_MOVE_REQUEST);
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
            RedisMiniGameInstance targetMiniGameInstance = findBestMiniGameInstance(serverTemplate);
            if (targetMiniGameInstance == null) {
                return false;
            }

            rhenium.getJedisPool().publish(RedisConstants.CHANNEL_PLAYER_MOVE_PROXY,
                    uuid + "," + targetMiniGameInstance.getServerId() + "," + targetMiniGameInstance.getMiniGameInstanceId().toString());
            return true;
        });
    }

    public void sendPlayerToMiniGameInstance(String playerUUID, RedisMiniGameInstance miniGameInstance) {
        rhenium.getJedisPool().publish(RedisConstants.CHANNEL_PLAYER_MOVE_PROXY,
            playerUUID + "," + miniGameInstance.getServerId() + "," + miniGameInstance.getMiniGameInstanceId().toString());
    }

    private RedisMiniGameInstance findBestMiniGameInstance(ServerTemplate serverTemplate) {
        if (serverTemplate == null) return null;

        RedisMiniGameInstance bestMiniGameInstance = null;
        int highestPlayers = -1;

        for (RedisMiniGameInstance miniGameInstances : serverManager.getAllRedisMiniGameInstances(serverTemplate)) {
            if (!miniGameInstances.isAcceptingPlayers()) continue;

            RedisGameServer gameServer = serverManager.getRedisGameServer(miniGameInstances.getServerId());
            if (gameServer.isScheduledForStop()) continue;

            if (miniGameInstances.getPlayerCount() < miniGameInstances.getMaxPlayers() && miniGameInstances.getPlayerCount() > highestPlayers) {
                bestMiniGameInstance = miniGameInstances;
                highestPlayers = miniGameInstances.getPlayerCount();
            }
        }

        return bestMiniGameInstance;
    }

    private void movePlayerToServerTemplate(String playerUUID, ServerTemplate serverTemplate) {
        if (serverTemplate == null) {
            LOGGER.warn("Failed to move player {} to a server, the server template is null.", playerUUID); // TODO: disonnect the player
            return;
        }

        RedisMiniGameInstance targetMiniGameInstance = findBestMiniGameInstance(serverTemplate);

        if (targetMiniGameInstance == null) {
            playerServerRequests.put(playerUUID, new ServerMoveRequest(serverTemplate.getTemplateName(), System.currentTimeMillis())); // Store the request for later retry
        } else {
            rhenium.getJedisPool().publish(RedisConstants.CHANNEL_PLAYER_MOVE_PROXY,
                playerUUID + "," + targetMiniGameInstance.getServerId() + "," + targetMiniGameInstance.getMiniGameInstanceId().toString());
        }
    }

    private record ServerMoveRequest(String serverTemplateName, long time) { }
}
