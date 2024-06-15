from amaranth import Array, Module, Signal
from amaranth.lib import wiring
from amaranth.lib.wiring import In, Out

from .lcd import Lcd
from .proto import LCD_INIT_ROM, LCD_INIT_SEQUENCE, LcdCommand

__all__ = ["Initter"]


class Initter(wiring.Component):
    lcd: Out(Lcd.CommandSignature)
    res: Out(1)
    done: Out(1)

    def elaborate(self, platform):
        m = Module()

        m.d.comb += self.done.eq(0)
        m.d.comb += self.res.eq(0)

        reset_apply_cyc = int((11 * platform.default_clk_frequency) // 1_000_000)  # tRW_min = 10µs
        reset_wait_cyc = int((121_000 * platform.default_clk_frequency) // 1_000_000)  # tRT_max = 120ms
        res_timer = Signal(range(reset_wait_cyc + 1), init=reset_apply_cyc)

        rom = Array(LCD_INIT_ROM)
        rom_len = len(LCD_INIT_ROM)
        rom_ix = Signal(range(rom_len + 1))
        cmd_rem = Signal(range(max(len(pair[1]) for pair in LCD_INIT_SEQUENCE) + 1))

        req = Signal(Lcd.Request)

        with m.FSM():
            with m.State("reset_apply"):
                m.d.comb += self.res.eq(1)
                m.d.sync += res_timer.eq(res_timer - 1)
                with m.If(res_timer == 0):
                    m.d.sync += res_timer.eq(reset_wait_cyc)
                    m.next = "reset_wait"

            with m.State("reset_wait"):
                m.d.sync += res_timer.eq(res_timer - 1)
                with m.If(res_timer == 0):
                    m.next = "init_cmd"

            with m.State("init_cmd"):
                with m.If(rom_ix != rom_len):
                    m.d.comb += [
                        req.data.eq(rom[rom_ix]),
                        req.dc.eq(1),
                        req.resp_len.eq(0),
                    ]
                    with self.lcd.req.Send(m, req):
                        m.d.sync += [
                            rom_ix.eq(rom_ix + 2),
                            cmd_rem.eq(rom[rom_ix + 1])
                        ]
                        m.next = "init_param"

                        with m.If(rom[rom_ix] == LcdCommand.NOP):
                            m.d.sync += res_timer.eq(reset_wait_cyc)
                            m.next = "reset_wait"

                with m.Else():
                    m.next = "done"

            with m.State("init_param"):
                with m.If(cmd_rem != 0):
                    m.d.comb += [
                        req.data.eq(rom[rom_ix]),
                        req.dc.eq(0),
                        req.resp_len.eq(0),
                    ]
                    with self.lcd.req.Send(m, req):
                        m.d.sync += [
                            rom_ix.eq(rom_ix + 1),
                            cmd_rem.eq(cmd_rem - 1),
                        ]
                with m.Else():
                    m.next = "init_cmd"

            with m.State("done"):
                m.d.comb += self.done.eq(1)

        return m
