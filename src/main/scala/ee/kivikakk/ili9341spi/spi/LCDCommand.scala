package ee.kivikakk.ili9341spi.spi

import chisel3._

object LCDCommand extends ChiselEnum {
  val NOP                 = Value(0x00.U)
  val SOFTWARE_RESET      = Value(0x01.U)
  val READ_DISPLAY_ID     = Value(0x04.U)
  val READ_DISPLAY_STATUS = Value(0x09.U)
  val SLEEP_IN            = Value(0x10.U)
  val SLEEP_OUT           = Value(0x11.U)
  val GAMMA_SET           = Value(0x26.U)
  val DISPLAY_OFF         = Value(0x28.U)
  val DISPLAY_ON          = Value(0x29.U)
  val CASET               = Value(0x2a.U)
  val PASET               = Value(0x2b.U)
  val MEMORY_WRITE        = Value(0x2c.U)
  val MEMORY_ACCESS_CTRL  = Value(0x36.U)
  val COLMOD              = Value(0x3a.U)
  val READ_ID1            = Value(0xda.U)
  val READ_ID2            = Value(0xdb.U)
  val READ_ID3            = Value(0xdc.U)

  // Level 2 commands
  val FRAME_RATE_CTRL      = Value(0xb1.U)
  val DISPLAY_FN_CTRL      = Value(0xb6.U)
  val POWER_CTRL_1         = Value(0xc0.U)
  val POWER_CTRL_2         = Value(0xc1.U)
  val VCOM_CTRL_1          = Value(0xc5.U)
  val VCOM_CTRL_2          = Value(0xc7.U)
  val POWER_CTRL_A         = Value(0xcb.U)
  val POWER_CTRL_B         = Value(0xcf.U)
  val POS_GAMMA_CORRECTION = Value(0xe0.U)
  val NEG_GAMMA_CORRECTION = Value(0xe1.U)
  val DRIVER_TIMING_CTRL_A = Value(0xe8.U)
  val DRIVER_TIMING_CTRL_B = Value(0xea.U)
  val POWER_ON_SEQ_CTRL    = Value(0xed.U)
  val ENABLE_3G            = Value(0xf2.U)
  val PUMP_RATIO_CTRL      = Value(0xf7.U)

  val UNK = Value(0xff.U)
}
