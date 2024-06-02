package ee.kivikakk.ili9341spi.spi

import chisel3._

object SPICommand {
  val NOP                 = 0x01.U(8.W)
  val SOFTWARE_RESET      = 0x01.U(8.W)
  val READ_DISPLAY_ID     = 0x04.U(8.W)
  val READ_DISPLAY_STATUS = 0x09.U(8.W)

  val DISPLAY_OFF  = 0x28.U(8.W)
  val DISPLAY_ON   = 0x29.U(8.W)
  val CASET        = 0x2a.U(8.W)
  val PASET        = 0x2b.U(8.W)
  val MEMORY_WRITE = 0x2c.U(8.W)

  val READ_ID1 = 0xda.U(8.W)
  val READ_ID2 = 0xdb.U(8.W)
  val READ_ID3 = 0xdc.U(8.W)
}
