# Browser Module

MCAV streams web pages in a separate module called `mcav-browser`. Add the module and install it when you create the
library instance.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-browser:1.0.0-SNAPSHOT")
}
```

```java
  final MCAVApi api = MCAV.api();
  api.install(BrowserModule.class);
```

The page is rendered by Chromium through [JCEF](https://github.com/chromiumembedded/java-cef), the Java binding of
the Chromium Embedded Framework, packaged by [jcefmaven](https://github.com/jcefmaven/jcefmaven) 146.0.10
(Chromium 146). Every started `BrowserPlayer` runs its Chromium in a helper process of its own, so the JVM of your
application needs no options at all, and a crash of the browser never takes your application with it. Chromium hands
every painted frame over as plain pixels, so frames arrive only when the page changes and need no image decoding.

## Installation

The first browser that starts on a machine downloads the CEF build for it, about 150 MB, from Maven Central into
MCAV's cache folder (`~/.mcav/cache/jcef`). The download is checked against a SHA-256 hash pinned in MCAV and unpacked
with checks that keep every file inside that folder; later starts reuse it. Two applications starting at the same
time download it once.

| Operating System | Architectures           | Notes                                                                        |
|------------------|-------------------------|------------------------------------------------------------------------------|
| Linux            | x86-64, ARM64           | Needs the `Xvfb` program: the package `xvfb` (Debian, Ubuntu, Alpine), `xorg-x11-server-Xvfb` (Fedora, RHEL) or `xorg-server-xvfb` (Arch), and the system libraries Chromium links against. |
| Windows          | x86-64, ARM64           | ARM64 is supported by the build but untested.                                |
| macOS            | x86-64, ARM64 (Apple)   |                                                                              |

On Linux, every browser starts a private `Xvfb` display of its own, which only its helper can use; Chromium never
draws on it, but CEF needs an X display to start. On any other platform, and on 32-bit systems, `start` fails with a
`PlayerException` that names the platform, and nothing is downloaded.

## Playing a Page

Describe the page with a `BrowserSource`: an absolute `http` or `https` address of at most 65 536 characters, the size
of the page and its frames (at most 4096 pixels per side), and how many painted frames make one streamed frame. `BrowserPlayer.create()` uses the
default options; `BrowserPlayer.create(BrowserOptions)` takes others.

The example opens a page, clicks into it, and types a search. The `display` filter is yours and shows the frames;
release the returned browser when you are done with the page.

```java
  public static BrowserPlayer searchWikipedia(final VideoFilter display) {
    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.of(display);
    final URI address = URI.create("https://www.wikipedia.org");
    final BrowserSource source = BrowserSource.uri(address, 1280, 720, 1);
    final BrowserPlayer browser = BrowserPlayer.create();
    final VideoAttachableCallback callback = browser.getVideoAttachableCallback();
    callback.attach(videoPipelineStep);

    browser.start(source); // throws a PlayerException if the page cannot be shown
    browser.sendMouseEvent(MouseClick.LEFT, 640, 360);
    browser.sendKeyEvent("hello");
    browser.sendKeyEvent("Enter");
    return browser;
  }
```

`start` returns once the page has loaded and its first frame arrived; a page that cannot be loaded, such as an
unknown host, fails the start. After a page stops changing, its last frame is handed to the pipeline again a few
times, so a video filter that spreads a big change over several frames, like the map encoder with its byte budget,
finishes it.

`release` ends the helper process, its Chromium processes and its display. Stopping the module, for example when a
plugin is disabled, ends every browser that is running or still starting, and each player hears of it through its
exception handler; no browser starts until the module starts again. If your application dies without releasing its
browsers, every helper notices, ends within 20 seconds, and takes its display with it.

## Input

Input coordinates are pixels of the page, which has the size of the frames. `sendMouseEvent` clicks with the left or
right button, double clicks, or holds and releases the left button, and a move while the button is held drags,
`scroll` turns the mouse wheel at a position, and `sendKeyEvent` presses a key when given the name of a
[KeyboardEvent key](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_key_values), such as
`Enter`, `Backspace`, or `ArrowLeft`, and types any other text character by character, however long it is; a line
break presses Enter, once for `\r\n`. Input goes through Chromium's
DevTools protocol inside the helper; no debugging port is ever opened. Like every Chromium, the browser ignores clicks
and keys for a moment right after a page appears.

## Security

A page is untrusted content, and the browser runs without Chromium's sandbox, which JCEF cannot use. MCAV limits what
a page can do instead:

- Only `http` and `https` pages are shown, besides the empty `about:blank`; frames may also hold `data:` and `blob:`
  documents. `file:`, `chrome:` and other addresses are refused, and so is handing an address to another program.
- By default, pages reach public addresses of the internet only. Every connection goes through a guard in the helper
  that resolves the host itself and refuses loopback, private, link-local (such as the metadata service of a cloud
  machine) and other special addresses, also when a public name resolves to one, and also behind a NAT64 prefix of
  the network, which the guard learns from `ipv4only.arpa`. WebRTC may only use proxied connections. `BrowserOptions.builder().privateNetworks(true)` allows the machine's own network, for example to show
  a dashboard of your network.
- JavaScript runs without V8's just-in-time compiler by default, the part of Chromium most exploits target.
  `BrowserOptions.builder().javaScriptJit(true)` turns it on for pages you trust.
- New windows open in place, as long as their address may be shown: `window.open`, links and forms that target
  another window, but only during a click or a key, as a popup blocker allows; links into a named frame of the page
  stay in that frame.
- Downloads, file choosers, logins and invalid certificates are refused, JavaScript dialogs are dismissed (alerts are
  confirmed, questions answered with cancel, pages may always be left), and permission prompts are denied.
- The helper gets a minimal environment, a folder only the user running MCAV can read (on Windows, a folder in the
  user's own temporary folder), which is deleted when the browser is released, and a profile that keeps nothing.

Audio of the page is not captured.
