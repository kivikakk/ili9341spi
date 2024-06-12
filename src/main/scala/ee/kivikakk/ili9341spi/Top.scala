package ee.kivikakk.ili9341spi

import chisel3._
import chisel3.util._
import ee.hrzn.athena.uart.Uart
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CxxrtlPlatform
import ee.hrzn.chryse.platform.ecp5.Ulx3SPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform
import ee.kivikakk.ili9341spi.lcd.Lcd
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

  ////

  object State extends ChiselEnum {
    val sInit, sRender, sRender2, sWaitPress, sProgress, sTransition = Value
  }
  private val state = RegInit(State.sInit)

  private val initter = Module(new Initter)
  initter.io.lcd <> DontCare

  private val LCD_WIDTH   = 320
  private val LCD_HEIGHT  = 240
  private val GOL_SIZE    = 20
  private val GOL_WIDTH   = LCD_WIDTH / GOL_SIZE
  private val GOL_HEIGHT  = LCD_HEIGHT / GOL_SIZE
  private val GOL_CELLCNT = GOL_WIDTH * GOL_HEIGHT

  private val start = """................
                        |................
                        |................
                        |................
                        |................
                        |...........#....
                        |...###.....#....
                        |...........#....
                        |................
                        |................
                        |................
                        |................
                        |""".stripMargin.replace("\n", "")

  private val golCells = RegInit(VecInit(for {
    i <- 0 until GOL_CELLCNT
  } yield (start(i) != '.').B))
  private val golCellsNext = Reg(Vec(GOL_CELLCNT, Bool()))

  private val colReg = RegInit(0.U(unsignedBitLength(LCD_WIDTH - 1).W))
  private val pagReg = RegInit(0.U(unsignedBitLength(LCD_HEIGHT - 1).W))

  private def cellIxAt(col: UInt, pag: UInt): UInt =
    col + (pag * GOL_WIDTH.U)

  private def cellValAt(col: UInt, pag: UInt): Bool =
    golCells(cellIxAt(col, pag))

  private val cellCol = colReg / GOL_SIZE.U
  private val cellPag = pagReg / GOL_SIZE.U

  private val neighboursAlive = Wire(UInt(unsignedBitLength(8).W))
  neighboursAlive :=
    PopCount(
      Seq(
        cellValAt(colReg - 1.U, pagReg - 1.U),
        cellValAt(colReg + 0.U, pagReg - 1.U),
        cellValAt(colReg + 1.U, pagReg - 1.U),
        //
        cellValAt(colReg - 1.U, pagReg + 0.U),
        cellValAt(colReg + 1.U, pagReg + 0.U),
        //
        cellValAt(colReg - 1.U, pagReg + 1.U),
        cellValAt(colReg + 0.U, pagReg + 1.U),
        cellValAt(colReg + 1.U, pagReg + 1.U),
      ),
    )

  switch(state) {
    is(State.sInit) {
      initter.io.lcd :<>= lcd.io
      ili.res := initter.io.res
      when(initter.io.done) {
        uart.io.tx.enq(0xff.U)
        state := State.sRender
      }
    }

    is(State.sRender) {
      val req = Wire(new LcdRequest)
      req.data    := Mux(cellValAt(cellCol, cellPag), 0xff.U, 0x00.U)
      req.dc      := false.B
      req.respLen := 0.U
      lcd.io.req.enq(req)

      when(lcd.io.req.fire) {
        state := State.sRender2
      }
    }
    is(State.sRender2) {
      val req = Wire(new LcdRequest)
      req.data    := Mux(cellValAt(cellCol, cellPag), 0xff.U, 0x00.U)
      req.dc      := false.B
      req.respLen := 0.U
      lcd.io.req.enq(req)

      when(lcd.io.req.fire) {
        state := State.sRender

        when(colReg === (LCD_WIDTH - 1).U) {
          colReg := 0.U
          when(pagReg === (LCD_HEIGHT - 1).U) {
            pagReg := 0.U
            state  := State.sWaitPress
          }.otherwise {
            pagReg := pagReg + 1.U
          }
        }.otherwise {
          colReg := colReg + 1.U
        }
      }
    }

    is(State.sWaitPress) {
      // uart.io.rx.deq()
      // when(uart.io.rx.fire && !uart.io.rx.bits.err) {
      state := State.sProgress
      // }
    }

    is(State.sProgress) {
      when(golCells(cellIxAt(colReg, pagReg))) {
        golCellsNext(
          cellIxAt(colReg, pagReg),
        ) := (neighboursAlive === 2.U || neighboursAlive === 3.U)
      }.otherwise {
        golCellsNext(cellIxAt(colReg, pagReg)) := (neighboursAlive === 3.U)
      }

      when(colReg === (GOL_WIDTH - 1).U) {
        colReg := 0.U
        when(pagReg === (GOL_HEIGHT - 1).U) {
          pagReg := 0.U
          state  := State.sTransition
        }.otherwise {
          pagReg := pagReg + 1.U
        }
      }.otherwise {
        colReg := colReg + 1.U
      }
    }

    is(State.sTransition) {
      golCells(cellIxAt(colReg, pagReg)) := golCellsNext(
        cellIxAt(colReg, pagReg),
      )
      when(colReg === (GOL_WIDTH - 1).U) {
        colReg := 0.U
        when(pagReg === (GOL_HEIGHT - 1).U) {
          pagReg := 0.U
          state  := State.sRender
        }.otherwise {
          pagReg := pagReg + 1.U
        }
      }.otherwise {
        colReg := colReg + 1.U
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

      plat.resources.ledg := state === State.sRender

    case plat: Ulx3SPlatform =>
      ili.cipo := false.B

      plat.resources.uart.tx := uart.pins.tx
      uart.pins.rx           := plat.resources.uart.rx

    case plat: CxxrtlPlatform =>
      val spi = IO(new IliIO)
      spi :<>= ili

      val uart_rx = IO(Input(Bool()))
      uart.pins.rx := uart_rx

    case _ =>
  }
}
