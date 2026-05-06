# froth-foamy
Java API for the Steamworks SDK, implemented with Java's Foreign Function &amp; Memory API.

Froth-Foamy is a drop-in replacement for the `froth` library: it uses the same package
(`com.threerings.froth`), same class names, same method signatures. SteamController
support has been removed, so you'll need to adjust for that. But a few new things have
been added. Unlike froth, native build artifacts are not required except those from steam
in the `steamworks_sdk`.

Java 25.

You will need to actually include the `steamworks_sdk` libraries in your project --
those are not included here. This was built against Steamworks SDK 1.64.

## Loading the Steam library

At init time (in `SteamAPI.init()`), this library calls `System.loadLibrary("steam_api")`
on Linux/macOS and `System.loadLibrary("steam_api64")` on Windows. As with froth, you are
responsible for putting the platform-appropriate Steam shared library somewhere on
`java.library.path` before initialization. Setting the system property
`com.threerings.froth.disable_steam_api=true` will avoid even trying to load the library.
