package ee.kivikakk.ili9341spi.spi

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import ee.hrzn.chryse.platform.Platform
import org.scalatest.flatspec.AnyFlatSpec

class SPISpec extends AnyFlatSpec {
  behavior.of("SPI")

  implicit val plat: Platform = new Platform {
    val id      = "topspec"
    val clockHz = 1_000_000
  }

  private val rand = new scala.util.Random

  def xfr(c: SPI, bytes: Seq[Byte], readCnt: Int = 0): Unit = {
    for { (byte, byteIx) <- bytes.zipWithIndex } {
      c.clock.step()
      c.pins.clk.expect(false.B, s"clk start byte $byteIx")
      c.io.req.ready.expect(true.B)

      c.io.req.bits.cmd.poke((byte & 0xff).U)
      c.io.req.bits.dc.poke((byteIx == 0).B)
      c.io.req.bits.respLen
        .poke(if (byteIx == bytes.length - 1) readCnt.U else 0.U)
      c.io.req.valid.poke(true.B)

      for { bitIx <- 0 until 8 } {
        c.clock.step()
        c.io.req.ready.expect(false.B)
        c.pins.clk.expect(false.B)

        c.io.req.valid.poke(false.B)

        c.clock.step()
        c.pins.clk.expect(true.B)
        c.pins.copi
          .expect((byte >> (7 - bitIx)) & 1, s"copi byte $byteIx bit $bitIx")
        if (bitIx == 7)
          c.pins.dc.expect((byteIx == 0).B, s"dc byte $byteIx")
      }
    }

    c.clock.step()
    c.pins.clk.expect(false.B)
    c.io.req.ready.expect((readCnt == 0).B)
  }

  it should "transmit a 1-byte command packet" in {
    simulate(new SPI) { c =>
      xfr(c, rand.nextBytes(1).toSeq)
    }
  }

  it should "transmit a 2-byte command packet" in {
    simulate(new SPI) { c =>
      xfr(c, rand.nextBytes(2).toSeq)
    }
  }

  it should "read a response to a command packet" in {
    simulate(new SPI) { c =>
      xfr(c, rand.nextBytes(1).toSeq, 1)
    }
  }
}
