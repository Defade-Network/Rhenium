package net.defade.rhenium.servers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.defade.rhenium.Rhenium;
import net.defade.rhenium.config.ServerTemplate;
import net.defade.rhenium.servers.instances.MiniGameInstance;
import net.defade.rhenium.servers.instances.ServerInstance;
import net.defade.rhenium.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerServerDispatcher implements HttpHandler {
    private static final Logger LOGGER = LogManager.getLogger(PlayerServerDispatcher.class);
    private static final int MAX_WAIT_TIME = 15000; // If the player is not moved within 15 seconds, cancel the request

    private final Rhenium rhenium;
    private final ServerManager serverManager;

    private final Map<String, MiniGameInstanceHolder> playersRequiredToRejoin = new HashMap<>();
    private final Map<String, ServerMoveRequest> playerServerRequests = new HashMap<>();

    public PlayerServerDispatcher(Rhenium rhenium, ServerManager serverManager) {
        this.rhenium = rhenium;
        this.serverManager = serverManager;

        rhenium.getRestServer().registerEndpoint("/player-dispatcher", this);
    }

    public void checkRequests() {
        playerServerRequests.entrySet().removeIf(entry -> {
            String uuid = entry.getKey();
            ServerMoveRequest request = entry.getValue();

            if (System.currentTimeMillis() - request.time > MAX_WAIT_TIME) {
                LOGGER.warn("Failed to move player {} to server {}.", uuid, request.serverTemplateName);
                return true;
            }

            ServerTemplate serverTemplate = rhenium.getRheniumConfig().getTemplateByName(request.serverTemplateName);
            if (serverTemplate == null) {
                LOGGER.warn("Failed to move player {} to server {}, the server template is null.", uuid, request.serverTemplateName);
                return true;
            }

            MiniGameInstance targetMiniGameInstance = findBestMiniGameInstance(serverTemplate);
            if (targetMiniGameInstance == null) {
                return false;
            }

            sendPlayerToMiniGameInstance(uuid, targetMiniGameInstance);
            return true;
        });
    }

    public void sendPlayerToMiniGameInstance(String playerUUID, MiniGameInstance miniGameInstance) {
        if (miniGameInstance == null) {
            LOGGER.warn("Failed to move player {} to a server, the mini-game instance is null.", playerUUID); // TODO: disonnect the player
            return;
        }

        String serverId = miniGameInstance.getServerId();
        String miniGameId = miniGameInstance.getMiniGameInstanceId().toString();

        Utils.sendHTTPRequestToVelocity(
            rhenium,
            "/servers/player-move",
            "POST",
            "{\"player-uuid\":\"" + playerUUID + "\",\"server-id\":\"" + serverId + "\",\"mini-game-instance\":\"" + miniGameId + "\"}"
        ).exceptionally(throwable -> {
            LOGGER.error("Failed to send player {} to server {}.", playerUUID, serverId, throwable);
            return null;
        });
    }

    private MiniGameInstance findBestMiniGameInstance(ServerTemplate serverTemplate) {
        if (serverTemplate == null) return null;

        MiniGameInstance bestMiniGameInstance = null;
        int highestPlayers = -1;

        for (ServerInstance serverInstance : serverManager.getServerInstances(serverTemplate)) {
            if (serverInstance.isScheduledForDeletion() || serverInstance.getOnlinePlayers() >= serverTemplate.maxPlayers()) continue;

            for (MiniGameInstance miniGameInstance : serverInstance.getMiniGameInstances().values()) {
                if (!miniGameInstance.isAcceptingPlayers()) continue;

                if (miniGameInstance.getOnlinePlayers() < miniGameInstance.getMaxPlayers() && miniGameInstance.getOnlinePlayers() > highestPlayers) {
                    bestMiniGameInstance = miniGameInstance;
                    highestPlayers = miniGameInstance.getOnlinePlayers();
                }
            }
        }

        return bestMiniGameInstance;
    }

    private void movePlayerToServerTemplate(String playerUUID, ServerTemplate serverTemplate) {
        if (serverTemplate == null) {
            LOGGER.warn("Failed to move player {} to a server, the server template is null.", playerUUID); // TODO: disconnect the player
            return;
        }

        MiniGameInstance targetMiniGameInstance = findBestMiniGameInstance(serverTemplate);

        if (targetMiniGameInstance == null) {
            playerServerRequests.put(playerUUID, new ServerMoveRequest(serverTemplate.templateName(), System.currentTimeMillis())); // Store the request for later retry
            LOGGER.info("Player {} is waiting for a server to be available.", playerUUID);
        } else {
            sendPlayerToMiniGameInstance(playerUUID, targetMiniGameInstance);
            LOGGER.info("Player {} moved to server {}.", playerUUID, targetMiniGameInstance.getServerId());
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes())).getAsJsonObject();
        switch (path) {
            case "/player-dispatcher/player-join" -> {
                String playerUUID = body.get("player-uuid").getAsString();

                MiniGameInstanceHolder playerMiniGameInstance = playersRequiredToRejoin.get(playerUUID);
                if (playerMiniGameInstance != null) {
                    ServerInstance serverInstance = serverManager.getServerInstance(playerMiniGameInstance.serverId);
                    MiniGameInstance miniGameInstance = serverInstance != null ? serverInstance.getMiniGameInstances().get(playerMiniGameInstance.miniGameInstanceId) : null;
                    if (miniGameInstance != null && miniGameInstance.requirePlayingPlayersToRejoin()) {
                        sendPlayerToMiniGameInstance(playerUUID, serverInstance.getMiniGameInstances().get(playerMiniGameInstance.miniGameInstanceId));
                        exchange.sendResponseHeaders(200, 0);
                        return;
                    }
                }

                String requestedServerTemplateName = body.has("server") ? body.get("server").getAsString() : "hub"; // TODO: make the default server configurable
                movePlayerToServerTemplate(playerUUID, rhenium.getRheniumConfig().getTemplateByName(requestedServerTemplateName));

                exchange.sendResponseHeaders(200, 0);
            }
            case "/player-dispatcher/player-move" -> {
                String playerUUID = body.get("player-uuid").getAsString();
                String serverTemplateName = body.get("server").getAsString();

                movePlayerToServerTemplate(playerUUID, rhenium.getRheniumConfig().getTemplateByName(serverTemplateName));
                playersRequiredToRejoin.remove(playerUUID);
                exchange.sendResponseHeaders(200, 0);
            }
            case "/player-dispatcher/update-players-required-to-rejoin" -> {
                MiniGameInstanceHolder miniGameInstanceHolder = new MiniGameInstanceHolder(
                    body.get("server-id").getAsString(),
                    UUID.fromString(body.get("mini-game-instance-id").getAsString())
                );
                List<String> players = body.getAsJsonArray("players").asList().stream().map(JsonElement::getAsString).toList();

                players.forEach(player -> playersRequiredToRejoin.put(player, miniGameInstanceHolder));
                exchange.sendResponseHeaders(200, 0);
            }
            default -> exchange.sendResponseHeaders(404, 0);
        }
    }

    private record MiniGameInstanceHolder(String serverId, UUID miniGameInstanceId) { }
    private record ServerMoveRequest(String serverTemplateName, long time) { }
}
