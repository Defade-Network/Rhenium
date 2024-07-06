package net.defade.rhenium.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import java.net.URL;
import java.nio.file.Path;

public class RheniumConfig {
    private String redisHost;
    private int redisPort;
    private String redisUser;
    private String redisPassword;

    private String mongoConnectionString;
    private String mongoDatabase;

    public RheniumConfig(CommentedFileConfig config) {
        loadRedisConfig(config.get("redis"));
        loadMongoConfig(config.get("mongodb"));
    }

    private void loadRedisConfig(CommentedConfig config) {
        redisHost = config.get("host");
        redisPort = config.get("port");
        redisUser = config.get("user");
        redisPassword = config.get("password");
    }

    private void loadMongoConfig(CommentedConfig mongodb) {
        mongoConnectionString = mongodb.get("connection-string");
        mongoDatabase = mongodb.get("database");
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisUser() {
        return redisUser;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public String getMongoConnectionString() {
        return mongoConnectionString;
    }

    public String getMongoDatabase() {
        return mongoDatabase;
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
