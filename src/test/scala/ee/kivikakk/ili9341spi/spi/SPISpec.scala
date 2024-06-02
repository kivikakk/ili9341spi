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

  it should "transmit a command packet and read a byte back" in {
    simulate(new SPI) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)

      c.clock.step()
      c.io.req.ready.expect(true.B)

      c.io.req.bits.cmd.poke("b_0110_1010".U)
      c.io.req.bits.cmdRem.poke(0.U)
      c.io.req.bits.respLen.poke(0.U)
      c.io.req.valid.poke(true.B)

      c.clock.step()
      c.io.req.ready.expect(false.B)
      c.pins.clk.expect(false.B)

      for { (bit, ix) <- Seq(0, 1, 1, 0, 1, 0, 1, 0).zipWithIndex } {
        c.clock.step()
        c.pins.clk.expect(true.B)
        c.pins.copi.expect(bit)
        if (ix == 7) {
          c.pins.dc.expect(true.B)
        }

        c.clock.step()
        c.pins.clk.expect(false.B)
      }

      c.io.req.ready.expect(true.B)

    }
  }
}
