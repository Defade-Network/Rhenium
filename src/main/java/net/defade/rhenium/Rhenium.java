package net.defade.rhenium;

import net.defade.rhenium.config.RheniumConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redis.clients.jedis.JedisPooled;

public class Rhenium {
    private static final Logger LOGGER = LogManager.getLogger(Rhenium.class);

    private final RheniumConfig rheniumConfig;

    private JedisPooled jedisPool;

    public Rhenium(RheniumConfig rheniumConfig) {
        this.rheniumConfig = rheniumConfig;
    }

    public void start() {
        LOGGER.info("Starting Rhenium...");
        jedisPool = new JedisPooled(rheniumConfig.getRedisHost(), rheniumConfig.getRedisPort(),
                rheniumConfig.getRedisUser(), rheniumConfig.getRedisPassword());

        // Check if the connection is successful by sending a command
        try {
            jedisPool.ping();
        } catch (Exception e) {
            LOGGER.error("Failed to connect to the Redis server.");
            System.exit(1);
        }
        LOGGER.info("Successfully connected to the Redis server.");

        LOGGER.info("Rhenium has started.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down Rhenium...");

            jedisPool.close();
            LOGGER.info("Rhenium has shut down.");
        }));
    }
}
