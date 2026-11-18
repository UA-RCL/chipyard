package chipyard.fpga.vcu128

import chisel3._

import freechips.rocketchip.diplomacy.{LazyModule, LazyRawModuleImp, BundleBridgeSource, ValName}
import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.subsystem.{SystemBusKey}
import freechips.rocketchip.prci._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.ip.xilinx.{IBUF, PowerOnResetFPGAOnly}
import sifive.fpgashells.shell._
import sifive.fpgashells.clocks._

import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTPortIO}

import testchipip.tsi.{UARTTSIClientKey}

import chipyard._
import chipyard.harness._

class VCU128FPGATestHarness(override implicit val p: Parameters) extends VCU128ShellBasicOverlays {

  def dp = designParameters

  // Order matters; ddr depends on sys_clock
  val uart      = Seq.tabulate(2)(i => Overlay(UARTOverlayKey, new UARTVCU128ShellPlacer(this, UARTShellInput(index = i))(valName = ValName(s"uart_$i"))))
  val jtagBScan = Overlay(JTAGDebugBScanOverlayKey, new JTAGDebugBScanVCU128ShellPlacer(this, JTAGDebugBScanShellInput()))

  // place all clocks in the shell
  require(dp(ClockInputOverlayKey).size >= 1)
  val sysClkNode = dp(ClockInputOverlayKey)(0).place(ClockInputDesignInput()).overlayOutput.node

  /*** Connect/Generate clocks ***/

  // connect to the PLL that will generate multiple clocks
  val harnessSysPLL = dp(PLLFactoryKey)()
  harnessSysPLL := sysClkNode

  // create and connect to the dutClock
  val dutFreqMHz = (dp(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toInt
  val dutClock = ClockSinkNode(freqMHz = dutFreqMHz)
  println(s"VCU128 FPGA Base Clock Freq: ${dutFreqMHz} MHz")
  val dutWrangler = LazyModule(new ResetWrangler)
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLL

  /*** UART ***/

// DOC include start: UartOverlay
  // UART0 is the console, UART1 carries TSI (used by the uart_tsi host program to load binaries)
  // Placed overlays drop out of dp(UARTOverlayKey), so take the list before placing
  val uartOverlays = dp(UARTOverlayKey)

  val io_uart_bb = BundleBridgeSource(() => (new UARTPortIO(dp(PeripheryUARTKey).head)))
  uartOverlays(0).place(UARTDesignInput(io_uart_bb))

  val io_uart_tsi_bb = BundleBridgeSource(() => (new UARTPortIO(dp(UARTTSIClientKey).get.uartParams)))
  uartOverlays(1).place(UARTDesignInput(io_uart_tsi_bb))
// DOC include end: UartOverlay

  /*** DDR ***/

  val ddrNode = dp(DDROverlayKey).head.place(DDRDesignInput(dp(ExtTLMem).get.master.base, dutWrangler.node, harnessSysPLL)).overlayOutput.ddr

  // connect 1 mem. channel to the FPGA DDR
  val ddrClient = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = "chip_ddr",
    sourceId = IdRange(0, 1 << dp(ExtTLMem).get.master.idBits)
  )))))
  ddrNode := TLWidthWidget(dp(ExtTLMem).get.master.beatBytes) := ddrClient

  /*** JTAG ***/

  // JTAG over the on-board USB-JTAG cable through a BSCAN tunnel
  val jtagPlacedOverlay = dp(JTAGDebugBScanOverlayKey).head.place(JTAGDebugBScanDesignInput())

  // module implementation
  override lazy val module = new VCU128FPGATestHarnessImp(this)
}

class VCU128FPGATestHarnessImp(_outer: VCU128FPGATestHarness) extends LazyRawModuleImp(_outer) with HasHarnessInstantiators {
  override def provideImplicitClockToLazyChildren = true
  val vcu128Outer = _outer

  val reset = IO(Input(Bool())).suggestName("reset")
  _outer.xdc.addPackagePin(reset, "BM29")
  _outer.xdc.addIOStandard(reset, "LVCMOS12")

  val resetIBUF = Module(new IBUF)
  resetIBUF.io.I := reset

  val sysclk: Clock = _outer.sysClkNode.out.head._1.clock

  val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
  _outer.sdc.addAsyncPath(Seq(powerOnReset))

  _outer.pllReset := (resetIBUF.io.O || powerOnReset)

  // reset setup
  val hReset = Wire(Reset())
  hReset := _outer.dutClock.in.head._1.reset

  def referenceClockFreqMHz = _outer.dutFreqMHz
  def referenceClock = _outer.dutClock.in.head._1.clock
  def referenceReset = hReset
  def success = { require(false, "Unused"); false.B }

  childClock := referenceClock
  childReset := referenceReset

  val ethernet: Option[VCU128EthernetCores] = if (p(VCU128EthernetKey)) Some(VCU128Ethernet(this)) else None

  instantiateChipTops()
}
