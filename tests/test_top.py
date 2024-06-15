import random
import unittest

from amaranth.hdl import Fragment
from amaranth.sim import Simulator

from ili9341spi.rtl.lcd import Lcd


class test:
    simulation = True


class TestLcd(unittest.TestCase):
    @staticmethod
    async def _snd(ctx, dut, bytes, rcv_cnt=0):
        for byte_ix, byte in enumerate(bytes):
            await ctx.tick()
            assert ctx.get(dut.pins.clk) == 0, f"snd pins.clk @ {byte_ix}"
            assert ctx.get(dut.command.req.ready) == 1

            ctx.set(dut.command.req.payload.data, byte)
            ctx.set(dut.command.req.payload.dc, byte_ix == 0)
            ctx.set(
                dut.command.req.payload.resp_len,
                rcv_cnt if byte_ix == len(bytes) - 1 else 0,
            )
            ctx.set(dut.command.req.valid, 1)

            for bit_ix in range(8):
                await ctx.tick()
                assert ctx.get(dut.command.req.ready) == 0
                assert ctx.get(dut.pins.clk) == 0

                ctx.set(dut.command.req.valid, 0)

                await ctx.tick()
                assert ctx.get(dut.pins.clk) == 1
                assert ctx.get(dut.pins.copi) == (
                    (byte >> (7 - bit_ix)) & 1
                ), f"snd pins.copi @ {byte_ix}:{bit_ix}"
                if bit_ix == 7:
                    assert ctx.get(dut.pins.dc) == (
                        byte_ix == 0
                    ), f"snd pins.dc @ {byte_ix}"

        await ctx.tick()
        assert ctx.get(dut.pins.clk) == 0
        assert ctx.get(dut.command.req.ready) == (rcv_cnt == 0)

    @staticmethod
    async def _rcv(ctx, dut, bytes):
        for byte_ix, byte in enumerate(bytes):
            for bit_ix in range(8):
                assert ctx.get(dut.pins.clk) == 0, f"rcv pins.clk @ {byte_ix}:{bit_ix}"
                assert ctx.get(
                    dut.command.req.ready == 0
                ), f"rcv req.ready @ {byte_ix}:{bit_ix}"

                ctx.set(dut.pins.cipo, (byte >> (7 - bit_ix)) & 1)

                await ctx.tick()
                assert ctx.get(dut.pins.clk) == 1

                await ctx.tick()

            assert ctx.get(dut.command.resp.valid) == 1, f"rcv resp.valid @ {byte_ix}"
            assert (
                ctx.get(dut.command.resp.payload) == byte
            ), f"rcv resp.payload @ {byte_ix}"

    @staticmethod
    def _run(tb_with_dut):
        dut = Lcd()

        async def testbench(ctx):
            await tb_with_dut(ctx, dut)

        sim = Simulator(Fragment.get(dut, test()))
        sim.add_clock(1e-6)
        sim.add_testbench(testbench)
        sim.run()

    def test_transmits_1_byte(self):
        async def testbench(ctx, dut):
            await self._snd(ctx, dut, list(random.randbytes(1)))

        self._run(testbench)

    def test_transmits_2_bytes(self):
        async def testbench(ctx, dut):
            await self._snd(ctx, dut, list(random.randbytes(2)))

        self._run(testbench)

    def test_receive_1_byte_resp(self):
        async def testbench(ctx, dut):
            await self._snd(ctx, dut, list(random.randbytes(1)), 1)
            await self._rcv(ctx, dut, list(random.randbytes(1)))

        self._run(testbench)

    def test_receive_2_byte_resp(self):
        async def testbench(ctx, dut):
            # TODO: optional cycle delay between send/receive. The datasheet is
            # ambiguous as to whether or not it should be just one cycle, or an
            # entire byte.
            await self._snd(ctx, dut, list(random.randbytes(1)), 2)
            await self._rcv(ctx, dut, list(random.randbytes(2)))

        self._run(testbench)
