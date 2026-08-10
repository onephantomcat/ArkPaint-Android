from __future__ import annotations

import io
from datetime import datetime
from pathlib import Path

from PIL import Image
from PySide6.QtCore import QPoint, Qt, QTimer
from PySide6.QtGui import QResizeEvent
from PySide6.QtWidgets import (
    QAbstractSpinBox,
    QDialog,
    QFileDialog,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSlider,
    QSpinBox,
    QSplitter,
    QToolButton,
    QToolTip,
    QVBoxLayout,
    QWidget,
)

from arkpaint.adb import is_adb_serial_target
from arkpaint.assets import icon as asset_icon
from arkpaint.config import (
    AppSettings,
    ImageSettings,
    default_config_path,
    load_settings,
    save_settings,
)
from arkpaint.drawing import Painter
from arkpaint.gui.controls import PaletteSelector, SegmentedControl, SwitchButton
from arkpaint.gui.image_widgets import CropPreview, ImagePreview
from arkpaint.gui.screen_capture import ScreenCaptureOverlay, capture_virtual_desktop
from arkpaint.gui.workers import ConnectThread, DrawThread
from arkpaint.imaging.palette import PALETTE
from arkpaint.imaging.processing import (
    MAPPING_LABELS,
    RESAMPLING_OPTIONS,
    MappingMethod,
    ProcessResult,
    ResizeMode,
    adjust_image_colors,
    initial_square_crop,
    load_source_image,
    process_image,
)
from arkpaint.models import ScreenLayout

CONNECTION_NOTICE_TEXT = (
    "大多数模拟器（一个电脑只开一个）用地址127.0.0.1端口5555即可，目前测试过原生Mumu模拟器和雷电14，似乎不支持雷电9\n"
    "MuMu模拟器查看端口：右上角菜单栏->设备设置->问题诊断->倒数第二大块网络信息->ADB端口(一般会有两个，任选其一)\n"
    "雷电模拟器查看端口：右上角菜单栏->诊断信息->倒数第二行找到ADB调试端口（一般是emulator-xxxx）\n"
    "如果不是纯数字端口，比如“emulator-5554”填到地址栏，端口栏不管\n"
    "雷电比较特殊，一般127.0.0.1和5555就能连上，如果不行，填emulator-xxxx和端口不填来尝试连接\n"
    "如果遇到问题可以私信UP，发模拟器版本，诊断截图，本软件截图，我会尝试解决。"
)


class MainWindow(QMainWindow):
    def __init__(self, settings: AppSettings | None = None) -> None:
        super().__init__()
        self.settings = settings or load_settings()
        self._source_image: Image.Image | None = None
        self._processed: ProcessResult | None = None
        self._screen_layout: ScreenLayout | None = None
        self._connect_thread: ConnectThread | None = None
        self._draw_thread: DrawThread | None = None
        self._screen_capture_overlay: ScreenCaptureOverlay | None = None
        self._current_palette_index: int | None = None
        self._active_draw_total = 0
        self._build_ui()
        self._load_settings_into_ui()

    def _build_ui(self) -> None:
        self.setWindowTitle("ArkPaint")
        self.setMinimumSize(1200, 760)

        root = QWidget()
        root_layout = QVBoxLayout(root)
        root_layout.setContentsMargins(10, 8, 10, 8)
        root_layout.setSpacing(6)

        root_layout.addWidget(self._build_connection_group())
        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.setChildrenCollapsible(False)
        splitter.setHandleWidth(18)
        splitter.addWidget(self._build_image_group())
        splitter.addWidget(self._build_preview_group())
        splitter.setStretchFactor(0, 5)
        splitter.setStretchFactor(1, 3)
        splitter.setSizes([930, 550])
        root_layout.addWidget(splitter, 1)
        root_layout.addWidget(self._build_draw_group())
        self.setCentralWidget(root)
        self.watermark_label = QLabel("Bilibili@吃着火锅唱着歌r", root)
        self.watermark_label.setObjectName("watermarkLabel")
        self.watermark_label.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.watermark_label.adjustSize()
        self._position_watermark()
        self._apply_theme(False)

    def _position_watermark(self) -> None:
        """Keep the watermark in the upper-right corner of the content area."""
        if not hasattr(self, "watermark_label"):
            return
        root = self.centralWidget()
        if root is None:
            return
        right_margin = 14
        top_margin = 2
        x = max(0, root.width() - self.watermark_label.width() - right_margin)
        self.watermark_label.move(x, top_margin)
        self.watermark_label.raise_()

    def resizeEvent(self, event: QResizeEvent) -> None:
        super().resizeEvent(event)
        self._position_watermark()

    def _build_connection_group(self) -> QGroupBox:
        group = QGroupBox("连接模拟器")
        layout = QHBoxLayout(group)
        layout.setContentsMargins(8, 4, 8, 4)
        layout.setSpacing(8)

        connection_layout = QVBoxLayout()
        connection_layout.setContentsMargins(0, 0, 0, 0)
        connection_layout.setSpacing(2)
        controls_row = QHBoxLayout()
        controls_row.setContentsMargins(0, 0, 0, 0)
        controls_row.setSpacing(6)
        controls_row.addWidget(
            _label_with_help(
                "ADB 地址",
                "填写模拟器的 ADB TCP 地址；也可填写 emulator-5554，程序会通过本机 ADB Server 选择该设备。",
            )
        )
        self.host_edit = QLineEdit()
        self.host_edit.setMinimumWidth(150)
        self.host_edit.setPlaceholderText("127.0.0.1 或 emulator-5554")
        self.host_edit.textChanged.connect(self._adb_target_changed)
        controls_row.addWidget(self.host_edit)
        self.port_label = QLabel("端口")
        controls_row.addWidget(self.port_label)
        self.port_spin = QSpinBox()
        self.port_spin.setRange(1, 65535)
        self.port_spin.setMaximumWidth(100)
        self.port_spin.setButtonSymbols(QAbstractSpinBox.ButtonSymbols.NoButtons)
        controls_row.addWidget(self.port_spin)
        self.connect_button = QPushButton("连接并验证")
        self.connect_button.setIcon(asset_icon("connect"))
        self.connect_button.clicked.connect(self._connect_and_verify)
        controls_row.addWidget(self.connect_button)
        self.connection_status = QLabel("未连接")
        self.connection_status.setObjectName("statusBad")
        controls_row.addWidget(self.connection_status, 1)
        connection_layout.addLayout(controls_row)

        self.connection_notice_label = QLabel(CONNECTION_NOTICE_TEXT, group)
        self.connection_notice_label.setObjectName("connectionNoticeLabel")
        self.connection_notice_label.setMinimumHeight(72)
        self.connection_notice_label.setWordWrap(True)
        self.connection_notice_label.setAlignment(
            Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignTop
        )
        self.connection_notice_label.setTextInteractionFlags(
            Qt.TextInteractionFlag.TextSelectableByMouse
        )
        connection_layout.addWidget(self.connection_notice_label)
        layout.addLayout(connection_layout, 1)

        options_layout = QVBoxLayout()
        options_layout.setContentsMargins(0, 0, 0, 0)
        options_layout.setSpacing(1)
        theme_row = QHBoxLayout()
        theme_row.setContentsMargins(0, 0, 0, 0)
        theme_row.setSpacing(4)
        theme_row.addWidget(QLabel("夜间模式"))
        self.theme_toggle = SwitchButton()
        self.theme_toggle.toggled.connect(self._theme_changed)
        theme_row.addWidget(self.theme_toggle)
        options_layout.addLayout(theme_row)
        log_row = QHBoxLayout()
        log_row.setContentsMargins(0, 0, 0, 0)
        log_row.setSpacing(4)
        log_row.addWidget(QLabel("生成日志"))
        self.log_toggle = SwitchButton()
        self.log_toggle.setAccessibleName("生成日志")
        self.log_toggle.setToolTip("在应用程序目录生成操作日志")
        self.log_toggle.toggled.connect(self._logging_changed)
        log_row.addWidget(self.log_toggle)
        options_layout.addLayout(log_row)
        layout.addLayout(options_layout)
        self.device_preview = ImagePreview(background="#20252b")
        self.device_preview.setFixedSize(170, 92)
        layout.addWidget(self.device_preview, 0, Qt.AlignmentFlag.AlignTop)
        return group

    def _build_image_group(self) -> QGroupBox:
        group = QGroupBox("图片处理")
        layout = QVBoxLayout(group)
        layout.setContentsMargins(8, 5, 8, 7)
        layout.setSpacing(5)
        top_row = QHBoxLayout()
        self.open_button = QPushButton("导入图片")
        self.open_button.setIcon(asset_icon("folder"))
        self.open_button.clicked.connect(self._open_image)
        top_row.addWidget(self.open_button)
        self.screen_capture_button = QPushButton("截图")
        self.screen_capture_button.setIcon(asset_icon("crop"))
        self.screen_capture_button.setToolTip("截取屏幕区域并作为图片导入")
        self.screen_capture_button.clicked.connect(self._start_screen_capture)
        top_row.addWidget(self.screen_capture_button)
        top_row.addWidget(
            _help_button(
                "支持 PNG、JPG、JPEG、BMP、GIF、TIF、TIFF 和 WEBP 图片格式。"
            )
        )
        self.image_path_label = QLabel("尚未选择图片")
        self.image_path_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        top_row.addWidget(self.image_path_label, 1)
        top_row.addWidget(QLabel("合并像素"))
        self.merge_combo = SegmentedControl()
        for size in (1, 2, 3, 4):
            self.merge_combo.addItem(f"{size}×{size}", size)
        self.merge_combo.currentIndexChanged.connect(self._merge_pixels_changed)
        top_row.addWidget(self.merge_combo)
        top_row.addWidget(QLabel("裁剪缩放"))
        self.crop_zoom_out_button = QToolButton()
        self.crop_zoom_out_button.setObjectName("zoomButton")
        self.crop_zoom_out_button.setText("-")
        self.crop_zoom_out_button.setToolTip("缩小裁剪视图")
        top_row.addWidget(self.crop_zoom_out_button)
        self.crop_zoom_slider = QSlider(Qt.Orientation.Horizontal)
        self.crop_zoom_slider.setRange(25, 400)
        self.crop_zoom_slider.setValue(100)
        self.crop_zoom_slider.setSingleStep(5)
        self.crop_zoom_slider.setPageStep(25)
        self.crop_zoom_slider.setFixedWidth(100)
        self.crop_zoom_slider.setToolTip("裁剪视图缩放比例")
        top_row.addWidget(self.crop_zoom_slider)
        self.crop_zoom_in_button = QToolButton()
        self.crop_zoom_in_button.setObjectName("zoomButton")
        self.crop_zoom_in_button.setText("+")
        self.crop_zoom_in_button.setToolTip("放大裁剪视图")
        top_row.addWidget(self.crop_zoom_in_button)
        self.crop_zoom_value_label = QLabel("100%")
        self.crop_zoom_value_label.setMinimumWidth(38)
        top_row.addWidget(self.crop_zoom_value_label)
        self.crop_zoom_out_button.clicked.connect(
            lambda: self.crop_zoom_slider.setValue(
                self.crop_zoom_slider.value() - 25
            )
        )
        self.crop_zoom_in_button.clicked.connect(
            lambda: self.crop_zoom_slider.setValue(
                self.crop_zoom_slider.value() + 25
            )
        )
        self.crop_zoom_slider.valueChanged.connect(self._crop_zoom_changed)
        layout.addLayout(top_row)

        self.crop_preview = CropPreview()
        self.crop_preview.setMinimumHeight(250)
        self.crop_preview.cropChanged.connect(self._reprocess)
        layout.addWidget(self.crop_preview, 1)

        controls = QGridLayout()
        controls.setHorizontalSpacing(7)
        controls.setVerticalSpacing(4)
        controls.setColumnStretch(1, 1)
        controls.addWidget(
            _label_with_help(
                "尺寸处理",
                "正方形裁切会保留中心方形区域并允许拖动裁切框；整图拉伸会使用完整图片。",
                width=92,
            ),
            0,
            0,
        )
        self.resize_mode_combo = SegmentedControl()
        self.resize_mode_combo.addItem("正方形裁切", ResizeMode.CROP.value)
        self.resize_mode_combo.addItem("整图拉伸", ResizeMode.STRETCH.value)
        self.resize_mode_combo.currentIndexChanged.connect(self._resize_mode_changed)
        controls.addWidget(self.resize_mode_combo, 0, 1)
        controls.addWidget(
            _label_with_help(
                "重采样",
                "将图片缩放到逻辑像素尺寸时使用的插值算法。邻近适合像素画，其他算法更平滑。",
                width=92,
            ),
            1,
            0,
        )
        self.resampling_combo = SegmentedControl()
        for option in RESAMPLING_OPTIONS:
            self.resampling_combo.addItem(_short_resampling_label(option.key), option.key)
        self.resampling_combo.currentIndexChanged.connect(self._reprocess)
        controls.addWidget(self.resampling_combo, 1, 1)
        controls.addWidget(
            _label_with_help(
                "颜色映射",
                "选择将图片颜色匹配到 ArkPaint 40 色调色板的感知距离算法。",
                width=92,
            ),
            2,
            0,
        )
        self.mapping_combo = SegmentedControl()
        self.mapping_combo.addItem("CIEDE2000 色差", MappingMethod.CIEDE2000.value)
        self.mapping_combo.addItem("OKLab 欧氏距离", MappingMethod.OKLAB.value)
        self.mapping_combo.addItem("CIE Lab 感知距离", MappingMethod.LAB.value)
        self.mapping_combo.addItem(
            "加权 RGB 感知距离", MappingMethod.WEIGHTED_RGB.value
        )
        self.mapping_combo.addItem("RGB 欧氏距离", MappingMethod.RGB.value)
        self.mapping_combo.currentIndexChanged.connect(self._reprocess)
        controls.addWidget(self.mapping_combo, 2, 1)
        controls.addWidget(
            _label_with_help(
                "透明色替换",
                "透明像素会先替换为指定的调色板颜色，再参与颜色映射。",
                width=92,
            ),
            3,
            0,
        )
        self.transparent_combo = PaletteSelector()
        for color in PALETTE:
            self.transparent_combo.addColor(color.hex_value, color.label, color.index)
        self.transparent_combo.currentIndexChanged.connect(self._reprocess)
        controls.addWidget(self.transparent_combo, 3, 1)
        layout.addLayout(controls)
        adjustment_row = QHBoxLayout()
        adjustment_row.setSpacing(5)
        adjustment_row.addWidget(
            _label_with_help(
                "简易调色",
                "在颜色映射前调整亮度、对比度、饱和度、色温和色调，数值范围为 -100 到 100。",
                width=92,
            )
        )
        self._adjustment_sliders: dict[str, QSlider] = {}
        self._adjustment_value_labels: dict[str, QLabel] = {}
        adjustment_specs = (
            ("brightness", "亮度", "调整整体明暗。"),
            ("contrast", "对比度", "调整明暗差异。"),
            ("saturation", "饱和度", "调整颜色鲜艳程度。"),
            ("color_temperature", "色温", "向右变暖，向左变冷。"),
            ("hue", "色调", "沿色相环旋转颜色。"),
        )
        for index, (key, label, tooltip) in enumerate(adjustment_specs):
            if index:
                separator = QFrame()
                separator.setObjectName("adjustmentSeparator")
                separator.setFrameShape(QFrame.Shape.VLine)
                separator.setFixedHeight(22)
                adjustment_row.addWidget(separator)
            adjustment_row.addWidget(QLabel(label))
            slider = QSlider(Qt.Orientation.Horizontal)
            slider.setRange(-100, 100)
            slider.setValue(0)
            slider.setFixedWidth(104)
            slider.setToolTip(tooltip)
            value_label = QLabel("0")
            value_label.setMinimumWidth(30)
            value_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
            slider.valueChanged.connect(
                lambda value, target=value_label: target.setText(str(value))
            )
            slider.valueChanged.connect(self._reprocess)
            self._adjustment_sliders[key] = slider
            self._adjustment_value_labels[key] = value_label
            setattr(self, f"{key}_slider", slider)
            adjustment_row.addWidget(slider)
            adjustment_row.addWidget(value_label)
        adjustment_row.addStretch(1)
        layout.insertLayout(2, adjustment_row)
        self.processing_controls = [
            self.resize_mode_combo,
            self.resampling_combo,
            self.mapping_combo,
            self.transparent_combo,
            self.merge_combo,
            *self._adjustment_sliders.values(),
        ]
        return group

    def _build_preview_group(self) -> QGroupBox:
        group = QGroupBox("预览")
        layout = QVBoxLayout(group)
        layout.setContentsMargins(8, 5, 8, 7)
        layout.setSpacing(6)
        self.mapped_preview = ImagePreview(pixelated=True, show_grid=True, background="#f7f9fb")
        self.mapped_preview.setFixedSize(400, 400)
        layout.addWidget(
            self.mapped_preview,
            1,
            Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignVCenter,
        )
        thumbnail_row = QHBoxLayout()
        self.mapped_thumbnail = ImagePreview(pixelated=True, background="#f7f9fb")
        self.mapped_thumbnail.setFixedSize(100, 100)
        thumbnail_row.addWidget(self.mapped_thumbnail)
        self.image_status = QLabel("请选择图片")
        self.image_status.setWordWrap(True)
        thumbnail_row.addWidget(self.image_status, 1)
        self.export_button = QPushButton("导出映射图")
        self.export_button.setIcon(asset_icon("download"))
        self.export_button.clicked.connect(self._export_mapped)
        self.export_button.setEnabled(False)
        thumbnail_row.addWidget(self.export_button)
        layout.addLayout(thumbnail_row)
        return group

    def _build_draw_group(self) -> QGroupBox:
        group = QGroupBox("绘制")
        layout = QHBoxLayout(group)
        layout.setContentsMargins(8, 4, 8, 4)
        layout.setSpacing(6)
        layout.addWidget(QLabel("当前绘制："))
        self.current_color_swatch = QLabel()
        self.current_color_swatch.setObjectName("currentColorSwatch")
        self.current_color_swatch.setFixedSize(26, 26)
        layout.addWidget(self.current_color_swatch)
        layout.addWidget(QLabel("进度"))
        self.color_progress_bar = QProgressBar()
        self.color_progress_bar.setRange(0, 1)
        self.color_progress_bar.setValue(0)
        layout.addWidget(self.color_progress_bar, 1)
        layout.addWidget(QLabel("总进度"))
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 576)
        self.progress_bar.setValue(0)
        layout.addWidget(self.progress_bar, 2)
        layout.addWidget(
            _label_with_help(
                "绘制延迟",
                "每次点击画布像素后的等待时间。数值越小绘制越快，但模拟器过慢时可能漏点。",
            )
        )
        self.tap_delay_spin = QSpinBox()
        self.tap_delay_spin.setRange(10, 500)
        self.tap_delay_spin.setSuffix(" ms")
        self.tap_delay_spin.setMaximumWidth(90)
        layout.addWidget(self.tap_delay_spin)
        self.start_button = QPushButton("开始绘制")
        self.start_button.setObjectName("primaryButton")
        self.start_button.setIcon(asset_icon("play"))
        self.start_button.clicked.connect(self._start_drawing)
        self.start_button.setEnabled(False)
        layout.addWidget(self.start_button)
        self.retry_button = QPushButton("重新绘制")
        self.retry_button.setIcon(asset_icon("refresh"))
        self.retry_button.clicked.connect(self._start_drawing)
        self.retry_button.setEnabled(False)
        layout.addWidget(self.retry_button)
        self.cancel_button = QPushButton("取消")
        self.cancel_button.setObjectName("dangerButton")
        self.cancel_button.setIcon(asset_icon("stop"))
        self.cancel_button.clicked.connect(self._cancel_drawing)
        self.cancel_button.setEnabled(False)
        layout.addWidget(self.cancel_button)
        return group

    def _load_settings_into_ui(self) -> None:
        self.host_edit.setText(self.settings.adb.host)
        self.port_spin.setValue(self.settings.adb.port)
        self._adb_target_changed(self.settings.adb.host)
        self.tap_delay_spin.setValue(self.settings.drawing.tap_delay_ms)
        _set_combo_data(self.resize_mode_combo, self.settings.image.resize_mode)
        _set_combo_data(self.resampling_combo, self.settings.image.resampling)
        _set_combo_data(self.mapping_combo, self.settings.image.mapping_method)
        _set_combo_data(self.transparent_combo, self.settings.image.transparent_palette_index)
        _set_combo_data(self.merge_combo, self.settings.image.merge_pixels)
        for key, slider in self._adjustment_sliders.items():
            slider.blockSignals(True)
            slider.setValue(int(getattr(self.settings.image, key)))
            slider.blockSignals(False)
            self._adjustment_value_labels[key].setText(str(slider.value()))
        self.resize_mode_combo.blockSignals(True)
        self._resize_mode_changed()
        self.resize_mode_combo.blockSignals(False)
        self.theme_toggle.blockSignals(True)
        self.theme_toggle.setChecked(self.settings.window.dark_mode)
        self.theme_toggle.blockSignals(False)
        self.log_toggle.blockSignals(True)
        self.log_toggle.setChecked(self.settings.window.generate_log)
        self.log_toggle.blockSignals(False)
        self._apply_theme(self.settings.window.dark_mode)

    def _theme_changed(self, dark: bool) -> None:
        self.settings.window.dark_mode = dark
        self._apply_theme(dark)
        self._log_operation(f"切换夜间模式：{'开启' if dark else '关闭'}")

    def _logging_changed(self, enabled: bool) -> None:
        self.settings.window.generate_log = enabled
        if enabled:
            self._log_operation("开启操作日志")

    def _log_operation(self, message: str) -> None:
        """Append a timestamped operation to the date-specific application log."""
        if not self.log_toggle.isChecked():
            return
        now = datetime.now()
        log_path = default_config_path().parent / f"ArkPaint{now:%Y-%m-%d}.log"
        safe_message = message.replace("\r", " ").replace("\n", " ")
        try:
            with log_path.open("a", encoding="utf-8") as stream:
                stream.write(f"[{now:%Y-%m-%d %H:%M:%S}] {safe_message}\n")
        except OSError:
            # Logging must never prevent the requested UI operation from running.
            pass

    def _apply_theme(self, dark: bool) -> None:
        colors = (
            {
                "window": "#14171a",
                "surface": "#1d2125",
                "surface_alt": "#252a2f",
                "control": "#2b3136",
                "hover": "#353c42",
                "selected": "#4b545c",
                "text": "#e8ecef",
                "muted": "#a7b0b7",
                "disabled": "#68727a",
                "track": "#30363b",
                "preview": "#181c20",
                "border": "#333a40",
                "help": "#343b41",
            }
            if dark
            else {
                "window": "#eef1f4",
                "surface": "#ffffff",
                "surface_alt": "#f4f6f8",
                "control": "#edf0f2",
                "hover": "#e2e7ea",
                "selected": "#cfd4d8",
                "text": "#20252b",
                "muted": "#66717a",
                "disabled": "#9aa4ab",
                "track": "#e5e9ec",
                "preview": "#f3f5f6",
                "border": "#d9dfe3",
                "help": "#f6f8f9",
            }
        )
        self.setStyleSheet(
            f"""
            QMainWindow, QDialog {{ background: {colors['window']}; }}
            QWidget {{ color: {colors['text']}; font-family: "Segoe UI"; font-size: 9pt; }}
            QGroupBox {{ background: {colors['surface']};
                         border: 1px solid {colors['border']}; border-radius: 6px;
                         margin-top: 20px; padding: 8px; }}
            QGroupBox::title {{ subcontrol-origin: margin;
                                subcontrol-position: top left; left: 10px;
                                padding: 0; background: transparent;
                                color: {colors['text']}; font-size: 11pt;
                                font-weight: bold; }}
            QLineEdit, QSpinBox, QComboBox {{ background: {colors['control']}; border: 0;
                                             border-radius: 5px; padding: 2px 7px;
                                             min-height: 20px; }}
            QLineEdit:focus, QSpinBox:focus, QComboBox:focus {{ background: {colors['hover']}; }}
            QComboBox::drop-down {{ width: 25px; border: 0; }}
            QComboBox QAbstractItemView {{ background: {colors['surface']};
                                           color: {colors['text']}; border: 0;
                                           selection-background-color: {colors['selected']}; }}
            QPushButton {{ background: {colors['control']}; border: 0; border-radius: 5px;
                           padding: 3px 10px; min-height: 22px; }}
            QPushButton:hover {{ background: {colors['hover']}; }}
            QPushButton:pressed {{ background: {colors['selected']}; }}
            QPushButton:disabled {{ color: {colors['disabled']}; background: {colors['surface_alt']}; }}
            QWidget#segmentedControl {{ background: {colors['control']}; border: 0;
                                        border-radius: 6px; }}
            QPushButton[segmentButton="true"] {{ background: transparent; border: 0;
                                                   border-radius: 4px; padding: 3px 8px;
                                                   min-height: 22px; }}
            QPushButton[segmentButton="true"]:hover {{ background: {colors['hover']}; }}
            QPushButton[segmentButton="true"]:checked {{ background: {colors['selected']};
                                                          font-weight: 600; }}
            QPushButton#primaryButton {{ background: #1599a7; color: white; font-weight: 600; }}
            QPushButton#primaryButton:hover {{ background: #128a96; }}
            QPushButton#successButton {{ background: #20945a; color: white; font-weight: 600; }}
            QPushButton#successButton:hover {{ background: #197f4b; }}
            QPushButton#dangerButton {{ background: #c74754; color: white; }}
            QPushButton#dangerButton:hover {{ background: #b43d49; }}
            QToolButton#helpButton {{ background: {colors['help']}; border: 0;
                                      border-radius: 8px; font-size: 8pt;
                                      font-weight: 700; color: {colors['muted']};
                                      min-width: 16px; max-width: 16px;
                                      min-height: 16px; max-height: 16px; }}
            QToolButton#helpButton:hover {{ background: {colors['hover']}; }}
            QToolButton#zoomButton {{ background: {colors['control']}; border: 0;
                                      border-radius: 4px; font-size: 10pt;
                                      font-weight: 600; min-width: 22px; max-width: 22px;
                                      min-height: 22px; max-height: 22px; }}
            QToolButton#zoomButton:hover {{ background: {colors['hover']}; }}
            QFrame#adjustmentSeparator {{ border: 0;
                                          border-left: 1px solid {colors['border']};
                                          min-width: 1px; max-width: 1px; }}
            QSlider::groove:horizontal {{ height: 4px; border-radius: 2px;
                                          background: {colors['track']}; }}
            QSlider::handle:horizontal {{ background: #1599a7; border: 0;
                                          width: 12px; margin: -4px 0;
                                          border-radius: 6px; }}
            QLabel#statusGood {{ color: #15915f; font-weight: 600; }}
            QLabel#statusBad {{ color: #c74754; font-weight: 600; }}
            QLabel#watermarkLabel {{ color: {colors['muted']}; background: transparent;
                                     font-size: 8pt; font-weight: 600; padding: 0 2px; }}
            QLabel#connectionNoticeLabel {{ color: {colors['muted']};
                                             background: transparent;
                                             font-size: 9.5pt;
                                             padding: 2px 12px 4px 12px; }}
            QProgressBar {{ min-height: 22px; border: 0; border-radius: 5px;
                            background: {colors['track']}; text-align: center; }}
            QProgressBar::chunk {{ background: #1599a7; border-radius: 5px; }}
            QSplitter::handle {{ background: {colors['window']}; width: 18px; }}
            """
        )
        self.watermark_label.adjustSize()
        self._position_watermark()
        self.theme_toggle.set_dark_mode(dark)
        self.log_toggle.set_dark_mode(dark)
        self.crop_preview.set_background(colors["preview"])
        self.mapped_preview.set_background(colors["preview"])
        self.mapped_thumbnail.set_background(colors["preview"])
        self._set_current_color(self._current_palette_index)

    def _connect_and_verify(self) -> None:
        if self._connect_thread is not None and self._connect_thread.isRunning():
            return
        self.settings.adb.host = self.host_edit.text().strip()
        self.settings.adb.port = self.port_spin.value()
        self._log_operation(
            f"开始连接并验证：{self._adb_target_description()}"
        )
        self.connection_status.setObjectName("statusBad")
        self.connection_status.setText("正在连接并验证…")
        self.connection_status.style().unpolish(self.connection_status)
        self.connection_status.style().polish(self.connection_status)
        self.connect_button.setEnabled(False)
        self._connect_thread = ConnectThread(
            self.settings.adb.host,
            self.settings.adb.port,
            self.settings.adb.timeout_seconds,
            self,
            adb_server_port=self.settings.adb.server_port,
        )
        self._connect_thread.succeeded.connect(self._connection_succeeded)
        self._connect_thread.failed.connect(self._connection_failed)
        self._connect_thread.finished.connect(lambda: self.connect_button.setEnabled(True))
        self._connect_thread.start()

    def _adb_target_changed(self, target: str) -> None:
        serial_mode = is_adb_serial_target(target)
        self.port_label.setEnabled(not serial_mode)
        self.port_spin.setEnabled(not serial_mode)

    def _adb_target_description(self) -> str:
        if is_adb_serial_target(self.settings.adb.host):
            return (
                f"{self.settings.adb.host} via "
                f"127.0.0.1:{self.settings.adb.server_port}"
            )
        return f"{self.settings.adb.host}:{self.settings.adb.port}"

    def _connection_succeeded(self, result: object) -> None:
        data = result
        self._screen_layout = data["layout"]
        with Image.open(io.BytesIO(data["screenshot"])) as image:
            self.device_preview.set_image(image.convert("RGB"))
        self.connection_status.setObjectName("statusGood")
        self.connection_status.setText("已连接，已识别画布和 40 色调色板")
        self.connection_status.style().unpolish(self.connection_status)
        self.connection_status.style().polish(self.connection_status)
        self._log_operation("连接并验证成功")
        self._update_action_state()

    def _connection_failed(self, message: str) -> None:
        self._screen_layout = None
        self.device_preview.set_image(None)
        self.connection_status.setObjectName("statusBad")
        self.connection_status.setText(f"连接/验证失败：{message}")
        self.connection_status.style().unpolish(self.connection_status)
        self.connection_status.style().polish(self.connection_status)
        self._log_operation(f"连接/验证失败：{message}")
        self._update_action_state()

    def _open_image(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "选择图片",
            "",
            "图像文件 (*.png *.jpg *.jpeg *.bmp *.gif *.tif *.tiff *.webp);;所有文件 (*.*)",
        )
        if not path:
            return
        try:
            image = load_source_image(Path(path))
        except (OSError, ValueError) as exc:
            QMessageBox.warning(self, "无法读取图片", str(exc))
            self._log_operation(f"导入图片失败：{path}；{exc}")
            return
        self._load_image(
            image,
            label=path,
            log_message=f"导入图片：{path}；已恢复默认图片处理参数",
        )

    def _start_screen_capture(self) -> None:
        if self._screen_capture_overlay is not None:
            return
        self.screen_capture_button.setEnabled(False)
        self.hide()
        QTimer.singleShot(180, self._show_screen_capture_overlay)

    def _show_screen_capture_overlay(self) -> None:
        try:
            capture = capture_virtual_desktop()
        except RuntimeError as exc:
            self._restore_after_screen_capture()
            QMessageBox.warning(self, "无法截图", str(exc))
            self._log_operation(f"屏幕截图失败：{exc}")
            return

        overlay = ScreenCaptureOverlay(capture)
        self._screen_capture_overlay = overlay
        overlay.imageCaptured.connect(self._screen_capture_completed)
        overlay.cancelled.connect(self._screen_capture_cancelled)
        overlay.show()
        overlay.raise_()
        overlay.activateWindow()
        overlay.setFocus()

    def _screen_capture_completed(self, image: object) -> None:
        screenshot = image
        if not isinstance(screenshot, Image.Image):
            self._screen_capture_cancelled()
            return
        self._load_image(
            screenshot,
            label=f"屏幕截图（{screenshot.width}×{screenshot.height}）",
            log_message=(
                f"导入屏幕截图：{screenshot.width}×{screenshot.height}；"
                "已恢复默认图片处理参数"
            ),
        )
        self._restore_after_screen_capture()

    def _screen_capture_cancelled(self) -> None:
        self._log_operation("取消屏幕截图")
        self._restore_after_screen_capture()

    def _restore_after_screen_capture(self) -> None:
        self._screen_capture_overlay = None
        self.screen_capture_button.setEnabled(True)
        self.show()
        self.raise_()
        self.activateWindow()

    def _load_image(self, image: Image.Image, *, label: str, log_message: str) -> None:
        image = image.convert("RGBA")
        self._reset_image_processing_controls()
        self._source_image = image
        self.image_path_label.setText(label)
        self.crop_preview.set_source(image, initial_square_crop(image))
        self._log_operation(log_message)
        self._reprocess()

    def _reset_image_processing_controls(self) -> None:
        """Restore all image-processing controls to their built-in defaults."""
        defaults = ImageSettings()
        for combo, value in (
            (self.resize_mode_combo, defaults.resize_mode),
            (self.resampling_combo, defaults.resampling),
            (self.mapping_combo, defaults.mapping_method),
            (self.transparent_combo, defaults.transparent_palette_index),
            (self.merge_combo, defaults.merge_pixels),
        ):
            combo.blockSignals(True)
            _set_combo_data(combo, value)
            combo.blockSignals(False)
        for key, slider in self._adjustment_sliders.items():
            slider.blockSignals(True)
            slider.setValue(int(getattr(defaults, key)))
            slider.blockSignals(False)
            self._adjustment_value_labels[key].setText(str(slider.value()))
        self.crop_zoom_slider.blockSignals(True)
        self.crop_zoom_slider.setValue(100)
        self.crop_zoom_slider.blockSignals(False)
        self.crop_zoom_value_label.setText("100%")
        self.crop_preview.set_zoom(1.0)
        self.crop_preview.set_interactive(defaults.resize_mode == ResizeMode.CROP.value)
        self.crop_preview.set_grid_size(24 // defaults.merge_pixels)

    def _resize_mode_changed(self) -> None:
        is_crop = self.resize_mode_combo.currentData() == ResizeMode.CROP.value
        self.crop_preview.set_interactive(is_crop)
        self._reprocess()

    def _merge_pixels_changed(self) -> None:
        merge_pixels = int(self.merge_combo.currentData() or 1)
        self.crop_preview.set_grid_size(24 // merge_pixels)
        self._reprocess()

    def _crop_zoom_changed(self, value: int) -> None:
        self.crop_zoom_value_label.setText(f"{value}%")
        self.crop_preview.set_zoom(value / 100.0)

    def _reprocess(self) -> None:
        if self._source_image is None:
            self._processed = None
            self.mapped_preview.set_image(None)
            self.mapped_thumbnail.set_image(None)
            self.image_status.setText("请选择图片")
            self._update_action_state()
            return
        try:
            mode = ResizeMode(self.resize_mode_combo.currentData())
            method = MappingMethod(self.mapping_combo.currentData())
            adjusted_source = adjust_image_colors(
                self._source_image,
                brightness=self.brightness_slider.value(),
                contrast=self.contrast_slider.value(),
                saturation=self.saturation_slider.value(),
                color_temperature=self.color_temperature_slider.value(),
                hue=self.hue_slider.value(),
            )
            self.crop_preview.set_source(
                adjusted_source, self.crop_preview.crop_box()
            )
            self._processed = process_image(
                adjusted_source,
                resize_mode=mode,
                crop_box=self.crop_preview.crop_box(),
                resampling=self.resampling_combo.currentData(),
                mapping_method=method,
                dither=False,
                transparent_palette_index=self.transparent_combo.currentData(),
                merge_pixels=self.merge_combo.currentData(),
            )
            self.mapped_preview.set_image(self._processed.mapped)
            self.mapped_thumbnail.set_image(self._processed.mapped)
            unique = len({int(value) for value in self._processed.palette_indices.flat})
            merge_pixels = int(self.merge_combo.currentData() or 1)
            logical_size = 24 // merge_pixels
            size_text = (
                "24×24"
                if merge_pixels == 1
                else f"{logical_size}×{logical_size} 逻辑像素 → 24×24"
            )
            self.image_status.setText(
                f"{size_text} | {MAPPING_LABELS[method]} | 使用 {unique} 种色 | 可绘制"
            )
        except (OSError, ValueError) as exc:
            self._processed = None
            self.mapped_preview.set_image(None)
            self.mapped_thumbnail.set_image(None)
            self.image_status.setText(f"图片处理失败：{exc}")
        self._update_action_state()

    def _export_mapped(self) -> None:
        if self._processed is None:
            return
        path, _ = QFileDialog.getSaveFileName(
            self,
            "导出映射图",
            "mapped_24x24.png",
            "PNG 图像 (*.png)",
        )
        if not path:
            return
        try:
            self._processed.mapped.save(path, format="PNG")
            self.image_status.setText(f"已导出：{path}")
            self._log_operation(f"导出映射图：{path}")
        except OSError as exc:
            QMessageBox.warning(self, "导出失败", str(exc))
            self._log_operation(f"导出映射图失败：{path}；{exc}")

    def _confirm_drawing(self) -> bool:
        dialog = QDialog(self)
        dialog.setWindowTitle("确认绘制")
        dialog.setModal(True)
        dialog.setMinimumWidth(420)
        layout = QVBoxLayout(dialog)
        layout.setContentsMargins(24, 22, 24, 20)
        layout.setSpacing(20)
        message = QLabel("即将覆盖画布，是否继续？")
        layout.addWidget(message)
        button_row = QHBoxLayout()
        button_row.setSpacing(10)
        button_row.addStretch(1)
        no_button = QPushButton("No")
        no_button.setObjectName("dangerButton")
        no_button.setMinimumSize(120, 34)
        no_button.clicked.connect(dialog.reject)
        button_row.addWidget(no_button)
        yes_button = QPushButton("Yes")
        yes_button.setObjectName("successButton")
        yes_button.setMinimumSize(120, 34)
        yes_button.clicked.connect(dialog.accept)
        button_row.addWidget(yes_button)
        layout.addLayout(button_row)
        no_button.setFocus()
        return dialog.exec() == QDialog.DialogCode.Accepted

    def _set_current_color(self, palette_index: int | None) -> None:
        self._current_palette_index = palette_index
        dark = hasattr(self, "theme_toggle") and self.theme_toggle.isChecked()
        border = "#687178" if dark else "#aab1b7"
        if palette_index is None:
            self.current_color_swatch.setStyleSheet(
                f"background: transparent; border: 1px solid {border}; border-radius: 4px;"
            )
            self.current_color_swatch.setToolTip("当前没有正在绘制的颜色")
            return
        color = PALETTE[palette_index]
        self.current_color_swatch.setStyleSheet(
            f"background: {color.hex_value}; border: 1px solid {border}; border-radius: 4px;"
        )
        self.current_color_swatch.setToolTip(f"当前颜色：{color.label}")

    def _start_drawing(self) -> None:
        if self._processed is None or self._screen_layout is None:
            return
        if self._draw_thread is not None and self._draw_thread.isRunning():
            return
        if not self._confirm_drawing():
            return
        self.settings.adb.host = self.host_edit.text().strip()
        self.settings.adb.port = self.port_spin.value()
        self._apply_image_settings()
        self._apply_drawing_settings()
        self._log_operation(
            f"开始绘制：{self._adb_target_description()}"
        )
        painter = Painter(
            self.settings.adb.host,
            self.settings.adb.port,
            self.settings.adb.timeout_seconds,
            self.settings.drawing,
            adb_server_port=self.settings.adb.server_port,
        )
        self._draw_thread = DrawThread(painter, self._processed.palette_indices, self)
        self._draw_thread.progress.connect(self._drawing_progress)
        self._draw_thread.succeeded.connect(self._drawing_succeeded)
        self._draw_thread.cancelled.connect(self._drawing_cancelled)
        self._draw_thread.failed.connect(self._drawing_failed)
        self._draw_thread.finished.connect(self._drawing_finished)
        self.progress_bar.setValue(0)
        self.color_progress_bar.setRange(0, 1)
        self.color_progress_bar.setValue(0)
        self._set_current_color(None)
        self.image_status.setText("正在绘制…")
        self._set_drawing_state(True)
        self._draw_thread.start()

    def _cancel_drawing(self) -> None:
        if self._draw_thread is not None:
            self._draw_thread.cancel()
            self.image_status.setText("正在停止当前批次…")
            self._log_operation("取消绘制")

    def _drawing_progress(
        self,
        completed: int,
        total: int,
        palette_index: int,
        color_completed: int,
        color_total: int,
    ) -> None:
        self._active_draw_total = total
        if total == 0:
            self.progress_bar.setRange(0, 1)
            self.progress_bar.setValue(1)
            self.color_progress_bar.setRange(0, 1)
            self.color_progress_bar.setValue(0)
            self._set_current_color(None)
            self.image_status.setText("目标白色格已经是白色，无需绘制")
            return
        self.progress_bar.setRange(0, total)
        self.progress_bar.setValue(completed)
        self.color_progress_bar.setRange(0, max(1, color_total))
        self.color_progress_bar.setValue(color_completed)
        self._set_current_color(palette_index if palette_index >= 0 else None)
        if palette_index >= 0:
            self.image_status.setText(
                f"正在绘制：{completed}/{total} 格，当前颜色 {palette_index + 1:02d}"
            )
        else:
            self.image_status.setText(f"准备绘制：0/{total} 格")

    def _drawing_succeeded(self) -> None:
        self.progress_bar.setValue(self.progress_bar.maximum())
        if self._active_draw_total > 0:
            self.image_status.setText("绘制完成")
        self._log_operation("绘制完成")

    def _drawing_cancelled(self) -> None:
        self.image_status.setText("已取消；画布保留已完成部分，可修改参数后重新绘制")
        self._log_operation("绘制已取消")

    def _drawing_failed(self, message: str) -> None:
        self.image_status.setText(f"绘制失败：{message}")
        QMessageBox.warning(self, "绘制失败", message)
        self._log_operation(f"绘制失败：{message}")

    def _drawing_finished(self) -> None:
        self._set_current_color(None)
        self._set_drawing_state(False)
        self._update_action_state()

    def _set_drawing_state(self, drawing: bool) -> None:
        self.connect_button.setEnabled(not drawing)
        self.open_button.setEnabled(not drawing)
        self.screen_capture_button.setEnabled(not drawing)
        for control in self.processing_controls:
            control.setEnabled(not drawing)
        self.tap_delay_spin.setEnabled(not drawing)
        self.start_button.setEnabled(not drawing and self._processed is not None and self._screen_layout is not None)
        self.retry_button.setEnabled(not drawing and self._processed is not None and self._screen_layout is not None)
        self.cancel_button.setEnabled(drawing)
        self.export_button.setEnabled(not drawing and self._processed is not None)

    def _update_action_state(self) -> None:
        drawing = self._draw_thread is not None and self._draw_thread.isRunning()
        self._set_drawing_state(drawing)

    def _apply_image_settings(self) -> None:
        self.settings.image.resize_mode = str(self.resize_mode_combo.currentData())
        self.settings.image.resampling = str(self.resampling_combo.currentData())
        self.settings.image.mapping_method = str(self.mapping_combo.currentData())
        self.settings.image.transparent_palette_index = int(
            self.transparent_combo.currentData()
        )
        self.settings.image.merge_pixels = int(self.merge_combo.currentData())
        for key, slider in self._adjustment_sliders.items():
            setattr(self.settings.image, key, slider.value())

    def _apply_drawing_settings(self) -> None:
        self.settings.drawing.tap_delay_ms = self.tap_delay_spin.value()

    def closeEvent(self, event: object) -> None:
        if self._draw_thread is not None and self._draw_thread.isRunning():
            answer = QMessageBox.question(
                self,
                "正在绘制",
                "绘制尚未完成，确定退出吗？",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No,
            )
            if answer != QMessageBox.StandardButton.Yes:
                event.ignore()  # type: ignore[attr-defined]
                return
            self._draw_thread.cancel()
            self._draw_thread.wait(3000)
        self._apply_drawing_settings()
        self.settings.adb.host = self.host_edit.text().strip()
        self.settings.adb.port = self.port_spin.value()
        self._log_operation("程序关闭")
        try:
            save_settings(self.settings)
        except (OSError, ValueError):
            pass
        event.accept()  # type: ignore[attr-defined]


def _set_combo_data(
    combo: SegmentedControl | PaletteSelector, value: object
) -> None:
    index = combo.findData(value)
    if index >= 0:
        combo.setCurrentIndex(index)

def _short_resampling_label(key: str) -> str:
    return {
        "nearest": "邻近",
        "box": "盒式平均",
        "bilinear": "双线性",
        "hamming": "汉明",
        "bicubic": "双立方",
        "lanczos": "Lanczos",
    }[key]


def _help_button(text: str) -> QToolButton:
    button = QToolButton()
    button.setObjectName("helpButton")
    button.setText("?")
    button.setAccessibleName("帮助")
    button.setToolTip(text)
    button.setToolTipDuration(8000)
    button.clicked.connect(
        lambda _checked=False: QToolTip.showText(
            button.mapToGlobal(QPoint(button.width() + 5, 0)), text, button
        )
    )
    return button


def _label_with_help(
    label: str, tooltip: str, *, width: int | None = None
) -> QWidget:
    container = QWidget()
    if width is not None:
        container.setFixedWidth(width)
    layout = QHBoxLayout(container)
    layout.setContentsMargins(0, 0, 0, 0)
    layout.setSpacing(4)
    layout.addWidget(QLabel(label))
    layout.addStretch(1)
    layout.addWidget(_help_button(tooltip))
    return container
