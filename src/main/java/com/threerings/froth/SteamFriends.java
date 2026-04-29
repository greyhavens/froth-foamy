//
// $Id$

package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CopyOnWriteArrayList;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam friends interface.
 */
public class SteamFriends
{
    /** The various states a user can be in. */
    public enum PersonaState {
        // these values have ordinals that correspond directly to the steam API values
        OFFLINE, ONLINE, BUSY, AWAY, SNOOZE, LOOKING_TO_TRADE, LOOKING_TO_PLAY,

        // UNKNOWN is added to the end and used for any unknown (newly-added) constants arriving
        // from steam. If you add more constants in the future they should be added before UNKNOWN.
        UNKNOWN
    };

    /** Different values when opening the store overlay. */
    public enum OverlayToStoreFlag {
        NONE, ADD_TO_CART, ADD_TO_CART_AND_SHOW
    };

    /**
     * Used to communicate activation and deactivation of the game overlay.
     */
    public interface GameOverlayActivationCallback
    {
        /**
         * Called when the user activates or deactivates the game overlay.
         */
        public void gameOverlayActivated (boolean active);
    }

    /**
     * Used to communicate requests to join a friend.
     */
    public interface GameRichPresenceJoinRequestCallback
    {
        /**
         * Called when a friend makes a rich presence join request to us.
         */
        public void gameRichPresenceJoinRequested (long steamIdFriend, String connect);
    }

    /** Flag for "regular" friends. */
    public static final int FRIEND_FLAG_IMMEDIATE = 0x04;

    /** A special rich presence key whose value is visible in the Steam friends list. */
    public static final String STATUS_KEY = "status";

    /** A special rich presence key providing information on how to connect to a player. */
    public static final String CONNECT_KEY = "connect";

    /**
     * Adds a listener for game overlay activation callbacks.
     */
    public static void addGameOverlayActivationCallback (GameOverlayActivationCallback callback)
    {
        if (_gameOverlayActivationCallbacks.isEmpty()) {
            SteamAPI.dispatcher().setBroadcastHandler(CB_GameOverlayActivated, seg -> {
                boolean active = seg.get(
                    ValueLayout.JAVA_BYTE, CSteam.OVERLAY_OFFSET_ACTIVE) != 0;
                for (GameOverlayActivationCallback cb : _gameOverlayActivationCallbacks) {
                    cb.gameOverlayActivated(active);
                }
            });
        }
        _gameOverlayActivationCallbacks.add(callback);
    }

    /**
     * Removes a game overlay activation callback listener.
     */
    public static void removeGameOverlayActivationCallback (GameOverlayActivationCallback callback)
    {
        _gameOverlayActivationCallbacks.remove(callback);
    }

    /**
     * Adds a listener for rich presence join request callbacks.
     */
    public static void addGameRichPresenceJoinRequestCallback (
        GameRichPresenceJoinRequestCallback callback)
    {
        if (_gameRichPresenceJoinRequestCallbacks.isEmpty()) {
            SteamAPI.dispatcher().setBroadcastHandler(CB_GameRichPresenceJoinRequested, seg -> {
                long steamIdFriend = seg.get(
                    ValueLayout.JAVA_LONG, CSteam.RICH_JOIN_OFFSET_FRIEND);
                String connect = CSteam.readFixedCString(
                    seg, CSteam.RICH_JOIN_OFFSET_CONNECT, CSteam.RICH_PRESENCE_VALUE_MAX);
                for (GameRichPresenceJoinRequestCallback cb :
                    _gameRichPresenceJoinRequestCallbacks)
                {
                    cb.gameRichPresenceJoinRequested(steamIdFriend, connect);
                }
            });
        }
        _gameRichPresenceJoinRequestCallbacks.add(callback);
    }

    /**
     * Removes a rich presence join request callback listener.
     */
    public static void removeGameRichPresenceJoinRequestCallback (
        GameRichPresenceJoinRequestCallback callback)
    {
        _gameRichPresenceJoinRequestCallbacks.remove(callback);
    }

    /**
     * Returns the local user's persona name.
     */
    public static String getPersonaName ()
    {
        try {
            MemorySegment ptr = (MemorySegment) CSteam.ISteamFriends_GetPersonaName
                .invokeExact(self());
            return CSteam.readCString(ptr);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Returns the number of friends with properties identified by the specified flags.
     */
    public static int getFriendCount (int flags)
    {
        try {
            return (int) CSteam.ISteamFriends_GetFriendCount.invokeExact(self(), flags);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Returns the Steam ID of the friend at the specified index.
     */
    public static long getFriendByIndex (int index, int flags)
    {
        try {
            return (long) CSteam.ISteamFriends_GetFriendByIndex.invokeExact(self(), index, flags);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Retrieves the state of a friend.
     */
    public static PersonaState getFriendPersonaState (long steamId)
    {
        try {
            int state = (int) CSteam.ISteamFriends_GetFriendPersonaState
                .invokeExact(self(), steamId);
            // Same enum-compatibility safety net as the original froth: anything outside
            // the known enum range is reported as UNKNOWN rather than crashing the caller.
            PersonaState[] vals = PersonaState.values();
            return (state >= 0 && state < vals.length - 1) ? vals[state] : PersonaState.UNKNOWN;
        } catch (Throwable t) {
            return PersonaState.UNKNOWN;
        }
    }

    /**
     * Returns the persona name of the friend with the supplied id.
     */
    public static String getFriendPersonaName (long steamId)
    {
        try {
            MemorySegment ptr = (MemorySegment) CSteam.ISteamFriends_GetFriendPersonaName
                .invokeExact(self(), steamId);
            return CSteam.readCString(ptr);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Notes that the user is using the in-game voice chat (in order to mute the friends chat).
     */
    public static void setInGameVoiceSpeaking (long steamId, boolean speaking)
    {
        try {
            CSteam.ISteamFriends_SetInGameVoiceSpeaking.invokeExact(self(), steamId, speaking);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Activates the game overlay and opens the identified web page.
     */
    public static void activateGameOverlayToWebPage (String url)
    {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment urlSeg = CSteam.allocCString(arena, url);
            // EActivateGameOverlayToWebPageMode: 0 = Default (matches froth's prior call,
            // which did not pass the optional second arg before its addition in 1.42).
            CSteam.ISteamFriends_ActivateGameOverlayToWebPage.invokeExact(self(), urlSeg, 0);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Activates the game overlay and opens the identified store page.
     */
    public static void activateGameOverlayToStore (int appId, OverlayToStoreFlag flag)
    {
        try {
            CSteam.ISteamFriends_ActivateGameOverlayToStore.invokeExact(
                self(), appId, flag.ordinal());
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Sets a "rich presence" value for friends to see.
     */
    public static boolean setRichPresence (String key, String value)
    {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment k = CSteam.allocCString(arena, key);
            MemorySegment v = CSteam.allocCString(arena, value);
            return (boolean) CSteam.ISteamFriends_SetRichPresence.invokeExact(self(), k, v);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Retrieves a friend's rich presence value.
     */
    public static String getFriendRichPresence (long steamId, String key)
    {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment k = CSteam.allocCString(arena, key);
            MemorySegment ptr = (MemorySegment) CSteam.ISteamFriends_GetFriendRichPresence
                .invokeExact(self(), steamId, k);
            return CSteam.readCString(ptr);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    /**
     * Invites a friend to the game.
     */
    public static boolean inviteUserToGame (long steamIdFriend, String connect)
    {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = CSteam.allocCString(arena, connect);
            return (boolean) CSteam.ISteamFriends_InviteUserToGame.invokeExact(
                self(), steamIdFriend, c);
        } catch (Throwable t) {
            throw SteamAPI.wrap(t);
        }
    }

    private static MemorySegment self ()
    {
        MemorySegment s = _self;
        if (s == null) {
            s = SteamAPI.iface(CSteam.SteamAPI_SteamFriends);
            _self = s;
        }
        return s;
    }

    /** Callback IDs (k_iSteamFriendsCallbacks = 300). */
    private static final int CB_GameOverlayActivated         = 331;
    private static final int CB_GameRichPresenceJoinRequested = 337;

    private static final CopyOnWriteArrayList<GameOverlayActivationCallback>
        _gameOverlayActivationCallbacks = new CopyOnWriteArrayList<>();

    private static final CopyOnWriteArrayList<GameRichPresenceJoinRequestCallback>
        _gameRichPresenceJoinRequestCallbacks = new CopyOnWriteArrayList<>();

    private static volatile MemorySegment _self;
}
