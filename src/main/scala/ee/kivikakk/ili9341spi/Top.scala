package ee.kivikakk.ili9341spi

import chisel3._
import chisel3.util._
import ee.hrzn.athena.uart.Uart
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CxxrtlPlatform
import ee.hrzn.chryse.platform.ecp5.Ulx3SPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform
import ee.kivikakk.ili9341spi.lcd.Lcd
import ee.kivikakk.ili9341spi.lcd.LcdInit
import ee.kivikakk.ili9341spi.lcd.LcdRequest

private class IliIO extends Bundle {
  val clk  = Output(Bool())
  val copi = Output(Bool())
  val res  = Output(Bool())
  val dc   = Output(Bool())
  val blk  = Output(Bool())
  val cipo = Input(Bool())
}

class Top(implicit platform: Platform) extends Module {
  override def desiredName = "ili9341spi"

  private val ili = Wire(new IliIO)
  ili.res := false.B
  ili.blk := true.B

  private val lcd = Module(new Lcd)
  ili.clk       := lcd.pins.clk
  ili.copi      := lcd.pins.copi
  ili.dc        := lcd.pins.dc
  lcd.pins.cipo := ili.cipo // FF sync?

  lcd.io.req.noenq()
  lcd.io.resp.nodeq()

  private val uart = Module(new Uart(baud = 115200))
  uart.io.rx.nodeq()
  uart.io.tx :<>= lcd.io.resp

  object State extends ChiselEnum {
    val sInit, sWriteImg = Value
  }
  private val state = RegInit(State.sInit)

  private val initter = Module(new Initter)
  initter.io.lcd <> DontCare

  private val pngRomLen    = LcdInit.pngrom.length
  private val pngRomOffReg = Reg(UInt(unsignedBitLength(pngRomLen).W))

  switch(state) {
    is(State.sInit) {
      initter.io.lcd :<>= lcd.io
      ili.res := initter.io.res
      when(initter.io.done) {
        state := State.sWriteImg
      }
    }

    is(State.sWriteImg) {
      val data = uart.io.rx.deq()
      when(uart.io.rx.fire && !uart.io.rx.bits.err) {
        val req = Wire(new LcdRequest)
        req.data    := data.byte
        req.dc      := false.B
        req.respLen := 0.U
        lcd.io.req.enq(req)

        pngRomOffReg := pngRomOffReg + 1.U
      }
    }
  }

  platform match {
    case plat: IceBreakerPlatform =>
      plat.resources.pmod1a(3).o := ili.clk.asBool
      plat.resources.pmod1a(4).o := ili.copi
      plat.resources.pmod1a(7).o := ~ili.res
      plat.resources.pmod1a(8).o := ~ili.dc
      plat.resources.pmod1a(9).o := ili.blk
      ili.cipo                   := plat.resources.pmod1a(10).i

      plat.resources.uart.tx := uart.pins.tx
      uart.pins.rx           := plat.resources.uart.rx

      plat.resources.pmod2(1).o := plat.resources.uart.rx

      plat.resources.ledg := state === State.sWriteImg

    case plat: Ulx3SPlatform =>
      ili.cipo := false.B

      plat.resources.uart.tx := uart.pins.tx
      uart.pins.rx           := plat.resources.uart.rx

    case plat: CxxrtlPlatform =>
      val bb = IO(new IliIO)
      bb :<>= ili

      val uart_rx = IO(Input(Bool()))
      uart.pins.rx := uart_rx

    case _ =>
  }
}
