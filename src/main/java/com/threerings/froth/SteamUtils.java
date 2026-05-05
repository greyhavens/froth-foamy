package com.threerings.froth;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CopyOnWriteArrayList;

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
   * Input modes for the full-screen gamepad text entry UI shown via
   * {@link #showGamepadTextInput}. Ordinals correspond to native
   * {@code EGamepadTextInputMode} values.
   */
  public enum GamepadTextInputMode
  {
    // Note: ordinals correspond to native EGamepadTextInputMode values. Do not reorder!
    /** Plaintext entry. */
    NORMAL,
    /** Entered characters are masked. */
    PASSWORD,
    ;
  }

  /**
   * Line modes for the full-screen gamepad text entry UI. Ordinals correspond to native
   * {@code EGamepadTextInputLineMode} values.
   */
  public enum GamepadTextInputLineMode
  {
    // Note: ordinals correspond to native EGamepadTextInputLineMode values. Do not reorder!
    /** A single line of input. Pressing enter dismisses the keyboard. */
    SINGLE_LINE,
    /** Multiple lines of input. The user must explicitly dismiss the keyboard. */
    MULTIPLE_LINES,
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
   * A callback interface for parties interested in the floating on-screen keyboard
   * being dismissed (either by the user or by an explicit
   * {@link #dismissFloatingGamepadTextInput} call).
   */
  public interface FloatingGamepadTextInputDismissedCallback
  {
    /**
     * Called when the floating on-screen keyboard has been closed. The Steam SDK
     * doesn't tell us whether the user submitted or canceled -- the host application
     * already knows what's in its own text field at the time of dismissal.
     */
    public void floatingGamepadTextInputDismissed ();
  }

  /**
   * A callback interface for parties interested in the application resuming from a
   * suspended state (e.g. the user putting their Steam Deck to sleep and waking it
   * back up). Useful for re-syncing audio devices, reconnecting controllers, or
   * pausing real-time game systems while the user steps away.
   */
  public interface AppResumingFromSuspendCallback
  {
    /**
     * Called when the application is resuming from a suspended state.
     */
    public void appResumingFromSuspend ();
  }

  /**
   * A callback interface for parties interested in the full-screen gamepad text input
   * UI (shown via {@link #showGamepadTextInput}) being dismissed. Unlike its floating
   * cousin, the full-screen flow has Steam own the entire text-entry session, so the
   * dismissal carries the submission state and length; retrieve the typed text via
   * {@link #getEnteredGamepadTextInput()}.
   */
  public interface GamepadTextInputDismissedCallback
  {
    /**
     * Called when the full-screen gamepad text input UI has been closed.
     *
     * @param submitted true if the user accepted their input, false if they canceled.
     * @param submittedText the byte-length of the entered text. Only meaningful when
     *     {@code submitted} is true; pair with {@link #getEnteredGamepadTextInput()}
     *     to retrieve the actual text.
     * @param appId the Steam AppID for which the dialog was shown.
     */
    public void gamepadTextInputDismissed (boolean submitted, int submittedText, int appId);
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
   * @return true if the floating keyboard was shown, otherwise, false.
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
   * Dismisses the floating on-screen keyboard if it is currently shown. Listeners
   * registered via {@link #addFloatingGamepadTextInputDismissedCallback} will be
   * notified once the keyboard finishes closing.
   *
   * @return true if the keyboard was successfully dismissed.
   */
  public static boolean dismissFloatingGamepadTextInput ()
  {
    try {
      return (boolean) CSteam.ISteamUtils_DismissFloatingGamepadTextInput.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Shows the full-screen gamepad text input UI -- Steam takes over the screen with
   * its own virtual keyboard. After the user dismisses the dialog, listeners
   * registered via {@link #addGamepadTextInputDismissedCallback} will fire with the
   * submission state; retrieve the entered text (if accepted) via
   * {@link #getEnteredGamepadTextInput()}.
   *
   * @param mode plaintext or password.
   * @param lineMode single line (enter dismisses) or multiple lines.
   * @param description prompt text shown above the input field.
   * @param charMax maximum number of characters the user may enter.
   * @param existingText pre-populated text in the input field, or null/empty for none.
   * @return true if the dialog was shown successfully.
   */
  public static boolean showGamepadTextInput (
    GamepadTextInputMode mode, GamepadTextInputLineMode lineMode,
    String description, int charMax, String existingText)
  {
    if (mode == null) {
      throw new NullPointerException("mode");
    }
    if (lineMode == null) {
      throw new NullPointerException("lineMode");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment desc = CSteam.allocCString(arena, description != null ? description : "");
      MemorySegment existing = CSteam.allocCString(
        arena, existingText != null ? existingText : "");
      return (boolean) CSteam.ISteamUtils_ShowGamepadTextInput.invokeExact(
        self(), mode.ordinal(), lineMode.ordinal(), desc, charMax, existing);
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Returns the byte-length of the most recently submitted gamepad text input. Equal
   * to the {@code submittedText} value delivered to a
   * {@link GamepadTextInputDismissedCallback}.
   */
  public static int getEnteredGamepadTextLength ()
  {
    try {
      return (int) CSteam.ISteamUtils_GetEnteredGamepadTextLength.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Convenience: retrieves the most recently submitted gamepad text input as a
   * Java String. Returns null if no text is available (e.g. the user canceled).
   *
   * <p>Steam returns NUL-terminated UTF-8; this method allocates a buffer sized to
   * {@link #getEnteredGamepadTextLength()} and decodes the result.
   */
  public static String getEnteredGamepadTextInput ()
  {
    int len = getEnteredGamepadTextLength();
    if (len <= 0) {
      return null;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buf = arena.allocate(len);
      boolean ok = (boolean) CSteam.ISteamUtils_GetEnteredGamepadTextInput.invokeExact(
        self(), buf, len);
      return ok ? CSteam.readCString(buf) : null;
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Dismisses the full-screen gamepad text input dialog if it is currently shown.
   * Listeners registered via {@link #addGamepadTextInputDismissedCallback} will be
   * notified once the dialog finishes closing.
   *
   * @return true if the dialog was successfully dismissed.
   */
  public static boolean dismissGamepadTextInput ()
  {
    try {
      return (boolean) CSteam.ISteamUtils_DismissGamepadTextInput.invokeExact(self());
    } catch (Throwable t) {
      throw SteamAPI.wrap(t);
    }
  }

  /**
   * Adds a listener that will be notified when the floating on-screen keyboard is
   * dismissed -- either by the user or via {@link #dismissFloatingGamepadTextInput}.
   */
  public static void addFloatingGamepadTextInputDismissedCallback (
    FloatingGamepadTextInputDismissedCallback callback)
  {
    if (_floatingDismissedCallbacks.isEmpty()) {
      SteamAPI.dispatcher().setBroadcastHandler(
        CB_FloatingGamepadTextInputDismissed, seg -> {
          // FloatingGamepadTextInputDismissed_t carries no payload.
          for (FloatingGamepadTextInputDismissedCallback cb : _floatingDismissedCallbacks) {
            cb.floatingGamepadTextInputDismissed();
          }
        });
    }
    _floatingDismissedCallbacks.add(callback);
  }

  /**
   * Removes a previously registered floating-keyboard dismissal listener.
   */
  public static void removeFloatingGamepadTextInputDismissedCallback (
    FloatingGamepadTextInputDismissedCallback callback)
  {
    _floatingDismissedCallbacks.remove(callback);
  }

  /**
   * Adds a listener that will be notified when the application is resuming from a
   * suspended state.
   */
  public static void addAppResumingFromSuspendCallback (AppResumingFromSuspendCallback callback)
  {
    if (_appResumingCallbacks.isEmpty()) {
      SteamAPI.dispatcher().setBroadcastHandler(CB_AppResumingFromSuspend, seg -> {
        // AppResumingFromSuspend_t carries no payload.
        for (AppResumingFromSuspendCallback cb : _appResumingCallbacks) {
          cb.appResumingFromSuspend();
        }
      });
    }
    _appResumingCallbacks.add(callback);
  }

  /**
   * Removes a previously registered app-resuming-from-suspend listener.
   */
  public static void removeAppResumingFromSuspendCallback (
    AppResumingFromSuspendCallback callback)
  {
    _appResumingCallbacks.remove(callback);
  }

  /**
   * Adds a listener that will be notified when the full-screen gamepad text input
   * dialog is dismissed.
   */
  public static void addGamepadTextInputDismissedCallback (
    GamepadTextInputDismissedCallback callback)
  {
    if (_gamepadDismissedCallbacks.isEmpty()) {
      SteamAPI.dispatcher().setBroadcastHandler(CB_GamepadTextInputDismissed, seg -> {
        boolean submitted = seg.get(
          ValueLayout.JAVA_BYTE, CSteam.GAMEPAD_DISMISSED_OFFSET_SUBMITTED) != 0;
        int submittedText = seg.get(
          ValueLayout.JAVA_INT, CSteam.GAMEPAD_DISMISSED_OFFSET_SUBMITTED_TEXT);
        int appId = seg.get(
          ValueLayout.JAVA_INT, CSteam.GAMEPAD_DISMISSED_OFFSET_APP_ID);
        for (GamepadTextInputDismissedCallback cb : _gamepadDismissedCallbacks) {
          cb.gamepadTextInputDismissed(submitted, submittedText, appId);
        }
      });
    }
    _gamepadDismissedCallbacks.add(callback);
  }

  /**
   * Removes a previously registered full-screen-keyboard dismissal listener.
   */
  public static void removeGamepadTextInputDismissedCallback (
    GamepadTextInputDismissedCallback callback)
  {
    _gamepadDismissedCallbacks.remove(callback);
  }

  /**
   * Checks whether Steam is running on a Steam Deck device.
   *
   * @return true if the current device is a Steam Deck, otherwise false.
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

  /** Callback IDs (k_iSteamUtilsCallbacks = 700). */
  private static final int CB_GamepadTextInputDismissed         = 714; // +14
  private static final int CB_AppResumingFromSuspend            = 736; // +36
  private static final int CB_FloatingGamepadTextInputDismissed = 738; // +38

  /** Listeners for floating-keyboard dismissal events. */
  private static final CopyOnWriteArrayList<FloatingGamepadTextInputDismissedCallback>
    _floatingDismissedCallbacks = new CopyOnWriteArrayList<>();

  /** Listeners for full-screen-keyboard dismissal events. */
  private static final CopyOnWriteArrayList<GamepadTextInputDismissedCallback>
    _gamepadDismissedCallbacks = new CopyOnWriteArrayList<>();

  /** Listeners for app-resuming-from-suspend events. */
  private static final CopyOnWriteArrayList<AppResumingFromSuspendCallback>
    _appResumingCallbacks = new CopyOnWriteArrayList<>();

  private static volatile MemorySegment _self;
}
