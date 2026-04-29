package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.threerings.froth.internal.CSteam;

/**
 * Represents the Steam utilities interface.
 */
public class SteamUtils
{
  /** The available positions for overlay notifications. */
  public enum NotificationPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT };

  /** Controls the mode for the floating keyboard. */
  public enum FloatingGamepadTextInputMode
  {
   /** Enter dismisses the keyboard */
   SINGLE_LINE,
   /** User needs to explicitly dismiss the keyboard */
   MULTIPLE_LINES,
   /** Keyboard is displayed in a special mode that makes it easier to enter emails */
   EMAIL,
   /** Numeric keypad is shown */
   NUMERIC,
   ;
  }

  /**
   * Provides a means to obtain warning messages from the Steam API.
   */
  public interface WarningMessageHook
  {
    /**
     * Handles a warning message from Steam.
     *
     * @param severity the severity level (zero for message, one for warning).
     */
    public void warning (int severity, String message);
  }

  /**
   * Returns the application ID of the current process.
   */
  public static int getAppID ()
  {
    try {
      return (int) CSteam.ISteamUtils_GetAppID.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Sets the location of the notifications on the overlay.
   */
  public static void setOverlayNotificationPosition (NotificationPosition position)
  {
    try {
      CSteam.ISteamUtils_SetOverlayNotificationPosition.invokeExact(
        self(), position.ordinal());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Sets the callback for warning messages.
   */
  public static void setWarningMessageHook (WarningMessageHook hook)
  {
    // Steam's SetWarningMessageHook accepts a C function pointer of type
    // void(*)(int, const char*). We build an upcall stub bound to a private static
    // method, hold it in a long-lived shared arena (the upcall stub remains valid for
    // the lifetime of the arena), and replace any prior stub.
    try {
      MemorySegment stub;
      if (hook == null) {
        stub = MemorySegment.NULL;
      } else {
        _hook = hook;
        if (_warningStub == null) {
          var mh = MethodHandles.lookup().findStatic(
            SteamUtils.class, "warningMessageTrampoline",
            MethodType.methodType(void.class, int.class, MemorySegment.class));
          _warningStub = CSteam.linker().upcallStub(
            mh, FunctionDescriptor.ofVoid(
              ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            _hookArena);
        }
        stub = _warningStub;
      }
      CSteam.ISteamUtils_SetWarningMessageHook.invokeExact(self(), stub);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Checks whether the overlay is enabled and ready for use.
   */
  public static boolean isOverlayEnabled ()
  {
    try {
      return (boolean) CSteam.ISteamUtils_IsOverlayEnabled.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Checks whether the overlay needs a call to Present (Direct3D) or SwapBuffers (OpenGL).
   */
  public static boolean overlayNeedsPresent ()
  {
    try {
      return (boolean) CSteam.ISteamUtils_BOverlayNeedsPresent.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Opens a floating keyboard over the game content and sends OS keyboard keys directly
   * to the game. The text field position is specified in pixels relative the origin of the
   * game window and is used to position the floating keyboard in a way that doesn't cover
   * the text field.
   *
   * @returns true if the floating keyboard was shown, otherwise, false.
   */
  public static boolean showFloatingGamepadTextInput (
   FloatingGamepadTextInputMode keyboardMode,
   int textFieldXPosition, int textFieldYPosition, int textFieldWidth, int textFieldHeight)
  {
    try {
      return (boolean) CSteam.ISteamUtils_ShowFloatingGamepadTextInput.invokeExact(
        self(),
        keyboardMode.ordinal(),
        textFieldXPosition, textFieldYPosition,
        textFieldWidth, textFieldHeight);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Checks whether Steam is running on a Steam Deck device.
   *
   * @returns true if the current device is a Steam Deck, otherwise false.
   */
  public static boolean isSteamRunningOnSteamDeck ()
  {
    try {
      return (boolean) CSteam.ISteamUtils_IsSteamRunningOnSteamDeck.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * The trampoline invoked by the native C function pointer registered with Steam.
   * Must remain a {@code static} method so it can be bound via {@link
   * MethodHandles.Lookup#findStatic}.
   */
  @SuppressWarnings("unused")
  private static void warningMessageTrampoline (int severity, MemorySegment messagePtr)
  {
    WarningMessageHook hook = _hook;
    if (hook == null) {
      return;
    }
    String message = CSteam.readCString(messagePtr);
    try {
      hook.warning(severity, message != null ? message : "");
    } catch (Throwable t) {
      // Don't let an exception propagate back across the C boundary.
      System.err.println("[froth-foamy] WarningMessageHook threw: " + t);
      t.printStackTrace();
    }
  }

  /**
   * The "current Utils" interface, falling back to the game-server flavor when the
   * client interface isn't available -- matches the original froth helper.
   */
  private static MemorySegment self ()
  {
    MemorySegment s = _self;
    if (s == null) {
      try {
        s = (MemorySegment) CSteam.SteamAPI_SteamUtils.invokeExact();
        if (s == null || s.equals(MemorySegment.NULL)) {
          s = (MemorySegment) CSteam.SteamAPI_SteamGameServerUtils.invokeExact();
        }
      } catch (Throwable t) {
        throw SteamAPI.wrap(t);
      }
      _self = s;
    }
    return s;
  }

  /** Long-lived arena for upcall stubs registered with Steam. */
  private static final Arena _hookArena = Arena.ofShared();

  /** Latest warning hook (replaced on each setWarningMessageHook call). */
  private static volatile WarningMessageHook _hook;

  /** Cached upcall stub; created lazily on first hook registration and reused. */
  private static volatile MemorySegment _warningStub;

  private static volatile MemorySegment _self;
}
