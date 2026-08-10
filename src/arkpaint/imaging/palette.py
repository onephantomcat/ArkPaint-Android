from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class PaletteColor:
    index: int
    hex_value: str
    rgb: tuple[int, int, int]

    @property
    def label(self) -> str:
        return f"{self.index + 1:02d}  {self.hex_value}"


_HEX_VALUES = (
    "#222222", "#B4B4B4", "#EAE7DF", "#FFFFFF",
    "#D32F36", "#9C0A00", "#D60C4A", "#E6968D",
    "#FE9875", "#F7D0C0", "#FCEFEA", "#FBF6E8",
    "#DCD2C8", "#E2CEAB", "#D56322", "#D48C42",
    "#F29900", "#F9C933", "#FCE499", "#B3B47A",
    "#C2DA72", "#6C6E00", "#B19155", "#A98F74",
    "#AA9228", "#3F2B12", "#74491F", "#534658",
    "#2A2446", "#394599", "#5A459D", "#BAA3D7",
    "#B6BCDF", "#A9ACBE", "#63ABB9", "#B4D2DC",
    "#91D8E6", "#47AEA0", "#B6D3C8", "#253660",
)


def _hex_to_rgb(value: str) -> tuple[int, int, int]:
    return tuple(int(value[index:index + 2], 16) for index in (1, 3, 5))  # type: ignore[return-value]


PALETTE = tuple(
    PaletteColor(index, value, _hex_to_rgb(value))
    for index, value in enumerate(_HEX_VALUES)
)
PALETTE_RGB = np.asarray([color.rgb for color in PALETTE], dtype=np.uint8)
