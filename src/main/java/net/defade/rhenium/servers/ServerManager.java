package net.defade.rhenium.servers;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.redis.RedisGameServer;
import net.defade.rhenium.redis.RedisMiniGameInstance;
import net.defade.rhenium.redis.RedisRheniumInstance;
import net.defade.rhenium.servers.template.ServerTemplate;
import net.defade.rhenium.servers.template.ServerTemplateManager;
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
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ServerManager {
    private static final Logger LOGGER = LogManager.getLogger(ServerManager.class);
    private static final int MIN_SERVERS = 2;

    private final Rhenium rhenium;
    private final ServerTemplateManager serverTemplateManager;

    private final List<Integer> usedPorts = new ArrayList<>();
    /*
    This map will store all the creation requests that will be made so that if they take a bit too long to be processed,
    we won't ask for a second server.
    Key: The server template name, Value: A list of pairs of server creation requests

    Only used when the instance is the leader.
     */
    private final Map<String, GameServer> gameServers = new ConcurrentHashMap<>();

    // Values used when the instance is the leader
    private final PlayerServerDispatcher playerServerDispatcher;
    private final Map<String, List<ServerCreationRequest>> serverCreationRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> stopRequests = new ConcurrentHashMap<>();

    public ServerManager(Rhenium rhenium) {
        this.rhenium = rhenium;
        this.serverTemplateManager = rhenium.getServerTemplateManager();
        this.playerServerDispatcher = new PlayerServerDispatcher(rhenium, this);
    }

    public void start() {
        listenForServerEvents();
        playerServerDispatcher.start();

        rhenium.getTimer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateServerInformation();
                checkOutdatedServers();

                if (rhenium.isLeader()) {
                    checkNewNeededServers();
                    downscaleServers();
                    checkOrphanedServers();
                    playerServerDispatcher.checkRequests();
                }
            }
        }, 0, 2 * 1000);
    }

    public void stop() {
        LOGGER.info("Stopping the servers...");

        try {
            CompletableFuture.allOf(gameServers.values().stream()
                    .map(gameServer -> gameServer.stop(false))
                    .toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS); // Wait for 20s. If the servers are still running, we will forcefully stop them
        } catch (InterruptedException | ExecutionException | TimeoutException exception) {
            throw new RuntimeException(exception);
        }

        CompletableFuture.allOf(gameServers.values().stream()
                .map(gameServer -> gameServer.stop(true))
                .toArray(CompletableFuture[]::new)).join(); // Wait for the servers to stop
    }

    public List<RedisRheniumInstance> getRheniumInstances() {
        List<RedisRheniumInstance> rheniumInstances = new ArrayList<>();
        for (String rheniumKey : rhenium.getJedisPool().keys(RedisConstants.RHENIUM_CLIENT_KEY.apply("*"))) {
            rheniumInstances.add(new RedisRheniumInstance(rhenium.getJedisPool(), rheniumKey.substring(RedisConstants.RHENIUM_CLIENT_KEY.apply("").length())));
        }

        return rheniumInstances;
    }

    /**
     * Get a RedisGameServer from a server id.
     * @param serverId The server id
     * @return The RedisGameServer
     */
    public RedisGameServer getRedisGameServer(String serverId) {
        return new RedisGameServer(rhenium.getJedisPool(), serverId);
    }

    /**
     * Get all the game servers registered in Redis for a specific server template.
     * @param serverTemplate The server template to get the servers from
     * @return A list of RedisGameServer
     */
    public List<RedisGameServer> getAllRedisGameServers(ServerTemplate serverTemplate) {
        List<RedisGameServer> redisGameServers = new ArrayList<>();
        for (String serverKey : rhenium.getJedisPool().keys(RedisConstants.GAME_SERVER_KEY.apply(serverTemplate.getTemplateName() + "*"))) {
            redisGameServers.add(getRedisGameServer(serverKey.substring(RedisConstants.GAME_SERVER_KEY.apply("").length())));
        }

        return redisGameServers;
    }

    /**
     * Get all the game servers registered in Redis.
     * @return A list of RedisGameServer
     */
    public List<RedisGameServer> getAllRedisGameServers() {
        List<RedisGameServer> redisGameServers = new ArrayList<>();
        for (String serverKey : rhenium.getJedisPool().keys(RedisConstants.GAME_SERVER_KEY.apply("*"))) {
            redisGameServers.add(getRedisGameServer(serverKey.substring(RedisConstants.GAME_SERVER_KEY.apply("").length())));
        }

        return redisGameServers;
    }

    public RedisMiniGameInstance getRedisMiniGameInstance(String key) {
        String serverId = key.split(":")[2];
        UUID miniGameInstanceId = UUID.fromString(key.split(":")[3]);

        try {
            return new RedisMiniGameInstance(rhenium.getJedisPool(), serverId, miniGameInstanceId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Get all the mini-game instances registered in Redis for a specific server template.
     * @param serverTemplate The server template to get the servers from
     * @return A list of RedisMiniGameInstance
     */
    public List<RedisMiniGameInstance> getAllRedisMiniGameInstances(ServerTemplate serverTemplate) {
        return rhenium.getJedisPool().keys(RedisConstants.MINI_GAME_INSTANCE_KEY.apply(serverTemplate.getTemplateName() + "*", "*"))
            .stream().map(this::getRedisMiniGameInstance).toList();
    }

    /**
     * Register the Rhenium server information in Redis.
     */
    private void updateServerInformation() {
        String rheniumClientKey = RedisConstants.RHENIUM_CLIENT_KEY.apply(rhenium.getRheniumId());

        if (!rhenium.getJedisPool().exists(rheniumClientKey)) {
            // The client is not registered in Redis
            // This can either be a new client or the key has expired due to a network issue/lag. We must stop all servers and cleanly restart.
            Map<String, String> infos = Map.of(
                    RedisConstants.RHENIUM_CLIENT_PUBLIC_IP_ADDRESS, rhenium.getRheniumConfig().getPublicServerIp(),
                    RedisConstants.RHENIUM_CLIENT_AVAILABLE_POWER, String.valueOf(rhenium.getRheniumConfig().getAvailablePower()),
                    RedisConstants.RHENIUM_CLIENT_USED_POWER, "0"
            );

            rhenium.getJedisPool().hset(rheniumClientKey, infos);

            if (!gameServers.isEmpty()) stop();
        }

        rhenium.getJedisPool().pexpire(rheniumClientKey, RedisConstants.RHENIUM_CLIENT_TIMEOUT_MS); // Renew the client key expiration
    }

    private void listenForServerEvents() {
        Thread.ofVirtual().start(() -> {
            rhenium.getJedisPool().subscribe(new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    switch (channel) {
                        case RedisConstants.CHANNEL_GAME_SERVER_CREATE -> {
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
                        case RedisConstants.CHANNEL_GAME_SERVER_CREATION_ACK -> {
                            String[] parts = message.split(",");
                            String templateName = parts[0];
                            String serverId = parts[1];

                            if (serverCreationRequests.containsKey(templateName)) {
                                serverCreationRequests.get(templateName).removeIf(request -> request.serverId().equals(serverId));
                            }
                        }
                        case RedisConstants.CHANNEL_GAME_SERVER_STOP -> {
                            GameServer gameServer = gameServers.get(message);
                            if (gameServer != null) {
                                gameServer.stop(false);
                            }
                        }
                    }
                }
            }, RedisConstants.CHANNEL_GAME_SERVER_CREATE, RedisConstants.CHANNEL_GAME_SERVER_CREATION_ACK, RedisConstants.CHANNEL_GAME_SERVER_STOP);
        });
    }

    private void checkOutdatedServers() {
        for (GameServer gameServer : gameServers.values()) {
            if (!gameServer.isOutdated() && gameServer.getServerTemplate().isOutdated()) {
                // The server is outdated, we need to flag it for deletion
                gameServer.markOutdated();
                rhenium.getJedisPool().hset(RedisConstants.GAME_SERVER_KEY.apply(gameServer.getServerId()), RedisConstants.GAME_SERVER_OUTDATED, "true");
            }
        }
    }

    /**
     * This method will check if new servers are needed and schedule them if necessary.
     */
    private void checkNewNeededServers() {
        for (ServerTemplate serverTemplate : serverTemplateManager.getServerTemplates()) {
            List<RedisGameServer> gameServers = getAllRedisGameServers(serverTemplate);

            int availableServers = serverCreationRequests.getOrDefault(serverTemplate.getTemplateName(), List.of()).size();
            availableServers += (int) gameServers.stream()
                    .filter(gameServer -> !gameServer.isScheduledForStop())
                    .filter(gameServer -> gameServer.getPlayerCount() < gameServer.getMaxPlayers())
                    .count();

            while (availableServers < MIN_SERVERS) {
                // Before scheduling a server, try to check if a server that is scheduled for stopping can be started again
                // This is to avoid having to start a new server if we can reuse an old one

                boolean found = false;
                for (RedisGameServer gameServer : gameServers) {
                    if (gameServer.isScheduledForStop() && !gameServer.isOutdated()) {
                        gameServer.setScheduledForStop(false);
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
        for (RedisGameServer gameServer : getAllRedisGameServers()) {
            String serverId = gameServer.getServerId();

            if (stopRequests.containsKey(serverId)) {
                if (System.currentTimeMillis() - stopRequests.get(serverId) > 20 * 1000) {
                    stopServer(serverId);
                }

                continue;
            }

            if (gameServer.isScheduledForStop()) {
                // If there are no players online, we can delete the server
                if (gameServer.getPlayerCount() == 0) {
                    stopServer(gameServer.getServerId());
                    LOGGER.info("Stopping the server {}.", serverId);
                }
                continue;
            }

            if (gameServer.isOutdated()) {
                gameServer.setScheduledForStop(true);
                continue;
            }

            String rheniumInstance = gameServer.getRheniumInstance();
            if (!stopRequests.containsKey(serverId) && !rhenium.getJedisPool().exists(RedisConstants.RHENIUM_CLIENT_KEY.apply(rheniumInstance))) {
                rhenium.getJedisPool().publish(RedisConstants.CHANNEL_GAME_SERVER_STOP, serverId);
                Utils.fullyDeleteGameServer(rhenium.getJedisPool(), serverId); // Since the rhenium instance is dead, the key won't be cleaned by it
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
            List<RedisGameServer> gameServers = getAllRedisGameServers(serverTemplate);

            int runningServers = 0;
            int connectedPlayers = 0;
            for (RedisGameServer gameServer : gameServers) {
                if (gameServer.isScheduledForStop()) {
                    continue;
                }

                runningServers++;
                connectedPlayers += gameServer.getPlayerCount();
            }

            int serversNeeded = (int) Math.ceil((double) connectedPlayers / serverTemplate.getMaxPlayers()) + MIN_SERVERS;
            if (runningServers > serversNeeded) {
                // We have too many servers!
                int serversToRemove = runningServers - serversNeeded;

                // Find the servers with the least amount of players and flag them for deletion
                while (serversToRemove > 0) {
                    RedisGameServer serverToRemove = null;
                    int minPlayers = Integer.MAX_VALUE;
                    for (RedisGameServer gameServer : gameServers) {
                        if (gameServer.isScheduledForStop()) {
                            continue;
                        }

                        if (gameServer.getPlayerCount() < minPlayers) {
                            minPlayers = gameServer.getPlayerCount();
                            serverToRemove = gameServer;
                        }
                    }

                    if (serverToRemove != null) {
                        serverToRemove.setScheduledForStop(true);
                        serversToRemove--;
                        LOGGER.info("Too many servers running! Flagged the server {} for deletion.", serverToRemove.getServerId());
                    }
                }
            }
        }
    }

    /**
     * Send a server creation request to a Rhenium instance with enough power to handle the server.
     * @param serverTemplate The server template to create the server from
     */
    private void sendServerCreateRequest(ServerTemplate serverTemplate) {
        // Find a rhenium instance that can handle the server
        RedisRheniumInstance selectedRheniumInstance = null;
        for (RedisRheniumInstance rheniumInstance : getRheniumInstances()) {
            int usedPower = rheniumInstance.getUsedPower();
            usedPower += serverCreationRequests.getOrDefault(serverTemplate.getTemplateName(), List.of()).stream()
                    .filter(serverCreationRequest -> serverCreationRequest.rheniumId.equals(rheniumInstance.getRheniumId()))
                    .mapToInt(ServerCreationRequest::power).sum();

            if (rheniumInstance.getAvailablePower() - usedPower >= serverTemplate.getPower()) {
                selectedRheniumInstance = rheniumInstance;
                break;
            }
        }

        if (selectedRheniumInstance == null) {
            LOGGER.error("Failed to find a Rhenium instance that can handle the server {}.", serverTemplate.getTemplateName());
            return;
        }

        String serverId = serverTemplate.getTemplateName() + "-" + Utils.generateUniqueNetworkId(rhenium.getJedisPool(), 8);
        if (!serverCreationRequests.containsKey(serverTemplate.getTemplateName())) {
            serverCreationRequests.put(serverTemplate.getTemplateName(), new ArrayList<>());
        }

        serverCreationRequests.get(serverTemplate.getTemplateName()).add(new ServerCreationRequest(selectedRheniumInstance.getRheniumId(), serverTemplate.getPower(), serverId));
        rhenium.getJedisPool().publish(RedisConstants.CHANNEL_GAME_SERVER_CREATE, serverTemplate.getTemplateName() + "," + selectedRheniumInstance.getRheniumId() + "," + serverId);

        LOGGER.info("Sent a server creation request for the server {} to the Rhenium instance {}.", serverId, selectedRheniumInstance.getRheniumId());
    }

    private void stopServer(String serverId) {
        rhenium.getJedisPool().publish(RedisConstants.CHANNEL_GAME_SERVER_STOP, serverId);
        stopRequests.put(serverId, System.currentTimeMillis());
    }

    /**
     * Start a server from a server template on the current Rhenium instance.
     * @param serverTemplate The server template to create the server from
     * @param serverId The server id
     */
    private void startServer(ServerTemplate serverTemplate, String serverId) {
        int port = findUnusedPort();
        if (port == -1) {
            LOGGER.error("Failed to find an unused port for the server {}.", serverId);
            rhenium.getJedisPool().publish(RedisConstants.CHANNEL_GAME_SERVER_CREATION_ACK, serverTemplate.getTemplateName() + "," + serverId);
            return;
        }

        GameServer gameServer = new GameServer(rhenium, this, serverTemplate, serverId, port);
        gameServer.launchServer();
        LOGGER.info("Started the server {} on port {}.", serverId, port);
        rhenium.getJedisPool().publish(RedisConstants.CHANNEL_GAME_SERVER_CREATION_ACK, serverTemplate.getTemplateName() + "," + serverId);

        gameServers.put(serverId, gameServer);
    }

    /**
     * Unregister a game server from the server manager.
     * <p>
     * Warning: This method should only be called by GameServer.
     * @param gameServer The game server to unregister
     */
    protected void unregisterServer(GameServer gameServer) {
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
