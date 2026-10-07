# Third-Party Notices

mcav is licensed under the GNU General Public License, version 3 or later (`LICENSE`). Most of its jars hold only mcav's
own code: their dependencies are listed in their POMs and fetched, under their own licences, by whoever builds with
them. This file names the third-party code that mcav's jars do contain, and what mcav downloads while it runs. It is
in `META-INF/` of every jar mcav publishes and of the sandbox plugin.

## mcav-installer.jar

The installer bundles the Maven resolver and what it needs, most of it relocated under `me.brandonli.mcav.libs`. The
NOTICE files of these components are merged into the jar's `META-INF/NOTICE`, and the Apache License 2.0 is its
`META-INF/LICENSE`. ASM's complete BSD licence and copyright notice are in `META-INF/LICENSE-ASM`.

| Component (group:artifact) | Licence |
|---|---|
| com.google.code.gson:gson | Apache-2.0 |
| com.google.errorprone:error_prone_annotations | Apache-2.0 |
| commons-codec:commons-codec | Apache-2.0 |
| org.apache.httpcomponents:httpclient | Apache-2.0 |
| org.apache.httpcomponents:httpcore | Apache-2.0 |
| org.apache.maven:maven-artifact | Apache-2.0 |
| org.apache.maven:maven-builder-support | Apache-2.0 |
| org.apache.maven:maven-model | Apache-2.0 |
| org.apache.maven:maven-model-builder | Apache-2.0 |
| org.apache.maven:maven-repository-metadata | Apache-2.0 |
| org.apache.maven:maven-resolver-provider | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-api | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-connector-basic | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-impl | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-named-locks | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-spi | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-supplier-mvn3 | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-transport-apache | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-transport-file | Apache-2.0 |
| org.apache.maven.resolver:maven-resolver-util | Apache-2.0 |
| org.codehaus.plexus:plexus-interpolation | Apache-2.0 |
| org.codehaus.plexus:plexus-utils | Apache-2.0 |
| org.ow2.asm:asm | BSD-3-Clause |
| org.slf4j:jcl-over-slf4j | Apache-2.0 |
| org.slf4j:slf4j-api | MIT |

## mcav-http.jar

The audio web page in the jar's `static/` folder is built from npm packages, minified into the page. The jar's
`static/THIRD-PARTY-NOTICES.txt`, written when the page is built, names every one of them with the licence and
copyright notice its package carries (MIT, Apache-2.0, BSD, ISC and 0BSD licences).

## The sandbox plugin jar

| Component (group:artifact) | Licence | Its licence text in the jar |
|---|---|---|
| xyz.jpenilla:gremlin-runtime | MIT | `META-INF/LICENSE_gremlin` |
| org.checkerframework:checker-qual | MIT | `META-INF/LICENSE.txt` |

## Downloaded While mcav Runs

mcav redistributes none of these; each comes from its own publisher, under its own licence:

- JavaCV and JavaCPP with FFmpeg (an LGPL build) and OpenCV, as Maven dependencies of mcav-common.
- VLC, from VideoLAN on Windows and macOS, and as an AppImage on Linux (GPL-2.0-or-later, libVLC LGPL-2.1-or-later).
- yt-dlp, from its GitHub releases (Unlicense; its executables include GPL-3.0-or-later code).
- The Chromium Embedded Framework and Chromium of the browser, as jcefmaven's natives from Maven Central (BSD-3-Clause,
  with Chromium's third-party licences inside the natives).
- The Debian 11 libraries the browser helper needs on a Linux server without them, each under its package's licence.
