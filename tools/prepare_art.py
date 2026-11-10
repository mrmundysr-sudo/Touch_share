#!/usr/bin/env python3
"""Turn App Visual Builder exports into Touch Share art.

Usage:  python3 -I tools/prepare_art.py <unzipped-export-folder> [<another-export-folder> ...]

Reads each export's manifest.json plus every page's layer file ("pictureshop-layers" json), then writes
  app/src/main/assets/art/<id>.webp        screen art (compressed)
  app/src/main/assets/art/art-config.json  id -> image + regions (fractions 0..1); existing pages are kept
  app/src/main/res/...                     launcher icon (adaptive + legacy)
The FIRST (original) export keeps its page ids (page_01 ...). Pages from LATER exports are named by their page
name (e.g. anywhere_send) because a new builder project restarts its numbering, so ids would collide.
Regions come from layer names (slug), adjusted by PAGE_RULES below. PSD files are never copied. Needs Pillow.
"""
import json, re, sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "app/src/main/assets/art"
RES = ROOT / "app/src/main/res"

# Per page-name rules for later exports.
#  images:  list of (output id, source) where source is "clean" or "other_png:N" (the Nth png in the Other slot)
#  rename:  layer-name slug -> region label the app uses (the artist's layer names do not always match what they cover)
#  ignore:  layer-name slugs that are decoration only
#  extra:   regions the layer file lacks, in pixels of the layer canvas: label -> (x, y, w, h)
PAGE_RULES = {
    "anywhere_beta": {
        "images": [("anywhere_send", "clean"), ("anywhere_receive", "other_png:1")],   # same layout, two looks
        "rename": {
            "finish_transmission": "share_button",       # sits over the big SHARE TRANSMISSION button
            "show_qr": "qr_button",
            "copy_transmission": "copy_button",
            "code_area_box": "code_area",
            "transmission_timer_box": "timer_area",
            "status_line": "status_line",
        },
        "ignore": ["transmission_expires_in"],           # the painted plaque above the timer
        "extra": {"stop_button": (20, 1556, 876, 118)},  # measured from the picture: the layer file has no Stop layer
    },
    "anywhere_qr_overlay": {
        "images": [("anywhere_qr", "clean")],
        "rename": {"code": "code_area", "web_address_box": "site_area", "qr_white_box": "qr_square"},
        "ignore": [], "extra": {},
    },
}


def slug(s):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")


def regions_from(layer_file, rules=None):
    data = json.loads(Path(layer_file).read_text())
    cw, ch = data["canvas"]["width"], data["canvas"]["height"]
    rules = rules or {}
    out = {}
    for layer in data["layers"]:
        if layer["type"] == "background":
            continue
        name = slug(layer["name"])
        if name in rules.get("ignore", []):
            continue
        label = rules.get("rename", {}).get(name, name)
        pos, size = layer["position"], layer["size"]
        out[label] = (pos["x"], pos["y"], size["width"], size["height"])
    for label, box in rules.get("extra", {}).items():
        out[label] = box
    return {k: {"x": round(x / cw, 5), "y": round(y / ch, 5), "w": round(w / cw, 5), "h": round(h / ch, 5)} for k, (x, y, w, h) in out.items()}, (cw, ch)


def save_webp(img, out_id):
    ART.mkdir(parents=True, exist_ok=True)
    p = ART / f"{out_id}.webp"
    img.save(p, "WEBP", quality=88, method=6)
    return p


def process(src, first, pages):
    m = json.loads((src / "manifest.json").read_text())
    for p in m["pages"]:
        clean = p["slots"].get("clean")
        pname = slug(p["name"])
        others = p["slots"].get("other", [])
        json_layers = next((o["path"] for o in others if o["path"].endswith(".json")), None)
        if first:
            if not clean:
                continue
            ids_sources = [(p["id"], "clean")]
            rules = None
        else:
            rules = PAGE_RULES.get(pname)
            if not rules or not clean:
                continue
            ids_sources = rules["images"]
        for out_id, source in ids_sources:
            if source == "clean":
                img_path = src / clean["path"]
            else:
                n = int(source.split(":")[1])
                pngs = [o["path"] for o in others if o["path"].lower().endswith(".png")]
                if len(pngs) < n:
                    print(f"  skip {out_id}: no png #{n} in the Other slot"); continue
                img_path = src / pngs[n - 1]
            img = Image.open(img_path)
            w, h = img.size
            regions = {}
            if json_layers:
                regions, (cw, ch) = regions_from(src / json_layers, rules)
                if (cw, ch) != (w, h):
                    print(f"  note: {out_id} layer canvas {cw}x{ch} differs from image {w}x{h}; fractions use the canvas")
            save_webp(img, out_id)
            pages[out_id] = {"name": p["name"], "image": f"{out_id}.webp", "width": w, "height": h, "regions": regions}
            print(f"  {out_id:22s} {p['name']}: {w}x{h}, regions: {', '.join(regions) or '-'}")
    return m


def icon(src, m):
    ic = next((p for p in m["pages"] if slug(p["name"]) == "app_icon"), None)
    if not ic:
        return
    d = {Path(o["path"]).stem: src / o["path"] for o in ic["slots"].get("other", [])}
    if "icon_foreground" not in d or "icon_background" not in d:
        return
    fg = Image.open(d["icon_foreground"]).convert("RGBA")
    bg = Image.open(d["icon_background"]).convert("RGBA")
    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    fg.resize((432, 432), Image.LANCZOS).save(RES / "drawable-nodpi/ic_launcher_fg.png", optimize=True)
    bg.resize((432, 432), Image.LANCZOS).convert("RGB").save(RES / "drawable-nodpi/ic_launcher_bg.png", optimize=True)
    leg = bg.resize((192, 192), Image.LANCZOS)
    leg.alpha_composite(fg.resize((150, 150), Image.LANCZOS), (21, 21))
    (RES / "mipmap-nodpi").mkdir(parents=True, exist_ok=True)
    leg.convert("RGB").save(RES / "mipmap-nodpi/ic_launcher.png", optimize=True)
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
    print("  icon: drawable-nodpi/ic_launcher_{fg,bg}.png, mipmap-nodpi/ic_launcher.png")


def main(folders):
    cfg_path = ART / "art-config.json"
    old = json.loads(cfg_path.read_text()) if cfg_path.exists() else {"version": 1, "pages": {}}
    pages = dict(old.get("pages", {}))
    source = old.get("source", {})
    for i, f in enumerate(folders):
        src = Path(f)
        print(f"export {i + 1}: {src}")
        m = process(src, i == 0, pages)
        icon(src, m)
        source = {"app": m["app_name"], "exported_at": m["exported_at"]} if i == 0 or not source else source
    cfg = {"version": 1, "source": source, "pages": pages}
    ART.mkdir(parents=True, exist_ok=True)
    cfg_path.write_text(json.dumps(cfg, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
