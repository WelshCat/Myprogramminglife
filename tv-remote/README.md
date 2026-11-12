# Samsung UN32ES6400F TV remote

A remote control app for the Samsung **UN32ES6400F (2012 ES series)** that you use from a phone browser.
It needs only Python 3.8+ (no external libraries).

![screenshot](screenshot.png)

## Why do I need a server?

Samsung TVs from 2012 (the ES series) don't use the WebSocket method (port 8001/8002) of newer models.
They use the **legacy remote protocol on TCP port 55000**. Browsers can't open raw TCP connections, so:

```
Phone browser ──HTTP──▶ remote.py (PC / Raspberry Pi) ──TCP 55000──▶ TV
```

## How to use

1. Connect the TV to your home network (wired or Wi-Fi) and turn it on.
2. Find the TV's IP address
   ```bash
   python3 remote.py discover
   ```
   If it isn't found, check on the TV: **Menu → Network → Network Status**
3. Start the server (on a PC connected to the same router)
   ```bash
   python3 remote.py serve --tv 192.168.0.20
   ```
4. In your phone browser, open the `http://<PC IP>:8080` address shown in the terminal.
5. The first time you press a button, a connection request pops up on the TV → choose **Allow**.
   - Tip: choose "Add to Home Screen" in your phone browser to use it like an app.

### Send keys from the command line

```bash
python3 remote.py key --tv 192.168.0.20 VOLUP VOLUP MUTE
python3 remote.py key --tv 192.168.0.20 KEY_SOURCE
```

## Features

- Power off, input source, HDMI, Smart Hub, menu, tools, info, guide
- Direction pad / OK / back / exit
- Volume, channel (hold to repeat), mute, channel list, previous channel
- Color buttons (A/B/C/D), playback controls, number pad, custom key code entry
- Phone vibration feedback; keyboard control on a PC (arrow keys, Enter, +/−, m, numbers)
- Automatic reconnect when the connection drops

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Access denied" | You pressed Deny earlier. Change this device to **Allow** under **Menu → Network → AllShare Settings** (or Device Manager / Remote Control) |
| "Can't connect" | Check that the TV is on and on the same network as the PC. The TV's IP may have changed (DHCP), so check it again |
| Can't turn the TV **on** | Network power-on is not supported on ES-series models when the TV is fully off. Power **off** works. |
| Some key doesn't respond | Supported keys vary by region and firmware. Try another code in "Other keys → Custom input" |

## Tests

Protocol and web API tests using a fake TV server:

```bash
python3 -m unittest discover tests
```

## File layout

- `samsung_legacy.py` — Samsung legacy (port 55000) protocol, auto-reconnect, SSDP discovery
- `remote.py` — web server + command-line tool
- `static/` — mobile web remote UI (PWA)
