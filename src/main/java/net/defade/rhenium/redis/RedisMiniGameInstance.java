package net.defade.rhenium.redis;

import net.defade.rhenium.utils.RedisConstants;
import redis.clients.jedis.JedisPooled;
import java.util.Map;
import java.util.UUID;

public class RedisMiniGameInstance {
    private final String serverId;
    private final UUID miniGameInstanceId;
    private final int playerCount;
    private final int maxPlayers;
    private final boolean acceptPlayers;

    public RedisMiniGameInstance(JedisPooled jedis, String serverId, UUID miniGameInstanceId) {
        this.serverId = serverId;
        this.miniGameInstanceId = miniGameInstanceId;

        Map<String, String> miniGameInstanceInfos = jedis.hgetAll(RedisConstants.MINI_GAME_INSTANCE_KEY.apply(serverId, miniGameInstanceId.toString()));
        this.playerCount = Integer.parseInt(miniGameInstanceInfos.get(RedisConstants.MINI_GAME_INSTANCE_PLAYER_COUNT));
        this.maxPlayers = Integer.parseInt(miniGameInstanceInfos.get(RedisConstants.MINI_GAME_INSTANCE_MAX_PLAYERS));
        this.acceptPlayers = Boolean.parseBoolean(miniGameInstanceInfos.get(RedisConstants.MINI_GAME_INSTANCE_ACCEPTING_PLAYERS));
    }

    public String getServerId() {
        return serverId;
    }

    public UUID getMiniGameInstanceId() {
        return miniGameInstanceId;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public boolean isAcceptingPlayers() {
        return acceptPlayers;
    }
}
