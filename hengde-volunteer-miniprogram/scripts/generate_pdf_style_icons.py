from collections import deque
from pathlib import Path
import subprocess

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[1]
PDF_PATH = REPO_ROOT / "志愿平台设想【第十版】.pdf"
TMP_DIR = REPO_ROOT / "tmp" / "pdf-preview"
PAGE_IMAGE = TMP_DIR / "volunteer-003.png"
ICON_DIR = ROOT / "assets" / "icons"

ICON_POINTS = [
    ("register", 282, 391),
    ("album", 384, 391),
    ("activity", 486, 391),
    ("structure", 587, 391),
    ("group", 282, 482),
    ("points", 384, 482),
    ("aid", 486, 482),
    ("wish", 587, 482),
    ("publicity", 919, 54),
    ("crowdfunding", 1020, 54),
    ("leader", 1122, 54),
    ("download", 1224, 54),
    ("enterprise", 919, 145),
    ("ranking", 1020, 145),
    ("model", 1122, 145),
    ("book", 1224, 145),
]


def render_pdf_page():
    TMP_DIR.mkdir(parents=True, exist_ok=True)
    if PAGE_IMAGE.exists():
        return
    subprocess.run(
        [
            "pdftoppm",
            "-png",
            "-f",
            "3",
            "-l",
            "3",
            "-r",
            "120",
            str(PDF_PATH),
            str(TMP_DIR / "volunteer"),
        ],
        check=True,
    )


def is_icon_pixel(pixel):
    r, g, b, a = pixel
    return a > 0 and r > 220 and 90 < g < 235 and 90 < b < 245 and not (r > 248 and g > 248 and b > 248)


def extract_icon(source, cx, cy):
    half = 55
    x0 = max(0, cx - half)
    y0 = max(0, cy - half)
    x1 = min(source.width, cx + half)
    y1 = min(source.height, cy + half)
    region = source.crop((x0, y0, x1, y1))
    width, height = region.size
    mask = [[is_icon_pixel(region.getpixel((x, y))) for x in range(width)] for y in range(height)]
    sx = min(max(cx - x0, 0), width - 1)
    sy = min(max(cy - y0, 0), height - 1)

    starts = []
    for y in range(height):
        for x in range(width):
            if mask[y][x]:
                starts.append((abs(x - sx) + abs(y - sy), x, y))
    _, sx, sy = min(starts)

    queue = deque([(sx, sy)])
    seen = {(sx, sy)}
    points = []
    while queue:
        x, y = queue.popleft()
        points.append((x, y))
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < width and 0 <= ny < height and mask[ny][nx] and (nx, ny) not in seen:
                seen.add((nx, ny))
                queue.append((nx, ny))

    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    bx0 = max(0, min(xs) - 4)
    by0 = max(0, min(ys) - 4)
    bx1 = min(width, max(xs) + 5)
    by1 = min(height, max(ys) + 5)

    side = max(bx1 - bx0, by1 - by0)
    mx = (bx0 + bx1) // 2
    my = (by0 + by1) // 2
    bx0 = max(0, mx - side // 2)
    by0 = max(0, my - side // 2)
    bx1 = min(width, bx0 + side)
    by1 = min(height, by0 + side)
    return region.crop((bx0, by0, bx1, by1)).resize((96, 96))


def main():
    render_pdf_page()
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    source = Image.open(PAGE_IMAGE).convert("RGBA")
    for name, x, y in ICON_POINTS:
        extract_icon(source, x, y).save(ICON_DIR / f"{name}.png")


if __name__ == "__main__":
    main()
