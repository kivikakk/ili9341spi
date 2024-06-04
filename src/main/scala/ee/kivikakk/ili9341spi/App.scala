package ee.kivikakk.ili9341spi

import ee.hrzn.athena.flashable.PlatformFlashable
import ee.hrzn.athena.flashable.SubcommandRom
import ee.hrzn.chryse.ChryseApp
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CXXRTLOptions
import ee.hrzn.chryse.platform.cxxrtl.CXXRTLPlatform
import ee.hrzn.chryse.platform.ecp5.LFE5U_45F
import ee.hrzn.chryse.platform.ecp5.ULX3SPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform
import ee.kivikakk.ili9341spi.lcd.LCDInit

object App extends ChryseApp {
  override val name                                  = "ili9341spi"
  override def genTop()(implicit platform: Platform) = new Top
  override val targetPlatforms =
    Seq(
      new IceBreakerPlatform(ubtnReset = true) with PlatformFlashable {
        var romFlashBase = BigInt("00800000", 16)
        def romFlashCommand(binPath: String) =
          Seq("iceprog", "-o", f"0x$romFlashBase%x", binPath)
      },
      new ULX3SPlatform(LFE5U_45F) with PlatformFlashable {
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
  override val additionalSubcommands = Seq(rom)
  override val cxxrtlOptions = Some(
    CXXRTLOptions(
      platforms = Seq(new CXXRTLPlatform("cxxrtl") { val clockHz = 3_000_000 }),
    ),
  )

  object rom extends SubcommandRom(this) {
    override def romContent = LCDInit.pngrom
  }
}
