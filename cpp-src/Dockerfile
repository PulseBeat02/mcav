FROM debian:bullseye
RUN apt-get update && apt-get install -y \
    build-essential \
    gcc-x86-64-linux-gnu \
    gcc-aarch64-linux-gnu \
    gcc-arm-linux-gnueabihf \
    gcc-mingw-w64 \
    openjdk-11-jdk \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY filterlite.c /src/
RUN mkdir -p /tmp/win_jni/win32
RUN cp /usr/lib/jvm/java-11-openjdk-amd64/include/*.h /tmp/win_jni/ && \
    cp /usr/lib/jvm/java-11-openjdk-amd64/include/linux/jni_md.h /tmp/win_jni/win32/
RUN x86_64-linux-gnu-gcc -shared -fPIC -I/usr/lib/jvm/java-11-openjdk-amd64/include -I/usr/lib/jvm/java-11-openjdk-amd64/include/linux filterlite.c -o libfilterlite-linux-x86_64.so && \
    aarch64-linux-gnu-gcc -shared -fPIC -I/usr/lib/jvm/java-11-openjdk-amd64/include -I/usr/lib/jvm/java-11-openjdk-amd64/include/linux filterlite.c -o libfilterlite-linux-aarch64.so && \
    arm-linux-gnueabihf-gcc -shared -fPIC -I/usr/lib/jvm/java-11-openjdk-amd64/include -I/usr/lib/jvm/java-11-openjdk-amd64/include/linux filterlite.c -o libfilterlite-linux-armhf.so && \
    x86_64-w64-mingw32-gcc -shared -I/tmp/win_jni -I/tmp/win_jni/win32 filterlite.c -o filterlite-win64.dll && \
    i686-w64-mingw32-gcc -shared -I/tmp/win_jni -I/tmp/win_jni/win32 filterlite.c -o filterlite-win32.dll && \
    mkdir -p /output && \
    cp libfilterlite-linux-x86_64.so /output/ && \
    cp libfilterlite-linux-aarch64.so /output/ && \
    cp libfilterlite-linux-armhf.so /output/ && \
    cp filterlite-win64.dll /output/ && \
    cp filterlite-win32.dll /output/ && \
    ls -la /output/
ENTRYPOINT ["sh", "-c", "ls -la /output/ && mkdir -p /mounted-output && cp /output/libfilterlite-linux-x86_64.so /output/libfilterlite-linux-aarch64.so /output/libfilterlite-linux-armhf.so /output/filterlite-win64.dll /output/filterlite-win32.dll /mounted-output/"]