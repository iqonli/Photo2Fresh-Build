from pathlib import Path
from PIL import Image

INPUT_FILE = Path("icon.ico")
OUTPUT_DIR = Path("android_icons")

# 改成适合你图标的纯色背景，避免黑边
BACKGROUND_COLOR = "#F2E6C9"

DENSITIES = {
    "ldpi": 0.75,
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

NEAREST = Image.Resampling.NEAREST


def load_16px_icon(path: Path) -> Image.Image:
    with Image.open(path) as ico:
        if hasattr(ico, "ico"):
            sizes = ico.ico.sizes()
            if (16, 16) in sizes:
                return ico.ico.getimage((16, 16)).convert("RGBA")
            raise ValueError(f"ICO 中没有 16x16 图层，现有尺寸：{sorted(sizes)}")

        image = ico.convert("RGBA")

    if image.size != (16, 16):
        raise ValueError(f"输入图像不是 16x16，而是 {image.size}")

    return image


def resize_nearest(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), resample=NEAREST)


def centered_pixel_art(source: Image.Image, canvas_size: int, content_size: int) -> Image.Image:
    enlarged = source.resize((content_size, content_size), resample=NEAREST)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    x = (canvas_size - content_size) // 2
    y = (canvas_size - content_size) // 2
    canvas.paste(enlarged, (x, y), enlarged)
    return canvas


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> None:
    if not INPUT_FILE.exists():
        raise FileNotFoundError(f"找不到输入文件：{INPUT_FILE.resolve()}")

    source = load_16px_icon(INPUT_FILE)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # 传统启动器图标：直接铺满
    for density, scale in DENSITIES.items():
        canvas_px = round(48 * scale)
        icon = resize_nearest(source, canvas_px)

        folder = OUTPUT_DIR / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        icon.save(folder / "ic_launcher.png", format="PNG")
        icon.save(folder / "ic_launcher_round.png", format="PNG")

    # Adaptive Icon 前景：108dp 画布，主体居中放在 64dp，避免过度裁切
    for density, scale in DENSITIES.items():
        canvas_px = round(108 * scale)
        content_px = round(64 * scale)

        foreground = centered_pixel_art(
            source,
            canvas_size=canvas_px,
            content_size=content_px,
        )

        folder = OUTPUT_DIR / f"drawable-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        foreground.save(folder / "ic_launcher_foreground.png", format="PNG")

    # Play 商店图标
    play_store_icon = resize_nearest(source, 512)
    play_store_icon.save(OUTPUT_DIR / "play_store_512.png", format="PNG")

    adaptive_xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""

    write_text(
        OUTPUT_DIR / "mipmap-anydpi-v26" / "ic_launcher.xml",
        adaptive_xml,
    )

    write_text(
        OUTPUT_DIR / "mipmap-anydpi-v26" / "ic_launcher_round.xml",
        adaptive_xml,
    )

    colors_xml = f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">{BACKGROUND_COLOR}</color>
</resources>
"""

    write_text(
        OUTPUT_DIR / "values" / "ic_launcher_colors.xml",
        colors_xml,
    )

    print(f"生成完成：{OUTPUT_DIR.resolve()}")


if __name__ == "__main__":
    main()