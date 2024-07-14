package net.defade.rhenium.redis;

import net.defade.rhenium.utils.RedisConstants;
import redis.clients.jedis.JedisPooled;
import java.util.Map;

/**
 * Represents a Rhenium instance stored in the Redis database.
 */
public class RedisRheniumInstance {
    private final String rheniumId;

    private final int usedPower;
    private final int availablePower;
    private final String ipAddress;

    public RedisRheniumInstance(JedisPooled jedis, String rheniumId) {
        this.rheniumId = rheniumId;

        Map<String, String> rheniumInfos = jedis.hgetAll(RedisConstants.RHENIUM_CLIENT_KEY.apply(rheniumId));
        this.usedPower = Integer.parseInt(rheniumInfos.get(RedisConstants.RHENIUM_USED_POWER));
        this.availablePower = Integer.parseInt(rheniumInfos.get(RedisConstants.RHENIUM_AVAILABLE_POWER));
        this.ipAddress = rheniumInfos.get(RedisConstants.RHENIUM_PUBLIC_IP_ADDRESS);
    }

    public String getRheniumId() {
        return rheniumId;
    }

    public int getUsedPower() {
        return usedPower;
    }

    public int getAvailablePower() {
        return availablePower;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
