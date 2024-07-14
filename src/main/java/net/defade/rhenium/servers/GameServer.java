package net.defade.rhenium.servers;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.config.RheniumConfig;
import net.defade.rhenium.servers.template.ServerTemplate;
import net.defade.rhenium.utils.RedisConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class GameServer {
    private static final Logger LOGGER = LogManager.getLogger(GameServer.class);

    private final Rhenium rhenium;
    private final String serverId;
    private final ServerManager serverManager;
    private final ServerTemplate serverTemplate;
    private final int port;

    private boolean isOutdated = false;

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
                RedisConstants.GAME_SERVER_STARTED, "false",
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

    public boolean isOutdated() {
        return isOutdated;
    }

    public void markOutdated() {
        isOutdated = true;
    }

    public CompletableFuture<Void> stop(boolean force) {
        if (!force) {
            process.destroy();
        } else {
            process.destroyForcibly();
        }

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

    private List<String> generateStartCommand(String instanceId, int port, RheniumConfig rheniumConfig) {
        String[] serverTemplateJavaArgs = serverTemplate.getJavaArgs().split(" ");
        List<String> command = new ArrayList<>(serverTemplateJavaArgs.length + 10);

        command.add("java");
        command.addAll(Arrays.asList(serverTemplateJavaArgs));
        command.add("-Dserver-port=" + port);
        command.add("-Dserver-id=" + instanceId);
        command.add("-Dredis.host=" + rheniumConfig.getRedisHost());
        command.add("-Dredis.port=" + rheniumConfig.getRedisPort());
        command.add("-Dredis.username=" + rheniumConfig.getRedisUser());
        command.add("-Dredis.password=" + rheniumConfig.getRedisPassword());
        command.add("-Dmongo.connection-string=" + rheniumConfig.getMongoConnectionString());
        command.add("-jar");
        command.add(ServerTemplate.SERVERS_CACHE.resolve(serverTemplate.getFileName()).toString());

        return command;
    }
}
