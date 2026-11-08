#!/usr/bin/env python3
"""Regenerate assets/ from a CleanFrame web checkout (only needed to pull in new artwork).

Usage: python tools/prepare-assets.py <CleanFrame checkout>/app/assets assets
Needs cairosvg, fonttools (with brotli) and pillow. theme.json and bootstrap.json next to
this script are copied as they are; theme.json holds the per-photo values measured from the
web build's computed styles.
"""
from pathlib import Path
import argparse
import json
import re
import shutil

import cairosvg
from fontTools.ttLib import TTFont
from PIL import Image

HERE = Path(__file__).resolve().parent
# The glass plate shows a heavily blurred photo; a quarter-size copy upscaled looks the same.
MATERIAL_SIZE = (480, 270)
BRAND_SIZE = 240


def js_object(path, start, end):
    text = path.read_text(encoding="utf-8")
    return json.loads(text[text.index(start):text.rindex(end) + 1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="CleanFrame app/assets folder")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    global SOURCE
    SOURCE = args.source.resolve()
    out = args.output.resolve()
    if out.exists():
        shutil.rmtree(out)
    for sub in ("wallpapers", "materials", "brands", "fonts", "licenses"):
        (out / sub).mkdir(parents=True)

    catalog = js_object(SOURCE / "wallpapers" / "catalog.js", "[", "]")
    walls = []
    for entry in catalog:
        wid = entry["id"]
        for suffix in ("", "-panel", "-thumb"):
            shutil.copy2(SOURCE / "wallpapers" / f"{wid}{suffix}.jpg", out / "wallpapers" / f"{wid}{suffix}.jpg")
        with Image.open(SOURCE / "materials" / f"{wid}-field-blur.jpg") as im:
            im.convert("RGB").resize(MATERIAL_SIZE, Image.LANCZOS).save(out / "materials" / f"{wid}.jpg", quality=90)
        walls.append({k: entry[k] for k in entry if k not in ("src", "thumb")})
    (out / "wallpapers.json").write_text(json.dumps(walls, ensure_ascii=False), encoding="utf-8")

    metrics = js_object(SOURCE / "icons" / "metrics.js", "{", "}")
    (out / "brands.json").write_text(json.dumps(metrics), encoding="utf-8")
    for svg in (SOURCE / "icons").glob("*.svg"):
        if svg.stem in ("apps", "art", "home", "settings"):
            continue
        png = out / "brands" / f"{svg.stem}.png"
        cairosvg.svg2png(url=str(svg), write_to=str(png), output_width=BRAND_SIZE, output_height=BRAND_SIZE)
        # Keep only coverage: the launcher tints marks with the current ink.
        with Image.open(png) as im:
            im.convert("RGBA").getchannel("A").save(png, optimize=True)

    font = TTFont(SOURCE / "manrope.woff2")
    font.flavor = None
    font.save(out / "fonts" / "manrope.ttf")
    font.close()
    shutil.copy2(SOURCE / "fonts" / "geist" / "Geist-Variable.ttf", out / "fonts" / "geist.ttf")

    for src, name in ((SOURCE / "manrope-OFL.txt", "Manrope-OFL.txt"),
                      (SOURCE / "fonts" / "geist" / "OFL.txt", "Geist-OFL.txt"),
                      (SOURCE / "wallpapers" / "SOURCES.json", "wallpaper-sources.json"),
                      (SOURCE.parent.parent / "LICENSE", "CleanFrame-LICENSE.txt")):
        if src.is_file():
            shutil.copy2(src, out / "licenses" / name)
    for lic in (SOURCE / "icons").glob("*LICENSE*.txt"):
        shutil.copy2(lic, out / "licenses" / lic.name)

    shutil.copy2(HERE / "theme.json", out / "theme.json")
    bootstrap = HERE / "bootstrap.json"
    if bootstrap.is_file():
        shutil.copy2(bootstrap, out / "bootstrap.json")


if __name__ == "__main__":
    main()
