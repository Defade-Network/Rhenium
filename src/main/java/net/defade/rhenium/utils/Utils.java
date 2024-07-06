package net.defade.rhenium.utils;

import redis.clients.jedis.JedisPooled;

import java.util.Random;

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
}
