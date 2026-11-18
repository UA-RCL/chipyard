package chipyard.fpga.vcu128

import chisel3._
import chisel3.util.{Cat}
import chisel3.experimental.{Analog, attach}

import scala.collection.immutable.SeqMap

import org.chipsalliance.cde.config.{Config, Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}
import org.chipsalliance.diplomacy.nodes.{MonitorsEnabled}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink.{DevNullParams, TLError}
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes, ValName}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{ElaborationArtefacts}

import sifive.fpgashells.ip.xilinx.{IOBUF}
import sifive.fpgashells.shell._

import chipyard.harness._
import chipyard.iobinders._

// 1G Ethernet on the VCU128: AXI 1G Ethernet (tri-mode MAC + 1G PCS/PMA, SGMII over LVDS) to the on-board TI DP83867
// PHY, with an AXI DMA, as in the working MicroBlaze example (example_ethernet). That example used the VCU128 board
// flow; the Chipyard flow has no board part, so IP settings and pins are written out explicitly here.
//
// Connections to the chip (ports from WithVCU128EthernetPorts):
//   - AXI4 MMIO port: core registers at 0x6000_0000 (256 KiB), DMA registers at 0x6010_0000 (64 KiB)
//   - AXI4 slave port: the DMA's descriptor, TX and RX masters into DDR (32-bit DMA addresses)
//   - external interrupts 0/1/2: DMA TX (MM2S), DMA RX (S2MM), core interrupt; the order Linux's xilinx_axienet
//     driver expects when the DMA is described in the Ethernet node

case object VCU128EthernetKey extends Field[Boolean](false)

class WithVCU128Ethernet extends Config(
  new WithVCU128EthernetMMIO ++
  new WithVCU128EthernetDMAPort ++
  new WithVCU128EthernetInterrupts ++
  new Config((site, here, up) => {
    case VCU128EthernetKey => true
  })
)

// Port lists of the IPs (from their instantiation templates, *.veo).
// The chipyard_fpga sbt project is built without the Chisel compiler plugin, which every Bundle needs, so the port
// lists are Records with explicit element lists.
object VCU128PortList {
  def in(n: String, w: Int = 1)  = n -> (if (w == 1) Input(Bool()) else Input(UInt(w.W)))
  def out(n: String, w: Int = 1) = n -> (if (w == 1) Output(Bool()) else Output(UInt(w.W)))
  def clock(n: String) = n -> Input(Clock())

  // AXI4-Lite slave
  def axiLite(p: String, addrBits: Int, strb: Boolean): Seq[(String, Data)] = Seq(
    in(p + "araddr", addrBits), in(p + "arvalid"), out(p + "arready"),
    in(p + "awaddr", addrBits), in(p + "awvalid"), out(p + "awready"),
    in(p + "wdata", 32), in(p + "wvalid"), out(p + "wready"),
    out(p + "bresp", 2), out(p + "bvalid"), in(p + "bready"),
    out(p + "rdata", 32), out(p + "rresp", 2), out(p + "rvalid"), in(p + "rready")) ++
    (if (strb) Seq(in(p + "wstrb", 4)) else Nil)

  // 32-bit AXI4 master without ID, lock or QoS signals (the DMA's)
  def axiMaster(p: String, read: Boolean, write: Boolean): Seq[(String, Data)] = {
    def addr(c: String) = Seq(out(p + c + "addr", 32), out(p + c + "len", 8), out(p + c + "size", 3),
      out(p + c + "burst", 2), out(p + c + "prot", 3), out(p + c + "cache", 4), out(p + c + "valid"), in(p + c + "ready"))
    (if (write) addr("aw") ++ Seq(out(p + "wdata", 32), out(p + "wstrb", 4), out(p + "wlast"), out(p + "wvalid"),
      in(p + "wready"), in(p + "bresp", 2), in(p + "bvalid"), out(p + "bready")) else Nil) ++
    (if (read) addr("ar") ++ Seq(in(p + "rdata", 32), in(p + "rresp", 2), in(p + "rlast"), in(p + "rvalid"),
      out(p + "rready")) else Nil)
  }

  // 32-bit AXI4-Stream with tkeep
  def streamOut(p: String) = Seq(out(p + "tdata", 32), out(p + "tkeep", 4), out(p + "tlast"), out(p + "tvalid"), in(p + "tready"))
  def streamIn(p: String)  = Seq(in(p + "tdata", 32), in(p + "tkeep", 4), in(p + "tlast"), in(p + "tvalid"), out(p + "tready"))
}

class VCU128EthernetIO extends Record {
  import VCU128PortList._
  private def nibbles(f: Int => (String, Data)) = (1 to 3).map(f)

  val elements = SeqMap[String, Data](Seq(
    // clocks and resets
    clock("s_axi_lite_clk"), in("s_axi_lite_resetn"),
    clock("axis_clk"), in("axi_txd_arstn"), in("axi_txc_arstn"), in("axi_rxd_arstn"), in("axi_rxs_arstn"),
    // interrupts
    out("mac_irq"), out("interrupt"),
    // PCS/PMA status and shared-logic outputs (unused)
    in("signal_detect"), out("clk125_out"), out("clk312_out"), out("rst_125_out"),
    out("tx_logic_reset"), out("rx_logic_reset"), out("rx_locked"), out("tx_locked"),
    out("tx_bsc_rst_out"), out("rx_bsc_rst_out"), out("tx_bs_rst_out"), out("rx_bs_rst_out"),
    out("tx_rst_dly_out"), out("rx_rst_dly_out"), out("tx_bsc_en_vtc_out"), out("tx_bs_en_vtc_out"),
    out("rx_bsc_en_vtc_out"), out("rx_bs_en_vtc_out"), out("riu_clk_out"), out("riu_wr_en_out"),
    out("tx_pll_clk_out"), out("rx_pll_clk_out"), out("tx_rdclk_out"),
    out("riu_addr_out", 6), out("riu_wr_data_out", 16), out("riu_nibble_sel_out", 2),
    out("phy_rst_n"), in("dummy_port_in")
  ) ++
    // shared-logic inputs from other nibbles (none here)
    nibbles(i => in(s"riu_valid_$i")) ++ nibbles(i => in(s"riu_prsnt_$i")) ++ nibbles(i => in(s"riu_rddata_$i", 16)) ++
    nibbles(i => in(s"tx_dly_rdy_$i")) ++ nibbles(i => in(s"rx_dly_rdy_$i")) ++
    nibbles(i => in(s"tx_vtc_rdy_$i")) ++ nibbles(i => in(s"rx_vtc_rdy_$i")) ++
    nibbles(i => out(s"rx_btval_$i", 9)) ++
    // AXI4-Lite register interface
    axiLite("s_axi_", 18, strb = true) ++
    // AXI4-Stream TX data and control (from the DMA), RX data and status (to the DMA)
    streamIn("s_axis_txd_") ++ streamIn("s_axis_txc_") ++ streamOut("m_axis_rxd_") ++ streamOut("m_axis_rxs_") ++
    Seq(
    // pins: SGMII over LVDS, 625 MHz clock from the PHY, MDIO
    in("sgmii_rxp"), in("sgmii_rxn"), out("sgmii_txp"), out("sgmii_txn"),
    in("lvds_clk_clk_p"), in("lvds_clk_clk_n"),
    out("mdio_mdc"), in("mdio_mdio_i"), out("mdio_mdio_o"), out("mdio_mdio_t")
  ): _*)

  override def cloneType: this.type = (new VCU128EthernetIO).asInstanceOf[this.type]
}

class VCU128EthernetDMAIO extends Record {
  import VCU128PortList._

  val elements = SeqMap[String, Data](Seq(
    clock("s_axi_lite_aclk"), clock("m_axi_sg_aclk"), clock("m_axi_mm2s_aclk"), clock("m_axi_s2mm_aclk"),
    in("axi_resetn"),
    out("mm2s_prmry_reset_out_n"), out("mm2s_cntrl_reset_out_n"), out("s2mm_prmry_reset_out_n"),
    out("s2mm_sts_reset_out_n"),
    out("mm2s_introut"), out("s2mm_introut"), out("axi_dma_tstvec", 32)
  ) ++
    // register interface (10 address bits, no write strobes)
    axiLite("s_axi_lite_", 10, strb = false) ++
    // masters: descriptors (read and write), TX data (read), RX data (write)
    axiMaster("m_axi_sg_", read = true, write = true) ++
    axiMaster("m_axi_mm2s_", read = true, write = false) ++
    axiMaster("m_axi_s2mm_", read = false, write = true) ++
    // streams: TX data and control to the core, RX data and status from the core
    streamOut("m_axis_mm2s_") ++ streamOut("m_axis_mm2s_cntrl_") ++
    streamIn("s_axis_s2mm_") ++ streamIn("s_axis_s2mm_sts_"): _*)

  override def cloneType: this.type = (new VCU128EthernetDMAIO).asInstanceOf[this.type]
}

class VCU128NamedPortsBlackBox(name: String, ports: => Record) extends BlackBox {
  override def desiredName = name
  // Named explicitly: without the compiler plugin (see VCU128PortList) nothing else names it "io"
  val io = IO(ports).suggestName("io")

  def data(n: String) = io.elements(n)
  def bool(n: String) = data(n).asInstanceOf[Bool]
  def uint(n: String) = data(n).asInstanceOf[UInt]
}

class VCU128EthernetBlackBox    extends VCU128NamedPortsBlackBox("vcu128_eth", new VCU128EthernetIO)
class VCU128EthernetDMABlackBox extends VCU128NamedPortsBlackBox("vcu128_eth_dma", new VCU128EthernetDMAIO)

case class VCU128EthernetCores(eth: VCU128EthernetBlackBox, dma: VCU128EthernetDMABlackBox)

// Bridge from the chip's AXI4 MMIO port (64-bit, bursts, 4 ID bits) to the 32-bit AXI4-Lite register interfaces of
// the core and the DMA, built from rocket-chip adapters: AXI4 -> TileLink (as rocket-chip's AXI4 slave port does), a
// TileLink crossbar, and TileLink -> single-beat 32-bit AXI4 with no ID bits (as rocket-chip's AXI4-Lite tests do),
// which is AXI4-Lite. Addresses in the MMIO window outside the register blocks get an error response from a TLError.
class VCU128EthernetMMIO(edge: AXI4EdgeParameters, base: BigInt, size: BigInt)(implicit p: Parameters)
    extends LazyModule {
  import VCU128Ethernet._

  val master = AXI4MasterNode(Seq(edge.master))
  def liteNode(offset: BigInt, bytes: BigInt) = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    slaves = Seq(AXI4SlaveParameters(
      address       = Seq(AddressSet(base + offset, bytes - 1)),
      supportsRead  = TransferSizes(1, 4),
      supportsWrite = TransferSizes(1, 4),
      interleavedId = Some(0))),
    beatBytes = 4)))
  val ethRegs = liteNode(regsOffset, regsSize)
  val dmaRegs = liteNode(dmaRegsOffset, dmaRegsSize)
  val error = LazyModule(new TLError(
    DevNullParams(Seq(AddressSet(base + size - 0x1000, 0xfff)), maxAtomic = 4, maxTransfer = edge.slave.maxTransfer),
    beatBytes = edge.slave.beatBytes))
  val xbar = TLXbar()

  // wcorrupt = false: the chip's MMIO port has no write-corrupt bit
  xbar := TLFIFOFixer(TLFIFOFixer.all) := AXI4ToTL(wcorrupt = false) := AXI4UserYanker(Some(4)) := AXI4Fragmenter() := master
  Seq(ethRegs, dmaRegs).foreach { regs =>
    regs := AXI4UserYanker() := AXI4IdIndexer(0) := TLToAXI4() :=
      TLFragmenter(4, edge.slave.maxTransfer, holdFirstDeny = true) := TLWidthWidget(edge.slave.beatBytes) := xbar
  }
  error.node := xbar

  val io_axi4     = InModuleBody { master.makeIOs() }
  val io_eth_regs = InModuleBody { ethRegs.makeIOs() }
  val io_dma_regs = InModuleBody { dmaRegs.makeIOs() }

  lazy val module = new LazyModuleImp(this)
}

// Bridge from the DMA's three 32-bit AXI4 masters to the chip's 64-bit AXI4 slave port: each master -> TileLink (as
// rocket-chip's AXI4 slave port does), a TileLink crossbar, then TileLink -> 64-bit AXI4 (as rocket-chip's AXI4 MMIO
// port does). The DMA can address DDR from 0x8000_0000 up to its 32-bit limit; other addresses get an error response
// from a TLError.
class VCU128EthernetDMABridge(beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val dmaMasterNames = Seq("sg", "mm2s", "s2mm")
  val dmaMasters = dmaMasterNames.map { n =>
    AXI4MasterNode(Seq(AXI4MasterPortParameters(Seq(AXI4MasterParameters(name = s"eth-dma-$n", id = IdRange(0, 1))))))
  }
  val mem = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    slaves = Seq(AXI4SlaveParameters(
      address       = Seq(AddressSet(0x80000000L, 0x7fffffffL)),
      regionType    = RegionType.UNCACHED,
      supportsRead  = TransferSizes(1, 256),
      supportsWrite = TransferSizes(1, 256),
      interleavedId = Some(0))),
    beatBytes = beatBytes)))
  val error = LazyModule(new TLError(DevNullParams(Seq(AddressSet(0x0, 0xfff)), maxAtomic = 4, maxTransfer = 256),
    beatBytes = 4))
  val xbar = TLXbar()

  dmaMasters.foreach { m =>
    xbar := TLFIFOFixer(TLFIFOFixer.all) := AXI4ToTL(wcorrupt = false) := AXI4UserYanker(Some(4)) := AXI4Fragmenter() := m
  }
  mem := AXI4UserYanker() := TLToAXI4(wcorrupt = false) := TLWidthWidget(4) := xbar
  error.node := xbar

  val io_dma = InModuleBody {
    dmaMasters.zip(dmaMasterNames).map { case (m, n) => m.makeIOs()(ValName(s"io_dma_$n")).head }
  }
  val io_mem = InModuleBody { mem.makeIOs().head }

  lazy val module = new LazyModuleImp(this)
}

object VCU128EthernetConnect {
  private def fit(x: UInt, w: Int): UInt = if (x.getWidth >= w) x(w - 1, 0) else x.pad(w)

  // AXI4-Lite side of a bridge (single beats of at most 4 bytes, no ID bits) to an IP's AXI4-Lite slave
  def lite(regs: AXI4Bundle, bb: VCU128NamedPortsBlackBox, p: String, addrBits: Int): Unit = {
    bb.uint(p + "awaddr")  := regs.aw.bits.addr(addrBits - 1, 0)
    bb.bool(p + "awvalid") := regs.aw.valid
    regs.aw.ready          := bb.bool(p + "awready")
    bb.uint(p + "wdata")   := regs.w.bits.data
    bb.io.elements.get(p + "wstrb").foreach(_ := regs.w.bits.strb)
    bb.bool(p + "wvalid")  := regs.w.valid
    regs.w.ready           := bb.bool(p + "wready")
    regs.b.bits            := DontCare
    regs.b.bits.id         := 0.U
    regs.b.bits.resp       := bb.uint(p + "bresp")
    regs.b.valid           := bb.bool(p + "bvalid")
    bb.bool(p + "bready")  := regs.b.ready
    bb.uint(p + "araddr")  := regs.ar.bits.addr(addrBits - 1, 0)
    bb.bool(p + "arvalid") := regs.ar.valid
    regs.ar.ready          := bb.bool(p + "arready")
    regs.r.bits            := DontCare
    regs.r.bits.id         := 0.U
    regs.r.bits.data       := bb.uint(p + "rdata")
    regs.r.bits.resp       := bb.uint(p + "rresp")
    regs.r.bits.last       := true.B
    regs.r.valid           := bb.bool(p + "rvalid")
    bb.bool(p + "rready")  := regs.r.ready
  }

  // An IP's AXI4 master (no ID, lock or QoS signals) to the AXI4 master side of a bridge
  def master(io: AXI4Bundle, bb: VCU128NamedPortsBlackBox, p: String, read: Boolean, write: Boolean): Unit = {
    io.aw.bits := DontCare
    io.w.bits  := DontCare
    io.ar.bits := DontCare
    def addrChannel(ch: AXI4BundleA, c: String): Unit = {
      ch.id    := 0.U
      ch.addr  := fit(bb.uint(p + c + "addr"), ch.addr.getWidth)
      ch.len   := bb.uint(p + c + "len")
      ch.size  := bb.uint(p + c + "size")
      ch.burst := bb.uint(p + c + "burst")
      ch.lock  := 0.U
      ch.cache := bb.uint(p + c + "cache")
      ch.prot  := bb.uint(p + c + "prot")
      ch.qos   := 0.U
    }
    if (write) {
      addrChannel(io.aw.bits, "aw")
      io.aw.valid            := bb.bool(p + "awvalid")
      bb.bool(p + "awready") := io.aw.ready
      io.w.bits.data         := bb.uint(p + "wdata")
      io.w.bits.strb         := bb.uint(p + "wstrb")
      io.w.bits.last         := bb.bool(p + "wlast")
      io.w.valid             := bb.bool(p + "wvalid")
      bb.bool(p + "wready")  := io.w.ready
      bb.uint(p + "bresp")   := io.b.bits.resp
      bb.bool(p + "bvalid")  := io.b.valid
      io.b.ready             := bb.bool(p + "bready")
    } else {
      io.aw.valid := false.B
      io.w.valid  := false.B
      io.b.ready  := true.B
    }
    if (read) {
      addrChannel(io.ar.bits, "ar")
      io.ar.valid            := bb.bool(p + "arvalid")
      bb.bool(p + "arready") := io.ar.ready
      bb.uint(p + "rdata")   := io.r.bits.data
      bb.uint(p + "rresp")   := io.r.bits.resp
      bb.bool(p + "rlast")   := io.r.bits.last
      bb.bool(p + "rvalid")  := io.r.valid
      io.r.ready             := bb.bool(p + "rready")
    } else {
      io.ar.valid := false.B
      io.r.ready  := true.B
    }
  }

  // AXI4 master side of a bridge (out) to an AXI4 slave port of the chip (in), resizing IDs and addresses
  def chipSlavePort(in: AXI4Bundle, out: AXI4Bundle): Unit = {
    require(in.params.dataBits == out.params.dataBits, s"data width ${out.params.dataBits} != ${in.params.dataBits}")
    require(in.params.idBits >= out.params.idBits, s"chip port has ${in.params.idBits} ID bits, ${out.params.idBits} needed")
    in.aw.bits := DontCare
    in.w.bits  := DontCare
    in.ar.bits := DontCare
    out.b.bits := DontCare
    out.r.bits := DontCare
    Seq((in.aw, out.aw), (in.ar, out.ar)).foreach { case (i, o) =>
      i.valid      := o.valid
      o.ready      := i.ready
      i.bits.id    := fit(o.bits.id, i.bits.id.getWidth)
      i.bits.addr  := fit(o.bits.addr, i.bits.addr.getWidth)
      i.bits.len   := o.bits.len
      i.bits.size  := o.bits.size
      i.bits.burst := o.bits.burst
      i.bits.lock  := o.bits.lock
      i.bits.cache := o.bits.cache
      i.bits.prot  := o.bits.prot
      i.bits.qos   := o.bits.qos
    }
    in.w.valid       := out.w.valid
    out.w.ready      := in.w.ready
    in.w.bits.data   := out.w.bits.data
    in.w.bits.strb   := out.w.bits.strb
    in.w.bits.last   := out.w.bits.last
    out.b.valid      := in.b.valid
    in.b.ready       := out.b.ready
    out.b.bits.id    := fit(in.b.bits.id, out.b.bits.id.getWidth)
    out.b.bits.resp  := in.b.bits.resp
    out.r.valid      := in.r.valid
    in.r.ready       := out.r.ready
    out.r.bits.id    := fit(in.r.bits.id, out.r.bits.id.getWidth)
    out.r.bits.data  := in.r.bits.data
    out.r.bits.resp  := in.r.bits.resp
    out.r.bits.last  := in.r.bits.last
  }

  def cores(th: VCU128FPGATestHarnessImp, binder: String): VCU128EthernetCores =
    th.ethernet.getOrElse(throw new Exception(s"$binder needs WithVCU128Ethernet"))

  val bridgeParams = Parameters.empty.alterPartial { case MonitorsEnabled => false }
}

// Binds the chip's AXI4 MMIO port to the register interfaces of the core and the DMA
class WithVCU128EthernetMMIO extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: AXI4MMIOPort, chipId: Int) => {
    val cores = VCU128EthernetConnect.cores(th, "WithVCU128EthernetMMIO")
    val mmio = LazyModule(new VCU128EthernetMMIO(port.edge, port.params.base, port.params.size)(
      VCU128EthernetConnect.bridgeParams))
    withClockAndReset(port.io.clock, th.referenceReset) { Module(mmio.module).suggestName("eth_mmio") }
    mmio.io_axi4.head <> port.io.bits

    cores.eth.io.elements("s_axi_lite_clk")  := port.io.clock
    cores.dma.io.elements("s_axi_lite_aclk") := port.io.clock
    VCU128EthernetConnect.lite(mmio.io_eth_regs.head, cores.eth, "s_axi_", VCU128Ethernet.regsAddrBits)
    VCU128EthernetConnect.lite(mmio.io_dma_regs.head, cores.dma, "s_axi_lite_", VCU128Ethernet.dmaRegsAddrBits)
  }
})

// Binds the chip's AXI4 slave port to the DMA's masters. The DMA's AXI side and the streams between the DMA and the
// core run on the port's clock.
class WithVCU128EthernetDMAPort extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: AXI4InPort, chipId: Int) => {
    val cores = VCU128EthernetConnect.cores(th, "WithVCU128EthernetDMAPort")
    val bridge = LazyModule(new VCU128EthernetDMABridge(port.params.beatBytes)(VCU128EthernetConnect.bridgeParams))
    withClockAndReset(port.io.clock, th.referenceReset) { Module(bridge.module).suggestName("eth_dma_bridge") }
    VCU128EthernetConnect.chipSlavePort(port.io.bits, bridge.io_mem.getWrappedValue)

    Seq("m_axi_sg_aclk", "m_axi_mm2s_aclk", "m_axi_s2mm_aclk").foreach(cores.dma.io.elements(_) := port.io.clock)
    cores.eth.io.elements("axis_clk") := port.io.clock
    val Seq(sg, mm2s, s2mm) = bridge.io_dma.getWrappedValue
    VCU128EthernetConnect.master(sg,   cores.dma, "m_axi_sg_",   read = true,  write = true)
    VCU128EthernetConnect.master(mm2s, cores.dma, "m_axi_mm2s_", read = true,  write = false)
    VCU128EthernetConnect.master(s2mm, cores.dma, "m_axi_s2mm_", read = false, write = true)
  }
})

// Binds the chip's external interrupts: 0 = DMA TX (MM2S), 1 = DMA RX (S2MM), 2 = core interrupt
class WithVCU128EthernetInterrupts extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: ExtIntPort, chipId: Int) => {
    val cores = VCU128EthernetConnect.cores(th, "WithVCU128EthernetInterrupts")
    require(port.io.getWidth == 3, s"expected 3 external interrupts, chip has ${port.io.getWidth}")
    port.io := Cat(cores.eth.bool("interrupt"), cores.dma.bool("s2mm_introut"), cores.dma.bool("mm2s_introut"))
  }
})

object VCU128Ethernet {
  // Register blocks in the chip's MMIO window: the core's 256 KiB (18 address bits) at the window's base, the DMA's
  // at +1 MiB (it decodes 10 address bits; 64 KiB reserved, as in the example)
  val regsOffset      = BigInt(0)
  val regsAddrBits    = 18
  val regsSize        = BigInt(1) << regsAddrBits
  val dmaRegsOffset   = BigInt(0x100000)
  val dmaRegsAddrBits = 10
  val dmaRegsSize     = BigInt(0x10000)

  // Pins (bank 67) and I/O standards, as in the example's routed design
  val pins = Seq(
    ("eth_sgmii_rxp",  "BJ22", "DIFF_HSTL_I_18"),
    ("eth_sgmii_rxn",  "BK21", "DIFF_HSTL_I_18"),
    ("eth_sgmii_txp",  "BG22", "DIFF_HSTL_I_18"),
    ("eth_sgmii_txn",  "BH22", "DIFF_HSTL_I_18"),
    ("eth_phyclk_p",   "BH27", "LVDS"),
    ("eth_phyclk_n",   "BJ27", "LVDS"),
    ("eth_mdc",        "BN27", "LVCMOS18"),
    ("eth_mdio",       "BG23", "LVCMOS18"),
    // Data input of the RX nibble's bitslice 0, which the RX pair does not use. The bitslice is in native mode, so
    // it needs an I/O pin: this pin places it (BITSLICE_RX_TX_X0Y195 in the example).
    ("eth_dummy_port_in", "BL23", "LVCMOS18"))

  // Creates the IPs. Same settings as eth-step3/vcu128_eth_ip.tcl, which step 3 checked against the example's .xci
  // files.
  def ipTcl(axiFreqMHz: Double) =
    s"""# AXI 1G Ethernet: tri-mode MAC + 1G PCS/PMA, SGMII over LVDS (625 MHz clock from the DP83867)
       |create_ip -name axi_ethernet -vendor xilinx.com -library ip -module_name vcu128_eth -dir $$ipdir -force
       |set_property -dict [list \\
       | CONFIG.PHY_TYPE {SGMII} \\
       | CONFIG.ENABLE_LVDS {true} \\
       | CONFIG.speed_1_2p5 {1G} \\
       | CONFIG.processor_mode {true} \\
       | CONFIG.PHYADDR {1} \\
       | CONFIG.Include_IO {true} \\
       | CONFIG.SupportLevel {1} \\
       | CONFIG.ClockSelection {Sync} \\
       | CONFIG.lvdsclkrate {625} \\
       | CONFIG.drpclkrate {50.0} \\
       | CONFIG.axiliteclkrate {${axiFreqMHz}} \\
       | CONFIG.axisclkrate {${axiFreqMHz}} \\
       | CONFIG.InstantiateBitslice0 {true} \\
       | CONFIG.tx_in_upper_nibble {true} \\
       | CONFIG.txlane0_placement {DIFF_PAIR_1} \\
       | CONFIG.rxlane0_placement {DIFF_PAIR_2} \\
       | CONFIG.TXMEM {4k} \\
       | CONFIG.RXMEM {4k} \\
       | CONFIG.TXCSUM {None} \\
       | CONFIG.RXCSUM {None} \\
       | CONFIG.Frame_Filter {true} \\
       | CONFIG.Number_of_Table_Entries {4} \\
       | CONFIG.Statistics_Counters {true} \\
       | CONFIG.Statistics_Width {64bit} \\
       |] [get_ips vcu128_eth]
       |# Set separately and last: setting the lane placements resets it to true. It must be false because the RX pair
       |# (BJ22/BK21) is on bitslices 4/5 of its nibble, so bitslice 0 is not a data pin.
       |set_property CONFIG.rxnibblebitslice0used {false} [get_ips vcu128_eth]
       |
       |# AXI DMA for the Ethernet streams: scatter-gather, with status/control streams, 32-bit addresses
       |create_ip -name axi_dma -vendor xilinx.com -library ip -module_name vcu128_eth_dma -dir $$ipdir -force
       |set_property -dict [list \\
       | CONFIG.c_include_sg {1} \\
       | CONFIG.c_sg_include_stscntrl_strm {1} \\
       | CONFIG.c_sg_length_width {16} \\
       | CONFIG.c_sg_use_stsapp_length {1} \\
       | CONFIG.c_include_mm2s_dre {1} \\
       | CONFIG.c_include_s2mm_dre {1} \\
       | CONFIG.c_addr_width {32} \\
       |] [get_ips vcu128_eth_dma]
       |
       |foreach {ip key expect} {
       |  vcu128_eth rxnibblebitslice0used false
       |  vcu128_eth rxlane0_placement DIFF_PAIR_2
       |  vcu128_eth txlane0_placement DIFF_PAIR_1
       |  vcu128_eth lvdsclkrate 625
       |  vcu128_eth_dma c_sg_include_stscntrl_strm 1
       |  vcu128_eth_dma c_addr_width 32
       |  vcu128_eth_dma c_m_axi_mm2s_data_width 32
       |  vcu128_eth_dma c_m_axi_s2mm_data_width 32
       |} {
       |  set got [get_property CONFIG.$$key [get_ips $$ip]]
       |  puts "$$ip: $$key = $$got"
       |  if {$$got ne $$expect} {
       |    error "$$ip: $$key is $$got, expected $$expect"
       |  }
       |}
       |""".stripMargin

  // Creates the core and the DMA, the pins, the streams and resets between them, and the tie-offs. Register
  // interfaces, DMA masters, interrupts and the AXI clocks are connected by the harness binders above.
  def apply(th: VCU128FPGATestHarnessImp): VCU128EthernetCores = {
    ElaborationArtefacts.add("vcu128_eth.vivado.tcl", ipTcl(th.referenceClockFreqMHz.toDouble))

    val sgmii_rxp = IO(Input(Bool())).suggestName("eth_sgmii_rxp")
    val sgmii_rxn = IO(Input(Bool())).suggestName("eth_sgmii_rxn")
    val sgmii_txp = IO(Output(Bool())).suggestName("eth_sgmii_txp")
    val sgmii_txn = IO(Output(Bool())).suggestName("eth_sgmii_txn")
    val phyclk_p  = IO(Input(Bool())).suggestName("eth_phyclk_p")
    val phyclk_n  = IO(Input(Bool())).suggestName("eth_phyclk_n")
    val mdc       = IO(Output(Bool())).suggestName("eth_mdc")
    val mdio      = IO(Analog(1.W)).suggestName("eth_mdio")
    val dummy_in  = IO(Input(Bool())).suggestName("eth_dummy_port_in")

    val ports: Map[String, Element] = Map(
      "eth_sgmii_rxp" -> sgmii_rxp, "eth_sgmii_rxn" -> sgmii_rxn, "eth_sgmii_txp" -> sgmii_txp,
      "eth_sgmii_txn" -> sgmii_txn, "eth_phyclk_p" -> phyclk_p, "eth_phyclk_n" -> phyclk_n,
      "eth_mdc" -> mdc, "eth_mdio" -> mdio, "eth_dummy_port_in" -> dummy_in)
    pins.foreach { case (name, pin, std) =>
      th.vcu128Outer.xdc.addPackagePin(IOPin(ports(name)), pin)
      th.vcu128Outer.xdc.addIOStandard(IOPin(ports(name)), std)
    }

    val eth = Module(new VCU128EthernetBlackBox).suggestName("eth")
    val dma = Module(new VCU128EthernetDMABlackBox).suggestName("eth_dma")

    // Resets: the DMA and the core's register interface from the harness reset; the core's stream interfaces from
    // the DMA's per-channel reset outputs, so a DMA reset also resets them (as in the example)
    val resetn = !th.referenceReset.asBool
    dma.bool("axi_resetn")        := resetn
    eth.bool("s_axi_lite_resetn") := resetn
    eth.bool("axi_txd_arstn") := dma.bool("mm2s_prmry_reset_out_n")
    eth.bool("axi_txc_arstn") := dma.bool("mm2s_cntrl_reset_out_n")
    eth.bool("axi_rxd_arstn") := dma.bool("s2mm_prmry_reset_out_n")
    eth.bool("axi_rxs_arstn") := dma.bool("s2mm_sts_reset_out_n")

    // Streams: DMA TX data/control -> core, core RX data/status -> DMA
    def stream(from: VCU128NamedPortsBlackBox, f: String, to: VCU128NamedPortsBlackBox, t: String): Unit = {
      Seq("tdata", "tkeep", "tlast", "tvalid").foreach(s => to.data(t + s) := from.data(f + s))
      from.bool(f + "tready") := to.bool(t + "tready")
    }
    stream(dma, "m_axis_mm2s_",       eth, "s_axis_txd_")
    stream(dma, "m_axis_mm2s_cntrl_", eth, "s_axis_txc_")
    stream(eth, "m_axis_rxd_",        dma, "s_axis_s2mm_")
    stream(eth, "m_axis_rxs_",        dma, "s_axis_s2mm_sts_")

    // Pins
    eth.bool("sgmii_rxp") := sgmii_rxp
    eth.bool("sgmii_rxn") := sgmii_rxn
    sgmii_txp := eth.bool("sgmii_txp")
    sgmii_txn := eth.bool("sgmii_txn")
    eth.bool("lvds_clk_clk_p") := phyclk_p
    eth.bool("lvds_clk_clk_n") := phyclk_n
    eth.bool("dummy_port_in")  := dummy_in
    mdc := eth.bool("mdio_mdc")
    val mdioBuf = Module(new IOBUF)
    mdioBuf.io.I := eth.bool("mdio_mdio_o")
    mdioBuf.io.T := eth.bool("mdio_mdio_t")
    eth.bool("mdio_mdio_i") := mdioBuf.io.O
    attach(mdioBuf.io.IO, mdio)

    // Tie-offs as in the example's generated block-design wrapper: no other nibbles share this core's bitslice
    // logic, and signal_detect is unused with SGMII. mac_irq is not used by the Linux driver.
    eth.bool("signal_detect") := true.B
    (1 to 3).foreach { i =>
      eth.bool(s"riu_valid_$i") := false.B
      eth.bool(s"riu_prsnt_$i") := false.B
      eth.uint(s"riu_rddata_$i") := 0.U
      Seq("tx_dly_rdy", "rx_dly_rdy", "tx_vtc_rdy", "rx_vtc_rdy").foreach(n => eth.bool(s"${n}_$i") := true.B)
    }

    VCU128EthernetCores(eth, dma)
  }
}
