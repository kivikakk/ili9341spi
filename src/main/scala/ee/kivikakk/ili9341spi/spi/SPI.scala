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
  val io = IO(new SPIIO)

  val pins = IO(new SPIPinsIO)
  pins.clk := false.B

  val dcReg = Reg(Bool())
  pins.dc := dcReg

  val srNext = Wire(UInt(8.W))
  val srReg  = RegEnable(srNext, pins.clk)
  srNext    := srReg(6, 0) ## pins.cipo
  pins.copi := srReg(7)

  io.req.ready  := false.B
  io.resp.bits  := 0.U
  io.resp.valid := false.B

  object State extends ChiselEnum {
    val sIdle, sSndLow, sSndHigh, sRcvLow, sRcvHigh = Value
  }
  val state = RegInit(State.sIdle)

  val bitRemReg     = Reg(UInt(3.W))
  val sndByteRemReg = Reg(UInt(4.W))

  switch(state) {
    is(State.sIdle) {
      io.req.ready := true.B
      when(io.req.valid) {
        srReg         := io.req.bits.cmd
        dcReg         := io.req.bits.dc
        bitRemReg     := 7.U
        sndByteRemReg := io.req.bits.respLen
        state         := State.sSndLow
      }
    }
    is(State.sSndLow) {
      state := State.sSndHigh
    }
    is(State.sSndHigh) {
      pins.clk  := true.B
      state     := State.sSndLow
      bitRemReg := bitRemReg - 1.U

      when(bitRemReg === 0.U) {
        bitRemReg     := 7.U
        sndByteRemReg := sndByteRemReg - 1.U
        dcReg         := false.B
        state         := Mux(sndByteRemReg =/= 0.U, State.sRcvLow, State.sIdle)
      }
    }
    is(State.sRcvLow) {
      state := State.sRcvHigh
    }
    is(State.sRcvHigh) {
      pins.clk  := true.B
      state     := State.sRcvLow
      bitRemReg := bitRemReg - 1.U

      when(bitRemReg === 0.U) {
        // TODO: to test. Is it more expensive to use srNext here, instead of
        // waiting an extra cycle and setting bits from srReg instead?
        io.resp.bits  := srNext
        io.resp.valid := true.B
        bitRemReg     := 7.U
        sndByteRemReg := sndByteRemReg - 1.U

        state := Mux(sndByteRemReg =/= 0.U, State.sRcvLow, State.sIdle)
      }
    }
  }
}
