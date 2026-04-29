package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam Input interface. Steam Input is a flexible input API that supports
 * over three hundred devices including all common variants of Xbox, Playstation, Nintendo
 * Switch Pro, and Steam Controllers.
 */
public class SteamInput
{
  /** The maximum number of connected controllers supported. */
  public static final int MAX_COUNT = 16;

  /** The maximum number of active action set layers. */
  public static final int MAX_ACTIVE_LAYERS = 16;

  /** The maximum number of origins for a single action. */
  public static final int MAX_ORIGINS = 8;

  /** When sending an option to all controllers. */
  public static final long HANDLE_ALL_CONTROLLERS = -1L; // UINT64_MAX

  /** The input source modes. */
  public enum InputSourceMode
  {
    // Note: ordinals correspond to native EInputSourceMode values. Do not reorder!
    NONE, DPAD, BUTTONS, FOUR_BUTTONS, ABSOLUTE_MOUSE, RELATIVE_MOUSE,
    JOYSTICK_MOVE, JOYSTICK_MOUSE, JOYSTICK_CAMERA, SCROLL_WHEEL,
    TRIGGER, TOUCH_MENU, MOUSE_JOYSTICK, MOUSE_REGION, RADIAL_MENU,
    SINGLE_BUTTON, SWITCHES,
    ;
  }

  /** The Steam input device types. */
  public enum InputType
  {
    // Note: ordinals correspond to native ESteamInputType values. Do not reorder!
    UNKNOWN,
    STEAM_CONTROLLER,
    XBOX_360_CONTROLLER,
    XBOX_ONE_CONTROLLER,
    GENERIC_GAMEPAD,
    PS4_CONTROLLER,
    APPLE_MFI_CONTROLLER,
    ANDROID_CONTROLLER,
    SWITCH_JOYCON_PAIR,
    SWITCH_JOYCON_SINGLE,
    SWITCH_PRO_CONTROLLER,
    MOBILE_TOUCH,
    PS3_CONTROLLER,
    PS5_CONTROLLER,
    STEAM_DECK_CONTROLLER,
    ;
  }

  /** Sizes for PNG glyphs. */
  public enum GlyphSize
  {
    // Note: ordinals correspond to native ESteamInputGlyphSize values. Do not reorder!
    /** 32x32 pixels. */
    SMALL,
    /** 128x128 pixels. */
    MEDIUM,
    /** 256x256 pixels. */
    LARGE,
    ;
  }

  /** Flags for {@link #setLEDColor}. */
  public enum LEDFlag
  {
    // Note: ordinals correspond to native ESteamInputLEDFlag values. Do not reorder!
    SET_COLOR,
    RESTORE_USER_DEFAULT,
    ;
  }

  /**
   * The controller pads for legacy haptic pulse methods. Deprecated alongside the
   * {@code Legacy_*} haptic methods that consume it.
   */
  @Deprecated
  public enum ControllerPad
  {
    // Note: ordinals correspond to native ESteamControllerPad values. Do not reorder!
    LEFT,
    RIGHT,
    ;
  }

  /**
   * Represents the current state of a digital action.
   */
  public static final class DigitalActionData
  {
    /** Whether the action is currently pressed. */
    public boolean state;

    /** Whether the action is currently available to be bound in the active action set. */
    public boolean active;

    @Override
    public String toString ()
    {
      return "DigitalActionData{state=" + state + ", active=" + active + "}";
    }
  }

  /**
   * Represents the current state of an analog action.
   */
  public static final class AnalogActionData
  {
    /** The type of data coming from this action. */
    public InputSourceMode mode;

    /** The current state of this action (x axis). */
    public float x;

    /** The current state of this action (y axis). */
    public float y;

    /** Whether the action is currently available to be bound in the active action set. */
    public boolean active;

    @Override
    public String toString ()
    {
      return "AnalogActionData{mode=" + mode + ", x=" + x + ", y=" + y +
        ", active=" + active + "}";
    }
  }

  /**
   * Represents raw motion data from a controller.
   */
  public static final class MotionData
  {
    /** Gyro quaternion components. */
    public float rotQuatX, rotQuatY, rotQuatZ, rotQuatW;

    /** Positional acceleration components. */
    public float posAccelX, posAccelY, posAccelZ;

    /** Angular velocity components. */
    public float rotVelX, rotVelY, rotVelZ;

    @Override
    public String toString ()
    {
      return "MotionData{rotQuat=(" + rotQuatX + ", " + rotQuatY + ", " +
        rotQuatZ + ", " + rotQuatW + "), posAccel=(" + posAccelX + ", " +
        posAccelY + ", " + posAccelZ + "), rotVel=(" + rotVelX + ", " +
        rotVelY + ", " + rotVelZ + ")}";
    }
  }

  // ---- Lifecycle ----

  /**
   * Initializes the Steam Input interface. Must be called before any other SteamInput methods.
   *
   * @param explicitlyCallRunFrame if true, you must manually call {@link #runFrame} each frame;
   * otherwise Steam Input will be updated when {@link SteamAPI#runCallbacks} is called.
   * @return true on success.
   */
  public static boolean init (boolean explicitlyCallRunFrame)
  {
    try {
      _initialized = SteamAPI.isSteamRunning() &&
        (boolean) CSteam.ISteamInput_Init.invokeExact(self(), explicitlyCallRunFrame);
      return _initialized;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Shuts down the Steam Input interface.
   *
   * @return true on success.
   */
  public static boolean shutdown ()
  {
    if (!_initialized) {
      return false;
    }
    try {
      boolean ok = (boolean) CSteam.ISteamInput_Shutdown.invokeExact(self());
      if (ok) {
        _initialized = false;
      }
      return ok;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Sets the absolute path to the Input Action Manifest file containing the in-game actions
   * and file paths to the official configurations.
   *
   * @return true on success.
   */
  public static boolean setInputActionManifestFilePath (String path)
  {
    if (path == null) {
      throw new NullPointerException("path");
    }
    if (!_initialized) return false;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment p = CSteam.allocCString(arena, path);
      return (boolean) CSteam.ISteamInput_SetInputActionManifestFilePath
        .invokeExact(self(), p);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Synchronizes API state with the latest Steam Input action data available. This is performed
   * automatically by {@link SteamAPI#runCallbacks}, but for the absolute lowest possible
   * latency, you can call this directly before reading controller state.
   */
  public static void runFrame ()
  {
    if (!_initialized) return;
    try {
      // The second arg ("bReservedValue") is ignored by Steam Input but required at
      // the ABI level since 1.57.
      CSteam.ISteamInput_RunFrame.invokeExact(self(), false);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Waits on an IPC event from Steam sent when there is new data to be fetched from
   * the data drop. Useful for games with a dedicated input thread.
   *
   * @param waitForever if true, waits indefinitely for data.
   * @param timeout timeout in milliseconds if not waiting forever.
   * @return true when data was received before the timeout expires.
   */
  public static boolean waitForData (boolean waitForever, int timeout)
  {
    if (!_initialized) return false;
    try {
      return (boolean) CSteam.ISteamInput_BWaitForData.invokeExact(
        self(), waitForever, timeout);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns true if new data has been received since the last time action data was accessed
   * via {@link #getDigitalActionData} or {@link #getAnalogActionData}.
   */
  public static boolean newDataAvailable ()
  {
    if (!_initialized) return false;
    try {
      return (boolean) CSteam.ISteamInput_BNewDataAvailable.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Controllers ----

  /**
   * Enumerates currently connected Steam Input enabled devices.
   *
   * @param handlesOut an array of at least {@link #MAX_COUNT} elements to receive the handles.
   * @return the number of handles written to handlesOut.
   */
  public static int getConnectedControllers (long[] handlesOut)
  {
    if (handlesOut == null) {
      throw new NullPointerException("handlesOut");
    }
    if (!_initialized) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buf = arena.allocate(ValueLayout.JAVA_LONG, MAX_COUNT);
      int n = (int) CSteam.ISteamInput_GetConnectedControllers.invokeExact(self(), buf);
      int copied = Math.min(n, handlesOut.length);
      for (int ii = 0; ii < copied; ii++) {
        handlesOut[ii] = buf.getAtIndex(ValueLayout.JAVA_LONG, ii);
      }
      return n;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Enables {@code SteamInputDeviceConnected_t} and {@code SteamInputDeviceDisconnected_t}
   * callbacks. Each controller that is already connected will generate a device connected
   * callback when you enable them.
   */
  public static void enableDeviceCallbacks ()
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_EnableDeviceCallbacks.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Action Sets ----

  /**
   * Looks up the handle for an Action Set. Best to do this once on startup, and store the
   * handles for all future API calls.
   *
   * @param actionSetName the name of the action set (e.g. "Menu", "Walk", "Drive").
   * @return the handle, or 0 if not found.
   */
  public static long getActionSetHandle (String actionSetName)
  {
    if (actionSetName == null) {
      throw new NullPointerException("actionSetName");
    }
    if (!_initialized) return 0L;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment n = CSteam.allocCString(arena, actionSetName);
      return (long) CSteam.ISteamInput_GetActionSetHandle.invokeExact(self(), n);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Reconfigures the controller to use the specified action set. This is cheap, and can be
   * safely called repeatedly.
   *
   * @param inputHandle the controller handle, or {@link #HANDLE_ALL_CONTROLLERS} for all.
   * @param actionSetHandle the action set handle obtained from {@link #getActionSetHandle}.
   */
  public static void activateActionSet (long inputHandle, long actionSetHandle)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_ActivateActionSet.invokeExact(
        self(), inputHandle, actionSetHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns the current action set handle for the specified controller.
   */
  public static long getCurrentActionSet (long inputHandle)
  {
    if (!_initialized) return 0L;
    try {
      return (long) CSteam.ISteamInput_GetCurrentActionSet.invokeExact(self(), inputHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Action Set Layers ----

  /**
   * Activates an action set layer for the specified controller.
   */
  public static void activateActionSetLayer (long inputHandle, long actionSetLayerHandle)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_ActivateActionSetLayer.invokeExact(
        self(), inputHandle, actionSetLayerHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Deactivates an action set layer for the specified controller.
   */
  public static void deactivateActionSetLayer (long inputHandle, long actionSetLayerHandle)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_DeactivateActionSetLayer.invokeExact(
        self(), inputHandle, actionSetLayerHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Deactivates all action set layers for the specified controller.
   */
  public static void deactivateAllActionSetLayers (long inputHandle)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_DeactivateAllActionSetLayers.invokeExact(self(), inputHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Enumerates currently active action set layers for the specified controller.
   *
   * @param inputHandle the controller handle.
   * @param handlesOut an array of at least {@link #MAX_ACTIVE_LAYERS} elements.
   * @return the number of handles written to handlesOut.
   */
  public static int getActiveActionSetLayers (long inputHandle, long[] handlesOut)
  {
    if (handlesOut == null) {
      throw new NullPointerException("handlesOut");
    }
    if (!_initialized) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buf = arena.allocate(ValueLayout.JAVA_LONG, MAX_ACTIVE_LAYERS);
      int n = (int) CSteam.ISteamInput_GetActiveActionSetLayers.invokeExact(
        self(), inputHandle, buf);
      int copied = Math.min(n, handlesOut.length);
      for (int ii = 0; ii < copied; ii++) {
        handlesOut[ii] = buf.getAtIndex(ValueLayout.JAVA_LONG, ii);
      }
      return n;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Digital Actions ----

  /**
   * Looks up the handle for a digital action. Best to do this once on startup.
   *
   * @return the handle, or 0 if not found.
   */
  public static long getDigitalActionHandle (String actionName)
  {
    if (actionName == null) {
      throw new NullPointerException("actionName");
    }
    if (!_initialized) return 0L;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment n = CSteam.allocCString(arena, actionName);
      return (long) CSteam.ISteamInput_GetDigitalActionHandle.invokeExact(self(), n);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns the current state of the supplied digital game action.
   *
   * @param inputHandle the controller handle.
   * @param digitalActionHandle the digital action handle.
   * @param data the data object to populate with the result.
   * @return true if the data was successfully populated.
   */
  public static boolean getDigitalActionData (
    long inputHandle, long digitalActionHandle, DigitalActionData data)
  {
    if (data == null) {
      throw new NullPointerException("data");
    }
    if (!_initialized) return false;
    try (Arena arena = Arena.ofConfined()) {
      // Steam returns InputDigitalActionData_t by value (2-byte struct).
      MemorySegment ret = (MemorySegment) CSteam.ISteamInput_GetDigitalActionData
        .invokeExact((java.lang.foreign.SegmentAllocator) arena,
          self(), inputHandle, digitalActionHandle);
      data.state  = ret.get(ValueLayout.JAVA_BOOLEAN, 0);
      data.active = ret.get(ValueLayout.JAVA_BOOLEAN, 1);
      return true;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets the origin(s) for a digital action within an action set.
   *
   * @param inputHandle the controller handle.
   * @param actionSetHandle the action set handle.
   * @param digitalActionHandle the digital action handle.
   * @param originsOut an array of at least {@link #MAX_ORIGINS} elements.
   * @return the number of origins written to originsOut.
   */
  public static int getDigitalActionOrigins (
    long inputHandle, long actionSetHandle, long digitalActionHandle, int[] originsOut)
  {
    if (originsOut == null) {
      throw new NullPointerException("originsOut");
    }
    if (!_initialized) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buf = arena.allocate(ValueLayout.JAVA_INT, MAX_ORIGINS);
      int n = (int) CSteam.ISteamInput_GetDigitalActionOrigins.invokeExact(
        self(), inputHandle, actionSetHandle, digitalActionHandle, buf);
      int copied = Math.min(n, originsOut.length);
      for (int ii = 0; ii < copied; ii++) {
        originsOut[ii] = buf.getAtIndex(ValueLayout.JAVA_INT, ii);
      }
      return n;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns a localized string for the user-facing action name corresponding to the
   * specified digital action handle.
   */
  public static String getStringForDigitalActionName (long digitalActionHandle)
  {
    if (!_initialized) return null;
    try {
      MemorySegment ptr = (MemorySegment) CSteam.ISteamInput_GetStringForDigitalActionName
        .invokeExact(self(), digitalActionHandle);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Analog Actions ----

  /**
   * Looks up the handle for an analog action. Best to do this once on startup.
   *
   * @return the handle, or 0 if not found.
   */
  public static long getAnalogActionHandle (String actionName)
  {
    if (actionName == null) {
      throw new NullPointerException("actionName");
    }
    if (!_initialized) return 0L;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment n = CSteam.allocCString(arena, actionName);
      return (long) CSteam.ISteamInput_GetAnalogActionHandle.invokeExact(self(), n);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns the current state of the supplied analog game action.
   *
   * @param inputHandle the controller handle.
   * @param analogActionHandle the analog action handle.
   * @param data the data object to populate with the result.
   * @return true if the data was successfully populated.
   */
  public static boolean getAnalogActionData (
    long inputHandle, long analogActionHandle, AnalogActionData data)
  {
    if (data == null) {
      throw new NullPointerException("data");
    }
    if (!_initialized) return false;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment ret = (MemorySegment) CSteam.ISteamInput_GetAnalogActionData
        .invokeExact((java.lang.foreign.SegmentAllocator) arena,
          self(), inputHandle, analogActionHandle);
      int modeOrdinal = ret.get(ValueLayout.JAVA_INT, 0);
      float x = ret.get(ValueLayout.JAVA_FLOAT, 4);
      float y = ret.get(ValueLayout.JAVA_FLOAT, 8);
      boolean active = ret.get(ValueLayout.JAVA_BOOLEAN, 12);
      InputSourceMode[] modes = InputSourceMode.values();
      data.mode = (modeOrdinal >= 0 && modeOrdinal < modes.length) ?
        modes[modeOrdinal] : InputSourceMode.NONE;
      data.x = x;
      data.y = y;
      data.active = active;
      return true;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets the origin(s) for an analog action within an action set.
   *
   * @param inputHandle the controller handle.
   * @param actionSetHandle the action set handle.
   * @param analogActionHandle the analog action handle.
   * @param originsOut an array of at least {@link #MAX_ORIGINS} elements.
   * @return the number of origins written to originsOut.
   */
  public static int getAnalogActionOrigins (
    long inputHandle, long actionSetHandle, long analogActionHandle, int[] originsOut)
  {
    if (originsOut == null) {
      throw new NullPointerException("originsOut");
    }
    if (!_initialized) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buf = arena.allocate(ValueLayout.JAVA_INT, MAX_ORIGINS);
      int n = (int) CSteam.ISteamInput_GetAnalogActionOrigins.invokeExact(
        self(), inputHandle, actionSetHandle, analogActionHandle, buf);
      int copied = Math.min(n, originsOut.length);
      for (int ii = 0; ii < copied; ii++) {
        originsOut[ii] = buf.getAtIndex(ValueLayout.JAVA_INT, ii);
      }
      return n;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns a localized string for the user-facing action name corresponding to the
   * specified analog action handle.
   */
  public static String getStringForAnalogActionName (long analogActionHandle)
  {
    if (!_initialized) return null;
    try {
      MemorySegment ptr = (MemorySegment) CSteam.ISteamInput_GetStringForAnalogActionName
        .invokeExact(self(), analogActionHandle);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Stops analog momentum for the action if it is a mouse action in trackball mode.
   */
  public static void stopAnalogActionMomentum (long inputHandle, long analogActionHandle)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_StopAnalogActionMomentum.invokeExact(
        self(), inputHandle, analogActionHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Glyphs and Strings ----

  /**
   * Gets a local path to a PNG file for the provided origin's glyph.
   *
   * @param origin the action origin (from {@link #getDigitalActionOrigins} or similar).
   * @param size the desired glyph size.
   * @param flags style flags (see ESteamInputGlyphStyle in the Steamworks SDK).
   * @return the file path, or null if unavailable.
   */
  public static String getGlyphPNGForActionOrigin (int origin, GlyphSize size, int flags)
  {
    if (size == null) {
      throw new NullPointerException("size");
    }
    if (!_initialized) return null;
    try {
      MemorySegment ptr = (MemorySegment) CSteam.ISteamInput_GetGlyphPNGForActionOrigin
        .invokeExact(self(), origin, size.ordinal(), flags);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets a local path to an SVG file for the provided origin's glyph.
   *
   * @param origin the action origin.
   * @param flags style flags.
   * @return the file path, or null if unavailable.
   */
  public static String getGlyphSVGForActionOrigin (int origin, int flags)
  {
    if (!_initialized) return null;
    try {
      MemorySegment ptr = (MemorySegment) CSteam.ISteamInput_GetGlyphSVGForActionOrigin
        .invokeExact(self(), origin, flags);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets a local path to an older, Big Picture Mode-style PNG file for a particular origin.
   *
   * @param origin the action origin.
   * @return the file path, or null if unavailable.
   * @deprecated wraps Steam's {@code GetGlyphForActionOrigin_Legacy}. Prefer
   *     {@link #getGlyphPNGForActionOrigin} or {@link #getGlyphSVGForActionOrigin}.
   */
  @Deprecated
  public static String getGlyphForActionOriginLegacy (int origin)
  {
    if (!_initialized) return null;
    try {
      MemorySegment ptr =
        (MemorySegment) CSteam.ISteamInput_GetGlyphForActionOrigin_Legacy
          .invokeExact(self(), origin);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns a localized string for the specified action origin.
   */
  public static String getStringForActionOrigin (int origin)
  {
    if (!_initialized) return null;
    try {
      MemorySegment ptr = (MemorySegment) CSteam.ISteamInput_GetStringForActionOrigin
        .invokeExact(self(), origin);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns a localized string for the specified Xbox controller origin.
   */
  public static String getStringForXboxOrigin (int origin)
  {
    if (!_initialized) return null;
    try {
      MemorySegment ptr = (MemorySegment) CSteam.ISteamInput_GetStringForXboxOrigin
        .invokeExact(self(), origin);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets a local path to art for on-screen glyph for a particular Xbox controller origin.
   */
  public static String getGlyphForXboxOrigin (int origin)
  {
    if (!_initialized) return null;
    try {
      MemorySegment ptr = (MemorySegment) CSteam.ISteamInput_GetGlyphForXboxOrigin
        .invokeExact(self(), origin);
      return CSteam.readCString(ptr);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets the equivalent action origin for a given Xbox controller origin.
   *
   * @return the equivalent action origin, or 0 (None) if unavailable.
   */
  public static int getActionOriginFromXboxOrigin (long inputHandle, int xboxOrigin)
  {
    if (!_initialized) return 0;
    try {
      return (int) CSteam.ISteamInput_GetActionOriginFromXboxOrigin
        .invokeExact(self(), inputHandle, xboxOrigin);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Converts an origin to another controller type. For inputs not present on the other
   * controller type this will return 0 (None).
   */
  public static int translateActionOrigin (InputType destinationType, int sourceOrigin)
  {
    if (destinationType == null) {
      throw new NullPointerException("destinationType");
    }
    if (!_initialized) return 0;
    try {
      return (int) CSteam.ISteamInput_TranslateActionOrigin
        .invokeExact(self(), destinationType.ordinal(), sourceOrigin);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Motion Data ----

  /**
   * Returns raw motion data from the specified controller.
   *
   * @param inputHandle the controller handle.
   * @param data the data object to populate with the result.
   * @return true if the data was successfully populated.
   */
  public static boolean getMotionData (long inputHandle, MotionData data)
  {
    if (data == null) {
      throw new NullPointerException("data");
    }
    if (!_initialized) return false;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment ret = (MemorySegment) CSteam.ISteamInput_GetMotionData
        .invokeExact((java.lang.foreign.SegmentAllocator) arena, self(), inputHandle);
      data.rotQuatX = ret.get(ValueLayout.JAVA_FLOAT, 0);
      data.rotQuatY = ret.get(ValueLayout.JAVA_FLOAT, 4);
      data.rotQuatZ = ret.get(ValueLayout.JAVA_FLOAT, 8);
      data.rotQuatW = ret.get(ValueLayout.JAVA_FLOAT, 12);
      data.posAccelX = ret.get(ValueLayout.JAVA_FLOAT, 16);
      data.posAccelY = ret.get(ValueLayout.JAVA_FLOAT, 20);
      data.posAccelZ = ret.get(ValueLayout.JAVA_FLOAT, 24);
      data.rotVelX = ret.get(ValueLayout.JAVA_FLOAT, 28);
      data.rotVelY = ret.get(ValueLayout.JAVA_FLOAT, 32);
      data.rotVelZ = ret.get(ValueLayout.JAVA_FLOAT, 36);
      return true;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Haptics and LEDs ----

  /**
   * Triggers a vibration event on supported controllers. Steam will translate these commands
   * into haptic pulses for Steam Controllers.
   */
  public static void triggerVibration (long inputHandle, int leftSpeed, int rightSpeed)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_TriggerVibration.invokeExact(
        self(), inputHandle, (short) leftSpeed, (short) rightSpeed);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Triggers a vibration event on supported controllers including Xbox trigger impulse rumble.
   */
  public static void triggerVibrationExtended (
    long inputHandle, int leftSpeed, int rightSpeed,
    int leftTriggerSpeed, int rightTriggerSpeed)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_TriggerVibrationExtended.invokeExact(
        self(), inputHandle, (short) leftSpeed, (short) rightSpeed,
        (short) leftTriggerSpeed, (short) rightTriggerSpeed);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Sends a haptic pulse, works on Steam Deck and Steam Controller devices.
   */
  public static void triggerSimpleHapticEvent (
    long inputHandle, int hapticLocation,
    int intensity, int gainDB, int otherIntensity, int otherGainDB)
  {
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_TriggerSimpleHapticEvent.invokeExact(
        self(), inputHandle, hapticLocation,
        (byte) intensity, (byte) gainDB, (byte) otherIntensity, (byte) otherGainDB);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Sets the controller LED color on supported controllers.
   */
  public static void setLEDColor (
    long inputHandle, int colorR, int colorG, int colorB, LEDFlag flag)
  {
    if (flag == null) {
      throw new NullPointerException("flag");
    }
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_SetLEDColor.invokeExact(
        self(), inputHandle, (byte) colorR, (byte) colorG, (byte) colorB,
        flag.ordinal());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Triggers a haptic pulse on a Steam Controller. If you are approximating rumble you may
   * want to use {@link #triggerVibration} instead.
   *
   * @deprecated wraps Steam's {@code Legacy_TriggerHapticPulse}. Prefer
   *     {@link #triggerVibration} or {@link #triggerSimpleHapticEvent}.
   */
  @Deprecated
  public static void legacyTriggerHapticPulse (
    long inputHandle, ControllerPad targetPad, int durationMicroSec)
  {
    if (targetPad == null) {
      throw new NullPointerException("targetPad");
    }
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_Legacy_TriggerHapticPulse.invokeExact(
        self(), inputHandle, targetPad.ordinal(), (short) durationMicroSec);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Triggers a repeated haptic pulse on a Steam Controller.
   *
   * @deprecated wraps Steam's {@code Legacy_TriggerRepeatedHapticPulse}. Prefer
   *     {@link #triggerVibration} or {@link #triggerSimpleHapticEvent}.
   */
  @Deprecated
  public static void legacyTriggerRepeatedHapticPulse (
    long inputHandle, ControllerPad targetPad,
    int durationMicroSec, int offMicroSec, int repeat, int flags)
  {
    if (targetPad == null) {
      throw new NullPointerException("targetPad");
    }
    if (!_initialized) return;
    try {
      CSteam.ISteamInput_Legacy_TriggerRepeatedHapticPulse.invokeExact(
        self(), inputHandle, targetPad.ordinal(),
        (short) durationMicroSec, (short) offMicroSec, (short) repeat, flags);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  // ---- Utility ----

  /**
   * Invokes the Steam overlay and brings up the binding screen.
   *
   * @param inputHandle the controller handle.
   * @return true if the overlay was successfully shown.
   */
  public static boolean showBindingPanel (long inputHandle)
  {
    if (!_initialized) return false;
    try {
      return (boolean) CSteam.ISteamInput_ShowBindingPanel.invokeExact(self(), inputHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns the input type for a particular controller handle.
   *
   * @return the input type, or {@link InputType#UNKNOWN} if not initialized or not found.
   */
  public static InputType getInputTypeForHandle (long inputHandle)
  {
    if (!_initialized) {
      return InputType.UNKNOWN;
    }
    try {
      int type = (int) CSteam.ISteamInput_GetInputTypeForHandle
        .invokeExact(self(), inputHandle);
      InputType[] types = InputType.values();
      return (type >= 0 && type < types.length) ? types[type] : InputType.UNKNOWN;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns the associated controller handle for the specified emulated gamepad index.
   *
   * @return the controller handle, or 0 if not associated with Steam Input.
   */
  public static long getControllerForGamepadIndex (int index)
  {
    if (!_initialized) return 0L;
    try {
      return (long) CSteam.ISteamInput_GetControllerForGamepadIndex
        .invokeExact(self(), index);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns the associated gamepad index for the specified controller, or -1 if not
   * associated with an XInput index.
   */
  public static int getGamepadIndexForController (long inputHandle)
  {
    if (!_initialized) return -1;
    try {
      return (int) CSteam.ISteamInput_GetGamepadIndexForController
        .invokeExact(self(), inputHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets the binding revision for a given device.
   *
   * @param inputHandle the controller handle.
   * @param revisionOut an array of at least 2 elements to receive [major, minor].
   * @return false if the handle was not valid or if a mapping is not yet loaded.
   */
  public static boolean getDeviceBindingRevision (long inputHandle, int[] revisionOut)
  {
    if (revisionOut == null || revisionOut.length < 2) {
      throw new IllegalArgumentException("revisionOut must have at least 2 elements");
    }
    if (!_initialized) return false;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment major = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment minor = arena.allocate(ValueLayout.JAVA_INT);
      boolean ok = (boolean) CSteam.ISteamInput_GetDeviceBindingRevision
        .invokeExact(self(), inputHandle, major, minor);
      if (ok) {
        revisionOut[0] = major.get(ValueLayout.JAVA_INT, 0);
        revisionOut[1] = minor.get(ValueLayout.JAVA_INT, 0);
      }
      return ok;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets the Steam Remote Play session ID associated with a device, or 0 if none.
   */
  public static int getRemotePlaySessionID (long inputHandle)
  {
    if (!_initialized) return 0;
    try {
      return (int) CSteam.ISteamInput_GetRemotePlaySessionID
        .invokeExact(self(), inputHandle);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Gets a bitmask of the Steam Input Configuration types opted in for the current session.
   */
  public static int getSessionInputConfigurationSettings ()
  {
    if (!_initialized) return 0;
    try {
      return (int) CSteam.ISteamInput_GetSessionInputConfigurationSettings
        .invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  private static MemorySegment self ()
  {
    MemorySegment s = _self;
    if (s == null) {
      s = SteamAPI.iface(CSteam.SteamAPI_SteamInput);
      _self = s;
    }
    return s;
  }

  /** Are we initialized? */
  private static boolean _initialized;

  private static volatile MemorySegment _self;
}
