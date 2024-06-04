package ee.kivikakk.ili9341spi

import chisel3._
import chisel3.util._
import ee.hrzn.chryse.platform.Platform

class SPIFlashReaderIO extends Bundle {
  val req = Flipped(Decoupled(new Bundle {
    val addr = Output(UInt(24.W))
    val len  = Output(UInt(16.W))
  }))
  val resp = Decoupled(UInt(8.W))
}

class SPIPinsIO extends Bundle {
  val copi  = Output(Bool())
  val cipo  = Input(Bool())
  val cs    = Output(Bool())
  val clock = Output(Bool())
}

class SPIFlashReader(implicit platform: Platform) extends Module {
  val io   = IO(new SPIFlashReaderIO)
  val pins = IO(new SPIPinsIO)

  // tRES1 (/CS High to Standby Mode without ID Read) and tDP (/CS High to
  // Power-down Mode) are both max 3us.
  val TRES1_TDP_CYCLES =
    (platform.clockHz.toDouble * 3.0 / 1_000_000.0).toInt + 1

  val srReg = RegInit(0.U(32.W))
  val sndBitRemReg = RegInit(
    0.U(unsignedBitLength(Seq(32, TRES1_TDP_CYCLES).max - 1).W),
  )
  val rcvBitRemReg  = RegInit(0.U(unsignedBitLength(7).W))
  val rcvByteRemReg = RegInit(0.U(16.W))

  val csReg = RegInit(false.B)
  pins.cs := csReg

  pins.copi    := srReg(31)
  pins.clock   := csReg & ~clock.asBool
  io.resp.bits := srReg(7, 0)
  val respValid = RegInit(false.B)
  respValid     := false.B
  io.resp.valid := respValid

  object State extends ChiselEnum {
    val sIdle, sPowerDownRelease, sWaitTres1, sSendCmd, sReceiving, sPowerDown =
      Value
  }
  val state = RegInit(State.sIdle)
  io.req.ready := state === State.sIdle
  switch(state) {
    is(State.sIdle) {
      when(io.req.valid) {
        csReg        := true.B
        srReg        := "h_ab00_0000".U(32.W)
        sndBitRemReg := 31.U
        state        := State.sPowerDownRelease
      }
    }
    is(State.sPowerDownRelease) {
      sndBitRemReg := sndBitRemReg - 1.U
      srReg        := srReg(30, 0) ## 1.U(1.W)
      when(sndBitRemReg === 0.U) {
        csReg        := false.B
        sndBitRemReg := (TRES1_TDP_CYCLES - 1).U
        state        := State.sWaitTres1
      }
    }
    is(State.sWaitTres1) {
      sndBitRemReg := sndBitRemReg - 1.U
      when(sndBitRemReg === 0.U) {
        csReg         := true.B
        srReg         := 0x03.U(8.W) ## io.req.bits.addr
        sndBitRemReg  := 31.U
        rcvBitRemReg  := 7.U
        rcvByteRemReg := io.req.bits.len - 1.U
        state         := State.sSendCmd
      }
    }
    is(State.sSendCmd) {
      sndBitRemReg := sndBitRemReg - 1.U
      srReg        := srReg(30, 0) ## 1.U(1.W)
      when(sndBitRemReg === 0.U) {
        state := State.sReceiving
      }
    }
    is(State.sReceiving) {
      rcvBitRemReg := rcvBitRemReg - 1.U
      srReg        := srReg(30, 0) ## pins.cipo
      when(rcvBitRemReg === 0.U) {
        rcvByteRemReg := rcvByteRemReg - 1.U
        rcvBitRemReg  := 7.U
        respValid     := true.B
        when(rcvByteRemReg === 0.U) {
          csReg        := false.B
          sndBitRemReg := (TRES1_TDP_CYCLES - 1).U
          state        := State.sPowerDown
        }.otherwise {
          state := State.sReceiving
        }
      }
    }
    is(State.sPowerDown) {
      sndBitRemReg := sndBitRemReg - 1.U
      when(sndBitRemReg === 0.U) {
        state := State.sIdle
      }
    }
  }
}
