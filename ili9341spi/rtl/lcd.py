from amaranth import Cat, ClockSignal, Module, Signal
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

    CmdSignature = wiring.Signature(
        {
            "req": In(stream.Signature(Request)),
            "resp": Out(stream.Signature(8)),
        }
    )

    pin: Out(PinSignature)
    cmd: Out(CmdSignature)

    def elaborate(self, platform):
        m = Module()

        sr = Signal(8)

        bit_rem = Signal(3)
        rcv_byte_rem = Signal(4)

        with m.FSM() as fsm:
            with m.State("idle"):
                with self.cmd.req.Recv(m) as payload:
                    m.d.sync += [
                        sr.eq(payload.data),
                        self.pin.dc.eq(payload.dc),
                        bit_rem.eq(7),
                        rcv_byte_rem.eq(payload.resp_len),
                    ]
                    m.next = "snd"

            with m.State("snd"):
                m.d.comb += self.pin.copi.eq(sr[7])
                m.d.sync += bit_rem.eq(bit_rem - 1)

                with m.If(bit_rem == 0):
                    m.d.sync += [
                        bit_rem.eq(7),
                        rcv_byte_rem.eq(rcv_byte_rem - 1),
                        self.pin.dc.eq(0),
                    ]
                    with m.If(rcv_byte_rem == 0):
                        m.next = "idle"
                    with m.Else():
                        m.next = "rcv"

            with m.State("rcv"):
                m.d.sync += bit_rem.eq(bit_rem - 1)

                with m.If(bit_rem == 0):
                    m.d.sync += bit_rem.eq(7)
                    m.next = "rcv_end"

            with m.State("rcv_end"):
                # XXX no support for backpressure, probably violates `stream`
                # policy.
                self.cmd.resp.enq(m, sr)
                m.d.sync += rcv_byte_rem.eq(rcv_byte_rem - 1)

                with m.If(rcv_byte_rem == 0):
                    m.next = "idle"
                with m.Else():
                    m.next = "rcv"

        with m.If(~fsm.ongoing("idle")):
            m.d.comb += self.pin.clk.eq(~ClockSignal("sync"))
            m.d.sync += sr.eq(Cat(self.pin.cipo, sr[:7]))

        return m
