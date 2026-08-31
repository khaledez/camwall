#!/usr/bin/env python3
"""
Probe RTSP cameras: find each one's substream path, codec and resolution.

Uses ffprobe as the RTSP engine — hand-rolled digest auth is not worth maintaining.

    ./probe-rtsp.py --user admin --pass 'secret' 192.168.88.200 192.168.88.10

Prints a ready-to-use cameras.json, preferring the smallest H.264 stream found,
since Media3's RTSP stack cannot decode H.265 and the SoC has few decoder slots.
"""
import argparse, json, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor

PATHS = [
    # Dahua / Amcrest / Lorex
    "/cam/realmonitor?channel=1&subtype=1",
    "/cam/realmonitor?channel=1&subtype=2",
    "/cam/realmonitor?channel=1&subtype=0",
    # Hikvision: NNM = channel N, stream M (1=main, 2=sub, 3=third)
    "/Streaming/Channels/102",
    "/Streaming/Channels/103",
    "/Streaming/Channels/101",
    "/h264/ch1/sub/av_stream",
    # Reolink / TP-Link / generic ONVIF
    "/h264Preview_01_sub",
    "/stream2",
    "/live/ch1",
    "/media/video2",
    "/onvif2",
    "/1/2",                                   # Tiandy: /<channel>/<stream>
    "/1/1",
]

def ffprobe(url, timeout=15):
    """Return list of stream dicts, or None if the URL did not open."""
    cmd = [
        "ffprobe", "-v", "error",
        "-rtsp_transport", "tcp", "-rtsp_flags", "prefer_tcp",
        "-select_streams", "v",
        "-show_entries", "stream=codec_name,width,height,avg_frame_rate",
        "-of", "json", "-i", url,
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=timeout, text=True)
    except subprocess.TimeoutExpired:
        return None
    if out.returncode != 0:
        return None
    try:
        streams = json.loads(out.stdout).get("streams", [])
    except json.JSONDecodeError:
        return None
    return streams or None


def fps(s):
    raw = s.get("avg_frame_rate", "0/0")
    try:
        n, d = raw.split("/")
        return round(int(n) / int(d)) if int(d) else 0
    except (ValueError, ZeroDivisionError):
        return 0


def probe_host(host, port, user, password):
    results = []
    def one(path):
        url = f"rtsp://{user}:{password}@{host}:{port}{path}"
        st = ffprobe(url)
        if st:
            v = st[0]
            return (path, v.get("codec_name", "?"), v.get("width", 0), v.get("height", 0), fps(v))
        return None
    with ThreadPoolExecutor(max_workers=4) as ex:
        for r in ex.map(one, PATHS):
            if r:
                results.append(r)
    return host, results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("hosts", nargs="+")
    ap.add_argument("--user", default=os.environ.get("CAM_USER", "admin"))
    ap.add_argument("--password", "--pass", dest="password",
                    default=os.environ.get("CAM_PASS", ""))
    ap.add_argument("--port", type=int, default=554)
    args = ap.parse_args()

    chosen = {}
    with ThreadPoolExecutor(max_workers=len(args.hosts)) as ex:
        futures = [ex.submit(probe_host, h, args.port, args.user, args.password)
                   for h in args.hosts]
        for f in futures:
            host, results = f.result()
            print(f"\n=== {host}:{args.port} ===")
            if not results:
                print("  nothing opened — wrong credentials, or a path not in the list")
                continue
            for path, codec, w, h, r in sorted(results, key=lambda x: x[2] * x[3]):
                note = "" if codec == "h264" else f"   <-- {codec.upper()}: Media3 RTSP cannot decode this"
                print(f"  {path}\n      {codec} {w}x{h} @{r}fps{note}")
            # Pick the stream closest to a wall tile (~960x540) without going far under:
            # the smallest stream is often CIF, which is too soft to be useful.
            target = 960 * 540
            chosen[host] = min(results, key=lambda x: abs(x[2] * x[3] - target))

    if chosen:
        cams = []
        for i, (host, (path, codec, w, h, r)) in enumerate(chosen.items(), 1):
            cams.append({"name": f"Cam {i} ({host.split('.')[-1]})",
                         "url": f"rtsp://{args.user}:{args.password}@{host}:{args.port}{path}",
                         "_stream": f"{codec} {w}x{h}@{r}"})
        print("\n--- cameras.json ---")
        print(json.dumps({"cameras": [{k: v for k, v in c.items() if not k.startswith("_")}
                                      for c in cams]}, indent=2))
        print("\n(selected: " + "; ".join(f"{c['name']} {c['_stream']}" for c in cams) + ")")

if __name__ == "__main__":
    sys.exit(main())
