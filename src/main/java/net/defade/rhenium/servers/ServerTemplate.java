package net.defade.rhenium.servers;

import com.mongodb.client.gridfs.GridFSBucket;
import org.bson.BsonString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class ServerTemplate {
    public static final Path SERVERS_CACHE = Path.of(".servers");

    private final String templateId;
    private final String serverName;
    private final int maxPlayers;

    private boolean isOutdated = false;

    /**
     * @param templateId The unique id of the server template. When changing any property of the server template, the id should be changed.
     *                   This is used to check if the server template has been updated and to make sure that the Rhenium swarm all uses
     *                   the same server template when being requested to start a server.
     * @param serverName The name of the server template. This is used to identify the server template in the Rhenium swarm.
     * @param maxPlayers The maximum amount of players that can join the server. Used to create/delete servers based on the demand.
     */
    public ServerTemplate(String templateId, String serverName, int maxPlayers) {
        this.templateId = templateId;
        this.serverName = serverName;
        this.maxPlayers = maxPlayers;
    }

    public CompletableFuture<Void> downloadServer(GridFSBucket gridFSBucket) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            createServerDirectory();

            try {
                // Check if the server has already been downloaded
                Path serverPath = SERVERS_CACHE.resolve(serverName);
                Files.deleteIfExists(serverPath);
                Files.createFile(serverPath);

                // Download the server from the MongoDB GridFS
                gridFSBucket.downloadToStream(new BsonString(serverName), Files.newOutputStream(serverPath));

                future.complete(null);
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    public String getTemplateId() {
        return templateId;
    }

    public String getServerName() {
        return serverName;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void markOutdated() {
        isOutdated = true;
    }

    /**
     * @return true if the server template doesn't exist anymore and should be deleted, false otherwise
     */
    public boolean isOutdated() {
        return isOutdated;
    }

    private void createServerDirectory() {
        if (!Files.exists(SERVERS_CACHE)) {
            try {
                Files.createDirectory(SERVERS_CACHE);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to create the servers cache directory.", exception);
            }
        }
    }
}
