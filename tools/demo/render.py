#!/usr/bin/env python3
"""Cuts, lays out and captions the raw demo recording (tools/demo/record.sh).

Reads build/demo/raw.mkv, events.log and record-start, and writes to
docs/media/ (or the directory given as the first argument):

  build/demo/demo.mp4   1920x1080 side-by-side video with step captions (not
                        committed: upload it to YouTube or a release)
  demo.gif          800x450 looping version for READMEs and mod pages
  step-*.png        1280x720 stills of each step, for the mod page galleries

Needs ffmpeg and Pillow, plus the Fira Sans fonts (FONT_DIR to override).
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
DEMO = ROOT / "build" / "demo"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "docs" / "media"
FONT_DIR = Path(os.environ.get("FONT_DIR", "/usr/share/fonts/TTF"))

W, H = 1920, 1080
PANE_W, PANE_H = 928, 522
PANE_Y = 236
LEFT_X, RIGHT_X = 20, W - 20 - PANE_W

BG = (30, 35, 48)
CREAM = (239, 230, 214)
EMBER = (244, 162, 97)
BRICK = (184, 74, 58)
MUTED = (160, 160, 175)


def font(weight, size):
    return ImageFont.truetype(str(FONT_DIR / f"FiraSans-{weight}.ttf"), size)


def events():
    start = int((DEMO / "record-start").read_text().strip())
    ev = {}
    for line in (DEMO / "events.log").read_text().splitlines():
        ms, role, name = line.split()
        ev.setdefault(name, (int(ms) - start) / 1000.0)
    return ev


def background(path):
    img = Image.new("RGB", (W, H), BG)
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse((W // 2 - 900, 700, W // 2 + 900, 1500), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    img = Image.composite(Image.new("RGB", (W, H), (120, 60, 35)), img, glow)
    d = ImageDraw.Draw(img)

    title = "Hearthgate"
    f = font("Bold", 76)
    d.text((W // 2, 70), title, font=f, fill=CREAM, anchor="mm")
    d.text((W // 2, 132), "Play with your friends, wherever they are.", font=font("SemiBold", 34),
           fill=EMBER, anchor="mm")

    for x, label, sub in ((LEFT_X, "HOST", "Alex shares a singleplayer world"),
                          (RIGHT_X, "FRIEND", "Steve, on another network")):
        lf = font("Bold", 26)
        tw = d.textlength(label, font=lf)
        d.rounded_rectangle((x, PANE_Y - 50, x + tw + 28, PANE_Y - 14), radius=6, outline=BRICK, width=3,
                            fill=(26, 30, 42))
        d.text((x + 14, PANE_Y - 32), label, font=lf, fill=CREAM, anchor="lm")
        d.text((x + tw + 44, PANE_Y - 32), sub, font=font("Medium", 26), fill=MUTED, anchor="lm")
        d.rounded_rectangle((x - 4, PANE_Y - 4, x + PANE_W + 3, PANE_Y + PANE_H + 3), radius=6, outline=BRICK,
                            width=4)
    img.save(path)


def caption(path, step, text):
    img = Image.new("RGBA", (W, 200), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f = font("SemiBold", 46)
    tw = d.textlength(text, font=f)
    badge = 64 if step else 0
    gap = 22 if step else 0
    x = (W - (badge + gap + tw)) / 2
    cy = 100
    if step:
        d.ellipse((x, cy - badge / 2, x + badge, cy + badge / 2), fill=BRICK)
        d.text((x + badge / 2, cy + 1), str(step), font=font("Bold", 40), fill=CREAM, anchor="mm")
    d.text((x + badge + gap, cy), text, font=f, fill=CREAM if step else EMBER, anchor="lm")
    img.save(path)


def run(cmd):
    print("+", " ".join(str(c) for c in cmd), file=sys.stderr)
    subprocess.run(cmd, check=True)


def main():
    ev = events()
    raw = DEMO / "raw.mkv"
    OUT.mkdir(parents=True, exist_ok=True)

    start = ev["share-clicked"] - 4.6
    ready = ev["share-ready"]
    copied = ev["copied"]
    connect = ev["connect-clicked"]
    joined = ev["joined"]
    end = max(ev["join-done"], ev.get("host-back-in-game", 0) + 3.5)

    # Raw-time spans, each with a playback speed; loading screens play faster.
    spans = [(start, connect + 0.6, 1.0), (connect + 0.6, joined + 0.3, 2.0), (joined + 0.3, end, 1.0)]
    captions = [
        (start, ready, 1, "Host: press Esc and click Share with Hearthgate"),
        (ready, copied + 0.9, 2, "Copy the invitation and send it to your friend"),
        (copied + 0.9, connect + 0.6, 3, "Friend: Multiplayer → Join with Hearthgate → paste → Connect"),
        (connect + 0.6, joined + 0.3, 0, "Connecting through an encrypted tunnel…"),
        (joined + 0.3, end + 1, 0, "You're in. No port forwarding, no server, no extra apps."),
    ]

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        background(tmp / "bg.png")
        inputs = ["-i", str(raw), "-loop", "1", "-i", str(tmp / "bg.png")]
        for i, (_, _, step, text) in enumerate(captions):
            caption(tmp / f"cap{i}.png", step, text)
            inputs += ["-loop", "1", "-i", str(tmp / f"cap{i}.png")]

        f = [
            "[0:v]split[a][b]",
            f"[a]crop=1280:720:0:0,scale={PANE_W}:{PANE_H}:flags=lanczos[l]",
            f"[b]crop=1280:720:1280:0,scale={PANE_W}:{PANE_H}:flags=lanczos[r]",
            f"[1:v][l]overlay={LEFT_X}:{PANE_Y}:shortest=1[v0]",
            f"[v0][r]overlay={RIGHT_X}:{PANE_Y}[v1]",
        ]
        cur = "v1"
        for i, (a, b, _, _) in enumerate(captions):
            f.append(f"[{cur}][{i + 2}:v]overlay=0:{PANE_Y + PANE_H + 40}:enable='between(t,{a:.2f},{b:.2f})'[c{i}]")
            cur = f"c{i}"
        f.append(f"[{cur}]split={len(spans)}" + "".join(f"[s{i}]" for i in range(len(spans))))
        for i, (a, b, speed) in enumerate(spans):
            f.append(f"[s{i}]trim={a:.3f}:{b:.3f},setpts=(PTS-STARTPTS)/{speed}[t{i}]")
        f.append("".join(f"[t{i}]" for i in range(len(spans))) + f"concat=n={len(spans)}:v=1:a=0,"
                 "fade=in:st=0:d=0.4,format=yuv420p[out]")

        mp4 = DEMO / "demo.mp4"
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *inputs, "-filter_complex", ";".join(f),
             "-map", "[out]", "-r", "30", "-c:v", "libx264", "-preset", "slow", "-crf", "20",
             "-movflags", "+faststart", str(mp4)])

        gif = OUT / "demo.gif"
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(mp4), "-filter_complex",
             "setpts=PTS/1.3,fps=10,scale=800:-1:flags=lanczos,split[a][b];"
             "[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
             "-loop", "0", str(gif)])

        stills = {
            "step-1-share-button": ("host", ev["share-clicked"] - 0.6),
            "step-2-invitation": ("host", copied - 1.2),
            "step-3-join-screen": ("join", connect - 0.4),
            "step-4-connected": ("host", ev["peer-connected"] + 1.5),
            "step-5-together": ("join", end - 1.0),
        }
        for name, (role, t) in stills.items():
            x = 0 if role == "host" else 1280
            run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", f"{t:.2f}", "-i", str(raw),
                 "-frames:v", "1", "-vf", f"crop=1280:720:{x}:0", str(OUT / f"{name}.png")])

    for p in [mp4, *sorted(OUT.iterdir())]:
        print(f"{p.stat().st_size / 1e6:6.2f} MB  {p}")


if __name__ == "__main__":
    main()
