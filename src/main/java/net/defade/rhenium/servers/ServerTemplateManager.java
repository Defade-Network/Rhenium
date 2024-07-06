package net.defade.rhenium.servers;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import net.defade.rhenium.Rhenium;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;
import java.util.function.Consumer;

public class ServerTemplateManager {
    private static final Logger LOGGER = LogManager.getLogger(ServerTemplateManager.class);

    private final Rhenium rhenium;
    private GridFSBucket serverFSBucket;

    private final Map<String, ServerTemplate> serverTemplates = new HashMap<>();
    private final List<String> downloadingTemplates = new ArrayList<>(); // Used to prevent downloading the same template multiple times
    private final List<String> failedDownloads = new ArrayList<>(); // Used to prevent infinitely trying to download a template

    public ServerTemplateManager(Rhenium rhenium) {
        this.rhenium = rhenium;
    }

    public void start() {
        this.serverFSBucket = GridFSBuckets.create(rhenium.getMongoDatabase(), "servers");

        rhenium.getTimer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateServerTemplates();
            }
        }, 0, 2 * 1000); // Update the server templates every 2 seconds
    }

    public void stop() {
        try {
            Files.delete(ServerTemplate.SERVERS_CACHE);
        } catch (IOException ignored) { }
    }

    public Collection<ServerTemplate> getServerTemplates() {
        return serverTemplates.values();
    }

    public ServerTemplate getServerTemplate(String templateId) {
        return serverTemplates.get(templateId);
    }

    /**
     * Update the server templates by fetching the new ones from the MongoDB GridFS.
     * This method will also mark the old server templates as obsolete if they have been updated.
     */
    private void updateServerTemplates() {
        List<ServerTemplate> templatesToDownload = new ArrayList<>();

        /*
         Copy the current server templates to a new list, where we will remove the ones that are still registered in the
         database, so that we can then delete the ones that are not registered anymore from the list.
         */
        List<String> currentTemplates = new ArrayList<>(serverTemplates.keySet());

        serverFSBucket.find().forEach((Consumer<? super GridFSFile>) (gridFSFile) -> {
            if (gridFSFile.getMetadata() == null) {
                LOGGER.warn("The server {} does not have any metadata. Skipping.", gridFSFile.getId().asString().getValue());
                return;
            }

            String templateId = gridFSFile.getId().asString().getValue();
            currentTemplates.remove(templateId);
            if (failedDownloads.contains(templateId)) return;

            if (!serverTemplates.containsKey(templateId) && !downloadingTemplates.contains(templateId)) {
                templatesToDownload.add(getTemplateFromGridFS(gridFSFile));
            }
        });

        currentTemplates.forEach(templateId -> {
            LOGGER.info("The server template {} has been removed.", templateId);
            serverTemplates.remove(templateId).markOutdated();
        });

        templatesToDownload.forEach(serverTemplate -> {
            downloadingTemplates.add(serverTemplate.getTemplateId());
            serverTemplate.downloadServer(serverFSBucket).whenComplete((unused, error) -> {
                if (error != null) {
                    LOGGER.error("Failed to download the server template {}.", serverTemplate.getTemplateId(), error);
                    downloadingTemplates.remove(serverTemplate.getTemplateId());
                    failedDownloads.add(serverTemplate.getTemplateId());
                    return;
                }

                downloadingTemplates.remove(serverTemplate.getTemplateId());
                serverTemplates.put(serverTemplate.getTemplateId(), serverTemplate);

                LOGGER.info("The server template {} has been registered.", serverTemplate.getTemplateId());
            });
        });
    }

    private static ServerTemplate getTemplateFromGridFS(GridFSFile gridFSFile) {
        if (gridFSFile.getMetadata() == null) {
            throw new IllegalArgumentException("The server " + gridFSFile.getId().asString().getValue() + " does not have any metadata.");
        }

        String id = gridFSFile.getId().asString().getValue();
        String serverName = gridFSFile.getMetadata().getString("server-name");
        int power = gridFSFile.getMetadata().getInteger("power");
        int maxPlayers = gridFSFile.getMetadata().getInteger("max-players");

        return new ServerTemplate(id, serverName, power, maxPlayers);
    }
}
