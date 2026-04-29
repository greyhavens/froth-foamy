//
// $Id$

package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import com.threerings.froth.internal.CallbackDispatcher;
import com.threerings.froth.internal.CSteam;

/**
 * Contains global Steam bindings.
 */
public class SteamAPI
{
    /**
     * Initializes the Steam API.
     *
     * @return whether or not the API initialized successfully.
     */
    public static boolean init ()
    {
        if (!_haveLib) {
            return false;
        }
        if (_initialized) {
            return true;
        }
        try {
            // SteamAPI_Init is implemented as an inline call in the C++ header that bundles
            // a NUL-separated list of every interface-version string the binary was built
            // against. We replicate that here using the version constants from the SDK 1.64
            // headers, then call SteamInternal_SteamAPI_Init directly.
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment versions = arena.allocateFrom(VERSIONS_BUNDLE);
                int rc = (int) CSteam.SteamInternal_SteamAPI_Init.invokeExact(
                    versions, MemorySegment.NULL);
                if (rc != 0) {
                    return false; // 0 == k_ESteamAPIInitResult_OK
                }
            }
            // Switch to manual dispatch -- our CallbackDispatcher drives it.
            CSteam.SteamAPI_ManualDispatch_Init.invokeExact();
            _initialized = true;
            return true;
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Checks whether we were able to locate the Steam native library.
     */
    public static boolean hasLibrary ()
    {
        return _haveLib;
    }

    /**
     * Checks whether the Steam API was successfully initialized.
     */
    public static boolean isInitialized ()
    {
        return _initialized;
    }

    /**
     * Checks whether Steam is running.
     */
    public static boolean isSteamRunning ()
    {
        if (!_initialized) {
            return false;
        }
        try {
            return (boolean) CSteam.SteamAPI_IsSteamRunning.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Shuts down the Steam API.
     */
    public static void shutdown ()
    {
        if (!_initialized) {
            return;
        }
        try {
            CSteam.SteamAPI_Shutdown.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
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

    /** Package-internal accessor for the client-pipe dispatcher (used by sibling Steam* classes). */
    static CallbackDispatcher dispatcher ()
    {
        return DISPATCHER;
    }

    /**
     * Package-internal: invoke a Steam interface-singleton accessor MethodHandle (e.g.
     * {@code SteamAPI_SteamUser_v023}) and return the resulting interface pointer. Used
     * by sibling Steam* classes; throws if the API hasn't been initialized.
     */
    static MemorySegment iface (MethodHandle accessor)
    {
        try {
            return (MemorySegment) accessor.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** Wrap a {@link Throwable} from {@code MethodHandle} invocation. */
    static RuntimeException wrap (Throwable t)
    {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new RuntimeException(t);
    }

    /** Whether the native library was successfully loaded. */
    protected static boolean _haveLib = CSteam.LIB_LOADED;

    /** Whether or not we have successfully initialized. */
    protected static boolean _initialized;

    /**
     * Bundle of NUL-separated interface version strings, as required by
     * {@code SteamInternal_SteamAPI_Init}. Sourced from the Steamworks SDK 1.64 headers.
     */
    private static final String VERSIONS_BUNDLE =
        "SteamUtils010" + '\0' +
        "SteamNetworkingUtils004" + '\0' +
        "STEAMAPPS_INTERFACE_VERSION009" + '\0' +
        "SteamController008" + '\0' +
        "SteamFriends018" + '\0' +
        "STEAMHTMLSURFACE_INTERFACE_VERSION_005" + '\0' +
        "STEAMHTTP_INTERFACE_VERSION003" + '\0' +
        "SteamInput006" + '\0' +
        "STEAMINVENTORY_INTERFACE_V003" + '\0' +
        "SteamMatchMakingServers002" + '\0' +
        "SteamMatchMaking009" + '\0' +
        "STEAMMUSIC_INTERFACE_VERSION001" + '\0' +
        "SteamNetworkingMessages002" + '\0' +
        "SteamNetworkingSockets012" + '\0' +
        "SteamNetworking006" + '\0' +
        "STEAMPARENTALSETTINGS_INTERFACE_VERSION001" + '\0' +
        "SteamParties002" + '\0' +
        "STEAMREMOTEPLAY_INTERFACE_VERSION004" + '\0' +
        "STEAMREMOTESTORAGE_INTERFACE_VERSION016" + '\0' +
        "STEAMSCREENSHOTS_INTERFACE_VERSION003" + '\0' +
        "STEAMUGC_INTERFACE_VERSION021" + '\0' +
        "STEAMUSERSTATS_INTERFACE_VERSION013" + '\0' +
        "SteamUser023" + '\0' +
        "STEAMVIDEO_INTERFACE_V007" + '\0';

    /** Dispatcher for the client (user) pipe. */
    private static final CallbackDispatcher DISPATCHER = new CallbackDispatcher(
        "SteamAPI",
        () -> (int) CSteam.SteamAPI_GetHSteamPipe.invokeExact());
}
