package net.defade.rhenium.utils;

import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.JedisPooled;

import java.util.Random;
import java.util.Set;

public class Utils {
    private static final String UNIQUE_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static String generateUniqueNetworkId(JedisPooled jedisPool, int size) {
        long seed = jedisPool.incr(RedisConstants.SEED_GENERATOR_KEY);
        Random random = new Random(seed);
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < size; i++) {
            stringBuilder.append(UNIQUE_ID_CHARS.charAt(random.nextInt(UNIQUE_ID_CHARS.length())));
        }

        return stringBuilder.toString();
    }

    public static void fullyDeleteGameServer(JedisPooled jedis, String serverId) {
        Set<String> miniGameInstances = jedis.keys(RedisConstants.MINI_GAME_INSTANCE_KEY.apply(serverId, "*"));

        AbstractTransaction transaction = jedis.multi();

        transaction.del(RedisConstants.GAME_SERVER_KEY.apply(serverId));
        miniGameInstances.forEach(transaction::del);

        transaction.exec();
        transaction.close();
    }
}
