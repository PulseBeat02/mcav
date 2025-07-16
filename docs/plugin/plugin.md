# Installation

```{note}
The MCAV plugin is currently in heavy development and may contain bugs or incomplete features. As the name suggests,
this is a sandbox plugin just for fun purposes.
```

```{warning}
If you are on Linux and using a headless environment, you must install several dependencies before you run the plugin.

Run the following command to install the necessary dependencies:
`sudo apt update && sudo apt install -y vlc libopencv-dev qemu-user-static ffmpeg`

If you are on a dedicated host (without access to the server itself), you need to contact your host provider to run that
command automatically for you. Please open a support ticket with them to request this.
```

The MCAV plugin is a Paper plugin that allows you to display images, vidoes, browsers, and virtual machines in
Minecraft. It is designed to be a fun and experimental plugin that showcases the capabilities of the MCAV library.

## Installation

It's super easy to install the MCAV plugin on your Minecraft server.

```{warning}
The MCAV plugin is compatible with **Paper** servers only. It will not work on **Spigot** or **Bukkit** servers. You must
also use version **1.21.8** to run the plugin. It will not work on any other version of Minecraft.
```

1) Grab the latest JAR from the TeamCity CI page [here](https://ci.brandonli.me/repository/download/mcav/.lastFinished/mcav-sandbox-1.0.0-v1.21.8-all.jar).
2) Place the JAR file into the `plugins` folder of your server.

On first load, the plugin will take a while to load as it needs to download all the necessary dependencies. Give it
around a few minutes for the first boot to complete. After that, subsequent boots should load substantially faster.

```{warning}
You must have QEMU installed and configured to use the virtual machine functionality of the MCAV plugin. Install QEMU
from your package manager or download it from the [official QEMU website](https://www.qemu.org/download/).
```