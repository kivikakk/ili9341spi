package ee.kivikakk.ili9341spi.lcd

import chisel3._
import chisel3.util._

class LCDPinsIO extends Bundle {
  val clk  = Output(Bool())
  val copi = Output(Bool())
  val cipo = Input(Bool())
  val dc   = Output(Bool())
}

class LCDRequest extends Bundle {
  val data    = UInt(8.W)
  val dc      = Bool()
  val respLen = UInt(4.W)
}

class LCDIO extends Bundle {
  val req  = Flipped(Decoupled(new LCDRequest))
  val resp = Decoupled(UInt(8.W))
}

class LCD extends Module {
  val io = IO(new LCDIO)

  val pins = IO(new LCDPinsIO)
  pins.clk := false.B

  val dcReg = Reg(Bool())
  pins.dc := dcReg

  val srNext = Wire(UInt(8.W))
  val srReg  = RegEnable(srNext, pins.clk)
  srNext    := srReg(6, 0) ## pins.cipo
  pins.copi := srReg(7)

  io.req.nodeq()
  io.resp.noenq()

  object State extends ChiselEnum {
    val sIdle, sSndLow, sSndHigh, sRcvLow, sRcvHigh, sRcvDone = Value
  }
  val state = RegInit(State.sIdle)

  val bitRemReg     = Reg(UInt(3.W))
  val rcvByteRemReg = Reg(UInt(4.W))

  switch(state) {
    is(State.sIdle) {
      val req = io.req.deq()
      when(io.req.fire) {
        srReg         := req.data
        dcReg         := req.dc
        bitRemReg     := 7.U
        rcvByteRemReg := req.respLen
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
        rcvByteRemReg := rcvByteRemReg - 1.U
        dcReg         := false.B
        state         := Mux(rcvByteRemReg =/= 0.U, State.sRcvLow, State.sIdle)
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
        bitRemReg := 7.U
        state     := State.sRcvDone
      }
    }
    is(State.sRcvDone) {
      io.resp.enq(srReg)
      rcvByteRemReg := rcvByteRemReg - 1.U

      when(rcvByteRemReg === 0.U) {
        state := State.sIdle
      }.otherwise {
        state := State.sRcvHigh
      }
    }
  }
}
