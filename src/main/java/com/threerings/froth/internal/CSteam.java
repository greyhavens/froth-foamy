package com.threerings.froth.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;

/**
 * Internal FFM glue for the Steamworks shared library. Loads {@code steam_api} (or
 * {@code steam_api64} on Windows), defines {@link MethodHandle} downcalls for every flat
 * C function we invoke, and provides marshaling helpers for strings, buffers, SteamIDs,
 * and the manual-dispatch callback structures.
 *
 * <p>This class is package-internal; callers should only use the {@code com.threerings.froth}
 * public classes, which mirror the original froth API one-for-one.
 */
public final class CSteam
{
  // ====================================================================================
  // Section 1: Linker, library lookup, load state
  // ====================================================================================

  private static final Linker LINKER = Linker.nativeLinker();

  /** True if the Steam shared library was loaded successfully. */
  public static final boolean LIB_LOADED;

  /** Symbol lookup for the loaded library, or null if it failed to load. */
  private static final SymbolLookup LIB;

  static {
    boolean loaded = false;
    SymbolLookup lookup = null;
    if (!Boolean.getBoolean("com.threerings.froth.disable_steam_api")) {
      // Java 25 only ships 64-bit, so we try the platform-appropriate library name. Linux
      // and macOS use libsteam_api.{so,dylib} (just "steam_api"), Windows uses steam_api64.
      String osName = System.getProperty("os.name", "").toLowerCase();
      boolean isWindows = osName.contains("win");
      try {
        System.loadLibrary(isWindows ? "steam_api64" : "steam_api");
        lookup = SymbolLookup.loaderLookup();
        loaded = true;
      } catch (UnsatisfiedLinkError outer) {
        // Fall back to the other name in case someone has placed the lib oddly.
        try {
          System.loadLibrary(isWindows ? "steam_api" : "steam_api64");
          lookup = SymbolLookup.loaderLookup();
          loaded = true;
        } catch (UnsatisfiedLinkError inner) {
          // Leave LIB_LOADED = false; downcalls will not be initialized below.
        }
      }
    }
    LIB_LOADED = loaded;
    LIB = lookup;
  }

  /**
   * Steam structure packing: SMALL ({@code #pragma pack(4)}) on Linux/macOS, LARGE
   * ({@code pack(8)}) on Windows. This affects how {@code uint64} fields are aligned
   * when they follow {@code uint32} fields in callback structs.
   */
  public static final boolean PACK_SMALL =
    !System.getProperty("os.name", "").toLowerCase().contains("win");

  // ====================================================================================
  // Section 2: ValueLayout aliases used in descriptors
  // ====================================================================================

  private static final ValueLayout.OfBoolean BOOL = ValueLayout.JAVA_BOOLEAN;
  private static final ValueLayout.OfByte    I8   = ValueLayout.JAVA_BYTE;
  private static final ValueLayout.OfShort   I16  = ValueLayout.JAVA_SHORT;
  private static final ValueLayout.OfInt     I32  = ValueLayout.JAVA_INT;
  private static final ValueLayout.OfLong    I64  = ValueLayout.JAVA_LONG;
  private static final ValueLayout PTR  = ValueLayout.ADDRESS;

  // ====================================================================================
  // Section 3: Struct layouts and field offsets
  // ====================================================================================
  //
  // Declared before any downcall that references them as a return type, since static
  // field initializers run in source order.

  /**
   * {@code CallbackMsg_t}: { HSteamUser m_hSteamUser; int m_iCallback; uint8* m_pubParam; int m_cubParam; }
   *
   * <p>On 64-bit Linux/macOS (pack 4) and Windows (pack 8), the field offsets land at
   * the same locations: 0/4/8/16 with the pointer naturally 8-byte aligned and the
   * trailing int 4-byte aligned. We declare a layout with explicit padding so the
   * struct size is well-defined for allocation.
   */
  public static final StructLayout CALLBACK_MSG_LAYOUT = MemoryLayout.structLayout(
    ValueLayout.JAVA_INT.withName("hSteamUser"),
    ValueLayout.JAVA_INT.withName("iCallback"),
    ValueLayout.ADDRESS.withName("pubParam"),
    ValueLayout.JAVA_INT.withName("cubParam"),
    MemoryLayout.paddingLayout(4) // round size to 24 (8-byte multiple)
  );
  public static final long CB_OFFSET_HSTEAMUSER = 0;
  public static final long CB_OFFSET_ICALLBACK  = 4;
  public static final long CB_OFFSET_PUBPARAM   = 8;
  public static final long CB_OFFSET_CUBPARAM   = 16;

  /** {@code SteamAPICallCompleted_t}: { uint64 hAsyncCall; int iCallback; uint32 cubParam; } */
  public static final long APICOMPLETED_OFFSET_HASYNCCALL = 0;
  public static final long APICOMPLETED_OFFSET_ICALLBACK  = 8;
  public static final long APICOMPLETED_OFFSET_CUBPARAM   = 12;

  /** {@code MicroTxnAuthorizationResponse_t}: { uint32 appID; uint64 orderID; uint8 authorized; } */
  public static final long MICROTXN_OFFSET_APPID      = 0;
  public static final long MICROTXN_OFFSET_ORDERID    = PACK_SMALL ? 4  : 8;
  public static final long MICROTXN_OFFSET_AUTHORIZED = PACK_SMALL ? 12 : 16;

  /** {@code ValidateAuthTicketResponse_t}: { uint64 steamID; int authResponse; uint64 ownerSteamID; } */
  public static final long VATR_OFFSET_STEAMID      = 0;
  public static final long VATR_OFFSET_AUTHRESPONSE = 8;
  // We don't read m_OwnerSteamID, so we don't need its offset.

  /** {@code GameOverlayActivated_t}: { uint8 m_bActive; ... } */
  public static final long OVERLAY_OFFSET_ACTIVE = 0;

  /** {@code GameLobbyJoinRequested_t}: { uint64 lobby; uint64 friend; } */
  public static final long LOBBY_JOIN_OFFSET_LOBBY  = 0;
  public static final long LOBBY_JOIN_OFFSET_FRIEND = 8;

  /** {@code GameRichPresenceJoinRequested_t}: { uint64 friendID; char[256] connect; } */
  public static final long RICH_JOIN_OFFSET_FRIEND  = 0;
  public static final long RICH_JOIN_OFFSET_CONNECT = 8;
  /** {@code k_cchMaxRichPresenceValueLength} from steamclientpublic.h */
  public static final int  RICH_PRESENCE_VALUE_MAX  = 256;

  /** {@code DlcInstalled_t}: { AppId_t (uint32) appID; } */
  public static final long DLC_OFFSET_APPID = 0;

  /** {@code P2PSessionRequest_t}: { uint64 steamIDRemote; } */
  public static final long P2P_REQ_OFFSET_REMOTE = 0;

  /** {@code P2PSessionConnectFail_t}: { uint64 steamIDRemote; uint8 sessionError; } */
  public static final long P2P_FAIL_OFFSET_REMOTE = 0;
  public static final long P2P_FAIL_OFFSET_ERROR  = 8;

  /** {@code GSClientApprove_t}: { uint64 steamID; uint64 ownerSteamID; } */
  public static final long GSAPPROVE_OFFSET_STEAMID = 0;

  /** {@code GSClientDeny_t}: { uint64 steamID; int denyReason; char[128] optionalText; } */
  public static final long GSDENY_OFFSET_STEAMID      = 0;
  public static final long GSDENY_OFFSET_REASON       = 8;
  public static final long GSDENY_OFFSET_OPTIONALTEXT = 12;
  public static final int  GSDENY_OPTIONALTEXT_LEN    = 128;

  /** {@code LobbyEnter_t}: { uint64 lobbyID; uint32 chatPerms; bool locked; uint32 chatRoomEnterResponse; } */
  public static final long LOBBYENTER_OFFSET_LOBBYID              = 0;
  public static final long LOBBYENTER_OFFSET_CHATPERMS            = 8;
  public static final long LOBBYENTER_OFFSET_LOCKED               = 12;
  public static final long LOBBYENTER_OFFSET_CHATROOMENTERRESPONSE = 16;

  /** {@code LobbyCreated_t}: { EResult result; uint64 lobbyID; } */
  public static final long LOBBYCREATED_OFFSET_RESULT  = 0;
  public static final long LOBBYCREATED_OFFSET_LOBBYID = PACK_SMALL ? 4 : 8;

  /** {@code InputDigitalActionData_t}: { bool bState; bool bActive; } -- by-value return */
  public static final StructLayout DIGITAL_ACTION_DATA_LAYOUT = MemoryLayout.structLayout(
    ValueLayout.JAVA_BOOLEAN.withName("bState"),
    ValueLayout.JAVA_BOOLEAN.withName("bActive")
  );

  /** {@code InputAnalogActionData_t}: { EInputSourceMode eMode; float x; float y; bool bActive; } */
  public static final StructLayout ANALOG_ACTION_DATA_LAYOUT = MemoryLayout.structLayout(
    ValueLayout.JAVA_INT.withName("eMode"),
    ValueLayout.JAVA_FLOAT.withName("x"),
    ValueLayout.JAVA_FLOAT.withName("y"),
    ValueLayout.JAVA_BOOLEAN.withName("bActive"),
    MemoryLayout.paddingLayout(3) // align to 4
  );

  /** {@code InputMotionData_t}: ten floats (rotation quaternion + accel + angular vel). */
  public static final StructLayout MOTION_DATA_LAYOUT = MemoryLayout.structLayout(
    ValueLayout.JAVA_FLOAT.withName("rotQuatX"),
    ValueLayout.JAVA_FLOAT.withName("rotQuatY"),
    ValueLayout.JAVA_FLOAT.withName("rotQuatZ"),
    ValueLayout.JAVA_FLOAT.withName("rotQuatW"),
    ValueLayout.JAVA_FLOAT.withName("posAccelX"),
    ValueLayout.JAVA_FLOAT.withName("posAccelY"),
    ValueLayout.JAVA_FLOAT.withName("posAccelZ"),
    ValueLayout.JAVA_FLOAT.withName("rotVelX"),
    ValueLayout.JAVA_FLOAT.withName("rotVelY"),
    ValueLayout.JAVA_FLOAT.withName("rotVelZ")
  );

  // ====================================================================================
  // Section 4: Downcall plumbing
  // ====================================================================================

  /**
   * Resolve the named symbol in the Steam shared library and bind a downcall handle.
   * Returns {@code null} when the library hasn't loaded or the symbol is absent so
   * callers can no-op gracefully rather than aborting class loading.
   */
  private static MethodHandle dc (String name, FunctionDescriptor desc)
  {
    if (!LIB_LOADED) {
      return null;
    }
    var sym = LIB.find(name);
    if (sym.isEmpty()) {
      return null;
    }
    return LINKER.downcallHandle(sym.get(), desc);
  }

  // ====================================================================================
  // Section 5: Init / shutdown / steam-running / manual dispatch
  // ====================================================================================

  /** {@code ESteamAPIInitResult SteamInternal_SteamAPI_Init(const char* versionStr, SteamErrMsg* errOut)} */
  public static final MethodHandle SteamInternal_SteamAPI_Init =
    dc("SteamInternal_SteamAPI_Init", FunctionDescriptor.of(I32, PTR, PTR));

  public static final MethodHandle SteamAPI_Shutdown =
    dc("SteamAPI_Shutdown", FunctionDescriptor.ofVoid());
  public static final MethodHandle SteamAPI_IsSteamRunning =
    dc("SteamAPI_IsSteamRunning", FunctionDescriptor.of(BOOL));
  /** {@code HSteamPipe SteamAPI_GetHSteamPipe()}: HSteamPipe is a 32-bit integer handle. */
  public static final MethodHandle SteamAPI_GetHSteamPipe =
    dc("SteamAPI_GetHSteamPipe", FunctionDescriptor.of(I32));
  public static final MethodHandle SteamAPI_ManualDispatch_Init =
    dc("SteamAPI_ManualDispatch_Init", FunctionDescriptor.ofVoid());
  public static final MethodHandle SteamAPI_ManualDispatch_RunFrame =
    dc("SteamAPI_ManualDispatch_RunFrame", FunctionDescriptor.ofVoid(I32));
  public static final MethodHandle SteamAPI_ManualDispatch_GetNextCallback =
    dc("SteamAPI_ManualDispatch_GetNextCallback", FunctionDescriptor.of(BOOL, I32, PTR));
  public static final MethodHandle SteamAPI_ManualDispatch_FreeLastCallback =
    dc("SteamAPI_ManualDispatch_FreeLastCallback", FunctionDescriptor.ofVoid(I32));
  public static final MethodHandle SteamAPI_ManualDispatch_GetAPICallResult =
    dc("SteamAPI_ManualDispatch_GetAPICallResult",
      FunctionDescriptor.of(BOOL, I32, I64, PTR, I32, I32, PTR));

  // Game server lifecycle
  public static final MethodHandle SteamInternal_GameServer_Init_V2 =
    dc("SteamInternal_GameServer_Init_V2",
      FunctionDescriptor.of(I32, I32, I16, I16, I32, PTR, PTR, PTR));
  public static final MethodHandle SteamGameServer_Shutdown =
    dc("SteamGameServer_Shutdown", FunctionDescriptor.ofVoid());
  public static final MethodHandle SteamGameServer_GetHSteamPipe =
    dc("SteamGameServer_GetHSteamPipe", FunctionDescriptor.of(I32));

  // ====================================================================================
  // Section 6: Versioned interface accessors
  // ====================================================================================
  //
  // SteamAPI_Init populates the global interface pointers; we retrieve the per-interface
  // singleton via these accessors, then pass the returned pointer as the first arg to
  // every flat C method on that interface. Versions tracked against Steamworks SDK 1.64.

  public static final MethodHandle SteamAPI_SteamUser =
    dc("SteamAPI_SteamUser_v023", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamFriends =
    dc("SteamAPI_SteamFriends_v018", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamUtils =
    dc("SteamAPI_SteamUtils_v010", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamGameServerUtils =
    dc("SteamAPI_SteamGameServerUtils_v010", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamApps =
    dc("SteamAPI_SteamApps_v009", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamMatchmaking =
    dc("SteamAPI_SteamMatchmaking_v009", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamNetworking =
    dc("SteamAPI_SteamNetworking_v006", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamGameServer =
    dc("SteamAPI_SteamGameServer_v015", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamGameServerStats =
    dc("SteamAPI_SteamGameServerStats_v001", FunctionDescriptor.of(PTR));
  public static final MethodHandle SteamAPI_SteamInput =
    dc("SteamAPI_SteamInput_v006", FunctionDescriptor.of(PTR));

  // ====================================================================================
  // Section 7: ISteamUser
  // ====================================================================================

  public static final MethodHandle ISteamUser_BLoggedOn =
    dc("SteamAPI_ISteamUser_BLoggedOn", FunctionDescriptor.of(BOOL, PTR));
  public static final MethodHandle ISteamUser_GetSteamID =
    dc("SteamAPI_ISteamUser_GetSteamID", FunctionDescriptor.of(I64, PTR));
  public static final MethodHandle ISteamUser_StartVoiceRecording =
    dc("SteamAPI_ISteamUser_StartVoiceRecording", FunctionDescriptor.ofVoid(PTR));
  public static final MethodHandle ISteamUser_StopVoiceRecording =
    dc("SteamAPI_ISteamUser_StopVoiceRecording", FunctionDescriptor.ofVoid(PTR));
  public static final MethodHandle ISteamUser_GetAvailableVoice =
    dc("SteamAPI_ISteamUser_GetAvailableVoice",
      FunctionDescriptor.of(I32, PTR, PTR, PTR, I32));
  public static final MethodHandle ISteamUser_GetVoice =
    dc("SteamAPI_ISteamUser_GetVoice", FunctionDescriptor.of(
      I32, PTR, BOOL, PTR, I32, PTR, BOOL, PTR, I32, PTR, I32));
  public static final MethodHandle ISteamUser_DecompressVoice =
    dc("SteamAPI_ISteamUser_DecompressVoice",
      FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, I32, PTR, I32));
  public static final MethodHandle ISteamUser_GetVoiceOptimalSampleRate =
    dc("SteamAPI_ISteamUser_GetVoiceOptimalSampleRate", FunctionDescriptor.of(I32, PTR));
  public static final MethodHandle ISteamUser_GetAuthSessionTicket =
    dc("SteamAPI_ISteamUser_GetAuthSessionTicket",
      FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, PTR));
  public static final MethodHandle ISteamUser_CancelAuthTicket =
    dc("SteamAPI_ISteamUser_CancelAuthTicket", FunctionDescriptor.ofVoid(PTR, I32));
  public static final MethodHandle ISteamUser_BeginAuthSession =
    dc("SteamAPI_ISteamUser_BeginAuthSession",
      FunctionDescriptor.of(I32, PTR, PTR, I32, I64));
  public static final MethodHandle ISteamUser_EndAuthSession =
    dc("SteamAPI_ISteamUser_EndAuthSession", FunctionDescriptor.ofVoid(PTR, I64));

  // ====================================================================================
  // Section 8: ISteamFriends
  // ====================================================================================

  public static final MethodHandle ISteamFriends_GetPersonaName =
    dc("SteamAPI_ISteamFriends_GetPersonaName", FunctionDescriptor.of(PTR, PTR));
  public static final MethodHandle ISteamFriends_GetFriendCount =
    dc("SteamAPI_ISteamFriends_GetFriendCount", FunctionDescriptor.of(I32, PTR, I32));
  public static final MethodHandle ISteamFriends_GetFriendByIndex =
    dc("SteamAPI_ISteamFriends_GetFriendByIndex", FunctionDescriptor.of(I64, PTR, I32, I32));
  public static final MethodHandle ISteamFriends_GetFriendPersonaState =
    dc("SteamAPI_ISteamFriends_GetFriendPersonaState", FunctionDescriptor.of(I32, PTR, I64));
  public static final MethodHandle ISteamFriends_GetFriendPersonaName =
    dc("SteamAPI_ISteamFriends_GetFriendPersonaName", FunctionDescriptor.of(PTR, PTR, I64));
  public static final MethodHandle ISteamFriends_SetInGameVoiceSpeaking =
    dc("SteamAPI_ISteamFriends_SetInGameVoiceSpeaking",
      FunctionDescriptor.ofVoid(PTR, I64, BOOL));
  public static final MethodHandle ISteamFriends_ActivateGameOverlayToWebPage =
    dc("SteamAPI_ISteamFriends_ActivateGameOverlayToWebPage",
      FunctionDescriptor.ofVoid(PTR, PTR, I32));
  public static final MethodHandle ISteamFriends_ActivateGameOverlayToStore =
    dc("SteamAPI_ISteamFriends_ActivateGameOverlayToStore",
      FunctionDescriptor.ofVoid(PTR, I32, I32));
  public static final MethodHandle ISteamFriends_SetRichPresence =
    dc("SteamAPI_ISteamFriends_SetRichPresence", FunctionDescriptor.of(BOOL, PTR, PTR, PTR));
  public static final MethodHandle ISteamFriends_GetFriendRichPresence =
    dc("SteamAPI_ISteamFriends_GetFriendRichPresence",
      FunctionDescriptor.of(PTR, PTR, I64, PTR));
  public static final MethodHandle ISteamFriends_InviteUserToGame =
    dc("SteamAPI_ISteamFriends_InviteUserToGame",
      FunctionDescriptor.of(BOOL, PTR, I64, PTR));

  // ====================================================================================
  // Section 9: ISteamApps
  // ====================================================================================

  public static final MethodHandle ISteamApps_GetCurrentGameLanguage =
    dc("SteamAPI_ISteamApps_GetCurrentGameLanguage", FunctionDescriptor.of(PTR, PTR));
  public static final MethodHandle ISteamApps_BIsDlcInstalled =
    dc("SteamAPI_ISteamApps_BIsDlcInstalled", FunctionDescriptor.of(BOOL, PTR, I32));

  // ====================================================================================
  // Section 10: ISteamUtils
  // ====================================================================================

  public static final MethodHandle ISteamUtils_GetAppID =
    dc("SteamAPI_ISteamUtils_GetAppID", FunctionDescriptor.of(I32, PTR));
  public static final MethodHandle ISteamUtils_SetOverlayNotificationPosition =
    dc("SteamAPI_ISteamUtils_SetOverlayNotificationPosition",
      FunctionDescriptor.ofVoid(PTR, I32));
  public static final MethodHandle ISteamUtils_IsOverlayEnabled =
    dc("SteamAPI_ISteamUtils_IsOverlayEnabled", FunctionDescriptor.of(BOOL, PTR));
  public static final MethodHandle ISteamUtils_BOverlayNeedsPresent =
    dc("SteamAPI_ISteamUtils_BOverlayNeedsPresent", FunctionDescriptor.of(BOOL, PTR));
  public static final MethodHandle ISteamUtils_SetWarningMessageHook =
    dc("SteamAPI_ISteamUtils_SetWarningMessageHook", FunctionDescriptor.ofVoid(PTR, PTR));
  public static final MethodHandle ISteamUtils_IsSteamRunningOnSteamDeck =
    dc("SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck", FunctionDescriptor.of(BOOL, PTR));
  public static final MethodHandle ISteamUtils_ShowFloatingGamepadTextInput =
    dc("SteamAPI_ISteamUtils_ShowFloatingGamepadTextInput",
      FunctionDescriptor.of(BOOL, PTR, I32, I32, I32, I32, I32));

  // ====================================================================================
  // Section 11: ISteamMatchmaking
  // ====================================================================================

  public static final MethodHandle ISteamMatchmaking_CreateLobby =
    dc("SteamAPI_ISteamMatchmaking_CreateLobby",
      FunctionDescriptor.of(I64, PTR, I32, I32));
  public static final MethodHandle ISteamMatchmaking_JoinLobby =
    dc("SteamAPI_ISteamMatchmaking_JoinLobby", FunctionDescriptor.of(I64, PTR, I64));
  public static final MethodHandle ISteamMatchmaking_LeaveLobby =
    dc("SteamAPI_ISteamMatchmaking_LeaveLobby", FunctionDescriptor.ofVoid(PTR, I64));
  public static final MethodHandle ISteamMatchmaking_InviteUserToLobby =
    dc("SteamAPI_ISteamMatchmaking_InviteUserToLobby",
      FunctionDescriptor.of(BOOL, PTR, I64, I64));
  public static final MethodHandle ISteamMatchmaking_GetLobbyData =
    dc("SteamAPI_ISteamMatchmaking_GetLobbyData",
      FunctionDescriptor.of(PTR, PTR, I64, PTR));
  public static final MethodHandle ISteamMatchmaking_SetLobbyData =
    dc("SteamAPI_ISteamMatchmaking_SetLobbyData",
      FunctionDescriptor.of(BOOL, PTR, I64, PTR, PTR));

  // ====================================================================================
  // Section 12: ISteamNetworking
  // ====================================================================================

  public static final MethodHandle ISteamNetworking_SendP2PPacket =
    dc("SteamAPI_ISteamNetworking_SendP2PPacket",
      FunctionDescriptor.of(BOOL, PTR, I64, PTR, I32, I32, I32));
  public static final MethodHandle ISteamNetworking_IsP2PPacketAvailable =
    dc("SteamAPI_ISteamNetworking_IsP2PPacketAvailable",
      FunctionDescriptor.of(BOOL, PTR, PTR, I32));
  public static final MethodHandle ISteamNetworking_ReadP2PPacket =
    dc("SteamAPI_ISteamNetworking_ReadP2PPacket",
      FunctionDescriptor.of(BOOL, PTR, PTR, I32, PTR, PTR, I32));
  public static final MethodHandle ISteamNetworking_AcceptP2PSessionWithUser =
    dc("SteamAPI_ISteamNetworking_AcceptP2PSessionWithUser",
      FunctionDescriptor.of(BOOL, PTR, I64));
  public static final MethodHandle ISteamNetworking_CloseP2PSessionWithUser =
    dc("SteamAPI_ISteamNetworking_CloseP2PSessionWithUser",
      FunctionDescriptor.of(BOOL, PTR, I64));
  public static final MethodHandle ISteamNetworking_CloseP2PChannelWithUser =
    dc("SteamAPI_ISteamNetworking_CloseP2PChannelWithUser",
      FunctionDescriptor.of(BOOL, PTR, I64, I32));

  // ====================================================================================
  // Section 13: ISteamGameServer + ISteamGameServerStats
  // ====================================================================================

  public static final MethodHandle ISteamGameServer_GetSteamID =
    dc("SteamAPI_ISteamGameServer_GetSteamID", FunctionDescriptor.of(I64, PTR));
  public static final MethodHandle ISteamGameServer_BeginAuthSession =
    dc("SteamAPI_ISteamGameServer_BeginAuthSession",
      FunctionDescriptor.of(I32, PTR, PTR, I32, I64));
  public static final MethodHandle ISteamGameServer_EndAuthSession =
    dc("SteamAPI_ISteamGameServer_EndAuthSession", FunctionDescriptor.ofVoid(PTR, I64));

  public static final MethodHandle ISteamGameServerStats_SetUserAchievement =
    dc("SteamAPI_ISteamGameServerStats_SetUserAchievement",
      FunctionDescriptor.of(BOOL, PTR, I64, PTR));
  public static final MethodHandle ISteamGameServerStats_ClearUserAchievement =
    dc("SteamAPI_ISteamGameServerStats_ClearUserAchievement",
      FunctionDescriptor.of(BOOL, PTR, I64, PTR));

  // ====================================================================================
  // Section 14: ISteamInput
  // ====================================================================================

  public static final MethodHandle ISteamInput_Init =
    dc("SteamAPI_ISteamInput_Init", FunctionDescriptor.of(BOOL, PTR, BOOL));
  public static final MethodHandle ISteamInput_Shutdown =
    dc("SteamAPI_ISteamInput_Shutdown", FunctionDescriptor.of(BOOL, PTR));
  public static final MethodHandle ISteamInput_SetInputActionManifestFilePath =
    dc("SteamAPI_ISteamInput_SetInputActionManifestFilePath",
      FunctionDescriptor.of(BOOL, PTR, PTR));
  /**
   * {@code void RunFrame(ISteamInput*, bool bReservedValue)} — Steam SDK 1.57+ added the
   * second argument; in 1.64 the function still ignores the value.
   */
  public static final MethodHandle ISteamInput_RunFrame =
    dc("SteamAPI_ISteamInput_RunFrame", FunctionDescriptor.ofVoid(PTR, BOOL));
  public static final MethodHandle ISteamInput_BWaitForData =
    dc("SteamAPI_ISteamInput_BWaitForData", FunctionDescriptor.of(BOOL, PTR, BOOL, I32));
  public static final MethodHandle ISteamInput_BNewDataAvailable =
    dc("SteamAPI_ISteamInput_BNewDataAvailable", FunctionDescriptor.of(BOOL, PTR));
  public static final MethodHandle ISteamInput_GetConnectedControllers =
    dc("SteamAPI_ISteamInput_GetConnectedControllers",
      FunctionDescriptor.of(I32, PTR, PTR));
  public static final MethodHandle ISteamInput_EnableDeviceCallbacks =
    dc("SteamAPI_ISteamInput_EnableDeviceCallbacks", FunctionDescriptor.ofVoid(PTR));
  public static final MethodHandle ISteamInput_GetActionSetHandle =
    dc("SteamAPI_ISteamInput_GetActionSetHandle", FunctionDescriptor.of(I64, PTR, PTR));
  public static final MethodHandle ISteamInput_ActivateActionSet =
    dc("SteamAPI_ISteamInput_ActivateActionSet", FunctionDescriptor.ofVoid(PTR, I64, I64));
  public static final MethodHandle ISteamInput_GetCurrentActionSet =
    dc("SteamAPI_ISteamInput_GetCurrentActionSet", FunctionDescriptor.of(I64, PTR, I64));
  public static final MethodHandle ISteamInput_ActivateActionSetLayer =
    dc("SteamAPI_ISteamInput_ActivateActionSetLayer",
      FunctionDescriptor.ofVoid(PTR, I64, I64));
  public static final MethodHandle ISteamInput_DeactivateActionSetLayer =
    dc("SteamAPI_ISteamInput_DeactivateActionSetLayer",
      FunctionDescriptor.ofVoid(PTR, I64, I64));
  public static final MethodHandle ISteamInput_DeactivateAllActionSetLayers =
    dc("SteamAPI_ISteamInput_DeactivateAllActionSetLayers",
      FunctionDescriptor.ofVoid(PTR, I64));
  public static final MethodHandle ISteamInput_GetActiveActionSetLayers =
    dc("SteamAPI_ISteamInput_GetActiveActionSetLayers",
      FunctionDescriptor.of(I32, PTR, I64, PTR));
  public static final MethodHandle ISteamInput_GetDigitalActionHandle =
    dc("SteamAPI_ISteamInput_GetDigitalActionHandle",
      FunctionDescriptor.of(I64, PTR, PTR));
  /** {@code InputDigitalActionData_t GetDigitalActionData(self, InputHandle_t, InputDigitalActionHandle_t)} -- struct return by value */
  public static final MethodHandle ISteamInput_GetDigitalActionData =
    dc("SteamAPI_ISteamInput_GetDigitalActionData",
      FunctionDescriptor.of(DIGITAL_ACTION_DATA_LAYOUT, PTR, I64, I64));
  public static final MethodHandle ISteamInput_GetDigitalActionOrigins =
    dc("SteamAPI_ISteamInput_GetDigitalActionOrigins",
      FunctionDescriptor.of(I32, PTR, I64, I64, I64, PTR));
  public static final MethodHandle ISteamInput_GetStringForDigitalActionName =
    dc("SteamAPI_ISteamInput_GetStringForDigitalActionName",
      FunctionDescriptor.of(PTR, PTR, I64));
  public static final MethodHandle ISteamInput_GetAnalogActionHandle =
    dc("SteamAPI_ISteamInput_GetAnalogActionHandle", FunctionDescriptor.of(I64, PTR, PTR));
  public static final MethodHandle ISteamInput_GetAnalogActionData =
    dc("SteamAPI_ISteamInput_GetAnalogActionData",
      FunctionDescriptor.of(ANALOG_ACTION_DATA_LAYOUT, PTR, I64, I64));
  public static final MethodHandle ISteamInput_GetAnalogActionOrigins =
    dc("SteamAPI_ISteamInput_GetAnalogActionOrigins",
      FunctionDescriptor.of(I32, PTR, I64, I64, I64, PTR));
  public static final MethodHandle ISteamInput_GetStringForAnalogActionName =
    dc("SteamAPI_ISteamInput_GetStringForAnalogActionName",
      FunctionDescriptor.of(PTR, PTR, I64));
  public static final MethodHandle ISteamInput_StopAnalogActionMomentum =
    dc("SteamAPI_ISteamInput_StopAnalogActionMomentum",
      FunctionDescriptor.ofVoid(PTR, I64, I64));
  public static final MethodHandle ISteamInput_GetMotionData =
    dc("SteamAPI_ISteamInput_GetMotionData",
      FunctionDescriptor.of(MOTION_DATA_LAYOUT, PTR, I64));
  public static final MethodHandle ISteamInput_GetGlyphPNGForActionOrigin =
    dc("SteamAPI_ISteamInput_GetGlyphPNGForActionOrigin",
      FunctionDescriptor.of(PTR, PTR, I32, I32, I32));
  public static final MethodHandle ISteamInput_GetGlyphSVGForActionOrigin =
    dc("SteamAPI_ISteamInput_GetGlyphSVGForActionOrigin",
      FunctionDescriptor.of(PTR, PTR, I32, I32));
  public static final MethodHandle ISteamInput_GetGlyphForActionOrigin_Legacy =
    dc("SteamAPI_ISteamInput_GetGlyphForActionOrigin_Legacy",
      FunctionDescriptor.of(PTR, PTR, I32));
  public static final MethodHandle ISteamInput_GetStringForActionOrigin =
    dc("SteamAPI_ISteamInput_GetStringForActionOrigin", FunctionDescriptor.of(PTR, PTR, I32));
  public static final MethodHandle ISteamInput_GetStringForXboxOrigin =
    dc("SteamAPI_ISteamInput_GetStringForXboxOrigin", FunctionDescriptor.of(PTR, PTR, I32));
  public static final MethodHandle ISteamInput_GetGlyphForXboxOrigin =
    dc("SteamAPI_ISteamInput_GetGlyphForXboxOrigin", FunctionDescriptor.of(PTR, PTR, I32));
  public static final MethodHandle ISteamInput_GetActionOriginFromXboxOrigin =
    dc("SteamAPI_ISteamInput_GetActionOriginFromXboxOrigin",
      FunctionDescriptor.of(I32, PTR, I64, I32));
  public static final MethodHandle ISteamInput_TranslateActionOrigin =
    dc("SteamAPI_ISteamInput_TranslateActionOrigin",
      FunctionDescriptor.of(I32, PTR, I32, I32));
  public static final MethodHandle ISteamInput_TriggerVibration =
    dc("SteamAPI_ISteamInput_TriggerVibration",
      FunctionDescriptor.ofVoid(PTR, I64, I16, I16));
  public static final MethodHandle ISteamInput_TriggerVibrationExtended =
    dc("SteamAPI_ISteamInput_TriggerVibrationExtended",
      FunctionDescriptor.ofVoid(PTR, I64, I16, I16, I16, I16));
  public static final MethodHandle ISteamInput_TriggerSimpleHapticEvent =
    dc("SteamAPI_ISteamInput_TriggerSimpleHapticEvent",
      FunctionDescriptor.ofVoid(PTR, I64, I32, I8, I8, I8, I8));
  public static final MethodHandle ISteamInput_SetLEDColor =
    dc("SteamAPI_ISteamInput_SetLEDColor",
      FunctionDescriptor.ofVoid(PTR, I64, I8, I8, I8, I32));
  public static final MethodHandle ISteamInput_Legacy_TriggerHapticPulse =
    dc("SteamAPI_ISteamInput_Legacy_TriggerHapticPulse",
      FunctionDescriptor.ofVoid(PTR, I64, I32, I16));
  public static final MethodHandle ISteamInput_Legacy_TriggerRepeatedHapticPulse =
    dc("SteamAPI_ISteamInput_Legacy_TriggerRepeatedHapticPulse",
      FunctionDescriptor.ofVoid(PTR, I64, I32, I16, I16, I16, I32));
  public static final MethodHandle ISteamInput_ShowBindingPanel =
    dc("SteamAPI_ISteamInput_ShowBindingPanel", FunctionDescriptor.of(BOOL, PTR, I64));
  public static final MethodHandle ISteamInput_GetInputTypeForHandle =
    dc("SteamAPI_ISteamInput_GetInputTypeForHandle", FunctionDescriptor.of(I32, PTR, I64));
  public static final MethodHandle ISteamInput_GetControllerForGamepadIndex =
    dc("SteamAPI_ISteamInput_GetControllerForGamepadIndex",
      FunctionDescriptor.of(I64, PTR, I32));
  public static final MethodHandle ISteamInput_GetGamepadIndexForController =
    dc("SteamAPI_ISteamInput_GetGamepadIndexForController",
      FunctionDescriptor.of(I32, PTR, I64));
  public static final MethodHandle ISteamInput_GetDeviceBindingRevision =
    dc("SteamAPI_ISteamInput_GetDeviceBindingRevision",
      FunctionDescriptor.of(BOOL, PTR, I64, PTR, PTR));
  public static final MethodHandle ISteamInput_GetRemotePlaySessionID =
    dc("SteamAPI_ISteamInput_GetRemotePlaySessionID",
      FunctionDescriptor.of(I32, PTR, I64));
  public static final MethodHandle ISteamInput_GetSessionInputConfigurationSettings =
    dc("SteamAPI_ISteamInput_GetSessionInputConfigurationSettings",
      FunctionDescriptor.of(I32, PTR));

  // ====================================================================================
  // Section 15: Linker convenience and marshaling helpers
  // ====================================================================================

  /** Expose the linker for use by the dispatcher when building upcall stubs. */
  public static Linker linker () { return LINKER; }

  /** Allocate a NUL-terminated UTF-8 C string in the given arena. */
  public static MemorySegment allocCString (Arena arena, String value)
  {
    if (value == null) {
      return MemorySegment.NULL;
    }
    return arena.allocateFrom(value); // UTF-8, NUL-terminated
  }

  /**
   * Read a NUL-terminated UTF-8 C string from a (possibly zero-length) MemorySegment
   * pointer. Returns null if {@code ptr} is {@link MemorySegment#NULL}.
   */
  public static String readCString (MemorySegment ptr)
  {
    if (ptr == null || ptr.equals(MemorySegment.NULL)) {
      return null;
    }
    // Steam returns interior pointers with zero size; reinterpret as effectively
    // unbounded so we can scan for the terminator.
    return ptr.reinterpret(Long.MAX_VALUE).getString(0);
  }

  /**
   * Read a fixed-size, NUL-terminated UTF-8 char array starting at {@code offset} in
   * the supplied segment. Used for inline {@code char[N]} fields in callback structs.
   */
  public static String readFixedCString (MemorySegment seg, long offset, int maxLen)
  {
    return seg.asSlice(offset, maxLen).getString(0);
  }

  /** Wrap a (possibly null) direct {@link Buffer} as a MemorySegment of its full capacity. */
  public static MemorySegment ofBuffer (Buffer buffer)
  {
    if (buffer == null) {
      return MemorySegment.NULL;
    }
    return MemorySegment.ofBuffer(buffer);
  }

  private CSteam () {} // not instantiable
}
