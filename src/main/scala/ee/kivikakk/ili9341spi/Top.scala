package ee.kivikakk.ili9341spi

import chisel3._
import chisel3.util._
import ee.hrzn.athena.uart.Uart
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CxxrtlPlatform
import ee.hrzn.chryse.platform.ecp5.Ulx3SPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform
import ee.kivikakk.ili9341spi.lcd.Lcd
import ee.kivikakk.ili9341spi.lcd.LcdCommand
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
    val sInit, sInitRam = Value

    val sInitiate                           = Value
    val sLoad, sLoadWait, sRender, sRender2 = Value

    val sProgressLoad, sProgressWait, sProgressPart, sProgressWrite = Value
    val sTransitionLoad, sTransitionWait, sTransitionWrite          = Value
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

  private val start = """..............#.
                        |..............#.
                        |................
                        |................
                        |................
                        |...........#....
                        |...###.....#....
                        |...........#....
                        |................
                        |................
                        |...###..........
                        |..............#.
                        |""".stripMargin.replace("\n", "")

  private val golInit = VecInit(for {
    i <- 0 until GOL_CELLCNT
  } yield (start(i) != '.').B)

  private val nowCells = SRAM(GOL_CELLCNT, Bool(), 0, 0, 1)
  val nowAddr          = RegInit(0.U(unsignedBitLength(GOL_CELLCNT - 1).W))
  val nowReadData      = WireInit(nowCells.readwritePorts(0).readData)
  val nowWriteEn       = Reg(Bool())
  nowWriteEn := false.B
  val nowWriteData = Reg(Bool())
  nowCells.readwritePorts(0).address   := nowAddr
  nowCells.readwritePorts(0).enable    := true.B
  nowCells.readwritePorts(0).isWrite   := nowWriteEn
  nowCells.readwritePorts(0).writeData := nowWriteData

  private val nextCells = SRAM(GOL_CELLCNT, Bool(), 0, 0, 1)
  val nextAddr          = RegInit(0.U(unsignedBitLength(GOL_CELLCNT - 1).W))
  val nextReadData      = WireInit(nextCells.readwritePorts(0).readData)
  val nextWriteEn       = Reg(Bool())
  nextWriteEn := false.B
  val nextWriteData = Reg(Bool())
  nextCells.readwritePorts(0).address   := nextAddr
  nextCells.readwritePorts(0).enable    := true.B
  nextCells.readwritePorts(0).isWrite   := nextWriteEn
  nextCells.readwritePorts(0).writeData := nextWriteData

  private val colReg = RegInit(0.U(unsignedBitLength(LCD_WIDTH - 1).W))
  private val pagReg = RegInit(0.U(unsignedBitLength(LCD_HEIGHT - 1).W))

  // To avoid nonsense, we define the 'real' range of x as [1..GOL_WIDTH] and y
  // as [1..GOL_HEIGHT], and define our UInts up to GOL_WIDTH+1 and GOL_HEIGHT+1
  // inclusive. This way we can detect wraps without having to reach for signed
  // integers.
  private def cellIxAt(x: UInt, y: UInt): UInt = {
    val effX = Wire(UInt(unsignedBitLength(GOL_CELLCNT - 1).W))
    when(x === 0.U)(effX := (GOL_WIDTH - 1).U)
      .elsewhen(x === (GOL_WIDTH + 1).U)(effX := 0.U)
      .otherwise(effX := x - 1.U)
    val effY = Wire(UInt(unsignedBitLength(GOL_CELLCNT - 1).W))
    when(y === 0.U)(effY := (GOL_HEIGHT - 1).U)
      .elsewhen(y === (GOL_HEIGHT + 1).U)(effY := 0.U)
      .otherwise(effY := y - 1.U)
    (effX + (effY * GOL_WIDTH.U))(unsignedBitLength(GOL_CELLCNT - 1) - 1, 0)
  }

  private val cellX = (colReg / GOL_SIZE.U) + 1.U
  private val cellY = (pagReg / GOL_SIZE.U) + 1.U

  private val directCellIx = WireInit(cellIxAt(colReg + 1.U, pagReg + 1.U))

  private val plIxReg            = Reg(UInt(unsignedBitLength(8).W))
  private val wasAliveReg        = Reg(Bool())
  private val neighboursAliveReg = Reg(UInt(8.W))

  switch(state) {
    is(State.sInit) {
      initter.io.lcd :<>= lcd.io
      ili.res := initter.io.res
      when(initter.io.done) {
        uart.io.tx.enq(0xff.U)
        state := State.sInitRam
      }
    }
    is(State.sInitRam) {
      nowAddr      := directCellIx
      nowWriteEn   := true.B
      nowWriteData := golInit(directCellIx)

      when(colReg === (GOL_WIDTH - 1).U) {
        colReg := 0.U
        when(pagReg === (GOL_HEIGHT - 1).U) {
          pagReg := 0.U
          state  := State.sInitiate
        }.otherwise {
          pagReg := pagReg + 1.U
        }
      }.otherwise {
        colReg := colReg + 1.U
      }
    }

    is(State.sInitiate) {
      val req = Wire(new LcdRequest)
      req.data    := LcdCommand.MEMORY_WRITE.asUInt
      req.dc      := true.B
      req.respLen := 0.U
      lcd.io.req.enq(req)

      when(lcd.io.req.fire) {
        state := State.sLoad
      }
    }

    is(State.sLoad) {
      nowAddr := cellIxAt(cellX, cellY)
      state   := State.sLoadWait
    }
    is(State.sLoadWait) {
      state := State.sRender
    }
    is(State.sRender) {
      val req = Wire(new LcdRequest)
      req.data    := Mux(nowReadData, 0xff.U, 0x00.U)
      req.dc      := false.B
      req.respLen := 0.U
      lcd.io.req.enq(req)

      when(lcd.io.req.fire) {
        state := State.sRender2
      }
    }
    is(State.sRender2) {
      val req = Wire(new LcdRequest)
      req.data    := Mux(nowReadData, 0xff.U, 0x00.U)
      req.dc      := false.B
      req.respLen := 0.U
      lcd.io.req.enq(req)

      when(lcd.io.req.fire) {
        state := State.sLoad

        when(colReg === (LCD_WIDTH - 1).U) {
          colReg := 0.U
          when(pagReg === (LCD_HEIGHT - 1).U) {
            pagReg             := 0.U
            plIxReg            := 0.U
            neighboursAliveReg := 0.U
            state              := State.sProgressLoad
          }.otherwise {
            pagReg := pagReg + 1.U
          }
        }.otherwise {
          colReg := colReg + 1.U
        }
      }
    }

    is(State.sProgressLoad) {
      when(plIxReg === 0.U)(nowAddr := cellIxAt(colReg + 0.U, pagReg + 0.U))
        .elsewhen(plIxReg === 1.U)(
          nowAddr := cellIxAt(colReg + 1.U, pagReg + 0.U),
        )
        .elsewhen(plIxReg === 2.U)(
          nowAddr := cellIxAt(colReg + 2.U, pagReg + 0.U),
        )
        .elsewhen(plIxReg === 3.U)(
          nowAddr := cellIxAt(colReg + 0.U, pagReg + 1.U),
        )
        .elsewhen(plIxReg === 4.U)(
          nowAddr := cellIxAt(colReg + 1.U, pagReg + 1.U),
        )
        .elsewhen(plIxReg === 5.U)(
          nowAddr := cellIxAt(colReg + 2.U, pagReg + 1.U),
        )
        .elsewhen(plIxReg === 6.U)(
          nowAddr := cellIxAt(colReg + 0.U, pagReg + 2.U),
        )
        .elsewhen(plIxReg === 7.U)(
          nowAddr := cellIxAt(colReg + 1.U, pagReg + 2.U),
        )
        .elsewhen(plIxReg === 8.U)(
          nowAddr := cellIxAt(colReg + 2.U, pagReg + 2.U),
        )

      state := State.sProgressWait
    }
    is(State.sProgressWait) {
      state := State.sProgressPart
    }
    is(State.sProgressPart) {
      when(plIxReg === 4.U) {
        wasAliveReg := nowReadData
      }.otherwise {
        neighboursAliveReg := neighboursAliveReg + nowReadData.asUInt
      }
      when(plIxReg =/= 8.U) {
        plIxReg := plIxReg + 1.U
        state   := State.sProgressLoad
      }.otherwise {
        state := State.sProgressWrite
      }
    }
    is(State.sProgressWrite) {
      nextAddr    := directCellIx
      nextWriteEn := true.B

      when(wasAliveReg) {
        nextWriteData := (neighboursAliveReg === 2.U || neighboursAliveReg === 3.U)
      }.otherwise {
        nextWriteData := (neighboursAliveReg === 3.U)
      }

      plIxReg            := 0.U
      neighboursAliveReg := 0.U
      state              := State.sProgressLoad
      when(colReg === (GOL_WIDTH - 1).U) {
        colReg := 0.U
        when(pagReg === (GOL_HEIGHT - 1).U) {
          pagReg := 0.U
          state  := State.sTransitionLoad
        }.otherwise {
          pagReg := pagReg + 1.U
        }
      }.otherwise {
        colReg := colReg + 1.U
      }
    }

    is(State.sTransitionLoad) {
      nextAddr := directCellIx
      state    := State.sTransitionWait
    }
    is(State.sTransitionWait) {
      state := State.sTransitionWrite
    }
    is(State.sTransitionWrite) {
      nowAddr      := directCellIx
      nowWriteEn   := true.B
      nowWriteData := nextReadData

      state := State.sTransitionLoad
      when(colReg === (GOL_WIDTH - 1).U) {
        colReg := 0.U
        when(pagReg === (GOL_HEIGHT - 1).U) {
          pagReg := 0.U
          state  := State.sInitiate
          uart.io.tx.enq(0x77.U)
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

      plat.resources.ledg := state === State.sLoad

    case plat: Ulx3SPlatform =>
      ili.cipo := false.B

      plat.resources.uart.tx := uart.pins.tx
      uart.pins.rx           := plat.resources.uart.rx

    case plat: CxxrtlPlatform =>
      val spi = IO(new IliIO)
      spi :<>= ili

      val uart_rx = IO(Input(Bool()))
      uart.pins.rx := uart_rx

      val uart_tx = IO(Output(Bool()))
      uart_tx := uart.pins.tx

    case _ =>
  }
}
