package net.defade.rhenium.servers;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.config.RheniumConfig;
import net.defade.rhenium.controller.ServerManager;
import net.defade.rhenium.utils.RedisConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class GameServer {
    private static final Logger LOGGER = LogManager.getLogger(GameServer.class);

    private final Rhenium rhenium;
    private final String serverId;
    private final ServerManager serverManager;
    private final ServerTemplate serverTemplate;
    private final int port;

    private Process process;

    public GameServer(Rhenium rhenium, ServerManager serverManager, ServerTemplate serverTemplate, String serverId, int port) {
        this.rhenium = rhenium;
        this.serverManager = serverManager;
        this.serverTemplate = serverTemplate;
        this.serverId = serverId;
        this.port = port;
    }

    /**
     * This method will launch a new server on the Rhenium instance.
     */
    public void launchServer() {
        String serverRedisKey = RedisConstants.GAME_SERVER_KEY.apply(serverId);

        Map<String, String> serverInfos = Map.of(
                RedisConstants.GAME_SERVER_RHENIUM_INSTANCE, rhenium.getRheniumId(),
                RedisConstants.GAME_SERVER_POWER, String.valueOf(serverTemplate.getPower()),
                RedisConstants.GAME_SERVER_PORT, String.valueOf(port),
                RedisConstants.GAME_SERVER_PLAYERS_COUNT, "0",
                RedisConstants.GAME_SERVER_MAX_PLAYERS, String.valueOf(serverTemplate.getMaxPlayers()),
                RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP, "false",
                RedisConstants.GAME_SERVER_OUTDATED, "false"
        );

        rhenium.getJedisPool().hset(serverRedisKey, serverInfos);
        String rheniumIdKey = RedisConstants.RHENIUM_CLIENT_KEY.apply(rhenium.getRheniumId());
        rhenium.getJedisPool().hset(
                rheniumIdKey,
                RedisConstants.RHENIUM_USED_POWER,
                String.valueOf(Integer.parseInt(rhenium.getJedisPool().hget(rheniumIdKey, RedisConstants.RHENIUM_USED_POWER)) + serverTemplate.getPower())
        );

        ProcessBuilder processBuilder = new ProcessBuilder(generateStartCommand(serverId, port, rhenium.getRheniumConfig()));
        try {
            process = processBuilder.start();
            Thread.ofVirtual().start(() -> {
                try {
                    process.waitFor();
                    cleanServer();
                } catch (InterruptedException exception) {
                    // Something bad happened, we need to clean up the server
                    process.destroy();
                    cleanServer();
                }
            });
        } catch (Exception exception) {
            LOGGER.error("Failed to launch the server {}.", serverId, exception);
            cleanServer();
        }
    }

    public ServerTemplate getServerTemplate() {
        return serverTemplate;
    }

    public String getServerId() {
        return serverId;
    }

    public int getPort() {
        return port;
    }

    public CompletableFuture<Void> stop() {
        process.destroyForcibly();
        return CompletableFuture.runAsync(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException exception) {
                LOGGER.error("Failed to stop the server {}.", serverId, exception);
            }
            cleanServer();
        });
    }

    /**
     * This method will clean up the Redis keys.
     */
    private void cleanServer() {
        String rheniumIdKey = RedisConstants.RHENIUM_CLIENT_KEY.apply(rhenium.getRheniumId());
        rhenium.getJedisPool().hset(
                rheniumIdKey,
                RedisConstants.RHENIUM_USED_POWER,
                String.valueOf(Integer.parseInt(rhenium.getJedisPool().hget(rheniumIdKey, RedisConstants.RHENIUM_USED_POWER)) - serverTemplate.getPower())
        );

        rhenium.getJedisPool().del(RedisConstants.GAME_SERVER_KEY.apply(serverId));

        serverManager.unregisterServer(this);
    }

    private String[] generateStartCommand(String instanceId, int port, RheniumConfig rheniumConfig) {
        return new String[] {
                "java",
                "-Dserver-port=" + port,
                "-Dserver-id=" + instanceId,
                "-Dredis.host=" + rheniumConfig.getRedisHost(),
                "-Dredis.port=" + rheniumConfig.getRedisPort(),
                "-Dredis.username=" + rheniumConfig.getRedisUser(),
                "-Dredis.password=" + rheniumConfig.getRedisPassword(),
                "-Dmongo.connection-string=" + rheniumConfig.getMongoConnectionString(),
                "-jar",
                ServerTemplate.SERVERS_CACHE.resolve(serverTemplate.getFileName()).toString()
        };
    }
}
