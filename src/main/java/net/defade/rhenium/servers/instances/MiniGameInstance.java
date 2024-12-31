package net.defade.rhenium.servers.instances;

import com.google.gson.JsonObject;

import java.util.UUID;

public class MiniGameInstance {
    private final String serverId;
    private final UUID miniGameInstanceId;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final boolean isAcceptingPlayers;
    private final boolean requirePlayingPlayersToRejoin;


    public MiniGameInstance(String serverId, UUID miniGameInstanceId, int onlinePlayers, int maxPlayers, boolean isAcceptingPlayers, boolean requirePlayingPlayersToRejoin) {
        this.serverId = serverId;
        this.miniGameInstanceId = miniGameInstanceId;
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
        this.isAcceptingPlayers = isAcceptingPlayers;
        this.requirePlayingPlayersToRejoin = requirePlayingPlayersToRejoin;
    }

    public MiniGameInstance(String serverId, UUID miniGameInstanceId, JsonObject jsonObject) {
        this.serverId = serverId;
        this.miniGameInstanceId = miniGameInstanceId;
        this.onlinePlayers = jsonObject.get("online-players").getAsInt();
        this.maxPlayers = jsonObject.get("max-players").getAsInt();
        this.isAcceptingPlayers = jsonObject.get("accepting-players").getAsBoolean();
        this.requirePlayingPlayersToRejoin = jsonObject.get("require-players-to-rejoin").getAsBoolean();
    }

    public String getServerId() {
        return serverId;
    }

    public UUID getMiniGameInstanceId() {
        return miniGameInstanceId;
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public boolean isAcceptingPlayers() {
        return isAcceptingPlayers;
    }

    public boolean requirePlayingPlayersToRejoin() {
        return requirePlayingPlayersToRejoin;
    }

    public String toJson() {
        JsonObject jsonObject = new JsonObject();

        jsonObject.addProperty("online-players", onlinePlayers);
        jsonObject.addProperty("max-players", maxPlayers);
        jsonObject.addProperty("accepting-players", isAcceptingPlayers);
        jsonObject.addProperty("require-players-to-rejoin", requirePlayingPlayersToRejoin);

        return jsonObject.toString();
    }
}
