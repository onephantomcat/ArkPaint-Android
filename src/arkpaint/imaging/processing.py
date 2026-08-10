from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from pathlib import Path

import numpy as np
from PIL import Image, ImageEnhance, ImageOps

from arkpaint.imaging.palette import PALETTE_RGB

TARGET_SIZE = (24, 24)


class ResizeMode(str, Enum):
    CROP = "crop"
    STRETCH = "stretch"


class MappingMethod(str, Enum):
    CIEDE2000 = "ciede2000"
    OKLAB = "oklab"
    RGB = "rgb"
    WEIGHTED_RGB = "weighted_rgb"
    LAB = "lab"


@dataclass(frozen=True)
class ResamplingOption:
    key: str
    label: str
    value: Image.Resampling


RESAMPLING_OPTIONS = (
    ResamplingOption("nearest", "邻近 / NEAREST", Image.Resampling.NEAREST),
    ResamplingOption("box", "盒式平均 / BOX", Image.Resampling.BOX),
    ResamplingOption("bilinear", "两次线性 / BILINEAR", Image.Resampling.BILINEAR),
    ResamplingOption("hamming", "汉明 / HAMMING", Image.Resampling.HAMMING),
    ResamplingOption("bicubic", "两次立方 / BICUBIC", Image.Resampling.BICUBIC),
    ResamplingOption("lanczos", "Lanczos / LANCZOS", Image.Resampling.LANCZOS),
)
RESAMPLING_BY_KEY = {option.key: option for option in RESAMPLING_OPTIONS}


MAPPING_LABELS = {
    MappingMethod.CIEDE2000: "CIEDE2000 色差",
    MappingMethod.OKLAB: "OKLab 欧氏距离",
    MappingMethod.LAB: "CIE Lab 感知距离",
    MappingMethod.WEIGHTED_RGB: "加权 RGB 感知距离",
    MappingMethod.RGB: "RGB 欧氏距离",
}


@dataclass(frozen=True)
class ProcessResult:
    resized: Image.Image
    mapped: Image.Image
    palette_indices: np.ndarray


def load_source_image(path: Path) -> Image.Image:
    with Image.open(path) as source:
        image = ImageOps.exif_transpose(source)
        return image.convert("RGBA")


def initial_square_crop(image: Image.Image) -> tuple[int, int, int, int]:
    side = min(image.size)
    left = (image.width - side) // 2
    top = (image.height - side) // 2
    return left, top, left + side, top + side


def process_image(
    source: Image.Image,
    *,
    resize_mode: ResizeMode,
    crop_box: tuple[int, int, int, int] | None,
    resampling: str,
    mapping_method: MappingMethod,
    dither: bool,
    transparent_palette_index: int,
    merge_pixels: int = 1,
    brightness: int = 0,
    contrast: int = 0,
    saturation: int = 0,
    color_temperature: int = 0,
    hue: int = 0,
) -> ProcessResult:
    if not 0 <= transparent_palette_index < len(PALETTE_RGB):
        raise ValueError("Transparent palette index is out of range")
    if merge_pixels not in (1, 2, 3, 4):
        raise ValueError("Pixel merge must be one of 1, 2, 3, or 4")
    for value in (brightness, contrast, saturation, color_temperature, hue):
        if not -100 <= int(value) <= 100:
            raise ValueError("Color adjustments must be between -100 and 100")
    try:
        resampling_filter = RESAMPLING_BY_KEY[resampling].value
    except KeyError as exc:
        raise ValueError(f"Unknown resampling method: {resampling}") from exc

    rgba = adjust_image_colors(
        source,
        brightness=brightness,
        contrast=contrast,
        saturation=saturation,
        color_temperature=color_temperature,
        hue=hue,
    )
    if resize_mode is ResizeMode.CROP:
        box = crop_box or initial_square_crop(rgba)
        _validate_crop_box(box, rgba.size)
        rgba = rgba.crop(box)
    logical_size = (TARGET_SIZE[0] // merge_pixels, TARGET_SIZE[1] // merge_pixels)
    resized = rgba.resize(logical_size, resampling_filter)
    background = PALETTE_RGB[transparent_palette_index]
    rgb = _composite_alpha(np.asarray(resized, dtype=np.uint8), background)
    logical_indices = _map_pixels(rgb, mapping_method, dither)
    if merge_pixels > 1:
        indices = np.repeat(
            np.repeat(logical_indices, merge_pixels, axis=0), merge_pixels, axis=1
        )
    else:
        indices = logical_indices
    mapped_array = PALETTE_RGB[indices]
    mapped = Image.fromarray(mapped_array.astype(np.uint8), mode="RGB")
    return ProcessResult(resized, mapped, indices)


def adjust_image_colors(
    source: Image.Image,
    *,
    brightness: int,
    contrast: int,
    saturation: int,
    color_temperature: int,
    hue: int,
) -> Image.Image:
    """Apply the five lightweight adjustments while preserving transparency."""
    rgba = source.convert("RGBA")
    if not any((brightness, contrast, saturation, color_temperature, hue)):
        return rgba

    rgb_image = rgba.convert("RGB")
    if brightness:
        rgb_image = ImageEnhance.Brightness(rgb_image).enhance(
            max(0.0, 1.0 + brightness / 100.0)
        )
    if contrast:
        rgb_image = ImageEnhance.Contrast(rgb_image).enhance(
            max(0.0, 1.0 + contrast / 100.0)
        )
    if saturation:
        rgb_image = ImageEnhance.Color(rgb_image).enhance(
            max(0.0, 1.0 + saturation / 100.0)
        )

    rgb = np.asarray(rgb_image, dtype=np.float32)
    if color_temperature:
        temperature = float(color_temperature) / 100.0
        rgb[..., 0] *= 1.0 + 0.25 * temperature
        rgb[..., 1] *= 1.0 + 0.05 * abs(temperature)
        rgb[..., 2] *= 1.0 - 0.25 * temperature
    rgb = np.clip(np.rint(rgb), 0, 255).astype(np.uint8)

    if hue:
        hsv = np.asarray(
            Image.fromarray(rgb, mode="RGB").convert("HSV"), dtype=np.uint8
        ).copy()
        hue_delta = round(float(hue) * 256.0 / 200.0)
        shifted = (hsv[..., 0].astype(np.int16) + hue_delta) % 256
        hsv[..., 0] = shifted.astype(np.uint8)
        rgb = np.asarray(Image.fromarray(hsv, mode="HSV").convert("RGB"), dtype=np.uint8)

    result = np.asarray(rgba, dtype=np.uint8).copy()
    result[..., :3] = rgb
    return Image.fromarray(result, mode="RGBA")


def export_mapped_image(result: ProcessResult, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    result.mapped.save(path, format="PNG")


def _validate_crop_box(
    box: tuple[int, int, int, int], image_size: tuple[int, int]
) -> None:
    del image_size
    left, top, right, bottom = box
    width = right - left
    height = bottom - top
    if width <= 0 or height <= 0 or width != height:
        raise ValueError("Crop box must be a non-empty square")


def _composite_alpha(rgba: np.ndarray, background: np.ndarray) -> np.ndarray:
    rgb = rgba[..., :3].astype(np.float32)
    alpha = rgba[..., 3:4].astype(np.float32) / 255.0
    output = rgb * alpha + background.astype(np.float32) * (1.0 - alpha)
    return np.clip(np.rint(output), 0, 255).astype(np.uint8)


def _map_pixels(rgb: np.ndarray, method: MappingMethod, dither: bool) -> np.ndarray:
    if dither:
        return _floyd_steinberg(rgb, method)
    flat = rgb.reshape(-1, 3).astype(np.float64)
    indices = _nearest_indices(flat, method)
    return indices.reshape(rgb.shape[:2]).astype(np.uint8)


def _nearest_indices(pixels: np.ndarray, method: MappingMethod) -> np.ndarray:
    palette = PALETTE_RGB.astype(np.float64)
    if method is MappingMethod.RGB:
        differences = pixels[:, None, :] - palette[None, :, :]
        distances = np.sum(differences * differences, axis=2)
    elif method is MappingMethod.WEIGHTED_RGB:
        differences = pixels[:, None, :] - palette[None, :, :]
        red_mean = (pixels[:, None, 0] + palette[None, :, 0]) / 2.0
        distances = (
            (2.0 + red_mean / 256.0) * differences[..., 0] ** 2
            + 4.0 * differences[..., 1] ** 2
            + (2.0 + (255.0 - red_mean) / 256.0) * differences[..., 2] ** 2
        )
    elif method is MappingMethod.LAB:
        pixel_lab = _rgb_to_lab(pixels)
        palette_lab = _rgb_to_lab(palette)
        differences = pixel_lab[:, None, :] - palette_lab[None, :, :]
        distances = np.sum(differences * differences, axis=2)
    elif method is MappingMethod.CIEDE2000:
        pixel_lab = _rgb_to_lab(pixels)
        palette_lab = _rgb_to_lab(palette)
        distances = _delta_e_ciede2000(
            pixel_lab[:, None, :],
            palette_lab[None, :, :],
        )
    elif method is MappingMethod.OKLAB:
        pixel_oklab = _rgb_to_oklab(pixels)
        palette_oklab = _rgb_to_oklab(palette)
        differences = pixel_oklab[:, None, :] - palette_oklab[None, :, :]
        distances = np.sum(differences * differences, axis=2)
    else:
        raise ValueError(f"Unknown mapping method: {method}")
    return np.argmin(distances, axis=1)


def _rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    linear = _srgb_to_linear(rgb)
    matrix = np.asarray(
        (
            (0.4124564, 0.3575761, 0.1804375),
            (0.2126729, 0.7151522, 0.0721750),
            (0.0193339, 0.1191920, 0.9503041),
        )
    )
    xyz = linear @ matrix.T
    xyz /= np.asarray((0.95047, 1.0, 1.08883))
    delta = 6.0 / 29.0
    transformed = np.where(
        xyz > delta**3,
        np.cbrt(xyz),
        xyz / (3.0 * delta**2) + 4.0 / 29.0,
    )
    lightness = 116.0 * transformed[:, 1] - 16.0
    a_channel = 500.0 * (transformed[:, 0] - transformed[:, 1])
    b_channel = 200.0 * (transformed[:, 1] - transformed[:, 2])
    return np.column_stack((lightness, a_channel, b_channel))


def _rgb_to_oklab(rgb: np.ndarray) -> np.ndarray:
    linear = _srgb_to_linear(rgb)
    linear_lms = (
        linear
        @ np.asarray(
            (
                (0.4122214708, 0.5363325363, 0.0514459929),
                (0.2119034982, 0.6806995451, 0.1073969566),
                (0.0883024619, 0.2817188376, 0.6299787005),
            )
        ).T
    )
    lms = np.cbrt(linear_lms)
    return (
        lms
        @ np.asarray(
            (
                (0.2104542553, 0.7936177850, -0.0040720468),
                (1.9779984951, -2.4285922050, 0.4505937099),
                (0.0259040371, 0.7827717662, -0.8086757660),
            )
        ).T
    )


def _srgb_to_linear(rgb: np.ndarray) -> np.ndarray:
    values = rgb.astype(np.float64) / 255.0
    return np.where(
        values <= 0.04045,
        values / 12.92,
        ((values + 0.055) / 1.055) ** 2.4,
    )


def _delta_e_ciede2000(first: np.ndarray, second: np.ndarray) -> np.ndarray:
    lightness_1, a_1, b_1 = np.moveaxis(first, -1, 0)
    lightness_2, a_2, b_2 = np.moveaxis(second, -1, 0)

    chroma_1 = np.hypot(a_1, b_1)
    chroma_2 = np.hypot(a_2, b_2)
    mean_chroma = (chroma_1 + chroma_2) / 2.0
    mean_chroma_7 = mean_chroma**7
    compensation = 0.5 * (1.0 - np.sqrt(mean_chroma_7 / (mean_chroma_7 + 25.0**7)))
    adjusted_a_1 = (1.0 + compensation) * a_1
    adjusted_a_2 = (1.0 + compensation) * a_2
    adjusted_chroma_1 = np.hypot(adjusted_a_1, b_1)
    adjusted_chroma_2 = np.hypot(adjusted_a_2, b_2)
    hue_1 = np.degrees(np.arctan2(b_1, adjusted_a_1)) % 360.0
    hue_2 = np.degrees(np.arctan2(b_2, adjusted_a_2)) % 360.0

    delta_lightness = lightness_2 - lightness_1
    delta_chroma = adjusted_chroma_2 - adjusted_chroma_1
    hue_difference = hue_2 - hue_1
    hue_difference = np.where(
        hue_difference > 180.0, hue_difference - 360.0, hue_difference
    )
    hue_difference = np.where(
        hue_difference < -180.0, hue_difference + 360.0, hue_difference
    )
    hue_difference = np.where(
        adjusted_chroma_1 * adjusted_chroma_2 == 0.0,
        0.0,
        hue_difference,
    )
    delta_hue = (
        2.0
        * np.sqrt(adjusted_chroma_1 * adjusted_chroma_2)
        * np.sin(np.radians(hue_difference / 2.0))
    )

    mean_lightness = (lightness_1 + lightness_2) / 2.0
    mean_adjusted_chroma = (adjusted_chroma_1 + adjusted_chroma_2) / 2.0
    hue_sum = hue_1 + hue_2
    mean_hue = np.where(
        adjusted_chroma_1 * adjusted_chroma_2 == 0.0,
        hue_sum,
        np.where(
            np.abs(hue_1 - hue_2) <= 180.0,
            hue_sum / 2.0,
            np.where(hue_sum < 360.0, (hue_sum + 360.0) / 2.0, (hue_sum - 360.0) / 2.0),
        ),
    )

    hue_weight = (
        1.0
        - 0.17 * np.cos(np.radians(mean_hue - 30.0))
        + 0.24 * np.cos(np.radians(2.0 * mean_hue))
        + 0.32 * np.cos(np.radians(3.0 * mean_hue + 6.0))
        - 0.20 * np.cos(np.radians(4.0 * mean_hue - 63.0))
    )
    rotation_angle = 30.0 * np.exp(-(((mean_hue - 275.0) / 25.0) ** 2))
    chroma_rotation = 2.0 * np.sqrt(
        mean_adjusted_chroma**7 / (mean_adjusted_chroma**7 + 25.0**7)
    )
    lightness_scale = 1.0 + (
        0.015
        * (mean_lightness - 50.0) ** 2
        / np.sqrt(20.0 + (mean_lightness - 50.0) ** 2)
    )
    chroma_scale = 1.0 + 0.045 * mean_adjusted_chroma
    hue_scale = 1.0 + 0.015 * mean_adjusted_chroma * hue_weight
    rotation = -np.sin(np.radians(2.0 * rotation_angle)) * chroma_rotation

    lightness_term = delta_lightness / lightness_scale
    chroma_term = delta_chroma / chroma_scale
    hue_term = delta_hue / hue_scale
    squared_distance = (
        lightness_term**2
        + chroma_term**2
        + hue_term**2
        + rotation * chroma_term * hue_term
    )
    return np.sqrt(np.maximum(0.0, squared_distance))


def _floyd_steinberg(rgb: np.ndarray, method: MappingMethod) -> np.ndarray:
    work = rgb.astype(np.float64).copy()
    height, width = work.shape[:2]
    indices = np.empty((height, width), dtype=np.uint8)
    for row in range(height):
        for column in range(width):
            old = np.clip(work[row, column], 0.0, 255.0)
            index = int(_nearest_indices(old.reshape(1, 3), method)[0])
            indices[row, column] = index
            error = old - PALETTE_RGB[index].astype(np.float64)
            if column + 1 < width:
                work[row, column + 1] += error * 7.0 / 16.0
            if row + 1 < height:
                if column > 0:
                    work[row + 1, column - 1] += error * 3.0 / 16.0
                work[row + 1, column] += error * 5.0 / 16.0
                if column + 1 < width:
                    work[row + 1, column + 1] += error / 16.0
    return indices
