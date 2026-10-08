# Project Licensing

Please note that MCAV integrates several different libraries, each under different licenses based on what is
incorporated into the project. The following table lists the libraries used in MCAV, and their respective licenses.

| Library                                                | License                                                     |
|--------------------------------------------------------|-------------------------------------------------------------|
| [VideoLAN/VLC](https://code.videolan.org/videolan/vlc) | [GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) (or later) |
| [FFmpeg/FFmpeg](https://git.ffmpeg.org/ffmpeg.git) | [LGPLv2.1+ or GPLv2+, depending on build options](https://ffmpeg.org/legal.html) |
| [OpenCV/OpenCV](https://github.com/opencv/opencv)      | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [caprica/vlcj](https://github.com/caprica/vlcj)        | [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)            |
| [bytedeco/javacv](https://github.com/bytedeco/javacv)  | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp)      | [Unlicense](https://opensource.org/license/unlicense)       |
| [chromiumembedded/java-cef](https://github.com/chromiumembedded/java-cef) | [BSD 3-Clause](https://opensource.org/license/bsd-3-clause) |
| [jcefmaven/jcefmaven](https://github.com/jcefmaven/jcefmaven) | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [Bukkit/Bukkit](https://github.com/Bukkit/Bukkit)      | [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)            |

The browser module does not bundle Chromium: it downloads the Chromium Embedded Framework, under the BSD license of
Chromium and the licenses of its third-party code, on its first start. The yt-dlp source code is released under the
Unlicense, but the standalone yt-dlp executables that MCAV downloads are built with PyInstaller and include GPLv3+
code, so each of them is licensed under the GPLv3 or later as a whole.

As a result of this, the MCAV library is licensed under the [GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html)
license. The Apache 2 license is compatible with the GPLv3 license, but not with the GPLv2 license. License your
project under the GPLv3 license or another license that is compatible with it.

Respect all the licenses of the libraries used in MCAV, and ensure that your project complies with their terms!

The table names the main libraries only. Most MCAV jars bundle no third-party code; their dependencies come with their
own licences. `mcav-installer` bundles the Maven resolver, `mcav-http` the npm packages of its audio web page, and the
sandbox plugin two small libraries: `THIRD-PARTY-NOTICES.md` in the repository, and in `META-INF/` of every jar, lists
them with their licences, and `mcav-http`'s `mcav/http/website/THIRD-PARTY-NOTICES.txt` carries the notice of every npm package.
