package net.defade.rhenium.utils;

import java.util.function.BiFunction;
import java.util.function.Function;

public class RedisConstants {

    // ==========================================================
    //                      Seed Management
    // ==========================================================

    /**
     * Key to store a seed for random values that must be unique across the network.
     * Always call incr so that the seed is always different.
     */
    public static final String SEED_GENERATOR_KEY = "rhenium:seed";

    // ==========================================================
    //                    Leader Management
    // ==========================================================

    /**
     * ID of the current leader of the swarm.
     */
    public static final String LEADER_ID = "rhenium:leader";

    // ==========================================================
    //                    Client Management
    // ==========================================================

    /**
     * Amount of time the rhenium instances have to update their status.
     */
    public static final int RHENIUM_CLIENT_TIMEOUT_MS = 5000;

    /**
     * Provides the key to store the client information. The id is the unique identifier of the client.
     */
    public static final Function<String, String> RHENIUM_CLIENT_KEY = (id) -> "rhenium:clients:" + id;

    /**
     * The public IP address of a client which will be used by the proxy to connect to the servers.
     */
    public static final String RHENIUM_CLIENT_PUBLIC_IP_ADDRESS = "ip-address";

    /**
     * The amount of power available for a rhenium client.
     */
    public static final String RHENIUM_CLIENT_AVAILABLE_POWER = "available-power";

    /**
     * The amount of power used by a rhenium client.
     */
    public static final String RHENIUM_CLIENT_USED_POWER = "used-power";

    // ==========================================================
    //                Game Server Management
    // ==========================================================

    /**
     * Provides the key to store a game server information. The id is the unique identifier of the game server.
     */
    public static final Function<String, String> GAME_SERVER_KEY = (id) -> "rhenium:game_server:" + id;

    /**
     * The key which holds the rhenium id running the game server.
     */
    public static final String GAME_SERVER_RHENIUM_INSTANCE_ID = "rhenium-instance";

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
    public static final String GAME_SERVER_PLAYER_COUNT = "players-count";

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

    // ==========================================================
    //                Mini Game Instances Management
    // ==========================================================

    /**
     * Provides the key to store a mini-game instance information. The serverId is the unique identifier of the server
     * and the miniGameInstanceUUID is the unique identifier of the mini-game instance.
     */
    public static final BiFunction<String, String, String> MINI_GAME_INSTANCE_KEY =
            (serverId, miniGameInstanceUUID) -> "rhenium:mini-game-instance:" + serverId + ":" + miniGameInstanceUUID;

    /**
     * The key which holds the amount of players in a mini-game instance.
     */
    public static final String MINI_GAME_INSTANCE_PLAYER_COUNT = "player-count";

    /**
     * The key which holds the maximum amount of players in a mini-game instance.
     */
    public static final String MINI_GAME_INSTANCE_MAX_PLAYERS = "max-players";

    /**
     * The key which holds whether a mini-game instance is accepting players or not.
     */
    public static final String MINI_GAME_INSTANCE_ACCEPTING_PLAYERS = "accept-players";

    public static final String MINI_GAME_INSTANCE_REQUIRE_PLAYING_PLAYERS_TO_REJOIN = "require-playing-players-to-rejoin";

    /**
     * Hashset key which holds the UUIDs of the players that are playing in a mini-game instance.
     */
    public static final String PLAYERS_LAST_MINI_GAME_INSTANCE_KEY = "playing-players-last-mini-game-instance";

    // ==========================================================
    //        Pub/Sub Channels for Game Server Management
    // ==========================================================

    /**
     * Pub/Sub channel to ask the creation of a server.
     */
    public static final String CHANNEL_GAME_SERVER_CREATE = "rhenium:game_server_create";

    /**
     * Pub/Sub channel to acknowledge that a server must be created.
     */
    public static final String CHANNEL_GAME_SERVER_CREATION_ACK = "rhenium:game_server_creation_acknowledge";

    /**
     * Pub/Sub channel to notify that a server has been marked for stop.
     */
    public static final String CHANNEL_GAME_SERVER_MARKED_FOR_STOP = "rhenium:game_server_marked_for_stop";

    /**
     * Pub/Sub channel to stop a server.
     */
    public static final String CHANNEL_GAME_SERVER_STOP = "rhenium:game_server_stop";

    // ==========================================================
    //           Pub/Sub Channels for Player Management
    // ==========================================================

    /**
     * Pub/Sub channel to receive requests to move a player to a server when the player joins the proxy.
     */
    public static final String CHANNEL_PLAYER_SERVER_JOIN_REQUEST = "rhenium:player_server_join_request";

    /**
     * Pub/Sub channel to receive requests to move a player to a server.
     */
    public static final String CHANNEL_PLAYER_MOVE_REQUEST = "rhenium:player_send_to_server_request";

    /**
     * Pub/Sub channel to send a player to a server with the proxy.
     */
    public static final String CHANNEL_PLAYER_MOVE_PROXY = "rhenium:player_send_to_server_proxy";
}
