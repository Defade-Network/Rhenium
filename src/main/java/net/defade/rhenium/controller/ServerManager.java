package net.defade.rhenium.controller;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.servers.GameServer;
import net.defade.rhenium.servers.ServerTemplate;
import net.defade.rhenium.servers.ServerTemplateManager;
import net.defade.rhenium.utils.RedisConstants;
import net.defade.rhenium.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ServerManager {
    private static final Logger LOGGER = LogManager.getLogger(ServerManager.class);
    private static final int MIN_SERVERS = 2;

    private final Rhenium rhenium;
    private final ServerTemplateManager serverTemplateManager;

    private String rheniumClientKey;
    private final List<Integer> usedPorts = new ArrayList<>();

    /*
    This map will store all the creation requests that will be made so that if they take a bit too long to be processed,
    we won't ask for a second server.
    Key: The server template name, Value: A list of pairs of server creation requests

    Only used when the instance is the leader.
     */
    private final Map<String, List<ServerCreationRequest>> serverCreationRequests = new ConcurrentHashMap<>();
    private final Map<String, GameServer> gameServers = new ConcurrentHashMap<>();

    public ServerManager(Rhenium rhenium) {
        this.rhenium = rhenium;
        this.serverTemplateManager = rhenium.getServerTemplateManager();
    }

    public void start() {
        this.rheniumClientKey = RedisConstants.RHENIUM_CLIENT_KEY.apply(rhenium.getRheniumId());
        listenForServerEvents();
        listenForPlayerMoveRequests();

        rhenium.getTimer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateServerInformation();
                checkOutdatedServers();

                if (rhenium.isLeader()) {
                    checkNewNeededServers();
                    downscaleServers();
                    checkOrphanedServers();
                }
            }
        }, 0, 2 * 1000);
    }

    public void stop() {
        LOGGER.info("Stopping the servers...");

        CompletableFuture.allOf(gameServers.values().stream()
                .map(GameServer::stop)
                .toArray(CompletableFuture[]::new)).join();
    }

    /**
     * Register the Rhenium server information in Redis.
     */
    private void updateServerInformation() {
        String key = RedisConstants.RHENIUM_CLIENT_KEY.apply(rhenium.getRheniumId());

        if (!rhenium.getJedisPool().exists(rheniumClientKey)) {
            // The client is not registered in Redis
            // This can either be a new client or the key has expired due to a network issue/lag. We must stop all servers and cleanly restart.
            Map<String, String> infos = Map.of(
                    RedisConstants.RHENIUM_PUBLIC_IP_ADDRESS, rhenium.getRheniumConfig().getPublicServerIp(),
                    RedisConstants.RHENIUM_AVAILABLE_POWER, String.valueOf(rhenium.getRheniumConfig().getAvailablePower()),
                    RedisConstants.RHENIUM_USED_POWER, "0"
            );

            rhenium.getJedisPool().hset(key, infos);

            gameServers.values().forEach(GameServer::stop); // Stop all the servers
        }

        rhenium.getJedisPool().pexpire(key, RedisConstants.RHENIUM_CLIENT_TIMEOUT); // Renew the client key expiration
    }

    private void listenForServerEvents() {
        Thread.ofVirtual().start(() -> {
            rhenium.getJedisPool().subscribe(new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    switch (channel) {
                        case RedisConstants.GAME_SERVER_CREATE_CHANNEL -> {
                            String[] parts = message.split(",");
                            String templateName = parts[0];
                            String rheniumInstance = parts[1];
                            String serverId = parts[2];

                            if (rhenium.getRheniumId().equals(rheniumInstance)) {
                                ServerTemplate serverTemplate = serverTemplateManager.getServerTemplate(templateName);
                                if (serverTemplate == null) {
                                    LOGGER.error("Failed to find the server template {}.", templateName);
                                    return;
                                }

                                startServer(serverTemplate, serverId);
                            }
                        }
                        case RedisConstants.GAME_SERVER_CREATE_ACKNOWLEDGE_CHANNEL -> {
                            String[] parts = message.split(",");
                            String templateName = parts[0];
                            String serverId = parts[1];

                            if (serverCreationRequests.containsKey(templateName)) {
                                serverCreationRequests.get(templateName).removeIf(request -> request.serverId().equals(serverId));
                            }
                        }
                    }
                }
            }, RedisConstants.GAME_SERVER_CREATE_CHANNEL, RedisConstants.GAME_SERVER_CREATE_ACKNOWLEDGE_CHANNEL);
        });
    }

    private void listenForPlayerMoveRequests() {
        Thread.ofVirtual().start(() -> {
            rhenium.getJedisPool().subscribe(new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    String[] parts = message.split(",");
                    String uuid = parts[0];
                    String serverName = parts[1];

                    // Find the server with the most players
                    GameServer targetServer = null;
                    int maxPlayers = 0;
                    for (GameServer gameServer : gameServers.values()) {
                        boolean canJoin = rhenium.getJedisPool().hget(RedisConstants.GAME_SERVER_KEY
                                .apply(gameServer.getServerId()), RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP).equals("true");
                        if (!canJoin) continue;

                        int playerCount = Integer.parseInt(rhenium.getJedisPool().hget(RedisConstants.GAME_SERVER_KEY
                                .apply(gameServer.getServerId()), RedisConstants.GAME_SERVER_PLAYERS_COUNT));
                        if (gameServer.getServerTemplate().getTemplateName().equals(serverName)
                                && playerCount < gameServer.getServerTemplate().getMaxPlayers()
                                && playerCount > maxPlayers) {
                            targetServer = gameServer;
                            maxPlayers = playerCount;
                        }
                    }

                    if (targetServer != null) {
                        rhenium.getJedisPool().publish(RedisConstants.PLAYER_SEND_TO_SERVER_PROXY_CHANNEL, uuid + "," + targetServer.getServerId());
                    }
                }
            }, RedisConstants.PLAYER_SEND_TO_SERVER_REQUEST_CHANNEL);
        });
    }

    private void checkOutdatedServers() {
        for (GameServer gameServer : gameServers.values()) {
            if (gameServer.getServerTemplate().isOutdated()) {
                // The server is outdated, we need to flag it for deletion
                String serverKey = RedisConstants.GAME_SERVER_KEY.apply(gameServer.getServerId());
                rhenium.getJedisPool().hset(serverKey, RedisConstants.GAME_SERVER_OUTDATED, "true");
            }
        }
    }

    /**
     * This method will check if new servers are needed and schedule them if necessary.
     */
    private void checkNewNeededServers() {
        for (ServerTemplate serverTemplate : serverTemplateManager.getServerTemplates()) {
            Set<String> serverKeys = rhenium.getJedisPool().keys(RedisConstants.GAME_SERVER_KEY.apply(serverTemplate.getTemplateName()) + "*");

            int availableServers = serverCreationRequests.getOrDefault(serverTemplate.getTemplateName(), List.of()).size();

            for (String serverKey : serverKeys) {
                if (rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP).equals("true")) {
                    continue;
                }

                if (Integer.parseInt(rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_PLAYERS_COUNT)) < serverTemplate.getMaxPlayers()) {
                    availableServers++;
                }
            }

            while (availableServers < MIN_SERVERS) {
                // Before scheduling a server, try to check if a server that is scheduled for stopping can be started again
                // This is to avoid having to start a new server if we can reuse an old one

                boolean found = false;
                for (String serverKey : serverKeys) {
                    if (rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP).equals("true") &&
                            rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_OUTDATED).equals("false")) {
                        rhenium.getJedisPool().hset(serverKey, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP, "false");
                        availableServers++;
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    sendServerCreateRequest(serverTemplate);
                    availableServers++;
                }
            }
        }
    }

    /**
     * This method will check if there are any servers that needs to be deleted, flagged for deletion or if their rhenium instance doesn't exist anymore.
     */
    private void checkOrphanedServers() {
        Set<String> serverKeys = rhenium.getJedisPool().keys(RedisConstants.GAME_SERVER_KEY.apply("*"));

        for (String serverKey : serverKeys) {
            String serverId = serverKey.substring(RedisConstants.GAME_SERVER_KEY.apply("").length());

            boolean scheduledForStop = rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP).equals("true");
            if (scheduledForStop) {
                // If there are no players online, we can delete the server
                if (Integer.parseInt(rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_PLAYERS_COUNT)) == 0) {
                    rhenium.getJedisPool().publish(RedisConstants.GAME_SERVER_STOP_CHANNEL, serverId);
                    LOGGER.info("Stopping the server {}.", serverId);
                }
                continue;
            }

            if (rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_OUTDATED).equals("true")) {
                rhenium.getJedisPool().hset(serverKey, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP, "true");
                continue;
            }

            String rheniumInstance = rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_RHENIUM_INSTANCE);
            if (!rhenium.getJedisPool().exists(RedisConstants.RHENIUM_CLIENT_KEY.apply(rheniumInstance))) {
                rhenium.getJedisPool().publish(RedisConstants.GAME_SERVER_STOP_CHANNEL, serverId);
                rhenium.getJedisPool().del(serverKey); // Since the rhenium instance is dead, the key won't be cleaned by it
                LOGGER.info("Found an orphan server: {}", serverId);
            }
        }

        // Also check for serverCreationRequests that the rhenium instance did not die
        for (Map.Entry<String, List<ServerCreationRequest>> creationRequests: serverCreationRequests.entrySet()) {
            for (ServerCreationRequest creationRequest : new ArrayList<>(creationRequests.getValue())) {
                if (!rhenium.getJedisPool().exists(RedisConstants.RHENIUM_CLIENT_KEY.apply(creationRequest.rheniumId))) {
                    LOGGER.info("Found an orphan server creation request: {} for rhenium instance {}.", creationRequest.serverId, creationRequest.rheniumId);
                    serverCreationRequests.get(creationRequests.getKey()).remove(creationRequest);
                }
            }
        }
    }

    /**
     * This method will flag servers for deletion if there are too many servers running compared to the demand.
     */
    private void downscaleServers() {
        for (ServerTemplate serverTemplate : serverTemplateManager.getServerTemplates()) {
            Set<String> serverKeys = rhenium.getJedisPool().keys(RedisConstants.GAME_SERVER_KEY.apply(serverTemplate.getTemplateName()) + "*");

            int runningServers = 0;
            int connectedPlayers = 0;
            for (String serverKey : serverKeys) {
                if (rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP).equals("true")) {
                    continue;
                }

                runningServers++;
                connectedPlayers += Integer.parseInt(rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_PLAYERS_COUNT));
            }

            int serversNeeded = (int) Math.ceil((double) connectedPlayers / serverTemplate.getMaxPlayers()) + MIN_SERVERS;
            if (runningServers > serversNeeded) {
                // We have too many servers!
                int serversToRemove = runningServers - serversNeeded;

                // Find the servers with the least amount of players and flag them for deletion
                while (serversToRemove > 0) {
                    String serverKeyToRemove = null;
                    int minPlayers = Integer.MAX_VALUE;
                    for (String serverKey : serverKeys) {
                        if (rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP).equals("true")) {
                            continue;
                        }

                        int players = Integer.parseInt(rhenium.getJedisPool().hget(serverKey, RedisConstants.GAME_SERVER_PLAYERS_COUNT));
                        if (players < minPlayers) {
                            minPlayers = players;
                            serverKeyToRemove = serverKey;
                        }
                    }

                    if (serverKeyToRemove != null) {
                        rhenium.getJedisPool().hset(serverKeyToRemove, RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP, "true");
                        serversToRemove--;
                        LOGGER.info("Too many servers running! Flagged the server {} for deletion.", serverKeyToRemove.substring(RedisConstants.GAME_SERVER_KEY.apply("").length()));
                    }
                }
            }
        }
    }

    /**
     * Send a server creation request to a Rhenium instance with enough power to handle the server.
     * @param serverTemplate The server template to create the server from
     */
    public void sendServerCreateRequest(ServerTemplate serverTemplate) {
        // Find a rhenium instance that can handle the server
        String rheniumInstance = null;
        Set<String> rheniumInstances = rhenium.getJedisPool().keys(RedisConstants.RHENIUM_CLIENT_KEY.apply("*"));
        for (String instance : rheniumInstances) {
            String instanceId = instance.substring(RedisConstants.RHENIUM_CLIENT_KEY.apply("").length());

            int availablePower = Integer.parseInt(rhenium.getJedisPool().hget(instance, RedisConstants.RHENIUM_AVAILABLE_POWER));
            int usedPower = Integer.parseInt(rhenium.getJedisPool().hget(instance, RedisConstants.RHENIUM_USED_POWER));
            usedPower += serverCreationRequests.getOrDefault(serverTemplate.getTemplateName(), List.of()).stream()
                    .filter(serverCreationRequest -> serverCreationRequest.rheniumId.equals(instanceId))
                    .mapToInt(ServerCreationRequest::power).sum();

            if (availablePower - usedPower >= serverTemplate.getPower()) {
                rheniumInstance = instanceId;
                break;
            }
        }

        if (rheniumInstance == null) {
            LOGGER.error("Failed to find a Rhenium instance that can handle the server {}.", serverTemplate.getTemplateName());
            return;
        }

        String serverId = serverTemplate.getTemplateName() + "-" + Utils.generateUniqueNetworkId(rhenium.getJedisPool(), 8);
        if (!serverCreationRequests.containsKey(serverTemplate.getTemplateName())) {
            serverCreationRequests.put(serverTemplate.getTemplateName(), new ArrayList<>());
        }

        serverCreationRequests.get(serverTemplate.getTemplateName()).add(new ServerCreationRequest(rheniumInstance, serverTemplate.getPower(), serverId));
        rhenium.getJedisPool().publish(RedisConstants.GAME_SERVER_CREATE_CHANNEL, serverTemplate.getTemplateName() + "," + rheniumInstance + "," + serverId);

        LOGGER.info("Sent a server creation request for the server {} to the Rhenium instance {}.", serverId, rheniumInstance);
    }

    /**
     * Start a server from a server template on the current Rhenium instance.
     * @param serverTemplate The server template to create the server from
     * @param serverId The server id
     */
    public void startServer(ServerTemplate serverTemplate, String serverId) {
        int port = findUnusedPort();
        if (port == -1) {
            LOGGER.error("Failed to find an unused port for the server {}.", serverId);
            rhenium.getJedisPool().publish(RedisConstants.GAME_SERVER_CREATE_ACKNOWLEDGE_CHANNEL, serverTemplate.getTemplateName() + "," + serverId);
            return;
        }

        GameServer gameServer = new GameServer(rhenium, this, serverTemplate, serverId, port);
        gameServer.launchServer();
        LOGGER.info("Started the server {} on port {}.", serverId, port);
        rhenium.getJedisPool().publish(RedisConstants.GAME_SERVER_CREATE_ACKNOWLEDGE_CHANNEL, serverTemplate.getTemplateName() + "," + serverId);

        gameServers.put(serverId, gameServer); // TODO delete
    }

    /**
     * Unregister a game server from the server manager.
     * <p>
     * Warning: This method should only be called by GameServer.
     * @param gameServer The game server to unregister
     */
    public void unregisterServer(GameServer gameServer) {
        gameServers.remove(gameServer.getServerId());
        usedPorts.remove((Object) gameServer.getPort()); // Cast to Object to avoid calling the remove(int index) method
    }

    /**
     * Find an unused port
     *
     * @return An unused port or -1 if no port is available
     */
    private int findUnusedPort() {
        for (int port = rhenium.getRheniumConfig().getMinServersPort(); port <= rhenium.getRheniumConfig().getMaxServersPort(); port++) {
            try {
                ServerSocket serverSocket = new ServerSocket(port);
                serverSocket.close();

                if (!usedPorts.contains(port)) {
                    usedPorts.add(port);
                    return port;
                }
            } catch (IOException ignored) { /* Port is in use */ }
        }

        return -1;
    }

    private record ServerCreationRequest(String rheniumId, int power, String serverId) { }
}
