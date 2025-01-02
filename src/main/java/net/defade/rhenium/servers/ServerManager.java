package net.defade.rhenium.servers;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1EnvVarSource;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1SecretKeySelector;
import net.defade.rhenium.Rhenium;
import net.defade.rhenium.config.ServerTemplate;
import net.defade.rhenium.servers.instances.ServerInstance;
import net.defade.rhenium.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;

import static net.defade.rhenium.servers.instances.ServerInstance.SERVER_TEMPLATE_IDENTIFIER_LABEL;

public class ServerManager {
    private static final Logger LOGGER = LogManager.getLogger(ServerManager.class);
    private static final int MIN_SERVERS = 2;

    private final Rhenium rhenium;

    // Values used when the instance is the leader
    private final PlayerServerDispatcher playerServerDispatcher;

    public ServerManager(Rhenium rhenium) {
        this.rhenium = rhenium;
        this.playerServerDispatcher = new PlayerServerDispatcher(rhenium, this);
    }

    public void start() {
        rhenium.getTimer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkOutdatedServers();
                checkNewNeededServers();
                downscaleServers();
                deleteEmptyServers();
                playerServerDispatcher.checkRequests();
            }
        }, 0, 2 * 1000);
    }

    private List<ServerInstance> getAllServerInstances() {
        List<ServerInstance> serverInstances = new ArrayList<>();

        try {
            V1PodList podList = rhenium.getKubernetesClient()
                .listNamespacedPod(rhenium.getRheniumConfig().getK8sNamespace())
                .labelSelector("type=server-instance")
                .execute();

            for (V1Pod item : podList.getItems()) {
                if (item.getStatus() != null && item.getStatus().getPhase().equals("Running")) {
                    serverInstances.add(new ServerInstance(rhenium, item));
                }
            }
        } catch (ApiException exception) {
            LOGGER.error("Failed to get the server instances running.", exception);
            // TODO: shouldn't we return the exception here so that the caller can handle it?
        }

        return serverInstances;
    }

    public List<ServerInstance> getServerInstances(ServerTemplate serverTemplate) {
        List<ServerInstance> serverInstances = new ArrayList<>();

        try {
            V1PodList podList = rhenium.getKubernetesClient()
                .listNamespacedPod(rhenium.getRheniumConfig().getK8sNamespace())
                .labelSelector(SERVER_TEMPLATE_IDENTIFIER_LABEL + "=" + serverTemplate.templateIdentifier())
                .execute();

            for (V1Pod item : podList.getItems()) {
                if (item.getStatus() != null && item.getStatus().getPhase().equals("Running") || item.getStatus().getPhase().equals("Pending")) {
                    serverInstances.add(new ServerInstance(rhenium, item));
                }
            }
        } catch (ApiException exception) {
            LOGGER.error("Failed to get the server instances for the server template {}.", serverTemplate.templateName(), exception);
            // TODO: shouldn't we return the exception here so that the caller can handle it?
        }

        return serverInstances;
    }

    public ServerInstance getServerInstance(String serverId) {
        try {
            V1Pod pod = rhenium.getKubernetesClient().readNamespacedPod(serverId, rhenium.getRheniumConfig().getK8sNamespace()).execute();
            if (pod == null) return null;

            return new ServerInstance(rhenium, pod);
        } catch (ApiException exception) {
            LOGGER.error("Failed to get the server instance {}.", serverId, exception);
            return null;
        }
    }

    private void checkOutdatedServers() {
        for (ServerInstance serverInstance : getAllServerInstances()) {
            if (!serverInstance.isScheduledForDeletion()) {
                // Check if the server template still exists
                if (rhenium.getRheniumConfig().getTemplateByIdentifier(serverInstance.getServerTemplateIdentifier()) == null) {
                    serverInstance.setScheduledForDeletion(true);
                }
            }
        }
    }

    /**
     * This method will check if there are any servers that needs to be deleted.
     */
    private void deleteEmptyServers() {
        for (ServerInstance serverInstance : getAllServerInstances()) {
            if (serverInstance.isScheduledForDeletion() && serverInstance.getOnlinePlayers() == 0) {
                stopServer(serverInstance.getServerId());
            }
        }

        // Get all pods that are not running and delete them
        try {
            V1PodList podList = rhenium.getKubernetesClient()
                .listNamespacedPod(rhenium.getRheniumConfig().getK8sNamespace())
                .labelSelector("type=server-instance")
                .execute();

            for (V1Pod item : podList.getItems()) {
                if (!item.getStatus().getPhase().equals("Running") && !item.getStatus().getPhase().equals("Pending")) {
                    rhenium.getKubernetesClient().deleteNamespacedPod(item.getMetadata().getName(), rhenium.getRheniumConfig().getK8sNamespace()).execute();
                    // TODO: store logs
                }
            }
        } catch (ApiException exception) {
            LOGGER.error("Failed to get the server instances running.", exception);
        }
    }

    /**
     * This method will check if new servers are needed and schedule them if necessary.
     */
    private void checkNewNeededServers() {
        for (ServerTemplate serverTemplate : rhenium.getRheniumConfig().getTemplates()) {
            List<ServerInstance> serverInstances = getServerInstances(serverTemplate);

            int availableServers = (int) serverInstances.stream()
                .filter(gameServer -> !gameServer.isScheduledForDeletion())
                .filter(gameServer -> gameServer.getOnlinePlayers() < serverTemplate.maxPlayers())
                .count();

            while (availableServers < MIN_SERVERS) {
                // Before scheduling a server, try to check if a server that is scheduled for stopping can be started again
                // This is done to avoid having to start a new server if we can reuse an old one

                boolean found = false;
                for (ServerInstance serverInstance : serverInstances) {
                    if (serverInstance.isScheduledForDeletion()) {
                        serverInstance.setScheduledForDeletion(false);
                        availableServers++;
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    createServer(serverTemplate);
                    availableServers++;
                }
            }
        }
    }

    /**
     * This method will flag servers for deletion if there are too many servers running compared to the demand.
     */
    private void downscaleServers() {
        for (ServerTemplate serverTemplate : rhenium.getRheniumConfig().getTemplates()) {
            List<ServerInstance> serverInstances = getServerInstances(serverTemplate);

            int runningServers = 0;
            int connectedPlayers = 0;
            for (ServerInstance serverInstance : serverInstances) {
                if (serverInstance.isScheduledForDeletion()) continue;

                runningServers++;
                connectedPlayers += serverInstance.getOnlinePlayers();
            }

            int serversNeeded = (int) Math.ceil((double) connectedPlayers / serverTemplate.maxPlayers()) + MIN_SERVERS;
            if (runningServers > serversNeeded) {
                // We have too many servers!
                int serversToRemove = runningServers - serversNeeded;

                // Find the servers with the least amount of players and flag them for deletion
                while (serversToRemove > 0) {
                    ServerInstance serverToRemove = null;
                    int minPlayers = Integer.MAX_VALUE;
                    for (ServerInstance serverInstance : serverInstances) {
                        if (serverInstance.isScheduledForDeletion()) continue;

                        if (serverInstance.getOnlinePlayers() < minPlayers) {
                            minPlayers = serverInstance.getOnlinePlayers();
                            serverToRemove = serverInstance;
                        }
                    }

                    if (serverToRemove != null) {
                        serverToRemove.setScheduledForDeletion(true);
                        serversToRemove--;
                        LOGGER.info("Too many servers running! Flagged the server {} for deletion.", serverToRemove.getServerId());
                    }
                }
            }
        }
    }

    /**
     * Create a new server based on the server template.
     *
     * @param serverTemplate The server template to create the server from
     */
    private void createServer(ServerTemplate serverTemplate) {
        String serverId = serverTemplate.templateName() + "-" + Utils.generateUniqueNetworkId(8);

        V1Pod pod = new V1Pod()
            .apiVersion("v1")
            .kind("Pod")
            .metadata(new V1ObjectMeta().name(serverId)
                .putLabelsItem("type", "server-instance")
                .putLabelsItem(SERVER_TEMPLATE_IDENTIFIER_LABEL, serverTemplate.templateIdentifier())
                .putAnnotationsItem(ServerInstance.ONLINE_PLAYERS_ANNOTATION, "0")
                .putAnnotationsItem(ServerInstance.SCHEDULED_FOR_DELETION_ANNOTATION, "false")
                .putAnnotationsItem(ServerInstance.MINI_GAME_INSTANCES_ANNOTATION, Base64.getEncoder().encodeToString("{}".getBytes()))
            )
            .spec(new V1PodSpec()
                .overhead(null) // Necessary else it will throw an error
                .restartPolicy("Never")
                .containers(List.of(new V1Container()
                        .name("minigame")
                        .image("registry.defade.net/" + serverTemplate.dockerImage())
                        .imagePullPolicy("Always")
                        .ports(Collections.singletonList(new V1ContainerPort().containerPort(25565)))
                        .env(List.of(
                            secretKeySelector("REST_AUTH_KEY"),
                            secretKeySelector("PROXY_FORWARDING_KEY"),
                            secretKeySelector("PROXY_COOKIE_SIGNING_KEY"),
                            new V1EnvVar().name("KUBERNETES_NAMESPACE").value(rhenium.getRheniumConfig().getK8sNamespace()),
                            new V1EnvVar().name("SERVER_ID").value(serverId),
                            secretKeySelector("MONGO_CONNECTION_STRING"),
                            secretKeySelector("MONGO_DATABASE")
                        ))
                        .resources(new V1ResourceRequirements()
                            .requests(Map.of("cpu", new Quantity(String.valueOf(serverTemplate.cpus())), "memory", new Quantity(serverTemplate.memory() + "Mi")))
                        )
                    )
                )
                .imagePullSecrets(List.of(new V1LocalObjectReference().name(rhenium.getRheniumConfig().getDockerRegistrySecretName())))
            );

        try {
            rhenium.getKubernetesClient().createNamespacedPod(rhenium.getRheniumConfig().getK8sNamespace(), pod).execute();
        } catch (ApiException exception) {
            LOGGER.error("Failed to create a new server {}.", serverId, exception);
            return;
        }
        LOGGER.info("Created a new server {}.", serverId);
    }

    private void stopServer(String serverId) {
        try {
            rhenium.getKubernetesClient().deleteNamespacedPod(serverId, rhenium.getRheniumConfig().getK8sNamespace()).execute();
            LOGGER.info("Deleted the server {}.", serverId);

            Utils.sendHTTPRequest(
                "http://" + Utils.getServerInstanceIp(rhenium.getKubernetesClient(), rhenium.getRheniumConfig().getK8sNamespace(), serverId) + "/server/stop",
                "POST",
                ""
            ).exceptionally(throwable -> {
                LOGGER.error("Failed to notify the server {} that it should stop.", serverId, throwable);
                return null;
            });
        } catch (ApiException exception) {
            LOGGER.error("Failed to delete the server {}.", serverId, exception);
        }
    }

    private static V1EnvVar secretKeySelector(String key) {
        return new V1EnvVar().name(key).valueFrom(new V1EnvVarSource().secretKeyRef(new V1SecretKeySelector().name("db-credentials").key(key)));
    }
}
