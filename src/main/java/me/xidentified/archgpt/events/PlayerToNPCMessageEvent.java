package me.xidentified.archgpt.events;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired whenever a player sends a message to an ArchGPT NPC as part of a conversation.
 * Carries the message content, player, NPC name, and NPC location at the time of sending.
 */
public class PlayerToNPCMessageEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String message;
    private final String npcName;
    private final Location npcLocation;

    public PlayerToNPCMessageEvent(Player player, String message, String npcName, Location npcLocation) {
        this.player = player;
        this.message = message;
        this.npcName = npcName;
        this.npcLocation = npcLocation == null ? null : npcLocation.clone();
    }

    public Player getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }

    public String getNpcName() {
        return npcName;
    }

    public Location getNpcLocation() {
        return npcLocation == null ? null : npcLocation.clone();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
