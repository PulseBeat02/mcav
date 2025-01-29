FROM debian:bullseye as linux-windows-build
RUN apt-get update && apt-get install -y \
    build-essential \
    gcc-x86-64-linux-gnu \
    gcc-aarch64-linux-gnu \
    gcc-arm-linux-gnueabihf \
    gcc-mingw-w64 \
    openjdk-11-jdk \
    nasm \
    wget \
    xz-utils \
    && rm -rf /var/lib/apt/lists/*
RUN wget -q https://github.com/mstorsjo/llvm-mingw/releases/download/20230320/llvm-mingw-20230320-ucrt-ubuntu-18.04-x86_64.tar.xz && \
    mkdir -p /opt/llvm-mingw && \
    tar xf llvm-mingw-20230320-ucrt-ubuntu-18.04-x86_64.tar.xz -C /opt && \
    rm llvm-mingw-20230320-ucrt-ubuntu-18.04-x86_64.tar.xz
WORKDIR /src
COPY filterlite.c x86_64_odd.asm filterlite_asm.h /src/
RUN mkdir -p /tmp/win_jni/win32
RUN cp /usr/lib/jvm/java-11-openjdk-amd64/include/*.h /tmp/win_jni/ && \
    cp /usr/lib/jvm/java-11-openjdk-amd64/include/linux/jni_md.h /tmp/win_jni/win32/
RUN nasm -f elf64 x86_64_odd.asm -o x86_64_odd_linux.o && \
    x86_64-linux-gnu-gcc -shared -fPIC -I/usr/lib/jvm/java-11-openjdk-amd64/include -I/usr/lib/jvm/java-11-openjdk-amd64/include/linux filterlite.c x86_64_odd_linux.o -o libfilterlite-linux-x86_64.so
RUN aarch64-linux-gnu-gcc -shared -fPIC -I/usr/lib/jvm/java-11-openjdk-amd64/include -I/usr/lib/jvm/java-11-openjdk-amd64/include/linux filterlite.c -o libfilterlite-linux-aarch64.so && \
    arm-linux-gnueabihf-gcc -shared -fPIC -I/usr/lib/jvm/java-11-openjdk-amd64/include -I/usr/lib/jvm/java-11-openjdk-amd64/include/linux filterlite.c -o libfilterlite-linux-armhf.so
RUN nasm -f win64 x86_64_odd.asm -o x86_64_odd_win64.obj && \
    x86_64-w64-mingw32-gcc -shared -I/tmp/win_jni -I/tmp/win_jni/win32 filterlite.c x86_64_odd_win64.obj -o filterlite-win64.dll
RUN i686-w64-mingw32-gcc -shared -I/tmp/win_jni -I/tmp/win_jni/win32 filterlite.c -o filterlite-win32.dll
RUN /opt/llvm-mingw-20230320-ucrt-ubuntu-18.04-x86_64/bin/aarch64-w64-mingw32-clang -shared -I/tmp/win_jni -I/tmp/win_jni/win32 filterlite.c -o filterlite-win-arm64.dll
RUN /opt/llvm-mingw-20230320-ucrt-ubuntu-18.04-x86_64/bin/armv7-w64-mingw32-clang -shared -I/tmp/win_jni -I/tmp/win_jni/win32 filterlite.c -o filterlite-win-arm32.dll
FROM ghcr.io/shepherdjerred/macos-cross-compiler:latest as macos-build
WORKDIR /src
COPY filterlite.c x86_64_odd.asm filterlite_asm.h /src/
RUN mkdir -p /tmp/mac_jni
COPY --from=linux-windows-build /usr/lib/jvm/java-11-openjdk-amd64/include/*.h /tmp/mac_jni/
RUN echo '#define JNIEXPORT __attribute__((visibility("default")))' > /tmp/mac_jni/jni_md.h && \
    echo '#define JNIIMPORT' >> /tmp/mac_jni/jni_md.h && \
    echo '#define JNICALL' >> /tmp/mac_jni/jni_md.h && \
    echo 'typedef long jint;' >> /tmp/mac_jni/jni_md.h && \
    echo 'typedef long long jlong;' >> /tmp/mac_jni/jni_md.h && \
    echo 'typedef signed char jbyte;' >> /tmp/mac_jni/jni_md.h
RUN apt-get update && apt-get install -y nasm && rm -rf /var/lib/apt/lists/* && \
    x86_64-apple-darwin24-gcc -shared -fPIC -I/tmp/mac_jni filterlite.c -o libfilterlite-darwin-x86_64.dylib && \
    aarch64-apple-darwin24-gcc -shared -fPIC -I/tmp/mac_jni filterlite.c -o libfilterlite-darwin-aarch64.dylib
FROM debian:bullseye
WORKDIR /output
COPY --from=linux-windows-build /src/libfilterlite-linux-x86_64.so /output/
COPY --from=linux-windows-build /src/libfilterlite-linux-aarch64.so /output/
COPY --from=linux-windows-build /src/libfilterlite-linux-armhf.so /output/
COPY --from=linux-windows-build /src/filterlite-win64.dll /output/
COPY --from=linux-windows-build /src/filterlite-win32.dll /output/
COPY --from=linux-windows-build /src/filterlite-win-arm64.dll /output/
COPY --from=linux-windows-build /src/filterlite-win-arm32.dll /output/
COPY --from=macos-build /src/libfilterlite-darwin-x86_64.dylib /output/
COPY --from=macos-build /src/libfilterlite-darwin-aarch64.dylib /output/
ENTRYPOINT ["sh", "-c", "ls -la /output/ && mkdir -p /mounted-output && cp /output/libfilterlite-linux-x86_64.so /output/libfilterlite-linux-aarch64.so /output/libfilterlite-linux-armhf.so /output/filterlite-win64.dll /output/filterlite-win32.dll /output/filterlite-win-arm64.dll /output/filterlite-win-arm32.dll /output/libfilterlite-darwin-x86_64.dylib /output/libfilterlite-darwin-aarch64.dylib /mounted-output/"]