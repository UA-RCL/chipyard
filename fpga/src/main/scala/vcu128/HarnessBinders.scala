package chipyard.fpga.vcu128

import chisel3._

import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}
import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.diplomacy.{LazyModule}
import freechips.rocketchip.system.{SimAXIMem}

import chipyard._
import chipyard.harness._
import chipyard.iobinders._

/*** UART ***/
class WithUART extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: UARTPort, chipId: Int) => {
    th.vcu128Outer.io_uart_bb.bundle <> port.io
  }
})

/*** UART TSI ***/
class WithUARTTSI extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: UARTTSIPort, chipId: Int) => {
    th.vcu128Outer.io_uart_tsi_bb.bundle <> port.io.uart
  }
})

/*** Experimental DDR ***/
class WithDDRMem extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: TLMemPort, chipId: Int) => {
    val bundles = th.vcu128Outer.ddrClient.out.map(_._1)
    val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
    bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
    ddrClientBundle <> port.io
  }
})

/*** AXI4 MMIO test RAM ***/
// 4 KiB RAM at the base of the AXI4 MMIO port, like rocket-chip's SimAXIMem.connectMMIO. Chipyard's default
// WithSimAXIMMIO sizes the RAM to the whole port (512 MiB), which does not fit in FPGA block RAM.
class WithMMIOTestRAM extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: AXI4MMIOPort, chipId: Int) => {
    val mmio_mem = LazyModule(new SimAXIMem(port.edge, base = port.params.base, size = 4096)(Parameters.empty))
    withClockAndReset(port.io.clock, th.referenceReset) { Module(mmio_mem.module).suggestName("mmio_mem") }
    mmio_mem.io_axi4.head <> port.io.bits
  }
})

/*** JTAG (BSCAN tunnel) ***/
class WithJTAG extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: JTAGPort, chipId: Int) => {
    val jtag_io = th.vcu128Outer.jtagPlacedOverlay.overlayOutput.jtag.getWrappedValue
    port.io.TCK := jtag_io.TCK
    port.io.TMS := jtag_io.TMS
    port.io.TDI := jtag_io.TDI
    port.io.reset.foreach(_ := th.referenceReset)
    jtag_io.TDO.data := port.io.TDO
    jtag_io.TDO.driven := true.B
    // ignore srst_n
    jtag_io.srst_n := DontCare

  }
})
