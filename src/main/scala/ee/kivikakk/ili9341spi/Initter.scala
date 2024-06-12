package ee.kivikakk.ili9341spi

import chisel3._
import chisel3.util._
import ee.hrzn.chryse.platform.Platform
import ee.kivikakk.ili9341spi.lcd.LcdIO
import ee.kivikakk.ili9341spi.lcd.LcdInit
import ee.kivikakk.ili9341spi.lcd.LcdRequest

class Initter(implicit platform: Platform) extends Module {
  val io = IO(new Bundle {
    val lcd  = Flipped(new LcdIO)
    val res  = Output(Bool())
    val done = Output(Bool())
  })

  io.done := false.B

  val resReg = RegInit(true.B) // start with reset on.
  io.res := resReg

  io.lcd.req.noenq()
  io.lcd.resp.nodeq()

  object State extends ChiselEnum {
    val sResetApply, sResetWait, sInitCmd, sInitParam, sDone = Value
  }

  val state         = RegInit(State.sResetApply)
  val resetApplyCyc = 11 * platform.clockHz / 1_000_000      // tRW_min = 10µs
  val resetWaitCyc  = 121_000 * platform.clockHz / 1_000_000 // tRT_max = 120ms
  val resTimerReg   = RegInit(resetApplyCyc.U(unsignedBitLength(resetWaitCyc).W))
  val initRomLen    = LcdInit.rom.length
  val initRomIxReg  = RegInit(0.U(unsignedBitLength(initRomLen).W))
  val initCmdRemReg = Reg(
    UInt(unsignedBitLength(LcdInit.sequence.map(_._2.length).max).W),
  )
  // We spend quite a few cells on this. TODO (Chryse): BRAM init.
  // Cbf putting every tiny initted memory on SPI flash.
  val initRom = VecInit(LcdInit.rom)

  switch(state) {
    is(State.sResetApply) {
      resTimerReg := resTimerReg - 1.U
      when(resTimerReg === 0.U) {
        resReg      := false.B
        resTimerReg := resetWaitCyc.U
        state       := State.sResetWait
      }
    }
    is(State.sResetWait) {
      resTimerReg := resTimerReg - 1.U
      when(resTimerReg === 0.U) {
        state := State.sInitCmd
      }
    }
    is(State.sInitCmd) {
      when(initRomIxReg =/= initRomLen.U) {
        val req = Wire(new LcdRequest)
        req.data    := initRom(initRomIxReg)
        req.dc      := true.B
        req.respLen := 0.U
        io.lcd.req.enq(req)

        when(io.lcd.req.fire) {
          initRomIxReg  := initRomIxReg + 2.U
          initCmdRemReg := initRom(initRomIxReg + 1.U)

          state := State.sInitParam

          when(initRom(initRomIxReg) === 0.U) {
            resTimerReg := resetWaitCyc.U
            state       := State.sResetWait
          }
        }
      }.otherwise {
        state := State.sDone
      }
    }
    is(State.sInitParam) {
      when(initCmdRemReg =/= 0.U) {
        val req = Wire(new LcdRequest)
        req.data    := initRom(initRomIxReg)
        req.dc      := false.B
        req.respLen := 0.U
        io.lcd.req.enq(req)

        when(io.lcd.req.fire) {
          initRomIxReg  := initRomIxReg + 1.U
          initCmdRemReg := initCmdRemReg - 1.U
        }
      }.otherwise {
        state := State.sInitCmd
      }
    }
    is(State.sDone) {
      io.done := true.B
    }
  }
}
