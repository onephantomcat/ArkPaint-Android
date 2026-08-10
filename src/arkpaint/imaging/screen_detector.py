from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from arkpaint.imaging.palette import PALETTE_RGB
from arkpaint.models import Rect, ScreenLayout


class ScreenDetectionError(RuntimeError):
    """Raised when an ArkPaint editing layout cannot be identified."""


@dataclass(frozen=True)
class _Swatch:
    palette_index: int
    x: int
    y: int
    width: int
    height: int


class ScreenDetector:
    def detect_png(self, png_data: bytes) -> ScreenLayout:
        encoded = np.frombuffer(png_data, dtype=np.uint8)
        image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
        if image is None:
            raise ScreenDetectionError("无法解析设备截图")
        return self.detect(image)

    def detect(self, bgr_image: np.ndarray) -> ScreenLayout:
        if bgr_image.ndim != 3 or bgr_image.shape[2] != 3:
            raise ScreenDetectionError("截图格式不是三通道彩色图像")
        height, width = bgr_image.shape[:2]
        if width <= height:
            raise ScreenDetectionError("当前不是横屏界面")

        canvas = self._detect_canvas(bgr_image)
        swatches = self._detect_swatches(bgr_image, canvas)
        columns = self._cluster_positions([item.x for item in swatches], width * 0.025)
        rows = self._cluster_positions([item.y for item in swatches], height * 0.035)
        if len(columns) != 4 or len(rows) < 5:
            raise ScreenDetectionError(
                f"未识别到完整调色板：检测到 {len(columns)} 列、{len(rows)} 行"
            )

        rows = rows[:6]
        start_row = self._match_palette_start(swatches, columns, rows)
        median_width = int(np.median([item.width for item in swatches]))
        median_height = int(np.median([item.height for item in swatches]))
        palette = Rect(
            max(0, columns[0] - median_width // 2),
            max(0, rows[0] - median_height // 2),
            min(width, columns[-1] + median_width // 2) - max(
                0, columns[0] - median_width // 2
            ),
            min(height, rows[-1] + median_height // 2) - max(
                0, rows[0] - median_height // 2
            ),
        )
        return ScreenLayout(
            screen_width=width,
            screen_height=height,
            canvas=canvas,
            palette=palette,
            palette_columns=tuple(columns),  # type: ignore[arg-type]
            palette_rows=tuple(rows),
            palette_start_row=start_row,
        )

    def _detect_canvas(self, image: np.ndarray) -> Rect:
        height, width = image.shape[:2]
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        edges = cv2.Canny(gray, 35, 100)
        contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        candidates: list[tuple[float, Rect]] = []
        for contour in contours:
            x, y, box_width, box_height = cv2.boundingRect(contour)
            if box_width < height * 0.55 or box_height < height * 0.55:
                continue
            if box_width > height * 0.92 or box_height > height * 0.92:
                continue
            if not 0.92 <= box_width / box_height <= 1.08:
                continue
            if x > width * 0.70 or y > height * 0.30:
                continue
            score = box_width * box_height - abs(box_width - box_height) * height
            candidates.append((score, Rect(x, y, box_width, box_height)))
        if not candidates:
            raise ScreenDetectionError("未识别画像册创作界面，请打开画像册并点击右下角新增")

        canvas = max(candidates, key=lambda item: item[0])[1]
        if not self._has_grid_structure(gray, canvas):
            raise ScreenDetectionError("请将左侧缩放拉到最低，以及打开绘图网格（右边小眼睛）")
        return canvas

    def _has_grid_structure(self, gray: np.ndarray, canvas: Rect) -> bool:
        inset = max(1, round(canvas.width * 0.004))
        crop = gray[
            canvas.y + inset:canvas.bottom - inset,
            canvas.x + inset:canvas.right - inset,
        ]
        if crop.size == 0:
            return False
        gradient_x = np.abs(np.diff(crop.astype(np.int16), axis=1)).mean(axis=0)
        gradient_y = np.abs(np.diff(crop.astype(np.int16), axis=0)).mean(axis=1)
        expected_spacing = canvas.width / 24.0
        x_hits = self._periodic_hits(gradient_x, expected_spacing)
        y_hits = self._periodic_hits(gradient_y, expected_spacing)
        # Fully white-painted cells reduce the contrast of several grid
        # lines. The square contour and palette checks remain mandatory, so
        # 15 periodic hits are sufficient without weakening page validation.
        return x_hits >= 15 and y_hits >= 15

    @staticmethod
    def _periodic_hits(values: np.ndarray, spacing: float) -> int:
        baseline = float(np.median(values))
        spread = float(np.std(values))
        threshold = baseline + max(1.2, spread * 0.35)
        best_hits = 0
        phase_step = max(1.0, spacing * 0.04)
        phases = np.arange(-spacing * 0.5, spacing * 0.5 + phase_step, phase_step)
        radius = max(1, round(spacing * 0.14))
        for phase in phases:
            hits = 0
            for index in range(1, 24):
                center = round(index * spacing + phase)
                start = max(0, center - radius)
                end = min(len(values), center + radius + 1)
                if end > start and float(values[start:end].max()) >= threshold:
                    hits += 1
            best_hits = max(best_hits, hits)
        return best_hits

    def _detect_swatches(self, image: np.ndarray, canvas: Rect) -> list[_Swatch]:
        height, width = image.shape[:2]
        # Exclude the dark tool chrome between the canvas and the palette.
        roi_x = min(width - 1, canvas.right + max(12, round(width * 0.065)))
        roi_y = round(height * 0.25)
        roi = cv2.cvtColor(image[roi_y:height, roi_x:width], cv2.COLOR_BGR2RGB)
        minimum_area = max(120, round((height / 900.0) ** 2 * 450))
        swatches: list[_Swatch] = []

        pixels = roi.astype(np.float32)
        for palette_index, color in enumerate(PALETTE_RGB.astype(np.int16)):
            distance_squared = np.sum((pixels - color.astype(np.float32)) ** 2, axis=2)
            mask = (distance_squared <= 22**2).astype(np.uint8)
            count, _, stats, centroids = cv2.connectedComponentsWithStats(mask, 8)
            best: tuple[int, int] | None = None
            for component in range(1, count):
                area = int(stats[component, cv2.CC_STAT_AREA])
                box_width = int(stats[component, cv2.CC_STAT_WIDTH])
                box_height = int(stats[component, cv2.CC_STAT_HEIGHT])
                if (
                    area < minimum_area
                    or box_width < max(12, canvas.width * 0.06)
                    or box_height < max(12, canvas.height * 0.06)
                ):
                    continue
                # Color masks can also match dark panel chrome or the thin
                # palette scrollbar. Real swatches are compact near-squares.
                if box_width > canvas.width * 0.16 or box_height > canvas.height * 0.16:
                    continue
                if not 0.70 <= box_width / box_height <= 1.30:
                    continue
                if best is None or area > best[1]:
                    best = component, area
            if best is None:
                continue
            component = best[0]
            x = round(float(centroids[component, 0])) + roi_x
            y = round(float(centroids[component, 1])) + roi_y
            swatches.append(
                _Swatch(
                    palette_index,
                    x,
                    y,
                    int(stats[component, cv2.CC_STAT_WIDTH]),
                    int(stats[component, cv2.CC_STAT_HEIGHT]),
                )
            )

        if len(swatches) < 12:
            raise ScreenDetectionError(
                f"右侧区域仅识别到 {len(swatches)} 个色块，当前可能不是颜料界面"
            )
        return swatches

    @staticmethod
    def _cluster_positions(values: list[int], tolerance: float) -> list[int]:
        if not values:
            return []
        groups: list[list[int]] = []
        for value in sorted(values):
            if not groups or value - np.mean(groups[-1]) > tolerance:
                groups.append([value])
            else:
                groups[-1].append(value)
        return [round(float(np.median(group))) for group in groups]

    @staticmethod
    def _match_palette_start(
        swatches: list[_Swatch], columns: list[int], rows: list[int]
    ) -> int:
        x_tolerance = max(6, round(np.median(np.diff(columns)) * 0.32))
        y_tolerance = max(6, round(np.median(np.diff(rows)) * 0.32))
        best_start = 0
        best_score = -1
        for start in range(0, 10 - len(rows) + 1):
            score = 0
            for swatch in swatches:
                column = min(range(4), key=lambda i: abs(columns[i] - swatch.x))
                row = min(range(len(rows)), key=lambda i: abs(rows[i] - swatch.y))
                expected = (start + row) * 4 + column
                if (
                    abs(columns[column] - swatch.x) <= x_tolerance
                    and abs(rows[row] - swatch.y) <= y_tolerance
                    and swatch.palette_index == expected
                ):
                    score += 1
            if score > best_score:
                best_start = start
                best_score = score
        if best_score < 8:
            raise ScreenDetectionError("调色板颜色顺序与 ArkPaint 40 色板不匹配")
        return best_start
