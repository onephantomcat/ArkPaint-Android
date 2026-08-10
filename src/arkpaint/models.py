from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Rect:
    x: int
    y: int
    width: int
    height: int

    @property
    def right(self) -> int:
        return self.x + self.width

    @property
    def bottom(self) -> int:
        return self.y + self.height

    @property
    def center(self) -> tuple[int, int]:
        return self.x + self.width // 2, self.y + self.height // 2


@dataclass(frozen=True)
class ScreenLayout:
    screen_width: int
    screen_height: int
    canvas: Rect
    palette: Rect
    palette_columns: tuple[int, int, int, int]
    palette_rows: tuple[int, ...]
    palette_start_row: int

    def canvas_cell_center(self, column: int, row: int) -> tuple[int, int]:
        if not 0 <= column < 24 or not 0 <= row < 24:
            raise ValueError("Canvas coordinates must be in the range 0..23")
        x = round(self.canvas.x + (column + 0.5) * self.canvas.width / 24)
        y = round(self.canvas.y + (row + 0.5) * self.canvas.height / 24)
        return x, y

    def palette_center(self, palette_index: int) -> tuple[int, int]:
        row = palette_index // 4 - self.palette_start_row
        column = palette_index % 4
        if not 0 <= row < len(self.palette_rows):
            raise ValueError(f"Palette color {palette_index} is not visible")
        return self.palette_columns[column], self.palette_rows[row]
