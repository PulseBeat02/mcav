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

The module provides two backends, both created from `BrowserPlayer`:

| Factory                        | Browser                                                                                   |
|--------------------------------|-------------------------------------------------------------------------------------------|
| `BrowserPlayer.selenium()`     | The Chrome installed on the machine, driven by [Selenium](https://www.selenium.dev/). The matching ChromeDriver is downloaded automatically. |
| `BrowserPlayer.playwright()`   | A headless Chromium downloaded by [Playwright](https://playwright.dev/) on first use. No Chrome installation is needed; on Linux the system libraries Chromium needs must be present. |

Both stream the page with the Chrome DevTools screencast, so frames arrive whenever the page changes. Audio is not
captured. Describe the page with a `BrowserSource`: the address, the JPEG quality of the frames, their size, and how
many browser frames are skipped between streamed frames.

The example opens a page, clicks into it, and types a search. The `display` filter is yours and shows the frames;
release the returned browser when you are done with the page.

```java
  public static BrowserPlayer searchWikipedia(final VideoFilter display) {
    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.of(display);
    final URI address = URI.create("https://www.wikipedia.org");
    final BrowserSource source = BrowserSource.uri(address, 80, 1280, 720, 1);
    final BrowserPlayer browser = BrowserPlayer.selenium();
    final VideoAttachableCallback callback = browser.getVideoAttachableCallback();
    callback.attach(videoPipelineStep);

    browser.start(source); // throws a PlayerException if the browser cannot be started
    browser.sendMouseEvent(MouseClick.LEFT, 640, 360);
    browser.sendKeyEvent("hello");
    browser.sendKeyEvent("Enter");
    return browser;
  }
```

Input coordinates are in the coordinate system of the streamed frames and are translated to the page. `sendKeyEvent`
presses a key when given the name of a
[KeyboardEvent key](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_key_values), such as
`Enter`, `Backspace`, or `ArrowLeft`, and types any other text character by character. When the page opens a new tab,
the stream follows it.
