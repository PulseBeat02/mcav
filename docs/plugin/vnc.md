# VNC Desktops

`/mcav vnc create` connects the **Minecraft server** to a VNC server and shows its desktop on a wall of maps; players
click it by clicking the wall and type into it through chat.

## The Allow-List

The Minecraft server is the one that connects, not the players, so a player must never choose where it connects. A
command can only name a server the operator listed in `vnc.allowed-hosts` of `plugins/MCAV/config.yml`, and **none is
listed by default**: until you list one, the command reaches nothing. Write the server's password there, never in the
command, since the server log keeps every command; MCAV never writes it to a log, and `/mcav dump` leaves it out.

```yaml
vnc:
  allowed-hosts:
    - host: 127.0.0.1
      port: 5901
      password: secret
    - host: "::1"
      port: 5902
```

Leave the password out for a server without one. Write an IPv6 address without brackets in the list; the command names
it as `[::1]:5902`. Restart the server after changing the list.

## Showing a Desktop

```text
/mcav screen 10x6 0 BLACK_CONCRETE ~ ~ ~
/mcav vnc create @a 1280x720 20 10x6 0 NEAREST_COLOR 127.0.0.1:5901
```

The arguments are the viewers, the size the desktop is scaled to, the frames a second streamed from it (1 to 240), the
wall's size and first map id, the dithering algorithm (`NEAREST_COLOR` keeps text sharp), and the server as
`host:port` exactly as listed. Add `--codec mcv2` at the end for a sharper picture; a desktop's text reads far better at
its own resolution than dithered at 128 pixels per map ([MCV2 on Maps](../bukkit/mcv2.md)). `/mcav vnc release`
disconnects and clears the wall. A VNC desktop has no sound.

## Clicking and Typing

Players with `mcav.vnc.interact` click the desktop by clicking the wall: left and right clicks. `/mcav vnc interact`
types their chat into it, and a key name such as `Return`, `Escape` or `Left` (an X11 key name) presses that key.

## Permissions

| Permission | Allows |
|---|---|
| `mcav.command.vnc.create` | `/mcav vnc create` |
| `mcav.vnc.interact` | `/mcav vnc interact`, and clicking the desktop's wall |
| `mcav.vnc.release` | `/mcav vnc release` |

All three are for operators until granted.

## A Hostile VNC Server

MCAV checks everything a VNC server sends before its VNC client reads it: every size, length, name, colour map and
message type, and only the encodings the client supports. A server that sends anything else, or a size that would make
the client allocate more than it should, is disconnected instead. Still, list only servers you control.
