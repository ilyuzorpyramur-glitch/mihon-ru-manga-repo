#!/usr/bin/env python3
"""Generate launcher icons for every RU manga extension in one unified style.

Style: 192px canvas (xxxhdpi), rounded square inset 5px, radius 44,
accent-coloured 5px border, near-black tinted fill, big bold accent monogram.
All mipmap densities are derived from the 432px master by downscaling.

Usage: python3 .github/scripts/make-icons.py [ext ...]
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SRC = Path(__file__).resolve().parents[2] / "src"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# density -> icon side in px
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

MASTER = 768  # render big, downscale for clean edges
SCALE = MASTER / 192

# ext dir -> (monogram, accent rgb, dark fill rgb)
# Ключ либо "<ext>" (лежит в src/ru), либо "<lang>/<ext>" для остальных языков.
ICONS = {
    "acomics": ("AC", (255, 150, 60), (34, 24, 14)),
    "allhentai": ("AH", (255, 70, 110), (36, 16, 24)),
    "animestock": ("AS", (208, 92, 227), (26, 16, 30)),
    "astramanga": ("AM", (140, 130, 255), (22, 22, 40)),
    "comx": ("CX", (255, 90, 80), (36, 18, 18)),
    "desu": ("DS", (120, 210, 255), (14, 28, 40)),
    "henchan": ("HC", (255, 100, 150), (36, 16, 26)),
    "hentailib": ("HL", (235, 90, 160), (34, 16, 28)),
    "inkstory": ("IS", (160, 200, 255), (18, 24, 38)),
    "mangabuff": ("MB", (255, 180, 70), (36, 26, 14)),
    "mangachan": ("MC", (120, 220, 140), (16, 32, 22)),
    "mangalove": ("ML", (255, 130, 130), (36, 18, 20)),
    "mangahub": ("MH", (90, 190, 240), (14, 26, 38)),
    "mangalib": ("ML", (110, 90, 245), (22, 22, 34)),
    "mangamen": ("MM", (200, 170, 255), (26, 20, 38)),
    "mangapoisk": ("MP", (100, 210, 200), (14, 30, 32)),
    "mangashi": ("MS", (255, 140, 120), (36, 20, 20)),
    "mintmanga": ("MT", (80, 225, 175), (14, 32, 28)),
    "ninegrid": ("9G", (150, 220, 90), (22, 32, 16)),
    "nudemoon": ("NM", (230, 120, 200), (32, 16, 30)),
    "puremanga": ("PM", (170, 225, 255), (16, 26, 36)),
    "readmanga": ("RM", (255, 120, 90), (36, 20, 18)),
    "seimanga": ("SM", (120, 200, 255), (16, 26, 40)),
    "selfmanga": ("SF", (255, 200, 90), (36, 28, 14)),
    "senkognito": ("SK", (190, 130, 255), (28, 18, 38)),
    "senkuro": ("SN", (90, 215, 190), (14, 32, 30)),
    "slashlib": ("SL", (255, 110, 170), (36, 16, 28)),
    "tomilolib": ("TL", (255, 160, 120), (36, 24, 18)),
    "unicomics": ("UC", (255, 220, 90), (36, 32, 14)),
    "usagi": ("US", (255, 170, 190), (36, 22, 26)),
    "wamanga": ("WM", (130, 180, 255), (18, 24, 40)),
    "yagamiproject": ("YP", (200, 220, 120), (28, 32, 16)),
    "yaoichan": ("YC", (245, 100, 180), (34, 16, 28)),
    # src/all — мультиязычные источники (18+ галереи)
    "all/hentai3": ("3H", (255, 80, 120), (36, 16, 24)),
    "all/imhentai": ("IM", (255, 120, 90), (36, 20, 18)),
    "all/hentaiera": ("HE", (255, 90, 140), (36, 16, 26)),
    "all/hentaienvy": ("HV", (200, 110, 255), (28, 18, 38)),
    "all/hentaizap": ("HZ", (255, 200, 80), (36, 28, 14)),
    "all/hentaihand": ("HH", (120, 200, 255), (16, 26, 40)),
    "all/akuma": ("AK", (235, 70, 70), (34, 16, 16)),
    "all/pandachaika": ("PC", (170, 225, 255), (16, 26, 36)),
    "all/mangadex": ("MD", (255, 103, 64), (36, 20, 16)),
    "all/kagane": ("KG", (140, 220, 200), (16, 30, 28)),
    "all/comicfury": ("CF", (255, 140, 60), (36, 24, 14)),
    "all/globalcomix": ("GC", (110, 200, 255), (14, 26, 38)),
    "all/lunaranime": ("LM", (180, 170, 255), (24, 22, 40)),
    "all/mangaball": ("BA", (255, 200, 110), (36, 28, 16)),
    "all/mangadotnet": ("DT", (130, 230, 170), (16, 32, 24)),
    "all/mangamillion": ("MN", (255, 120, 160), (36, 18, 26)),
    "all/mangaplus": ("M+", (240, 80, 80), (34, 16, 16)),
    "all/myreadingmanga": ("MR", (200, 150, 255), (28, 20, 38)),
    "all/namicomi": ("NC", (120, 210, 230), (14, 28, 34)),
    "all/niadd": ("ND", (255, 170, 90), (36, 26, 16)),
    "all/novelcool": ("NV", (150, 210, 255), (16, 26, 38)),
    "all/simplyhentai": ("SH", (255, 90, 170), (36, 16, 28)),
    "all/xcomic": ("XC", (220, 120, 255), (30, 18, 38)),
    "all/dragonballmultiverse": ("DB", (255, 165, 60), (36, 26, 14)),
    "all/leagueoflegends": ("LL", (200, 175, 110), (30, 26, 16)),
    "all/xkcd": ("XK", (190, 200, 215), (22, 24, 28)),
}


def fit_font(text: str, max_w: int, max_h: int) -> ImageFont.FreeTypeFont:
    size = max_h
    while size > 8:
        font = ImageFont.truetype(FONT, size)
        box = font.getbbox(text)
        if box[2] - box[0] <= max_w and box[3] - box[1] <= max_h:
            return font
        size -= 2
    return ImageFont.truetype(FONT, 8)


def render(monogram: str, accent, fill) -> Image.Image:
    img = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    inset = round(5 * SCALE)
    radius = round(44 * SCALE)
    border = round(5 * SCALE)
    box = (inset, inset, MASTER - inset - 1, MASTER - inset - 1)

    draw.rounded_rectangle(box, radius=radius, fill=(*accent, 255))
    draw.rounded_rectangle(
        (box[0] + border, box[1] + border, box[2] - border, box[3] - border),
        radius=radius - border,
        fill=(*fill, 255),
    )

    inner = MASTER - 2 * (inset + border)
    font = fit_font(monogram, int(inner * 0.80), int(inner * 0.52))
    left, top, right, bottom = font.getbbox(monogram)
    draw.text(
        (MASTER / 2 - (left + right) / 2, MASTER / 2 - (top + bottom) / 2),
        monogram,
        font=font,
        fill=(*accent, 255),
    )
    return img


def main(only=None):
    for ext, (monogram, accent, fill) in ICONS.items():
        if only and ext not in only:
            continue
        master = render(monogram, accent, fill)
        for density, side in DENSITIES.items():
            rel = ext if "/" in ext else f"ru/{ext}"
            out = SRC / rel / "res" / f"mipmap-{density}" / "ic_launcher.png"
            out.parent.mkdir(parents=True, exist_ok=True)
            master.resize((side, side), Image.LANCZOS).save(out)
        print(f"{ext}: {monogram}")


if __name__ == "__main__":
    import sys

    main(set(sys.argv[1:]) or None)
