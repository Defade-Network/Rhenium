package net.defade.rhenium.servers.template;

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

    public ServerTemplate getServerTemplate(String templateName) {
        return serverTemplates.get(templateName);
    }

    /**
     * Update the server templates by fetching the new ones from the MongoDB GridFS.
     * This method will also mark the old server templates as obsolete if they have been updated.
     */
    private void updateServerTemplates() {
        List<ServerTemplate> templatesToDownload = new ArrayList<>();

        /*
         Copy the current server templates to a new list, where we will remove the ones that are still registered in the
         database and are still valid, so that we can then delete the ones that are not registered anymore from the list.
         */
        List<String> currentTemplates = new ArrayList<>(serverTemplates.keySet());

        serverFSBucket.find().forEach((Consumer<? super GridFSFile>) (gridFSFile) -> {
            if (gridFSFile.getMetadata() == null) {
                LOGGER.warn("The server {} does not have any metadata. Skipping.", gridFSFile.getId().asString().getValue());
                return;
            }

            String templateName = gridFSFile.getId().asString().getValue();

            currentTemplates.removeIf(template -> !serverTemplates.get(template).isDifferentFromDB(gridFSFile)); // Delete it if the version we have is different from the one in the DB
            if (failedDownloads.contains(templateName)) return;

            if (!serverTemplates.containsKey(templateName) && !downloadingTemplates.contains(templateName)) {
                templatesToDownload.add(new ServerTemplate(gridFSFile));
            }
        });

        currentTemplates.forEach(templateName -> {
            LOGGER.info("The server template {} has been removed.", templateName);
            serverTemplates.remove(templateName).markOutdated();
        });

        templatesToDownload.forEach(serverTemplate -> {
            downloadingTemplates.add(serverTemplate.getTemplateName());
            serverTemplate.downloadServer(serverFSBucket).whenComplete((unused, error) -> {
                if (error != null) {
                    LOGGER.error("Failed to download the server template {}.", serverTemplate.getTemplateName(), error);
                    downloadingTemplates.remove(serverTemplate.getTemplateName());
                    failedDownloads.add(serverTemplate.getTemplateName());
                    return;
                }

                downloadingTemplates.remove(serverTemplate.getTemplateName());
                serverTemplates.put(serverTemplate.getTemplateName(), serverTemplate);

                LOGGER.info("The server template {} has been registered.", serverTemplate.getTemplateName());
            });
        });
    }
}
