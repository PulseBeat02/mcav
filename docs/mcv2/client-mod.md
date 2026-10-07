(mcv2-client-mod)=
# The MCV2 Client Mod

Iris draws with its own shaders while a shader pack is on, and they replace the shaders of the MCV2 pack, so a player
with Iris shaders on sees an MCV2 wall frozen or blank. A server cannot see a client's shader settings. The MCV2 client
mod tells it: while a shader pack is on, every MCV2 screen shows that player the dithered maps, the picture players
without the pack see; with the shaders off, the video again, without rejoining. Making MCV2 decode under a shader pack
is still to come.

## Who Needs It

Players who turn on Iris shader packs on a server with MCV2 screens. Nobody else does: vanilla, Sodium, and Iris with
its shaders off all show the MCV2 picture. A player with a modded client is told in the chat, once the MCV2 pack loads,
that shaders may hide the picture and that this mod helps; a player whose mod already reported is not.

## Installing

The mod is for Minecraft 26.3, on Fabric (Loader 0.19.5 or newer, with Fabric API) or NeoForge (26.3.0.43-beta or
newer), with or without Iris. Build it with `./gradlew :mcav-mcv2-client:assemble`, and put
`mcav-mcv2-client/build/libs/mcav-mcv2-client-fabric.jar` or `mcav-mcv2-client-neoforge.jar` into the client's `mods`
folder. It is not published anywhere yet.

## What It Sends

One plugin channel, `mcav:mcv2`, which carries nothing else. When the player joins a server that registered the channel,
as an MCAV server does, the mod sends a report, and another whenever the report changes; it sends a report once it held
for a second, as Iris has no shaders for a moment while it reloads a pack or the player changes world. A report is four
bytes:

| Byte | Meaning |
|---|---|
| 0 | the report's version, 1 |
| 1 | 1 if Iris is installed, else 0 |
| 2 | the shader pack: 0 none in use, 1 in use, 2 unknown (an Iris without the API the mod asks) |
| 3 | 1 if MCV2 decodes under the shader pack, else 0; always 0 for now |

The mod asks Iris only through its public API (`IrisApi.isShaderPackInUse()`). It sends nothing to a server that has
not registered the channel, which is every server without MCAV.

## For Admins

There is nothing to set up: the MCV2 pack server listens on the channel from the moment it starts, which the sandbox
plugin does as it is enabled. A client learns of the channel as it joins, so players who joined before the pack server
started report only after they rejoin. The server log says what each mod reports and what the player's screens show,
for example:

    The MCV2 client mod of Alex reports Iris with a shader pack in use: MCV2 screens show them the dithered maps

A report changes only how the player who sent it sees MCV2 screens, and is forgotten when they leave. Reports are
checked: one longer than 64 bytes, of another version, or malformed is ignored with a debug line, and so is one sent
faster than once a second after a burst of 5. A player whose mod reports a shader pack it cannot tell, or who has no
mod, sees MCV2 screens as before.
