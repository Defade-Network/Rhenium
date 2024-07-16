package net.defade.rhenium.servers;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.config.RheniumConfig;
import net.defade.rhenium.servers.template.ServerTemplate;
import net.defade.rhenium.utils.RedisConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

public class GameServer {
    private static final Logger LOGGER = LogManager.getLogger(GameServer.class);
    private static final Path LOGS_FOLDER = Path.of("logs");

    private final Rhenium rhenium;
    private final String serverId;
    private final ServerManager serverManager;
    private final ServerTemplate serverTemplate;
    private final int port;

    private boolean isOutdated = false;

    private Process process;
    private final CompletableFuture<Void> stopFuture = new CompletableFuture<>();

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
        redirectProcessLogs(processBuilder);

        try {
            process = processBuilder.start();
            Thread.ofVirtual().start(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException exception) {
                    // Something bad happened, we need to clean up the server
                    process.destroy();
                }

                cleanServer();
                stopFuture.complete(null);
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

        return stopFuture;
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

        compressLogFile();
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

    private void redirectProcessLogs(ProcessBuilder processBuilder) {
        try {
            if(!Files.exists(LOGS_FOLDER) || !Files.isDirectory(LOGS_FOLDER)) Files.createDirectory(LOGS_FOLDER);
        } catch (IOException exception) {
            LOGGER.error("Failed to create the logs folder.", exception);
            return;
        }

        Path logFile = LOGS_FOLDER.resolve(serverId + ".log");
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    }

    private void compressLogFile() {
        Path logFile = LOGS_FOLDER.resolve(serverId + ".log");
        Path compressedLogFile = LOGS_FOLDER.resolve(serverId + ".log.gz");

        // Compress the log file using GZIP
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(Files.newOutputStream(compressedLogFile))) {
            Files.copy(logFile, gzipOutputStream);
        } catch (IOException exception) {
            LOGGER.error("Failed to compress the log file.", exception);
        }

        // Delete the original log file
        try {
            Files.delete(logFile);
        } catch (IOException exception) {
            LOGGER.error("Failed to delete the log file.", exception);
        }
    }
}
