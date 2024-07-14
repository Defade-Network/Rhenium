package net.defade.rhenium.redis;

import net.defade.rhenium.utils.RedisConstants;
import redis.clients.jedis.JedisPooled;

import java.util.Map;

/**
 * Represents a game server stored in the Redis database.
 */
public class RedisGameServer {
    private final JedisPooled jedis;

    private final String serverId;
    private final String rheniumInstance;
    private final boolean started;
    private final int port;
    private boolean isScheduledForStop;
    private final boolean isOutdated;
    private final int power;
    private final int playerCount;
    private final int maxPlayers;

    public RedisGameServer(JedisPooled jedis, String serverId) {
        this.jedis = jedis;
        this.serverId = serverId;

        Map<String, String> serverInfos = jedis.hgetAll(RedisConstants.GAME_SERVER_KEY.apply(serverId));
        this.rheniumInstance = serverInfos.get(RedisConstants.GAME_SERVER_RHENIUM_INSTANCE);
        this.started = Boolean.parseBoolean(serverInfos.get(RedisConstants.GAME_SERVER_STARTED));
        this.port = Integer.parseInt(serverInfos.get(RedisConstants.GAME_SERVER_PORT));
        this.isScheduledForStop = Boolean.parseBoolean(serverInfos.get(RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP));
        this.isOutdated = Boolean.parseBoolean(serverInfos.get(RedisConstants.GAME_SERVER_OUTDATED));
        this.power = Integer.parseInt(serverInfos.get(RedisConstants.GAME_SERVER_POWER));
        this.playerCount = Integer.parseInt(serverInfos.get(RedisConstants.GAME_SERVER_PLAYERS_COUNT));
        this.maxPlayers = Integer.parseInt(serverInfos.get(RedisConstants.GAME_SERVER_MAX_PLAYERS));
    }

    public String getServerId() {
        return serverId;
    }

    public String getRheniumInstance() {
        return rheniumInstance;
    }

    public boolean hasStarted() {
        return started;
    }

    public int getPort() {
        return port;
    }

    public boolean isScheduledForStop() {
        return isScheduledForStop;
    }

    public void setScheduledForStop(boolean isScheduledForStop) {
        this.isScheduledForStop = isScheduledForStop;

        jedis.hset(RedisConstants.GAME_SERVER_KEY.apply(serverId), RedisConstants.GAME_SERVER_SCHEDULED_FOR_STOP, String.valueOf(isScheduledForStop));
        jedis.publish(RedisConstants.GAME_SERVER_MARKED_FOR_STOP_CHANNEL, serverId);
    }

    public boolean isOutdated() {
        return isOutdated;
    }

    public int getPower() {
        return power;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }
}
