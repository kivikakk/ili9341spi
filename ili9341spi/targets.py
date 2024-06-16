import os

import niar
from amaranth_boards.icebreaker import ICEBreakerPlatform
from amaranth_boards.ulx3s import ULX3S_45F_Platform

__all__ = ["icebreaker", "ulx3s", "cxxrtl"]


class icebreaker(ICEBreakerPlatform):
    if False: # XXX: testing DDR SPI and this would make us run probably-too-fast for the LCD>
    # if not os.getenv("GITHUB_ACTIONS"):
        # XXX: This meets timing when I build locally, but not on CI?!
        # https://github.com/kivikakk/ili9341spi/actions/runs/9535281535/job/26280812013?pr=1
        default_clk = "SB_HFOSC"
        hfosc_div = 1


class ulx3s(ULX3S_45F_Platform):
    pass


class cxxrtl(niar.CxxrtlPlatform):
    default_clk_frequency = 1_000_000.0
    uses_zig = True
