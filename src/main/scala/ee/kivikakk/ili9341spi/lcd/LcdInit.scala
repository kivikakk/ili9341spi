package ee.kivikakk.ili9341spi.lcd

import chisel3._

import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import scala.collection.mutable

import LcdCommand._

object LcdInit {
  val sequence: Seq[(LcdCommand.Type, Seq[UInt])] = Seq(
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
    (MEMORY_ACCESS_CTRL, Seq(0x28.U)),
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
    (CASET, Seq(0x00.U, 0x00.U, 0x01.U, 0x3f.U)),
    (PASET, Seq(0x00.U, 0x00.U, 0x00.U, 0xef.U)),
    (SLEEP_OUT, Seq()),
    (NOP, Seq()), // stand-in that means "wait 120ms"
    (DISPLAY_ON, Seq()),
  )

  lazy val rom: Seq[UInt] = {
    val rom = mutable.ArrayBuffer[UInt]()
    for { (cmd, params) <- sequence } {
      rom  += cmd.asUInt
      rom  += params.length.U
      rom ++= params
    }
    rom.toSeq
  }

  val pngrom: Array[Byte] = {
    val png    = ImageIO.read(new File("jjr.png"))
    val nyonks = mutable.ArrayBuffer[Byte]()
    for {
      y <- 0 until 240
      x <- 0 until 320
    } {
      val rgb = new Color(png.getRGB(x, y))
      // 565
      nyonks.append(((rgb.getRed() & 0xf8) | (rgb.getGreen() >> 5)).toByte)
      nyonks.append(
        (((rgb.getGreen() & 0x1c) << 3) | (rgb.getBlue() >> 3)).toByte,
      )
    }
    nyonks.toArray
  }
}
