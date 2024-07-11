package net.defade.rhenium.servers;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.BsonString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class ServerTemplate {
    public static final Path SERVERS_CACHE = Path.of(".servers");

    private final String templateName;
    private final int power;
    private final int maxPlayers;
    private final String fileMd5;

    private boolean isOutdated = false;

    /**
     * @param templateName The name of the server template. This is used to identify the server template in the Rhenium swarm.
     * @param power The power of the server template. The power represents the amount of resources that a game server will require.
     * @param maxPlayers The maximum amount of players that can join the server. Used to create/delete servers based on the demand.
     */
    public ServerTemplate(String templateName, int power, int maxPlayers, String fileMd5) {
        this.templateName = templateName;
        this.power = power;
        this.maxPlayers = maxPlayers;
        this.fileMd5 = fileMd5;
    }

    public ServerTemplate(GridFSFile gridFSFile) {
        if (gridFSFile.getMetadata() == null) {
            throw new IllegalArgumentException("The server template " + gridFSFile.getId().asString().getValue() + " does not have any metadata.");
        }

        this.templateName = gridFSFile.getId().asString().getValue();
        this.power = gridFSFile.getMetadata().getInteger("power");
        this.maxPlayers = gridFSFile.getMetadata().getInteger("max-players");
        this.fileMd5 = gridFSFile.getMetadata().getString("md5");
    }

    public CompletableFuture<Void> downloadServer(GridFSBucket gridFSBucket) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            createServerDirectory();

            try {
                // Check if the server has already been downloaded
                Path serverPath = SERVERS_CACHE.resolve(getFileName());
                Files.deleteIfExists(serverPath);
                Files.createFile(serverPath);

                // Download the server from the MongoDB GridFS
                gridFSBucket.downloadToStream(new BsonString(templateName), Files.newOutputStream(serverPath));

                future.complete(null);
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    public String getTemplateName() {
        return templateName;
    }

    public int getPower() {
        return power;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public String getFileName() {
        return templateName + "-" + fileMd5; // We add the md5 so that we don't overwrite servers that are running since an outdated server template can still have servers
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

    public boolean isDifferentFromDB(GridFSFile gridFSFile) {
        ServerTemplate serverTemplate = new ServerTemplate(gridFSFile);

        return !serverTemplate.equals(this);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerTemplate that = (ServerTemplate) o;
        return power == that.power && maxPlayers == that.maxPlayers && isOutdated == that.isOutdated && Objects.equals(templateName, that.templateName) && Objects.equals(fileMd5, that.fileMd5);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateName, power, maxPlayers, fileMd5, isOutdated);
    }
}
