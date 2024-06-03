package ee.kivikakk.ili9341spi

import chisel3._
import chisel3.util._
import ee.hrzn.athena.uart.UART
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CXXRTLPlatform
import ee.hrzn.chryse.platform.ecp5.ULX3SPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform
import ee.kivikakk.ili9341spi.spi.LCDInit
import ee.kivikakk.ili9341spi.spi.SPI

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

  val ili    = Wire(new ILIIO)
  val resReg = RegInit(true.B) // start with reset on.
  ili.res := resReg
  ili.blk := true.B

  val spi = Module(new SPI)
  ili.clk       := spi.pins.clk
  ili.copi      := spi.pins.copi
  ili.dc        := spi.pins.dc
  spi.pins.cipo := ili.cipo // FF sync?

  spi.io.req.bits.data    := 0.U
  spi.io.req.bits.dc      := false.B
  spi.io.req.bits.respLen := 0.U
  spi.io.req.valid        := false.B
  spi.io.resp.ready       := false.B

  val uart = Module(new UART)
  uart.io.rx.ready := false.B
  uart.io.tx :<>= spi.io.resp

  val fire = RegInit(0.U(8.W))
  uart.io.rx.ready := fire === 0.U
  when(uart.io.rx.fire && ~uart.io.rx.bits.err) {
    fire := uart.io.rx.bits.byte
  }

  object State extends ChiselEnum {
    val sResetApply, sResetWait, sInit, sInitParam, sIdle = Value
  }
  val state         = RegInit(State.sResetApply)
  val resetApplyCyc = 11 * platform.clockHz / 1_000_000      // tRW_min = 10µs
  val resetWaitCyc  = 121_000 * platform.clockHz / 1_000_000 // tRT_max = 120ms
  val resTimerReg   = RegInit(resetApplyCyc.U(unsignedBitLength(resetWaitCyc).W))
  val initRomLen    = LCDInit.rom.length
  val initRomIxReg  = RegInit(0.U(unsignedBitLength(initRomLen).W))
  val initCmdRemReg = Reg(
    UInt(unsignedBitLength(LCDInit.sequence.map(_._2.length).max).W),
  )
  // We spend quite a few cells on this. TODO (Chryse): BRAM init.
  // Cbf putting every initted memory on SPI flash.
  val rom = VecInit(LCDInit.rom)

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
        state := State.sInit
      }
    }
    is(State.sInit) {
      when(uart.io.tx.ready) {
        when(initRomIxReg =/= initRomLen.U) {
          initRomIxReg         := initRomIxReg + 2.U
          initCmdRemReg        := rom(initRomIxReg + 1.U)
          spi.io.req.bits.data := rom(initRomIxReg)
          spi.io.req.bits.dc   := true.B
          spi.io.req.valid     := true.B
          uart.io.tx.bits      := rom(initRomIxReg)
          uart.io.tx.valid     := true.B
          state                := State.sInitParam

          when(rom(initRomIxReg) === 0.U) {
            resTimerReg := resetWaitCyc.U
            state       := State.sResetWait
          }
        }.otherwise {
          state := State.sIdle
        }
      }
    }
    is(State.sInitParam) {
      when(spi.io.req.ready & uart.io.tx.ready) {
        when(initCmdRemReg =/= 0.U) {
          initRomIxReg         := initRomIxReg + 1.U
          initCmdRemReg        := initCmdRemReg - 1.U
          spi.io.req.bits.data := rom(initRomIxReg)
          spi.io.req.valid     := true.B
          uart.io.tx.bits      := rom(initRomIxReg)
          uart.io.tx.valid     := true.B
        }.otherwise {
          uart.io.tx.bits  := 0xff.U
          uart.io.tx.valid := true.B
          state            := State.sInit
        }
      }
    }
    is(State.sIdle) {
      when(fire === 0xfe.U) {
        resReg           := true.B
        uart.io.tx.bits  := 0x01.U
        uart.io.tx.valid := true.B
        fire             := 0.U
      }
      when(fire === 0xfd.U) {
        resReg           := false.B
        uart.io.tx.bits  := 0x00.U
        uart.io.tx.valid := true.B
        fire             := 0.U
      }
      when(fire =/= 0.U && fire < 0xfd.U && spi.io.req.ready) {
        spi.io.req.bits.data    := fire
        spi.io.req.bits.dc      := true.B
        spi.io.req.bits.respLen := 2.U
        spi.io.req.valid        := true.B

        uart.io.tx.bits  := fire
        uart.io.tx.valid := true.B

        fire := 0.U
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

    case plat: ULX3SPlatform =>
      ili.cipo     := false.B
      uart.pins.rx := false.B

    case plat: CXXRTLPlatform =>
      val io = IO(new ILIIO)
      io :<>= ili

      uart.pins.rx := false.B

    case _ =>
  }
}
