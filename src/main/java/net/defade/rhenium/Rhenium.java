package net.defade.rhenium;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import net.defade.rhenium.config.RheniumConfig;
import net.defade.rhenium.controller.LeaderTracker;
import net.defade.rhenium.controller.ServerManager;
import net.defade.rhenium.servers.ServerTemplateManager;
import net.defade.rhenium.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.conversions.Bson;
import redis.clients.jedis.JedisPooled;
import java.util.Timer;

public class Rhenium {
    private static final Logger LOGGER = LogManager.getLogger(Rhenium.class);

    private final RheniumConfig rheniumConfig;

    private JedisPooled jedisPool;
    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;

    private String rheniumId;

    private final Timer timer = new Timer();
    private final ServerTemplateManager serverTemplateManager = new ServerTemplateManager(this);
    private final LeaderTracker leaderTracker = new LeaderTracker(this);
    private final ServerManager serverManager = new ServerManager(this);

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

        LOGGER.info("Connecting to the MongoDB server...");
        mongoClient = MongoClients.create(new ConnectionString(rheniumConfig.getMongoConnectionString()));
        mongoDatabase = mongoClient.getDatabase(rheniumConfig.getMongoDatabase());

        if (!isMongoConnected()) {
            LOGGER.error("Failed to connect to the MongoDB server.");
            jedisPool.close();
            System.exit(1);
            return;
        }
        LOGGER.info("Successfully connected to the MongoDB server.");

        this.rheniumId = "rhenium-" + Utils.generateUniqueNetworkId(jedisPool, 6);

        serverTemplateManager.start();
        leaderTracker.start();
        serverManager.start();

        LOGGER.info("Rhenium has been started with the id {}.", rheniumId);
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public void stop() {
        LOGGER.info("Shutting down Rhenium...");

        timer.cancel();
        serverTemplateManager.stop();

        jedisPool.close();
        mongoClient.close();
        LOGGER.info("Rhenium has shut down.");
    }

    public RheniumConfig getRheniumConfig() {
        return rheniumConfig;
    }

    public Timer getTimer() {
        return timer;
    }

    public ServerTemplateManager getServerTemplateManager() {
        return serverTemplateManager;
    }

    public JedisPooled getJedisPool() {
        return jedisPool;
    }

    public MongoDatabase getMongoDatabase() {
        return mongoDatabase;
    }

    public String getRheniumId() {
        return rheniumId;
    }

    public boolean isLeader() {
        return leaderTracker.isLeader();
    }

    private boolean isMongoConnected() {
        Bson command = new BsonDocument("ping", new BsonInt64(1));

        try {
            mongoDatabase.runCommand(command);
        } catch (Exception exception) {
            return false;
        }

        return true;
    }
}
