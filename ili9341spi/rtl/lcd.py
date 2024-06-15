from amaranth import Cat, Module, Signal
from amaranth.lib import data, stream, wiring  # type: ignore
from amaranth.lib.wiring import In, Out

__all__ = ["Lcd"]


class Lcd(wiring.Component):
    PinSignature = wiring.Signature(
        {
            "clk": Out(1),
            "copi": Out(1),
            "dc": Out(1),
            "cipo": In(1),
        }
    )

    class Request(data.Struct):
        data: 8
        dc: 1
        resp_len: 4

    CommandSignature = wiring.Signature(
        {
            "req": In(stream.Signature(Request)),
            "resp": Out(stream.Signature(8)),
        }
    )

    pins: Out(PinSignature)
    command: Out(CommandSignature)

    def elaborate(self, platform):
        m = Module()

        m.d.comb += self.pins.clk.eq(0)

        sr = Signal(8)
        with m.If(self.pins.clk):
            m.d.sync += sr.eq(Cat(self.pins.cipo, sr[:7]))
        m.d.comb += self.pins.copi.eq(sr[7])

        bit_rem = Signal(3)
        rcv_byte_rem = Signal(4)

        with m.FSM():
            with m.State("idle"):
                with self.command.req.Deq(m) as payload:
                    m.d.sync += [
                        sr.eq(payload.data),
                        self.pins.dc.eq(payload.dc),
                        bit_rem.eq(7),
                        rcv_byte_rem.eq(payload.resp_len),
                    ]
                    m.next = "snd_low"

            with m.State("snd_low"):
                m.next = "snd_high"

            with m.State("snd_high"):
                m.d.comb += self.pins.clk.eq(1)
                m.d.sync += bit_rem.eq(bit_rem - 1)
                m.next = "snd_low"

                with m.If(bit_rem == 0):
                    m.d.sync += [
                        bit_rem.eq(7),
                        rcv_byte_rem.eq(rcv_byte_rem - 1),
                        self.pins.dc.eq(0),
                    ]
                    with m.If(rcv_byte_rem == 0):
                        m.next = "idle"
                    with m.Else():
                        m.next = "rcv_low"

            with m.State("rcv_low"):
                m.next = "rcv_high"

            with m.State("rcv_high"):
                m.d.comb += self.pins.clk.eq(1)
                m.d.sync += bit_rem.eq(bit_rem - 1)
                m.next = "rcv_low"

                with m.If(bit_rem == 0):
                    m.d.sync += bit_rem.eq(7)
                    m.next = "rcv_done"

            with m.State("rcv_done"):
                # XXX no support for backpressure, probably violates `stream`
                # policy.
                self.command.resp.enq(m, sr)
                m.d.sync += rcv_byte_rem.eq(rcv_byte_rem - 1)

                with m.If(rcv_byte_rem == 0):
                    m.next = "idle"
                with m.Else():
                    m.next = "rcv_high"

        return m
