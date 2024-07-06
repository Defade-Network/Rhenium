package net.defade.rhenium.utils;

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
     * Amount of time the leader has to take ownership of the key in milliseconds.
     */
    public static final int RHENIUM_CLIENT_TIMEOUT = 5000;
}
