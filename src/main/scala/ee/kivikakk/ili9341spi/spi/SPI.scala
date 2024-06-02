package ee.kivikakk.ili9341spi.spi

import chisel3._
import chisel3.util._

class SPIPinsIO extends Bundle {
  val clk  = Output(Bool())
  val copi = Output(Bool())
  val cipo = Input(Bool())
  val dc   = Output(Bool())
}

class SPIRequest extends Bundle {
  val cmd     = UInt(8.W)
  val dc      = Bool()
  val respLen = UInt(4.W)
}

class SPIIO extends Bundle {
  val req  = Flipped(Decoupled(new SPIRequest))
  val resp = Decoupled(UInt(8.W))
}

class SPI extends Module {
  val outSr = Reg(UInt(8.W))
  val dcReg = Reg(Bool())

  val io   = IO(new SPIIO)
  val pins = IO(new SPIPinsIO)

  pins.clk  := false.B
  pins.copi := outSr(7)
  pins.dc   := dcReg

  io.req.ready  := false.B
  io.resp.bits  := 0.U
  io.resp.valid := false.B

  object State extends ChiselEnum {
    val sIdle, sLow, sHigh = Value
  }
  val state = RegInit(State.sIdle)

  val outBitRemReg   = Reg(UInt(3.W))
  val respByteRemReg = Reg(UInt(4.W))

  switch(state) {
    is(State.sIdle) {
      io.req.ready := true.B
      when(io.req.valid) {
        outSr          := io.req.bits.cmd
        dcReg          := io.req.bits.dc
        outBitRemReg   := 7.U
        respByteRemReg := io.req.bits.respLen
        state          := State.sLow
      }
    }
    is(State.sLow) {
      state := State.sHigh
    }
    is(State.sHigh) {
      pins.clk     := true.B
      state        := State.sLow
      outSr        := outSr(6, 0) ## 0.U(1.W)
      outBitRemReg := outBitRemReg - 1.U

      when(
        outBitRemReg === 0.U &
          respByteRemReg === 0.U,
      ) {
        state := State.sIdle
      }
    }
  }
}
