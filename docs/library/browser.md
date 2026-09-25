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

The first browser that starts on a machine downloads the CEF build for it, 136 to 165 MB depending on the platform,
from Maven Central into MCAV's cache folder (`~/.mcav/cache/jcef` of the user that runs the application). The download
is checked against a SHA-256 hash pinned in MCAV and unpacked with checks that keep every file inside that folder;
later starts reuse it. Two applications starting at the same time download it once.

| Operating System | Architectures           | Notes                                                                        |
|------------------|-------------------------|------------------------------------------------------------------------------|
| Linux            | x86-64, ARM64           | Nothing to install: no X server, no Xvfb, no packages; see below.            |
| Windows          | x86-64, ARM64           | ARM64 is supported by the build but untested.                                |
| macOS            | x86-64, ARM64 (Apple)   |                                                                              |

`BrowserPlayer.isSupported()` tells whether there is a CEF build for the machine. On any other platform, and on 32-bit
systems, `start` fails with a `BrowserUnavailableException` that names the platform, and nothing is downloaded; so
does a start whose download or check fails, with the reason in its message.

### Linux on a stock server

The browser runs on a stock headless Linux server, such as a Minecraft server in a Docker image, with nothing
installed and no JVM options. Chromium draws on its headless platform, which needs no display server. CEF's Java
binding still asks for an X display once at the start, for a window of one pixel it never shows; the helper answers
it itself with a null display: a minimal X11 endpoint inside the helper, on the loopback interface, that only a
client presenting the helper's random cookie may use, and that answers the handful of questions CEF asks and nothing
else.

Chromium links against libraries a desktop has but a server image often lacks (the X11 client libraries, NSS, ALSA,
ATK, cups and others). The first start on Linux downloads those the server lacks from the frozen archive of Debian 11
(bullseye) into `~/.mcav/cache/jcef-libraries`, about 13 MB: 52 packages per architecture, each checked against a
SHA-256 hash pinned in MCAV, of which only the shared libraries are unpacked. Debian 11 is built for glibc 2.31, so
any server with glibc 2.31 or newer can use them (libcef itself needs glibc 2.25). A library the server has is always
the server's own: only the missing ones are linked into the folder of each browser, and only its helper process gets
that folder on its `LD_LIBRARY_PATH`. A few basic libraries (such as zlib, expat, fontconfig and freetype) are
expected from the server, as every server image tested has them; a server without one of them gets a
`BrowserUnavailableException` that names it.

This was proven in the images `eclipse-temurin:25-jre`, `ghcr.io/pterodactyl/yolks:java_25` and
`itzg/minecraft-server:latest`, run as an unprivileged user without capabilities: a page streams, the player is
released and started again, and no process of the browser is left.

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

`release` ends the helper process and its Chromium processes. Stopping the module, for example when a plugin is
disabled, ends every browser that is running or still starting, and each player hears of it through its exception
handler; no browser starts until the module starts again. If your application dies without releasing its browsers,
every helper notices and ends within 20 seconds.

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

- The sound of the page reaches the helper through a DevTools binding that the page could call too, before MCAV's
  script takes it away; the helper takes only exact calls with whole frames of sound, at most two seconds of sound per
  second. A page written to do so can therefore play sound before anyone clicked it, but nothing else.
- On Linux, the helper's null display listens on the loopback interface only and answers only a client that presents
  the random cookie of that helper. The libraries MCAV downloads for Linux are frozen Debian 11 packages, which get
  no more security updates; they only fill in for libraries the server lacks, and only the helper uses them.

## Sound

The sound a page plays through Web Audio and its audio and video elements arrives at the audio pipeline of the player,
as 16-bit little-endian stereo samples at 48 kHz, like the sound of every MCAV player:

```java
  final AudioAttachableCallback audio = browser.getAudioAttachableCallback();
  audio.attach(AudioPipelineStep.of(speakers));
```

JCEF has no way to hand over Chromium's own audio, so a script that MCAV adds to every document before the page's own
scripts does it: every Web Audio context of a document is one context at 48 kHz, audio and video elements play into it
at their own volume, and its samples go to the helper. Nothing plays on the speakers of the machine. As in a desktop
browser, a page may play sound only once someone clicked or typed into it, such as with `sendMouseEvent`;
`BrowserOptions.builder().autoplay(true)` lets pages play sound right away. The sound of one frame of the page plays at
a time, and the sound of frames from another site (which Chromium runs in another process), of media from another site
that does not allow it (CORS), and of protected media (DRM) stays silent. The sound reaches the pipeline within a few
tens of milliseconds of its picture.
