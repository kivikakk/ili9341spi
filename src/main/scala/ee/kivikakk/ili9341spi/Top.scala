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

class IliIO extends Bundle {
  val clk  = Output(Bool())
  val copi = Output(Bool())
  val res  = Output(Bool())
  val dc   = Output(Bool())
  val blk  = Output(Bool())
  val cipo = Input(Bool())
}

class Top(implicit platform: Platform) extends Module {
  override def desiredName = "ili9341spi"

  val ili    = Wire(new IliIO)
  val resReg = RegInit(true.B) // start with reset on.
  ili.res := resReg
  ili.blk := true.B

  val lcd = Module(new Lcd)
  ili.clk       := lcd.pins.clk
  ili.copi      := lcd.pins.copi
  ili.dc        := lcd.pins.dc
  lcd.pins.cipo := ili.cipo // FF sync?

  lcd.io.req.noenq()
  lcd.io.resp.nodeq()

  val uart = Module(new Uart(baud = 115200))
  uart.io.rx.nodeq()
  uart.io.tx :<>= lcd.io.resp

  object State extends ChiselEnum {
    val sResetApply, sResetWait, sInitCmd, sInitParam, sWriteImg = Value
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
  val pngRomLen    = LcdInit.pngrom.length
  val pngRomOffReg = Reg(UInt(unsignedBitLength(pngRomLen).W))
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
        val req = Wire(new LcdRequest())
        req.data    := initRom(initRomIxReg)
        req.dc      := true.B
        req.respLen := 0.U
        lcd.io.req.enq(req)

        when(lcd.io.req.fire) {
          initRomIxReg  := initRomIxReg + 2.U
          initCmdRemReg := initRom(initRomIxReg + 1.U)

          state := State.sInitParam

          when(initRom(initRomIxReg) === 0.U) {
            resTimerReg := resetWaitCyc.U
            state       := State.sResetWait
          }
        }
      }.otherwise {
        state        := State.sWriteImg
        pngRomOffReg := 0.U
      }
    }
    is(State.sInitParam) {
      when(initCmdRemReg =/= 0.U) {
        val req = Wire(new LcdRequest)
        req.data    := initRom(initRomIxReg)
        req.dc      := false.B
        req.respLen := 0.U
        lcd.io.req.enq(req)

        when(lcd.io.req.fire) {
          initRomIxReg  := initRomIxReg + 1.U
          initCmdRemReg := initCmdRemReg - 1.U
        }
      }.otherwise {
        uart.io.tx.enq(0xff.U)
        state := State.sInitCmd
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

      // val bb = IO(Flipped(new IliIO))
      // ili :<>= bb

      val uart_rx = IO(Input(Bool()))
      uart.pins.rx := uart_rx

    case _ =>
  }
}
