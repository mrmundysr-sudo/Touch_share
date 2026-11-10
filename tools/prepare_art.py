#!/usr/bin/env python3
"""Turn an App Visual Builder export into Touch Share art.

Usage:  python3 -I tools/prepare_art.py <unzipped-export-folder>

Reads manifest.json plus each page's layer file (other_1.json, "pictureshop-layers"), then writes
  app/src/main/assets/art/<page_id>.webp          screen art (compressed)
  app/src/main/assets/art/art-config.json         page ids -> image + regions (fractions 0..1)
  app/src/main/res/...                            launcher icon (adaptive + legacy)
Pages are wired by permanent page ID (page_01 ...), never by file name. Regions come from the layer
names (e.g. "Send Button" -> send_button) and are stored as fractions so any screen size works.
PSD files are never copied. Needs Pillow.
"""
import json, re, sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "app/src/main/assets/art"
RES = ROOT / "app/src/main/res"


def slug(s):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")


def main(src):
    src = Path(src)
    m = json.loads((src / "manifest.json").read_text())
    ART.mkdir(parents=True, exist_ok=True)
    pages = {}
    for p in m["pages"]:
        pid = p["id"]
        clean = p["slots"].get("clean")
        if not clean:
            continue
        img = Image.open(src / clean["path"])
        w, h = img.size
        out = f"{pid}.webp"
        img.save(ART / out, "WEBP", quality=88, method=6)
        regions = {}
        for o in p["slots"].get("other", []):
            if not o["path"].endswith(".json"):
                continue
            data = json.loads((src / o["path"]).read_text())
            if data.get("format") != "pictureshop-layers":
                continue
            cw, ch = data["canvas"]["width"], data["canvas"]["height"]
            if (cw, ch) != (w, h):
                print(f"  note: {pid} layer canvas {cw}x{ch} differs from image {w}x{h}; using canvas for fractions")
            for layer in data["layers"]:
                if layer["type"] == "background":
                    continue
                pos, size = layer["position"], layer["size"]
                regions[slug(layer["name"])] = {
                    "x": round(pos["x"] / cw, 5), "y": round(pos["y"] / ch, 5),
                    "w": round(size["width"] / cw, 5), "h": round(size["height"] / ch, 5),
                }
        pages[pid] = {"name": p["name"], "image": out, "width": w, "height": h, "regions": regions}
        print(f"  {pid} {p['name']}: {out} {w}x{h}, regions: {', '.join(regions) or '-'}")
    cfg = {"version": 1, "source": {"app": m["app_name"], "exported_at": m["exported_at"]}, "pages": pages}
    (ART / "art-config.json").write_text(json.dumps(cfg, indent=2) + "\n")

    # launcher icon
    icon = next((p for p in m["pages"] if p["id"] == "page_06"), None)
    if icon:
        d = {Path(o["path"]).stem: src / o["path"] for o in icon["slots"]["other"]}
        fg = Image.open(d["icon_foreground"]).convert("RGBA")
        bg = Image.open(d["icon_background"]).convert("RGBA")
        (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
        fg.resize((432, 432), Image.LANCZOS).save(RES / "drawable-nodpi/ic_launcher_fg.png", optimize=True)
        bg.resize((432, 432), Image.LANCZOS).convert("RGB").save(RES / "drawable-nodpi/ic_launcher_bg.png", optimize=True)
        # legacy icon (Android 7): background + foreground shrunk so the UFO is not cropped
        leg = bg.resize((192, 192), Image.LANCZOS)
        f2 = fg.resize((150, 150), Image.LANCZOS)
        leg.alpha_composite(f2, (21, 21))
        (RES / "mipmap-nodpi").mkdir(parents=True, exist_ok=True)
        leg.convert("RGB").save(RES / "mipmap-nodpi/ic_launcher.png", optimize=True)
        print("  icon: drawable-nodpi/ic_launcher_{fg,bg}.png, mipmap-nodpi/ic_launcher.png")
        a = RES / "mipmap-anydpi-v26"; a.mkdir(parents=True, exist_ok=True)
        (a / "ic_launcher.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_bg" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_fg_inset" />\n'
            '</adaptive-icon>\n')
        (RES / "drawable").mkdir(parents=True, exist_ok=True)
        (RES / "drawable/ic_launcher_fg_inset.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- shrink the art so the UFO stays inside the circular safe zone -->\n'
            '<inset xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:drawable="@drawable/ic_launcher_fg"\n'
            '    android:inset="10%" />\n')


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
