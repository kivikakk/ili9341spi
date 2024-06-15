from amaranth import Module, Mux, Signal
from amaranth.build import Attrs, Pins, PinsN, Resource, Subsignal
from amaranth.lib import wiring
from amaranth.lib.cdc import FFSynchronizer
from amaranth.lib.memory import Memory
from amaranth.lib.wiring import In, Out
from amaranth_stdio.serial import AsyncSerial

from ili9341spi.rtl.proto import LcdCommand

from ..targets import cxxrtl, icebreaker, ulx3s
from . import streamext as _
from .initter import Initter
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

        with lcd.command.resp.Recv(m) as payload:
            m.d.comb += [
                serial.tx.data.eq(payload),
                serial.tx.ack.eq(1),
            ]

        m.submodules.initter = initter = Initter()

        LCD_WIDTH = 320
        LCD_HEIGHT = 240
        GOL_SIZE = 16  # XXX: try 8
        GOL_WIDTH = LCD_WIDTH // GOL_SIZE
        GOL_HEIGHT = LCD_HEIGHT // GOL_SIZE
        GOL_CELLCNT = GOL_WIDTH * GOL_HEIGHT

        start = """
....................
....................
...............#....
................#...
..............###...
....................
....................
....................
...#................
....#...............
..###........###....
...................#
......##............
......##..........##
....................
""".strip().replace(
            "\n", ""
        )

        now_cells = Memory(shape=1, depth=GOL_CELLCNT, init=[c != "." for c in start])
        now_cells_rd = now_cells.read_port()
        now_cells_wr = now_cells.write_port()
        now_cells_addr = Signal.like(now_cells_rd.addr)
        m.d.comb += [
            now_cells_rd.addr.eq(now_cells_addr),
            now_cells_wr.addr.eq(now_cells_addr),
        ]

        next_cells = Memory(shape=1, depth=GOL_CELLCNT, init=[])
        next_cells_rd = next_cells.read_port()
        next_cells_wr = next_cells.write_port()
        next_cells_addr = Signal.like(next_cells_rd.addr)
        m.d.comb += [
            next_cells_rd.addr.eq(next_cells_addr),
            next_cells_wr.addr.eq(next_cells_addr),
        ]

        col = Signal(range(LCD_WIDTH))
        pag = Signal(range(LCD_HEIGHT))

        # To avoid nonsense, we define the 'real' range of x as [1..GOL_WIDTH]
        # and y as [1..GOL_HEIGHT], and define our UInts up to GOL_WIDTH+1 and
        # GOL_HEIGHT+1 inclusive. This way we can detect wraps without having to
        # reach for signed integers.
        def cell_ix_at(x, y):
            eff_x = Signal(range(GOL_CELLCNT))
            with m.If(x == 0):
                m.d.comb += eff_x.eq(GOL_WIDTH - 1)
            with m.Elif(x == (GOL_WIDTH + 1)):
                m.d.comb += eff_x.eq(0)
            with m.Else():
                m.d.comb += eff_x.eq(x - 1)
            eff_y = Signal(range(GOL_CELLCNT))
            with m.If(y == 0):
                m.d.comb += eff_y.eq(GOL_HEIGHT - 1)
            with m.Elif(y == (GOL_HEIGHT + 1)):
                m.d.comb += eff_y.eq(0)
            with m.Else():
                m.d.comb += eff_y.eq(y - 1)
            out = Signal(range(GOL_CELLCNT))
            m.d.comb += out.eq(eff_x + (eff_y * GOL_WIDTH))
            return out

        cell_x = col // GOL_SIZE + 1
        cell_y = pag // GOL_SIZE + 1

        direct_cell_ix = Signal(range(GOL_CELLCNT))
        m.d.comb += direct_cell_ix.eq(cell_ix_at(col + 1, pag + 1))

        pl_ix = Signal(range(8 + 1))
        was_alive = Signal()
        neighbours_alive = Signal(8)

        req = Signal(Lcd.Request)

        with m.FSM():
            with m.State("init"):
                wiring.connect(m, wiring.flipped(initter.lcd), lcd.command)
                m.d.comb += res.eq(initter.res)
                with m.If(initter.done):
                    m.next = "initiate"

            with m.State("initiate"):
                m.d.comb += [
                    req.data.eq(LcdCommand.MEMORY_WRITE),
                    req.dc.eq(1),
                ]
                with lcd.command.req.Send(m, req):
                    m.next = "load"

            with m.State("load"):
                m.d.sync += now_cells_addr.eq(cell_ix_at(cell_x, cell_y))
                m.next = "load_wait"
            with m.State("load_wait"):
                m.next = "render"
            with m.State("render"):
                m.d.comb += [
                    req.data.eq(Mux(now_cells_rd.data, 0xff, 0x00)),
                    req.dc.eq(0),
                ]
                with lcd.command.req.Send(m, req):
                    m.next = "render2"
            with m.State("render2"):
                m.d.comb += [
                    req.data.eq(Mux(now_cells_rd.data, 0xff, 0x00)),
                    req.dc.eq(0),
                ]
                with lcd.command.req.Send(m, req):
                    m.next = "load"

                    with m.If(col == LCD_WIDTH - 1):
                        m.d.sync += col.eq(0)
                        with m.If(pag == LCD_HEIGHT - 1):
                            m.d.sync += pag.eq(0)
                            m.next = "evolve_start"
                        with m.Else():
                            m.d.sync += pag.eq(pag + 1)
                    with m.Else():
                        m.d.sync += col.eq(col + 1)

            with m.State("evolve_start"):
                pass
                # TODO NEXT: add zig mode to niar cxxrtl, test that we render
                # the initial state, then continue.

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
