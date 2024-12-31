package net.defade.rhenium.utils;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import net.defade.rhenium.Rhenium;
import net.defade.rhenium.rest.RestServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;;

public class Utils {
    private static final Logger LOGGER = LogManager.getLogger(Utils.class);

    private static final String UNIQUE_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    public static String generateUniqueNetworkId(int size) {
        Random random = new Random();
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < size; i++) {
            stringBuilder.append(UNIQUE_ID_CHARS.charAt(random.nextInt(UNIQUE_ID_CHARS.length())));
        }

        return stringBuilder.toString();
    }

    public static String getServerInstanceIp(CoreV1Api k8sApi, String namespace, String serverId) throws ApiException {
        V1Pod pod = k8sApi.readNamespacedPod(serverId, namespace).execute();
        if (pod == null) return null;

        return pod.getStatus().getPodIP();
    }

    public static CompletableFuture<Void> sendHTTPRequestToVelocity(Rhenium rhenium, String path, String method, String body) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                V1PodList podList = rhenium.getKubernetesClient().listNamespacedPod(rhenium.getRheniumConfig().getK8sNamespace())
                    .labelSelector("app=velocity")
                    .execute();

                if (podList == null || podList.getItems().isEmpty()) {
                    future.completeExceptionally(new IOException("Failed to find the Velocity pod"));
                    return;
                }

                CompletableFuture<Void> httpFuture = sendHTTPRequest("http://" + podList.getItems().getFirst().getStatus().getPodIP() + ":6000" + path, method, body);
                httpFuture.whenComplete((result, exception) -> {
                    if (exception != null) future.completeExceptionally(exception);
                    else future.complete(null);
                });
            } catch (ApiException exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    public static CompletableFuture<Void> sendHTTPRequest(String endpoint, String method, String body) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                URL url = URI.create(endpoint).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod(method);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", RestServer.AUTH_KEY);
                connection.setDoOutput(true);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    byte[] input = body.getBytes(StandardCharsets.UTF_8);
                    outputStream.write(input, 0, input.length);
                }

                connection.disconnect();
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                    future.completeExceptionally(new IOException("HTTP request failed with status code " + connection.getResponseCode()));
                } else {
                    future.complete(null);
                }
            } catch (IOException exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }
}
