package ee.kivikakk.ili9341spi

import ee.hrzn.chryse.ChryseApp
import ee.hrzn.chryse.platform.Platform
import ee.hrzn.chryse.platform.cxxrtl.CXXRTLOptions
import ee.hrzn.chryse.platform.cxxrtl.CXXRTLPlatform
import ee.hrzn.chryse.platform.ecp5.LFE5U_45F
import ee.hrzn.chryse.platform.ecp5.ULX3SPlatform
import ee.hrzn.chryse.platform.ice40.IceBreakerPlatform

object App extends ChryseApp {
  override val name                                  = "ili9341spi"
  override def genTop()(implicit platform: Platform) = new Top
  override val targetPlatforms =
    Seq(IceBreakerPlatform(ubtnReset = true), ULX3SPlatform(LFE5U_45F))
  override val cxxrtlOptions = Some(
    CXXRTLOptions(
      platforms = Seq(new CXXRTLPlatform("cxxrtl") { val clockHz = 3_000_000 }),
    ),
  )
}
