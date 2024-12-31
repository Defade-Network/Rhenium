package net.defade.rhenium;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.Config;
import net.defade.rhenium.config.RheniumConfig;
import net.defade.rhenium.rest.RestServer;
import net.defade.rhenium.servers.ServerManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Timer;

public class Rhenium {
    private static final Logger LOGGER = LogManager.getLogger(Rhenium.class);

    private final RheniumConfig rheniumConfig;

    private final CoreV1Api k8sApi;

    private final RestServer restServer;
    private final Timer timer = new Timer();
    private final ServerManager serverManager;

    public Rhenium(RheniumConfig rheniumConfig) throws IOException {
        this.rheniumConfig = rheniumConfig;

        this.restServer = new RestServer("0.0.0.0", 6000); // TODO: make those configurable?
        this.serverManager = new ServerManager(this);

        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);
        this.k8sApi = new CoreV1Api();
    }

    public void start() throws ApiException {
        LOGGER.info("Starting Rhenium...");

        restServer.start();

        // TODO: check if the k8s client is connected
        // Check if the k8s namespace exists, if not create it
        if (k8sApi.listNamespace().execute().getItems().stream().noneMatch(ns -> ns.getMetadata().getName().equals(rheniumConfig.getK8sNamespace()))) {
            LOGGER.info("Namespace {} does not exist, creating it...", rheniumConfig.getK8sNamespace());
            k8sApi.createNamespace(new V1Namespace().metadata(new V1ObjectMeta().name(rheniumConfig.getK8sNamespace())));
        }

        serverManager.start();

        LOGGER.info("Rhenium has been started.");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public void stop() {
        LOGGER.info("Shutting down Rhenium...");
        timer.cancel();
    }

    public RheniumConfig getRheniumConfig() {
        return rheniumConfig;
    }

    public RestServer getRestServer() {
        return restServer;
    }

    public CoreV1Api getKubernetesClient() {
        return k8sApi;
    }

    public Timer getTimer() {
        return timer;
    }
}
