package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CopyOnWriteArrayList;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam matchmaking interface.
 */
public class SteamMatchmaking
{
  /** The available lobby types. */
  public enum LobbyType { PRIVATE, FRIENDS_ONLY, PUBLIC, INVISIBLE };

  /** Result codes. */
  public enum Result { OK, NO_CONNECTION, TIMEOUT, FAIL, ACCESS_DENIED, LIMIT_EXCEEDED };

  /** Chat room enter request responses. */
  public enum ChatRoomEnterResponse {
    SUCCESS, DOESNT_EXIST, NOT_ALLOWED, FULL, ERROR,
    BANNED, LIMITED, CLAN_DISABLED, COMMUNITY_BAN };

  /**
   * Used to communicate the result of a lobby creation request.
   */
  public interface CreateLobbyCallback
  {
    /**
     * Called to deliver the result of a lobby creation request.
     */
    public void createLobbyResponse (Result result, long steamIdLobby);
  }

  /**
   * Used to communicate the result of a lobby enter request.
   */
  public interface EnterLobbyCallback
  {
    /**
     * Called to deliver the result of a lobby enter request.
     */
    public void enterLobbyResponse (
      long steamIdLobby, int chatPermissions, boolean locked,
      ChatRoomEnterResponse response);
  }

  /**
   * Used to communicate requests to join a game lobby.
   */
  public interface GameLobbyJoinRequestCallback
  {
    /**
     * Called when someone invites the user to a game lobby.
     */
    public void gameLobbyJoinRequest (long steamIdLobby, long steamIdFriend);
  }

  /**
   * Adds a listener for game lobby join request callbacks.
   */
  public static void addGameLobbyJoinRequestCallback (GameLobbyJoinRequestCallback callback)
  {
    if (_gameLobbyJoinRequestCallbacks.isEmpty()) {
      SteamAPI.dispatcher().setBroadcastHandler(CB_GameLobbyJoinRequested, seg -> {
        long lobby = seg.get(ValueLayout.JAVA_LONG, CSteam.LOBBY_JOIN_OFFSET_LOBBY);
        long friend = seg.get(ValueLayout.JAVA_LONG, CSteam.LOBBY_JOIN_OFFSET_FRIEND);
        for (GameLobbyJoinRequestCallback cb : _gameLobbyJoinRequestCallbacks) {
          cb.gameLobbyJoinRequest(lobby, friend);
        }
      });
    }
    _gameLobbyJoinRequestCallbacks.add(callback);
  }

  /**
   * Removes a game lobby join request callback listener.
   */
  public static void removeGameLobbyJoinRequestCallback (GameLobbyJoinRequestCallback callback)
  {
    _gameLobbyJoinRequestCallbacks.remove(callback);
  }

  /**
   * Requests to create a lobby.
   */
  public static void createLobby (LobbyType type, int maxMembers, CreateLobbyCallback callback)
  {
    try {
      long hCall = (long) CSteam.ISteamMatchmaking_CreateLobby.invokeExact(
        self(), type.ordinal(), maxMembers);
      // LobbyCreated_t struct: { EResult m_eResult; uint64 m_ulSteamIDLobby; }.
      // Size differs by packing: 12 bytes on Linux/macOS pack(4), 16 on Windows pack(8).
      int structSize = (int) (CSteam.LOBBYCREATED_OFFSET_LOBBYID + 8);
      SteamAPI.dispatcher().registerCallResult(hCall, CB_LobbyCreated, structSize,
        (resultSeg, ioFailure) -> {
          int eresult = ioFailure ? E_RESULT_FAIL :
            resultSeg.get(ValueLayout.JAVA_INT, CSteam.LOBBYCREATED_OFFSET_RESULT);
          long lobbyId = ioFailure ? 0L :
            resultSeg.get(ValueLayout.JAVA_LONG, CSteam.LOBBYCREATED_OFFSET_LOBBYID);
          callback.createLobbyResponse(eresultToEnum(eresult), lobbyId);
        });
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Requests to join a lobby.
   */
  public static void joinLobby (long steamIdLobby, EnterLobbyCallback callback)
  {
    try {
      long hCall = (long) CSteam.ISteamMatchmaking_JoinLobby.invokeExact(
        self(), steamIdLobby);
      // LobbyEnter_t struct fields land at offsets 0/8/12/16 in both packings.
      int structSize = 24; // covers both pack(4)=20 and pack(8)=24
      SteamAPI.dispatcher().registerCallResult(hCall, CB_LobbyEnter, structSize,
        (resultSeg, ioFailure) -> {
          if (ioFailure) {
            callback.enterLobbyResponse(
              steamIdLobby, 0, false, ChatRoomEnterResponse.ERROR);
            return;
          }
          long lobby = resultSeg.get(
            ValueLayout.JAVA_LONG, CSteam.LOBBYENTER_OFFSET_LOBBYID);
          int perms = resultSeg.get(
            ValueLayout.JAVA_INT, CSteam.LOBBYENTER_OFFSET_CHATPERMS);
          boolean locked = resultSeg.get(
            ValueLayout.JAVA_BYTE, CSteam.LOBBYENTER_OFFSET_LOCKED) != 0;
          int rc = resultSeg.get(
            ValueLayout.JAVA_INT, CSteam.LOBBYENTER_OFFSET_CHATROOMENTERRESPONSE);
          callback.enterLobbyResponse(lobby, perms, locked, chatRoomEnterToEnum(rc));
        });
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Leaves a lobby.
   */
  public static void leaveLobby (long steamIdLobby)
  {
    try {
      CSteam.ISteamMatchmaking_LeaveLobby.invokeExact(self(), steamIdLobby);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Invites another user to a lobby.
   */
  public static boolean inviteUserToLobby (long steamIdLobby, long steamIdInvitee)
  {
    try {
      return (boolean) CSteam.ISteamMatchmaking_InviteUserToLobby.invokeExact(
        self(), steamIdLobby, steamIdInvitee);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Retrieves a piece of data associated with a lobby.
   */
  public static String getLobbyData (long steamIdLobby, String key)
  {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment k = CSteam.allocCString(arena, key);
      MemorySegment ptr = (MemorySegment) CSteam.ISteamMatchmaking_GetLobbyData
        .invokeExact(self(), steamIdLobby, k);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Sets a piece of data associated with a lobby.
   */
  public static boolean setLobbyData (long steamIdLobby, String key, String value)
  {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment k = CSteam.allocCString(arena, key);
      MemorySegment v = CSteam.allocCString(arena, value);
      return (boolean) CSteam.ISteamMatchmaking_SetLobbyData.invokeExact(
        self(), steamIdLobby, k, v);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Maps a Steam {@code EResult} value to our public {@link Result} enum. Steam's
   * EResult is a sparse, growing enum -- we map the six values that the original froth
   * exposed, returning {@link Result#FAIL} for anything else (matching the prior
   * behavior, which left the result {@code null} for unknown codes -- callers expect to
   * see "something failed" rather than NPE).
   */
  private static Result eresultToEnum (int eresult)
  {
    return switch (eresult) {
      case 1  -> Result.OK;             // k_EResultOK
      case 3  -> Result.NO_CONNECTION;  // k_EResultNoConnection
      case 16 -> Result.TIMEOUT;        // k_EResultTimeout
      case 2  -> Result.FAIL;           // k_EResultFail (generic)
      case 15 -> Result.ACCESS_DENIED;  // k_EResultAccessDenied
      case 25 -> Result.LIMIT_EXCEEDED; // k_EResultLimitExceeded
      default -> Result.FAIL;
    };
  }

  /**
   * Maps a Steam {@code EChatRoomEnterResponse} value (note: starts at 1, not 0) to
   * our {@link ChatRoomEnterResponse}. Matches the original froth's name-keyed switch.
   */
  private static ChatRoomEnterResponse chatRoomEnterToEnum (int response)
  {
    return switch (response) {
      case 1 -> ChatRoomEnterResponse.SUCCESS;
      case 2 -> ChatRoomEnterResponse.DOESNT_EXIST;
      case 3 -> ChatRoomEnterResponse.NOT_ALLOWED;
      case 4 -> ChatRoomEnterResponse.FULL;
      case 5 -> ChatRoomEnterResponse.ERROR;
      case 6 -> ChatRoomEnterResponse.BANNED;
      case 7 -> ChatRoomEnterResponse.LIMITED;
      case 8 -> ChatRoomEnterResponse.CLAN_DISABLED;
      case 9 -> ChatRoomEnterResponse.COMMUNITY_BAN;
      default -> ChatRoomEnterResponse.ERROR; // unknown values from newer SDKs
    };
  }

  private static MemorySegment self ()
  {
    MemorySegment s = _self;
    if (s == null) {
      s = SteamAPI.iface(CSteam.SteamAPI_SteamMatchmaking);
      _self = s;
    }
    return s;
  }

  /** Steam EResult value used as the "fail" sentinel when an IO failure pre-empts the result. */
  private static final int E_RESULT_FAIL = 2; // k_EResultFail

  /** Callback IDs for one-shot CCallResult deliveries. */
  private static final int CB_LobbyEnter   = 504; // k_iSteamMatchmakingCallbacks + 4
  private static final int CB_LobbyCreated = 513; // k_iSteamMatchmakingCallbacks + 13

  /** Broadcast callback ID. {@code GameLobbyJoinRequested_t} lives in iSteamFriends (300) + 33. */
  private static final int CB_GameLobbyJoinRequested = 333;

  private static final CopyOnWriteArrayList<GameLobbyJoinRequestCallback>
    _gameLobbyJoinRequestCallbacks = new CopyOnWriteArrayList<>();

  private static volatile MemorySegment _self;
}
