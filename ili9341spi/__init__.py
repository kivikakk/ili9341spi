import niar

from . import rtl
from .targets import cxxrtl, icebreaker, ulx3s

__all__ = ["ILI9341SPI"]


class ILI9341SPI(niar.Project):
    name = "ili9341spi"
    top = rtl.Top
    targets = [icebreaker, ulx3s]
    cxxrtl_targets = [cxxrtl]
