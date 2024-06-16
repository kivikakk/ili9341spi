from amaranth import ClockSignal, Module, Mux, Signal
from amaranth.build import Attrs, Pins, PinsN, Resource, Subsignal
from amaranth.lib import wiring
from amaranth.lib.cdc import FFSynchronizer
from amaranth.lib.memory import Memory
from amaranth.lib.wiring import In, Out
from amaranth_stdio.serial import AsyncSerial

from ..targets import cxxrtl, icebreaker, ulx3s
from . import streamext as _
from .initter import Initter
from .lcd import Lcd
from .proto import LcdCommand

__all__ = ["Top"]


icebreaker_spi_lcd = Resource("spi_lcd", 0,
    Subsignal("clk",  Pins("3",  dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")),
    Subsignal("copi", Pins("4",  dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")),
    Subsignal("res",  PinsN("7", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")),
    Subsignal("dc",   PinsN("8", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")),
    Subsignal("blk",  Pins("9",  dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")),
    Subsignal("cipo", Pins("10", dir="i", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")),
)


class Top(wiring.Component):
    def __init__(self, platform):
        if isinstance(platform, cxxrtl):
            super().__init__({
                "lcd": Out(Lcd.PinSignature),
                "lcd_res": Out(1),
                "uart_rx": In(1),
                "uart_tx": Out(1),
            })
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
        wiring.connect(m, lcd.pin, ili)

        with lcd.cmd.resp.Recv(m) as payload:
            m.d.comb += [
                serial.tx.data.eq(payload),
                serial.tx.ack.eq(1),
            ]

        m.submodules.initter = initter = Initter()

        LCD_WIDTH = 320
        LCD_HEIGHT = 240
        GOL_SIZE = 4
        GOL_WIDTH = LCD_WIDTH // GOL_SIZE
        GOL_HEIGHT = LCD_HEIGHT // GOL_SIZE
        GOL_CELLCNT = GOL_WIDTH * GOL_HEIGHT

        start = """
................................................................................
................................................................................
..........................#.....................................................
........................#.#.....................................................
..............##......##............##..........................................
.............#...#....##............##..........................................
..##........#.....#...##........................................................
..##........#...#.##....#.#.....................................................
............#.....#.......#.....................................................
.............#...#..............................................................
..............##................................................................
................................................................................
................................................................................
""".strip().replace(
            "\n", ""
        )

        m.submodules.now_cells = now_cells = \
            Memory(shape=1, depth=GOL_CELLCNT, init=[c != "." for c in start])
        now_cells_rd = now_cells.read_port()
        now_cells_wr = now_cells.write_port()
        now_cells_addr = Signal.like(now_cells_rd.addr)
        m.d.comb += [
            now_cells_rd.addr.eq(now_cells_addr),
            now_cells_wr.addr.eq(now_cells_addr),
        ]
        m.d.sync += now_cells_wr.en.eq(0)

        m.submodules.next_cells = next_cells = Memory(shape=1, depth=GOL_CELLCNT, init=[])
        next_cells_rd = next_cells.read_port()
        next_cells_wr = next_cells.write_port()
        next_cells_addr = Signal.like(next_cells_rd.addr)
        m.d.comb += [
            next_cells_rd.addr.eq(next_cells_addr),
            next_cells_wr.addr.eq(next_cells_addr),
        ]
        m.d.sync += next_cells_wr.en.eq(0)

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
                wiring.connect(m, wiring.flipped(initter.lcd), lcd.cmd)
                m.d.comb += res.eq(initter.res)
                with m.If(initter.done):
                    m.next = "initiate"

            with m.State("initiate"):
                m.d.comb += [
                    req.data.eq(LcdCommand.MEMORY_WRITE),
                    req.dc.eq(1),
                ]
                with lcd.cmd.req.Send(m, req):
                    m.next = "load"

            ## render

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
                with lcd.cmd.req.Send(m, req):
                    m.next = "render2"

            with m.State("render2"):
                m.d.comb += [
                    req.data.eq(Mux(now_cells_rd.data, 0xff, 0x00)),
                    req.dc.eq(0),
                ]
                with lcd.cmd.req.Send(m, req):
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
   
            ## evolve

            with m.State("evolve_start"):
                m.d.sync += [
                    pl_ix.eq(0),
                    neighbours_alive.eq(0),
                ]
                m.next = "evolve_load"

            with m.State("evolve_load"):
                with m.Switch(pl_ix):
                    with m.Case(0): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 0, pag + 0))
                    with m.Case(1): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 1, pag + 0))
                    with m.Case(2): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 2, pag + 0))
                    with m.Case(3): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 0, pag + 1))
                    with m.Case(4): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 1, pag + 1))
                    with m.Case(5): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 2, pag + 1))
                    with m.Case(6): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 0, pag + 2))
                    with m.Case(7): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 1, pag + 2))
                    with m.Case(8): m.d.sync += now_cells_addr.eq(cell_ix_at(col + 2, pag + 2))
                m.next = "evolve_wait"

            with m.State("evolve_wait"):
                m.next = "evolve_part"

            with m.State("evolve_part"):
                with m.If(pl_ix == 4):
                    m.d.sync += was_alive.eq(now_cells_rd.data)
                with m.Else():
                    m.d.sync += neighbours_alive.eq(neighbours_alive + now_cells_rd.data)

                with m.If(pl_ix != 8):
                    m.d.sync += pl_ix.eq(pl_ix + 1)
                    m.next = "evolve_load"
                with m.Else():
                    m.next = "evolve_write"

            with m.State("evolve_write"):
                m.d.sync += [
                    next_cells_addr.eq(direct_cell_ix),
                    next_cells_wr.en.eq(1),
                ]

                with m.If(was_alive):
                    m.d.sync += next_cells_wr.data.eq((neighbours_alive == 2) | (neighbours_alive == 3))
                with m.Else():
                    m.d.sync += next_cells_wr.data.eq(neighbours_alive == 3)

                m.next = "evolve_start"
                m.d.sync += col.eq(col + 1)
                with m.If(col == (GOL_WIDTH - 1)):
                    m.d.sync += col.eq(0)
                    m.d.sync += pag.eq(pag + 1)
                    with m.If(pag == (GOL_HEIGHT - 1)):
                        m.d.sync += pag.eq(0)
                        m.next = "transition_load"

            ## transition

            with m.State("transition_load"):
                m.d.sync += next_cells_addr.eq(direct_cell_ix)
                m.next = "transition_wait"

            with m.State("transition_wait"):
                m.next = "transition_write"

            with m.State("transition_write"):
                m.d.sync += [
                    now_cells_addr.eq(direct_cell_ix),
                    now_cells_wr.en.eq(1),
                    now_cells_wr.data.eq(next_cells_rd.data),
                ]

                m.next = "transition_load"
                m.d.sync += col.eq(col + 1)
                with m.If(col == (GOL_WIDTH - 1)):
                    m.d.sync += col.eq(0)
                    m.d.sync += pag.eq(pag + 1)
                    with m.If(pag == (GOL_HEIGHT - 1)):
                        m.d.sync += pag.eq(0)
                        m.next = "initiate"

        match platform:
            case icebreaker():
                platform.add_resources([icebreaker_spi_lcd])
                plat_spi = platform.request("spi_lcd")
                m.d.comb += [
                    plat_spi.clk.o.eq(ili.clk),
                    plat_spi.copi.o.eq(ili.copi),
                    plat_spi.dc.o.eq(ili.dc),
                    plat_spi.res.o.eq(res),
                    plat_spi.blk.o.eq(blk),
                    ili.cipo.eq(plat_spi.cipo.i),
                ]

                platform.add_resources([Resource("pmod_clk_out", 0,
                    Subsignal("clk", Pins("1", dir="o", conn=("pmod", 0)), Attrs(IO_STANDARD="SB_LVCMOS")),
                )])
                m.d.comb += platform.request("pmod_clk_out").clk.o.eq(ClockSignal("sync"))

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
                wiring.connect(m, wiring.flipped(self.lcd), wiring.flipped(ili))
                m.d.comb += self.lcd_res.eq(res)

        return m
