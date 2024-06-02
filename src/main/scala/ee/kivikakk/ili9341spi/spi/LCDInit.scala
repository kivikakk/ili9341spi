package ee.kivikakk.ili9341spi.spi

import chisel3._
import ee.kivikakk.ili9341spi.spi.LCDCommand._

import scala.collection.mutable

object LCDInit {
  val sequence: Seq[(LCDCommand.Type, Seq[UInt])] = Seq(
    (POWER_CTRL_A, Seq(0x39.U, 0x2c.U, 0x00.U, 0x34.U, 0x02.U)),
    (POWER_CTRL_B, Seq(0x00.U, 0xc1.U, 0x30.U)),
    (DRIVER_TIMING_CTRL_A, Seq(0x85.U, 0x00.U, 0x78.U)),
    (DRIVER_TIMING_CTRL_B, Seq(0x00.U, 0x00.U)),
    (POWER_ON_SEQ_CTRL, Seq(0x64.U, 0x03.U, 0x12.U, 0x81.U)),
    (PUMP_RATIO_CTRL, Seq(0x20.U)),
    (POWER_CTRL_1, Seq(0x23.U)),
    (POWER_CTRL_2, Seq(0x10.U)),
    (VCOM_CTRL_1, Seq(0x3e.U, 0x28.U)),
    (VCOM_CTRL_2, Seq(0x86.U)),
    (MEMORY_ACCESS_CTRL, Seq(0x48.U)),
    (COLMOD, Seq(0x55.U)),
    (FRAME_RATE_CTRL, Seq(0x00.U, 0x18.U)),
    (DISPLAY_FN_CTRL, Seq(0x08.U, 0x82.U, 0x27.U)),
    (ENABLE_3G, Seq(0x00.U)),
    (GAMMA_SET, Seq(0x01.U)),
    (
      POS_GAMMA_CORRECTION,
      Seq(0x0f.U, 0x31.U, 0x2b.U, 0x0c.U, 0x0e.U, 0x08.U, 0x4e.U, 0xf1.U,
        0x37.U, 0x07.U, 0x10.U, 0x03.U, 0x0e.U, 0x09.U, 0x00.U),
    ),
    (
      NEG_GAMMA_CORRECTION,
      Seq(0x00.U, 0x0e.U, 0x14.U, 0x03.U, 0x11.U, 0x07.U, 0x31.U, 0xc1.U,
        0x48.U, 0x08.U, 0x0f.U, 0x0c.U, 0x31.U, 0x36.U, 0x0f.U),
    ),
    (SLEEP_OUT, Seq()),
    (NOP, Seq()), // stand-in that means "wait 120ms"
    (DISPLAY_ON, Seq()),
  )

  val rom: Seq[UInt] = {
    val rom = mutable.ArrayBuffer[UInt]()
    for { (cmd, params) <- sequence } {
      rom  += cmd.asUInt
      rom  += params.length.U
      rom ++= params
    }
    rom.toSeq
  }
}
