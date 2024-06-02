package ee.kivikakk.ili9341spi

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import ee.hrzn.athena.uart.UART
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CXXRTLPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform
import ee.kivikakk.ili9341spi.spi.SPI
import ee.kivikakk.ili9341spi.spi.SPICommand
import ee.kivikakk.ili9341spi.spi.SPIRequest

class ILIIO extends Bundle {
  val clk  = Output(Bool())
  val copi = Output(Bool())
  val res  = Output(Bool())
  val dc   = Output(Bool())
  val blk  = Output(Bool())
  val cipo = Input(Bool())
}

class Top(implicit platform: Platform) extends Module {
  override def desiredName = "ili9341spi"

  val ili = Wire(new ILIIO)
  ili.res := false.B
  ili.blk := true.B

  val spi = Module(new SPI)
  ili.clk       := spi.pins.clk
  ili.copi      := spi.pins.copi
  ili.dc        := spi.pins.dc
  spi.pins.cipo := ili.cipo // FF sync?

  spi.io.req.bits   := new SPIRequest().Lit()
  spi.io.req.valid  := false.B
  spi.io.resp.ready := false.B

  val uart = Module(new UART)
  uart.io.rx.ready := false.B
  uart.io.tx.bits  := 0.U
  uart.io.tx.valid := false.B

  val fire = WireInit(false.B)

  when(uart.io.rx.valid) {
    uart.io.rx.ready := true.B
    fire             := true.B
  }

  object State extends ChiselEnum {
    val sIdle, sFire = Value
  }
  val state = RegInit(State.sIdle)

  switch(state) {
    is(State.sIdle) {
      state := Mux(fire, State.sFire, State.sIdle)
    }
    is(State.sFire) {
      when(spi.io.req.ready) {
        spi.io.req.bits := new SPIRequest().Lit(
          _.cmd     -> SPICommand.READ_DISPLAY_ID,
          _.dc      -> true.B,
          _.respLen -> 3.U,
        )
        spi.io.req.valid := true.B
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

    case plat: CXXRTLPlatform =>
      val io = IO(new ILIIO)
      io :<>= ili

    case _ =>
  }
}
