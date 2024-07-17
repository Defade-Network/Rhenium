package net.defade.rhenium.controller;

import net.defade.rhenium.Rhenium;
import net.defade.rhenium.utils.RedisConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redis.clients.jedis.params.SetParams;

import java.util.TimerTask;

public class LeaderTracker {
    private static final Logger LOGGER = LogManager.getLogger(LeaderTracker.class);

    private final Rhenium rhenium;

    private boolean isLeader = false;

    public LeaderTracker(Rhenium rhenium) {
        this.rhenium = rhenium;
    }

    public void start() {
        rhenium.getTimer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateLeader();
            }
        }, 0, 2 * 1000);
    }

    public boolean isLeader() {
        return isLeader;
    }

    /**
     * This method will try to become the leader of the swarm.
     */
    private void updateLeader() {
        String leaderId = rhenium.getJedisPool().setGet(
                RedisConstants.LEADER_ID,
                rhenium.getRheniumId(),
                SetParams.setParams().nx().px(RedisConstants.RHENIUM_CLIENT_TIMEOUT_MS)
        );

        if (rhenium.getRheniumId().equals(leaderId)) {
            if (!isLeader) LOGGER.info("This instance is now the leader of the swarm.");
            isLeader = true;

            rhenium.getJedisPool().pexpire(RedisConstants.LEADER_ID, RedisConstants.RHENIUM_CLIENT_TIMEOUT_MS); // Renew the leader key expiration
        } else {
            if (isLeader) LOGGER.info("This instance is no longer the leader of the swarm.");
            isLeader = false;
        }
    }
}
