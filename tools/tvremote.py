#!/usr/bin/env python3
"""
Virtual remote for the Google TV device, over the same protocol the phone app uses
(_androidtvremote2._tcp, port 6466). Useful when a physical button dies, and as a way
to reach Settings when an app is in the foreground and ADB is off.

    ./tvremote.py pair              # once; type the code shown on the TV
    ./tvremote.py key HOME
    ./tvremote.py key BACK DPAD_DOWN DPAD_CENTER
    ./tvremote.py app net.khaledez.camwall   # relaunch the wall after exiting it
    ./tvremote.py current                    # what is in the foreground right now

Certificates are stored next to this script, so pairing survives reboots.
"""
import asyncio, os, sys
from androidtvremote2 import AndroidTVRemote

HERE = os.path.dirname(os.path.abspath(__file__))
CERT = os.path.join(HERE, ".tvremote_cert.pem")
KEY = os.path.join(HERE, ".tvremote_key.pem")
HOST = os.environ.get("TV_HOST", "192.168.88.172")


async def remote():
    r = AndroidTVRemote("camwall-tools", CERT, KEY, HOST)
    await r.async_generate_cert_if_missing()
    return r


async def do_pair():
    r = await remote()
    name = await r.async_start_pairing()
    print(f"pairing with {name!r} — a code is now on the TV")
    code = input("code: ").strip()
    await r.async_finish_pairing(code)
    print("paired; certificates saved next to this script")


async def do_key(keys):
    r = await remote()
    try:
        await r.async_connect()
    except Exception as e:
        print(f"connect failed ({type(e).__name__}); run `{sys.argv[0]} pair` first", file=sys.stderr)
        raise SystemExit(1)
    r.keep_reconnecting()
    for k in keys:
        r.send_key_command(k)
        print(f"sent {k}")
        await asyncio.sleep(0.4)
    r.disconnect()


async def do_app(target):
    r = await remote()
    await r.async_connect()
    r.keep_reconnecting()
    r.send_launch_app_command(target)
    print(f"launched {target}")
    await asyncio.sleep(1.0)
    r.disconnect()


async def do_current():
    r = await remote()
    await r.async_connect()
    r.keep_reconnecting()
    await asyncio.sleep(1.0)
    print(r.current_app)
    r.disconnect()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    cmd = sys.argv[1]
    if cmd == "pair":
        asyncio.run(do_pair())
    elif cmd == "key":
        keys = sys.argv[2:] or ["HOME"]
        asyncio.run(do_key(keys))
    elif cmd == "app":
        if len(sys.argv) < 3:
            print("usage: tvremote.py app <package-or-deeplink>")
            raise SystemExit(2)
        asyncio.run(do_app(sys.argv[2]))
    elif cmd == "current":
        asyncio.run(do_current())
    else:
        print(__doc__)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
