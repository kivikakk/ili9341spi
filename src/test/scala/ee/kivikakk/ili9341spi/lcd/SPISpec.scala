package ee.kivikakk.ili9341spi.lcd

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class LCDSpec extends AnyFlatSpec {
  behavior.of("LCD")

  private val rand = new scala.util.Random

  def snd(c: LCD, bytes: Seq[Byte], rcvCnt: Int = 0): Unit = {
    for { (byte, byteIx) <- bytes.zipWithIndex } {
      c.clock.step()
      c.pins.clk.expect(false.B, s"snd pins.clk @ $byteIx")
      c.io.req.ready.expect(true.B)

      c.io.req.bits.data.poke((byte & 0xff).U)
      c.io.req.bits.dc.poke((byteIx == 0).B)
      c.io.req.bits.respLen
        .poke(if (byteIx == bytes.length - 1) rcvCnt.U else 0.U)
      c.io.req.valid.poke(true.B)

      for { bitIx <- 0 until 8 } {
        c.clock.step()
        c.io.req.ready.expect(false.B)
        c.pins.clk.expect(false.B)

        c.io.req.valid.poke(false.B)

        c.clock.step()
        c.pins.clk.expect(true.B)
        c.pins.copi
          .expect(
            (byte >> (7 - bitIx)) & 1,
            s"snd pins.copi @ $byteIx:$bitIx",
          )
        if (bitIx == 7)
          c.pins.dc.expect((byteIx == 0).B, s"snd pins.dc @ $byteIx")
      }
    }

    c.clock.step()
    c.pins.clk.expect(false.B)
    c.io.req.ready.expect((rcvCnt == 0).B)
  }

  def rcv(c: LCD, bytes: Seq[Byte]): Unit = {
    for { (byte, byteIx) <- bytes.zipWithIndex } {
      for { bitIx <- 0 until 8 } {
        c.pins.clk.expect(false.B, s"rcv pins.clk @ $byteIx:$bitIx")
        c.io.req.ready.expect(false.B, s"rcv req.ready @ $byteIx:$bitIx")

        c.pins.cipo.poke((byte >> (7 - bitIx)) & 1)

        c.clock.step()
        c.pins.clk.expect(true.B)

        c.clock.step()
      }

      c.io.resp.valid.expect(true.B, s"rcv resp.valid @ $byteIx")
      c.io.resp.bits.expect((byte & 0xff).U, s"rcv resp.bits @ $byteIx")
    }

    c.clock.step()
    c.pins.clk.expect(false.B, "rcv pins.clk @ end")
    c.io.req.ready.expect(true.B)
  }

  it should "transmit a 1-byte command packet" in {
    simulate(new LCD) { c =>
      snd(c, rand.nextBytes(1).toSeq)
    }
  }

  it should "transmit a 2-byte command packet" in {
    simulate(new LCD) { c =>
      snd(c, rand.nextBytes(2).toSeq)
    }
  }

  it should "read a 1-byte response to a command packet" in {
    simulate(new LCD) { c =>
      snd(c, rand.nextBytes(1).toSeq, 1)
      rcv(c, rand.nextBytes(1).toSeq)
    }
  }

  it should "read a 2-byte response to a command packet" in {
    simulate(new LCD) { c =>
      // TODO: optional cycle delay between send/receive. The datasheet is
      // ambiguous as to whether or not it should be just one cycle, or an
      // entire byte.
      snd(c, rand.nextBytes(1).toSeq, 2)
      rcv(c, rand.nextBytes(2).toSeq)
    }
  }
}
