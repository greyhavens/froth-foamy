package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import com.threerings.froth.internal.CallbackDispatcher;
import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam game server interface.
 */
public class SteamGameServer
{
  /** The available server modes. */
  public enum ServerMode {
    INVALID, NO_AUTHENTICATION, AUTHENTICATION, AUTHENTICATION_AND_SECURE };

  /** Denial codes for {@code sendUserConnectAndAuthenticate}. */
  public enum DenyReason {
    INVALID, INVALID_VERSION, GENERIC, NOT_LOGGED_ON, NO_LICENSE, CHEATER,
    LOGGED_IN_ELSEWHERE, UNKNOWN_TEXT, INCOMPATIBLE_ANTICHEAT, MEMORY_CORRUPTION,
    INCOMPATIBLE_SOFTWARE, STEAM_CONNECTION_LOST, STEAM_CONNECTION_ERROR,
    STEAM_RESPONSE_TIMED_OUT, STEAM_VALIDATION_STALLED, STEAM_OWNER_LEFT_GUEST_USER };

  /** Result codes for {@link #beginAuthSession}. */
  public enum BeginAuthSessionResult {
    OK, INVALID_TICKET, DUPLICATE_REQUEST, INVALID_VERSION, GAME_MISMATCH, EXPIRED_TICKET };

  /** Response codes for {@link #beginAuthSession}. Ordinals correspond to native
   *  {@code EAuthSessionResponse} values. */
  public enum AuthSessionResponse {
    // Note: ordinals correspond to native EAuthSessionResponse values. Do not reorder!
    OK, USER_NOT_CONNECTED_TO_STEAM, NO_LICENSE_OR_EXPIRED, VAC_BANNED,
    LOGGED_IN_ELSEWHERE, VAC_CHECK_TIMED_OUT, AUTH_TICKET_CANCELED,
    AUTH_TICKET_INVALID_ALREADY_USED, AUTH_TICKET_INVALID,
    PUBLISHER_ISSUED_BAN, AUTH_TICKET_NETWORK_IDENTITY_FAILURE };

  /**
   * A callback interface for parties interested in the response to
   * {@code sendUserConnectAndAuthenticate}.
   *
   * @deprecated The legacy {@code SendUserConnectAndAuthenticate} flow is marked
   *     {@code _DEPRECATED} in the Steamworks SDK and is slated for removal. Use
   *     {@link #beginAuthSession} with an {@link AuthSessionCallback} for new code.
   */
  @Deprecated
  public interface AuthenticateCallback
  {
    /**
     * Indicates that a client's request to authenticate was approved.
     */
    public void clientApprove ();

    /**
     * Indicates that a client's request to authenticate was denied.
     */
    public void clientDeny (DenyReason denyReason, String optionalText);
  }

  /**
   * A callback interface for parties interested in the response to {@link #beginAuthSession}.
   */
  public interface AuthSessionCallback
  {
    /**
     * Contains the response to a request to validate an auth ticket.
     */
    public void validateAuthTicketResponse (AuthSessionResponse authSessionResponse);
  }

  /**
   * Initializes the game server interface.
   *
   * @return whether or not the interface initialized successfully.
   */
  public static boolean init (
    int ip, short gamePort, short queryPort, ServerMode serverMode, String versionString)
  {
    if (!SteamAPI._haveLib) {
      return false;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment versions = arena.allocateFrom(VERSIONS_BUNDLE);
      MemorySegment versionStr = CSteam.allocCString(arena, versionString);
      int rc = (int) CSteam.SteamInternal_GameServer_Init_V2.invokeExact(
        ip, gamePort, queryPort, serverMode.ordinal(),
        versionStr, versions, MemorySegment.NULL);
      if (rc != 0) {
        return false;
      }
      // Same manual-dispatch flag as the client pipe; safe to call repeatedly.
      CSteam.SteamAPI_ManualDispatch_Init.invokeExact();
      // Wire up GS-pipe-only handlers for client approve/deny callbacks. These are
      // shared by every AuthenticateCallback we ever register, dispatched per-steamID.
      DISPATCHER.setBroadcastHandler(CB_GSClientApprove, seg -> {
        long steamId = seg.get(ValueLayout.JAVA_LONG, CSteam.GSAPPROVE_OFFSET_STEAMID);
        AuthenticateCallback cb = _authenticateCallbacks.remove(steamId);
        if (cb != null) {
          cb.clientApprove();
        }
      });
      DISPATCHER.setBroadcastHandler(CB_GSClientDeny, seg -> {
        long steamId = seg.get(ValueLayout.JAVA_LONG, CSteam.GSDENY_OFFSET_STEAMID);
        int reason = seg.get(ValueLayout.JAVA_INT, CSteam.GSDENY_OFFSET_REASON);
        String text = CSteam.readFixedCString(
          seg, CSteam.GSDENY_OFFSET_OPTIONALTEXT, CSteam.GSDENY_OPTIONALTEXT_LEN);
        AuthenticateCallback cb = _authenticateCallbacks.remove(steamId);
        if (cb != null) {
          DenyReason[] reasons = DenyReason.values();
          cb.clientDeny(
            (reason >= 0 && reason < reasons.length) ?
              reasons[reason] : DenyReason.INVALID,
            text);
        }
      });
      _initialized = true;
      return true;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Checks whether the Steam API was successfully initialized.
   */
  public static boolean isInitialized ()
  {
    return _initialized;
  }

  /**
   * Shuts down the game server interface.
   */
  public static void shutdown ()
  {
    if (!_initialized) {
      return;
    }
    try {
      CSteam.SteamGameServer_Shutdown.invokeExact();
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    } finally {
      _initialized = false;
    }
  }

  /**
   * Runs any callbacks from Steam.
   */
  public static void runCallbacks ()
  {
    if (!_initialized) {
      return;
    }
    DISPATCHER.runFrame();
  }

  /**
   * Returns the server's steam ID.
   */
  public static long getSteamID ()
  {
    try {
      return (long) CSteam.ISteamGameServer_GetSteamID.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Attempts to begin an auth session.
   */
  public static BeginAuthSessionResult beginAuthSession (
    ByteBuffer ticket, long steamId, AuthSessionCallback callback)
  {
    try {
      int rc = (int) CSteam.ISteamGameServer_BeginAuthSession.invokeExact(
        self(),
        CSteam.ofBuffer(ticket),
        ticket.limit(),
        steamId);
      BeginAuthSessionResult[] vals = BeginAuthSessionResult.values();
      BeginAuthSessionResult result = (rc >= 0 && rc < vals.length) ?
        vals[rc] : BeginAuthSessionResult.INVALID_TICKET;

      if (result == BeginAuthSessionResult.OK) {
        // Steam fires ValidateAuthTicketResponse_t (broadcast, not a CCallResult)
        // when the ticket validation completes. We track the registration by
        // steamID so multiple sessions in flight don't clobber each other.
        _authSessionCallbacks.put(steamId, callback);
        if (!_authSessionHandlerInstalled) {
          DISPATCHER.setBroadcastHandler(CB_ValidateAuthTicketResponse, seg -> {
            long sid = seg.get(ValueLayout.JAVA_LONG, CSteam.VATR_OFFSET_STEAMID);
            int authResp = seg.get(
              ValueLayout.JAVA_INT, CSteam.VATR_OFFSET_AUTHRESPONSE);
            AuthSessionCallback cb = _authSessionCallbacks.remove(sid);
            if (cb != null) {
              AuthSessionResponse[] arVals = AuthSessionResponse.values();
              cb.validateAuthTicketResponse(
                (authResp >= 0 && authResp < arVals.length) ?
                  arVals[authResp] : AuthSessionResponse.AUTH_TICKET_INVALID);
            }
          });
          _authSessionHandlerInstalled = true;
        }
      }
      return result;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Ends the auth session for the specified id.
   */
  public static void endAuthSession (long steamId)
  {
    try {
      CSteam.ISteamGameServer_EndAuthSession.invokeExact(self(), steamId);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // TODO: The {@link AuthenticateCallback} interface is preserved from the original
  // froth API but was never wired up to a registration call there either -- it was
  // designed for a {@code SendUserConnectAndAuthenticate} flow that was never added.
  // We dispatch GSClientApprove_t/GSClientDeny_t into _authenticateCallbacks above so
  // that a future public registration method can be added here without rewiring.

  private static MemorySegment self ()
  {
    MemorySegment s = _self;
    if (s == null) {
      s = SteamAPI.iface(CSteam.SteamAPI_SteamGameServer);
      _self = s;
    }
    return s;
  }

  /** Whether or not we have successfully initialized. */
  protected static boolean _initialized;

  /**
   * NUL-separated list of game-server-side interface versions, as required by
   * {@code SteamInternal_GameServer_Init_V2}. Sourced from the SDK 1.64
   * steam_gameserver.h.
   */
  private static final String VERSIONS_BUNDLE =
    "SteamUtils010" + '\0' +
    "SteamNetworkingUtils004" + '\0' +
    "SteamGameServer015" + '\0' +
    "SteamGameServerStats001" + '\0' +
    "STEAMHTTP_INTERFACE_VERSION003" + '\0' +
    "STEAMINVENTORY_INTERFACE_V003" + '\0' +
    "SteamNetworking006" + '\0' +
    "SteamNetworkingMessages002" + '\0' +
    "SteamNetworkingSockets012" + '\0' +
    "STEAMUGC_INTERFACE_VERSION021" + '\0';

  /** Callback IDs. */
  private static final int CB_ValidateAuthTicketResponse = 143; // k_iSteamUserCallbacks + 43
  private static final int CB_GSClientApprove            = 201; // k_iSteamGameServerCallbacks + 1
  private static final int CB_GSClientDeny               = 202; // k_iSteamGameServerCallbacks + 2

  /** Per-pipe dispatcher for the game server. */
  private static final CallbackDispatcher DISPATCHER = new CallbackDispatcher(
    "SteamGameServer",
    () -> (int) CSteam.SteamGameServer_GetHSteamPipe.invokeExact());

  /** Pending authenticate callbacks keyed by client steamID. */
  private static final Map<Long, AuthenticateCallback> _authenticateCallbacks =
    new ConcurrentHashMap<>();

  /** Pending auth-session callbacks keyed by client steamID. */
  private static final Map<Long, AuthSessionCallback> _authSessionCallbacks =
    new ConcurrentHashMap<>();

  /** Once-only flag so we install the ValidateAuthTicketResponse handler at most once. */
  private static volatile boolean _authSessionHandlerInstalled;

  private static volatile MemorySegment _self;
}
