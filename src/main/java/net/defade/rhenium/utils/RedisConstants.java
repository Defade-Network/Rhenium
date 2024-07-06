package net.defade.rhenium.utils;

import java.util.function.Function;

public class RedisConstants {
    /**
     * Key to store a seed for random values that must be unique across the network.
     * Always call incr so that the seed is always different.
     */
    public static final String SEED_GENERATOR_KEY = "rhenium:seed";

    /**
     * ID of the current leader of the swarm.
     */
    public static final String LEADER_ID = "rhenium:leader";

    /**
     * Amount of time the rhenium instances have to update their status.
     */
    public static final int RHENIUM_CLIENT_TIMEOUT = 5000;

    /**
     * Provides the key to store the client information. The id is the unique identifier of the client.
     */
    public static final Function<String, String> RHENIUM_CLIENT_KEY = (id) -> "rhenium:clients:" + id;

    /**
     * The public IP address of a client which will be used by the proxy to connect to the servers.
     */
    public static final String RHENIUM_PUBLIC_IP_ADDRESS = "ip-address";

    /**
     * The amount of power available for a rhenium client.
     */
    public static final String RHENIUM_AVAILABLE_POWER = "available-power";

    /**
     * The amount of power used by a rhenium client.
     */
    public static final String RHENIUM_USED_POWER = "used-power";

    /**
     * Provides the key to store a game server information. The id is the unique identifier of the game server.
     */
    public static final Function<String, String> GAME_SERVER_KEY = (id) -> "rhenium:game_server:" + id;

    /**
     * The key which holds the rhenium id running the game server.
     */
    public static final String GAME_SERVER_RHENIUM_INSTANCE = "rhenium-instance";

    /**
     * The key which holds the power of a game server.
     */
    public static final String GAME_SERVER_POWER = "power";

    /**
     * The key which holds the port on which the server is running on.
     */
    public static final String GAME_SERVER_PORT = "port";

    /**
     * The key which holds the amount of online players for a game server.
     */
    public static final String GAME_SERVER_PLAYERS_COUNT = "players-count";

    /**
     * The key which holds the maximum amount of players for a game server.
     */
    public static final String GAME_SERVER_MAX_PLAYERS = "max-players";

    /**
     * The key which holds whether a game server is scheduled for stop or not.
     */
    public static final String GAME_SERVER_SCHEDULED_FOR_STOP = "scheduled-for-stop";

    /**
     * The key which holds whether a game server is outdated or not.
     */
    public static final String GAME_SERVER_OUTDATED = "outdated";

    /**
     * Pub/Sub channel to notify that a server has been created.
     */
    public static final String GAME_SERVER_CREATE_CHANNEL = "rhenium:game_server_create";

    /**
     * Pub/Sub channel to acknowledge that a server has been created.
     */
    public static final String GAME_SERVER_CREATE_ACKNOWLEDGE_CHANNEL = "rhenium:game_server_create_acknowledge";

    /**
     * Pub/Sub channel to stop a server.
     */
    public static final String GAME_SERVER_STOP_CHANNEL = "rhenium:game_server_stop";

    /**
     * Pub/Sub channel to receive requests to move a player to a server
     */
    public static final String PLAYER_SEND_TO_SERVER_REQUEST_CHANNEL = "rhenium:player_send_to_server_request";

    /**
     * Pub/Sub channel to send a player to a server with the proxy
     */
    public static final String PLAYER_SEND_TO_SERVER_PROXY_CHANNEL = "rhenium:player_send_to_server_proxy";
}
