from amaranth import Module, Signal
from amaranth.build import Attrs, Pins, PinsN, Resource, Subsignal
from amaranth.lib import wiring
from amaranth.lib.cdc import FFSynchronizer
from amaranth.lib.wiring import In, Out
from amaranth_stdio.serial import AsyncSerial

from ..targets import cxxrtl, icebreaker, ulx3s
from .lcd import Lcd

__all__ = ["Top"]


icebreaker_spi_lcd = Resource(
    "spi_lcd",
    0,
    Subsignal(
        "clk", Pins("3", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")
    ),
    Subsignal(
        "copi", Pins("4", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")
    ),
    Subsignal(
        "res", PinsN("7", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")
    ),
    Subsignal(
        "dc", PinsN("8", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")
    ),
    Subsignal(
        "blk", Pins("9", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")
    ),
    Subsignal(
        "cipo", Pins("10", dir="i", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")
    ),
)


class Top(wiring.Component):
    def __init__(self, platform):
        if isinstance(platform, cxxrtl):
            super().__init__(
                {
                    "lcd": Out(Lcd.PinSignature),
                    "lcd_res": Out(1),
                    "uart_rx": In(1),
                    "uart_tx": Out(1),
                }
            )
        else:
            super().__init__({})

    def elaborate(self, platform):
        m = Module()

        m.submodules.serial = serial = AsyncSerial(
            divisor=int(platform.default_clk_frequency // 115_200)
        )

        res = Signal(init=0)
        blk = Signal(init=1)

        ili = wiring.flipped(Lcd.PinSignature.create())

        m.submodules.lcd = lcd = Lcd()
        wiring.connect(m, lcd.pins, ili)

        match platform:
            case icebreaker():
                platform.add_resources([icebreaker_spi_lcd])
                plat_spi = platform.request("spi_lcd")
                m.d.comb += [
                    plat_spi.clk.o.eq(ili.clk),
                    plat_spi.copi.o.eq(ili.copi),
                    plat_spi.res.o.eq(res),
                    plat_spi.dc.o.eq(ili.dc),
                    plat_spi.blk.o.eq(blk),
                    ili.cipo.eq(plat_spi.cipo.i),
                ]

                plat_uart = platform.request("uart")
                m.d.comb += plat_uart.tx.o.eq(serial.tx.o)
                m.submodules += FFSynchronizer(plat_uart.rx.i, serial.rx.i, init=1)

            case ulx3s():
                plat_uart = platform.request("uart")
                m.d.comb += plat_uart.tx.o.eq(serial.tx.o)
                m.submodules += FFSynchronizer(plat_uart.rx.i, serial.rx.i, init=1)

            case cxxrtl():
                m.d.comb += self.uart_tx.eq(serial.tx.o)
                m.d.comb += serial.rx.i.eq(self.uart_rx)
                wiring.connect(m, self.lcd, ili)
                m.d.comb += self.lcd_res.eq(res)

        return m


class Blinker(wiring.Component):
    ledr: Out(1)
    ledg: Out(1)

    def elaborate(self, platform):
        m = Module()

        m.d.comb += self.ledg.eq(1)

        timer_top = (int(platform.default_clk_frequency) // 2) - 1
        timer_half = (int(platform.default_clk_frequency) // 4) - 1
        timer_reg = Signal(range(timer_top), init=timer_half)

        with m.If(timer_reg == 0):
            m.d.sync += [
                self.ledr.eq(~self.ledr),
                timer_reg.eq(timer_top),
            ]
        with m.Else():
            m.d.sync += timer_reg.eq(timer_reg - 1)

        return m
