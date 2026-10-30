package chipyard.fpga.vcu128

import chisel3._

import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}

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
