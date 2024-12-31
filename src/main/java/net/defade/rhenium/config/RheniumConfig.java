package net.defade.rhenium.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RheniumConfig {
    private String k8sNamespace;
    private String dockerRegistrySecretName;
    private final Map<String, ServerTemplate> serverTemplates = new HashMap<>();

    public RheniumConfig(CommentedFileConfig config) {
        loadK8sConfig(config.get("k8s"));
        loadServerTemplates(config.get("server-templates"));
    }

    private void loadK8sConfig(CommentedConfig k8sConfig) {
        k8sNamespace = k8sConfig.get("namespace");
        dockerRegistrySecretName = k8sConfig.get("docker-registry-secret-name");
    }

    private void loadServerTemplates(CommentedConfig networkSettings) {
        serverTemplates.clear();

        for (Map.Entry<String, Object> entry : networkSettings.valueMap().entrySet()) {
            String serverName = entry.getKey();
            CommentedConfig serverConfig = (CommentedConfig) entry.getValue();

            int maxPlayers = serverConfig.getInt("max-players");
            String dockerImage = serverConfig.get("docker-image");
            int cpus = serverConfig.getInt("cpus");
            int memory = serverConfig.getInt("memory");

            ServerTemplate serverTemplate = new ServerTemplate(serverName, dockerImage, maxPlayers, cpus, memory);

            serverTemplates.put(serverTemplate.templateIdentifier(), serverTemplate);
        }
    }

    public String getK8sNamespace() {
        return k8sNamespace;
    }

    public String getDockerRegistrySecretName() {
        return dockerRegistrySecretName;
    }

    public ServerTemplate getTemplateByIdentifier(String identifier) {
        return serverTemplates.get(identifier);
    }

    public ServerTemplate getTemplateByName(String name) {
        for (ServerTemplate template : serverTemplates.values()) {
            if (template.templateName().equals(name)) {
                return template;
            }
        }

        return null;
    }

    public List<ServerTemplate> getTemplates() {
        return new ArrayList<>(serverTemplates.values());
    }

    public static RheniumConfig load() {
        URL defaultConfigLocation = RheniumConfig.class.getClassLoader()
                .getResource("config.toml");
        if (defaultConfigLocation == null) {
            throw new RuntimeException("The default configuration file does not exist.");
        }

        CommentedFileConfig config = CommentedFileConfig.builder(Path.of("config.toml"))
                .defaultData(defaultConfigLocation)
                .build();
        config.load();

        return new RheniumConfig(config);
    }
}
