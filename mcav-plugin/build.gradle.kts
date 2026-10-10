import me.brandonli.mcav.gradle.moduleCoordinate
import xyz.jpenilla.resourcefactory.bukkit.Permission
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("mcav.module")
    id("mcav.paper-plugin")
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(libs.gremlin.runtime)
    runtimeDownload(moduleCoordinate("mcav-bukkit"))
    runtimeDownload(moduleCoordinate("mcav-discord"))
    runtimeDownload(moduleCoordinate("mcav-http"))
    runtimeDownload(moduleCoordinate("mcav-common"))
    runtimeDownload(moduleCoordinate("mcav-vm"))
    runtimeDownload(moduleCoordinate("mcav-vnc"))
    runtimeDownload(moduleCoordinate("mcav-browser"))
    implementation(moduleCoordinate("mcav-voicechat"))
    runtimeDownload(libs.bundles.cloud)
    runtimeDownload(libs.commodore)
    runtimeDownload(libs.bstats.bukkit)
    runtimeDownload(libs.jda)
    testImplementation(libs.voicechat.api)
    testImplementation(libs.slf4j.simple)
}

paperPluginYaml {
    name = "MCAV"
    version = "${project.version}"
    description = "MCAV Sandbox Plugin"
    authors = listOf("PulseBeat_02")
    prefix = "MCAV Sandbox"
    loader = "me.brandonli.mcav.plugin.MCAVLoader"
    main = "me.brandonli.mcav.plugin.MCAVSandbox"
    dependencies.server("voicechat", PaperPluginYaml.Load.BEFORE, false)
    defaultPermission = Permission.Default.OP
    permissions {
        register("mcav.command.help") { description = "Shows the commands of MCAV and what they do" }
        register("mcav.command.dump") { description = "Uploads a report of the server and its logs, with addresses and other commands masked, for a bug report" }
        register("mcav.command.screen") { description = "Builds a wall of item frames with maps that images, videos, browsers and virtual machines show on" }
        register("mcav.command.hologram.set") { description = "Shows a video or an image as a hologram" }
        register("mcav.command.hologram.disable") { description = "Removes the hologram" }
        register("mcav.command.image.chat") { description = "Shows an image in chat" }
        register("mcav.command.image.block") { description = "Shows an image with blocks" }
        register("mcav.command.image.entity") { description = "Shows an image with the names of entities" }
        register("mcav.command.image.scoreboard") { description = "Shows an image on the scoreboard" }
        register("mcav.command.image.map") { description = "Shows an image on a wall of maps" }
        register("mcav.command.image.release") { description = "Removes the image" }
        register("mcav.command.video.chat") { description = "Plays a video in chat" }
        register("mcav.command.video.block") { description = "Plays a video with blocks" }
        register("mcav.command.video.entity") { description = "Plays a video with the names of entities" }
        register("mcav.command.video.scoreboard") { description = "Plays a video on the scoreboard" }
        register("mcav.command.video.map") { description = "Plays a video on a wall of maps" }
        register("mcav.command.video.mcv2") { description = "Plays a video on a wall of maps through the MCV2 codec, dithered for players without its resource pack" }
        register("mcav.command.video.pause") { description = "Pauses the video" }
        register("mcav.command.video.resume") { description = "Resumes the video" }
        register("mcav.command.video.release") { description = "Stops the video" }
        register("mcav.command.video.seek") { description = "Jumps to a time of the video" }
        register("mcav.command.video.volume") { description = "Sets the volume of the videos" }
        register("mcav.command.video.speed") { description = "Plays the video file faster or slower" }
        register("mcav.command.video.loop") { description = "Makes the videos play again when they end" }
        register("mcav.command.video.device") { description = "Lists the cameras and capture cards of the server, and plays them with the DEVICE player" }
        register("mcav.command.mcv2.play") { description = "Plays, streams and stops pre-encoded MCV2 streams from the plugin's mcv2 folder" }
        register("mcav.command.mcv2.encode") { description = "Pre-encodes a video file into an MCV2 stream in the plugin's mcv2 folder, and cancels that encode" }
        register("mcav.command.browser.create") { description = "Opens a web page on a wall of maps, with its sound in an audio output" }
        register("mcav.browser.release") { description = "Closes the browser" }
        register("mcav.browser.interact") { description = "Clicks and types into the browser: clicks on its screen, and chat with /mcav browser interact" }
        register("mcav.command.vm.create") { description = "Boots a QEMU virtual machine on a wall of maps, with its sound in an audio output" }
        register("mcav.vm.release") { description = "Powers off the virtual machine" }
        register("mcav.vm.interact") { description = "Clicks and types into the virtual machine: clicks on its screen, and chat with /mcav vm interact" }
        register("mcav.command.vnc.create") { description = "Shows the desktop of a VNC server listed in vnc.allowed-hosts on a wall of maps" }
        register("mcav.vnc.release") { description = "Disconnects from the VNC desktop" }
        register("mcav.vnc.interact") { description = "Clicks and types on the VNC desktop: clicks on its screen, and chat with /mcav vnc interact" }
    }
}
