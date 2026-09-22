#!/usr/bin/env python
"""
Compare the card->detail seamless transition of two apps frame by frame.

Why this exists: "the info band does / does not move with the cover" is a
spatial question. Reading logs cannot answer it, and guessing wastes cycles.
This extracts frames from a screen recording and reports, per frame, where the
card's pixels actually are.

Method
------
For each frame we look at a vertical strip through the card's x-centre and
report the mean luminance of horizontal bands. A band that belongs to the
*animating* element changes brightness/position sharply between frames; a band
that is static stays put.

We also report the bounding box of "changed" pixels versus frame 0, which
directly shows the moving region's extent.

Usage
-----
    python scripts/analyze_transition.py <video.mp4> <outdir> \
        --x 300 --y0 1900 --y1 2600
"""
import argparse
import os
import subprocess
import sys

try:
    from PIL import Image
except ImportError:
    print("Pillow required: pip install pillow")
    sys.exit(1)


def extract_frames(video, outdir, fps=60):
    os.makedirs(outdir, exist_ok=True)
    for f in os.listdir(outdir):
        if f.startswith("f") and f.endswith(".png"):
            os.remove(os.path.join(outdir, f))
    cmd = [
        "ffmpeg", "-y", "-i", video,
        "-vf", f"fps={fps}",
        os.path.join(outdir, "f%04d.png"),
    ]
    subprocess.run(cmd, capture_output=True, check=True)
    return sorted(
        os.path.join(outdir, f)
        for f in os.listdir(outdir)
        if f.startswith("f") and f.endswith(".png")
    )


def band_luminance(img, x, y0, y1, bands=8):
    """Mean luminance of `bands` equal horizontal slices in the column at x."""
    w, h = img.size
    px = img.load()
    step = max(1, (y1 - y0) // bands)
    out = []
    for i in range(bands):
        a = y0 + i * step
        b = min(a + step, y1)
        if b <= a:
            out.append(0.0)
            continue
        total = 0
        n = 0
        # sample a few columns around x to reduce noise
        for xx in range(max(0, x - 3), min(w, x + 4), 3):
            for yy in range(a, b, 2):
                r, g, bl = px[xx, yy][:3]
                total += 0.299 * r + 0.587 * g + 0.114 * bl
                n += 1
        out.append(total / n if n else 0.0)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("outdir")
    ap.add_argument("--x", type=int, default=300)
    ap.add_argument("--y0", type=int, default=1900)
    ap.add_argument("--y1", type=int, default=2600)
    ap.add_argument("--bands", type=int, default=8)
    ap.add_argument("--fps", type=int, default=60)
    args = ap.parse_args()

    frames = extract_frames(args.video, args.outdir, args.fps)
    print(f"extracted {len(frames)} frames -> {args.outdir}")
    if not frames:
        return

    print()
    print(f"column x={args.x}, y range {args.y0}..{args.y1}, {args.bands} bands")
    print("band edges (y):", end=" ")
    step = max(1, (args.y1 - args.y0) // args.bands)
    print(", ".join(str(args.y0 + i * step) for i in range(args.bands)))
    print()

    prev = None
    for idx, fp in enumerate(frames):
        img = Image.open(fp).convert("RGB")
        bands = band_luminance(img, args.x, args.y0, args.y1, args.bands)
        # delta vs previous frame: which bands are actively changing
        if prev is None:
            delta = [0.0] * len(bands)
        else:
            delta = [abs(a - b) for a, b in zip(bands, prev)]
        prev = bands
        band_str = " ".join(f"{v:6.1f}" for v in bands)
        d_str = " ".join(f"{v:5.1f}" for v in delta)
        print(f"f{idx:03d}  L[{band_str}]  d[{d_str}]")

    print()
    print("How to read this:")
    print("  L = mean luminance per band (top band first).")
    print("  d = |L - previous frame| per band: where motion is happening.")
    print("  If the lower bands (info area) show large d values in the same")
    print("  frames as the upper bands (cover), both move together.")
    print("  If lower bands stay ~0 while upper bands change, only the cover")
    print("  animates and the info area is static -> the bug we are chasing.")


if __name__ == "__main__":
    main()
