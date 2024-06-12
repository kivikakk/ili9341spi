package ee.kivikakk.ili9341spi

import ee.hrzn.athena.flashable.PlatformFlashable
import ee.hrzn.athena.flashable.SubcommandRom
import ee.hrzn.chryse.ChryseApp
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CxxrtlZigPlatform
import ee.hrzn.chryse.platform.ecp5.Lfe5U_45F
import ee.hrzn.chryse.platform.ecp5.Ulx3SPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform
import ee.kivikakk.ili9341spi.lcd.LcdInit
import org.apache.commons.io.FileUtils

import java.io.File

object App extends ChryseApp {
  override val name                                  = "ili9341spi"
  override def genTop()(implicit platform: Platform) = new Top
  override val targetPlatforms =
    Seq(
      new IceBreakerPlatform(
        ubtnReset = true,
        inferSpram = true,
        useHfosc = Some(1),
      ) with PlatformFlashable {
        var romFlashBase = BigInt("00800000", 16)
        def romFlashCommand(binPath: String) =
          Seq("iceprog", "-o", f"0x$romFlashBase%x", binPath)
      },
      new Ulx3SPlatform(Lfe5U_45F) with PlatformFlashable {
        var romFlashBase = BigInt("00100000", 16)
        def romFlashCommand(binPath: String) =
          Seq(
            "openFPGALoader",
            "-v",
            "-b",
            "ulx3s",
            "-f",
            "-o",
            f"0x$romFlashBase%x",
            binPath,
          )
      },
    )
  override val cxxrtlPlatforms = Seq(new CxxrtlZigPlatform("cxxrtl") {
    val clockHz = 1_000_000

    override def preBuild() = {
      val romPath = rom.generate()
      FileUtils.copyFile(new File(romPath), new File(s"$simDir/src/rom.bin"))
    }
  })
  override val additionalSubcommands = Seq(rom)

  object rom extends SubcommandRom(this) {
    override def romContent = LcdInit.pngrom
  }
}
