package net.defade.rhenium.servers.instances;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import net.defade.rhenium.Rhenium;
import net.defade.rhenium.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ServerInstance {
    public static final String SERVER_TEMPLATE_IDENTIFIER_LABEL = "server-template-identifier";
    public static final String ONLINE_PLAYERS_ANNOTATION = "online-players";
    public static final String MINI_GAME_INSTANCES_ANNOTATION = "mini-game-instances";
    public static final String SCHEDULED_FOR_DELETION_ANNOTATION = "scheduled-for-deletion";

    private static final Logger LOGGER = LogManager.getLogger(ServerInstance.class);

    private final Rhenium rhenium;

    private final String serverId;
    private final String serverTemplateIdentifier;
    private final Map<UUID, MiniGameInstance> miniGameInstances;
    private final int onlinePlayers;
    private boolean isScheduledForDeletion; // Used for downscaling

    public ServerInstance(Rhenium rhenium, V1Pod pod) {
        this.rhenium = rhenium;

        this.serverId = pod.getMetadata().getName();
        this.serverTemplateIdentifier = pod.getMetadata().getLabels().get(SERVER_TEMPLATE_IDENTIFIER_LABEL);
        this.onlinePlayers = Integer.parseInt(pod.getMetadata().getAnnotations().get(ONLINE_PLAYERS_ANNOTATION));
        this.miniGameInstances = new HashMap<>();
        this.isScheduledForDeletion = Boolean.parseBoolean(pod.getMetadata().getAnnotations().get(SCHEDULED_FOR_DELETION_ANNOTATION));

        String miniGameInstancesJson = new String(Base64.getDecoder().decode(pod.getMetadata().getAnnotations().get(MINI_GAME_INSTANCES_ANNOTATION)));
        JsonObject miniGameInstancesJsonObject = JsonParser.parseString(miniGameInstancesJson).getAsJsonObject();
        for (String miniGameInstanceId : miniGameInstancesJsonObject.keySet()) {
            UUID miniGameInstanceUUID = UUID.fromString(miniGameInstanceId);
            miniGameInstances.put(miniGameInstanceUUID, new MiniGameInstance(serverId, miniGameInstanceUUID, miniGameInstancesJsonObject.getAsJsonObject(miniGameInstanceId)));
        }
    }

    public String getServerId() {
        return serverId;
    }

    public String getServerTemplateIdentifier() {
        return serverTemplateIdentifier;
    }

    public Map<UUID, MiniGameInstance> getMiniGameInstances() {
        return miniGameInstances;
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public boolean isScheduledForDeletion() {
        return isScheduledForDeletion;
    }

    public void setScheduledForDeletion(boolean scheduledForDeletion) {
        if (isScheduledForDeletion == scheduledForDeletion) return;
        isScheduledForDeletion = scheduledForDeletion;

        // Update the annotation on the pod
        rhenium.getKubernetesClient().patchNamespacedPod(serverId, rhenium.getRheniumConfig().getK8sNamespace(),
            getAnnotationPatch(SCHEDULED_FOR_DELETION_ANNOTATION, String.valueOf(scheduledForDeletion)));

        // Notify the server that it's scheduled for stop
        try {
            Utils.sendHTTPRequestToVelocity(rhenium, "http://" + Utils.getServerInstanceIp(rhenium.getKubernetesClient(),
                rhenium.getRheniumConfig().getK8sNamespace(), serverId) + "/server/schedule-stop", "POST", "")
                .exceptionally(throwable -> {
                    LOGGER.error("Failed to notify server {} that it's scheduled for stop.", serverId, throwable);
                    return null;
                });
        } catch (ApiException exception) {
            LOGGER.error("Failed to notify server {} that it's scheduled for stop.", serverId, exception);
        }
    }

    private static V1Patch getAnnotationPatch(String key, String value) {
        return new V1Patch(String.format("{\"metadata\":{\"annotations\":{\"%s\":\"%s\"}}}", key, value));
    }
}
