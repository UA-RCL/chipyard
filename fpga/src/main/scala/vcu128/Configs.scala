package chipyard.fpga.vcu128

import org.chipsalliance.cde.config.{Config}
import freechips.rocketchip.subsystem.{ExtMem}
import freechips.rocketchip.resources.{DTSTimebase}

import sifive.fpgashells.shell.xilinx.{VCU128DDRSize}

import testchipip.serdes.{SerialTLKey}

class WithSystemModifications extends Config((site, here, up) => {
  case DTSTimebase => BigInt((1e6).toLong)
  case ExtMem => up(ExtMem, site).map(x => x.copy(master = x.master.copy(size = site(VCU128DDRSize)))) // set extmem to DDR size
  case SerialTLKey => Nil // remove serialized tl port
})

// DOC include start: WithVCU128Tweaks and Rocket
// The VCU128 has no SD card, so binaries are loaded into DDR over UART1 with TSI (uart_tsi host program)
// UART0 is the console
class WithVCU128Tweaks(freqMHz: Double = 50) extends Config(
  // clocking
  new chipyard.harness.WithAllClocksFromHarnessClockInstantiator ++
  new chipyard.clocking.WithPassthroughClockGenerator ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(freqMHz) ++
  new chipyard.config.WithUniformBusFrequencies(freqMHz) ++
  // harness binders
  new WithUART ++
  new WithUARTTSI ++
  new WithDDRMem ++
  new WithJTAG ++
  // other configuration
  new testchipip.tsi.WithUARTTSIClient(initBaudRate = BigInt(921600)) ++ // nonstandard baud rate to improve performance
  new chipyard.config.WithTLBackingMemory ++ // use TL backing memory
  new WithSystemModifications ++ // setup busses, setup ext. mem. size
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1)
)

class RocketVCU128Config extends Config(
  new WithVCU128Tweaks ++
  new chipyard.RocketConfig
)
// DOC include end: WithVCU128Tweaks and Rocket
