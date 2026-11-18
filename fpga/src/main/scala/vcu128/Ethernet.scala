package chipyard.fpga.vcu128

import chisel3._
import chisel3.experimental.{Analog, attach}

import scala.collection.immutable.SeqMap

import org.chipsalliance.cde.config.{Config, Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}
import org.chipsalliance.diplomacy.nodes.{MonitorsEnabled}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink.{DevNullParams, TLError}
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{ElaborationArtefacts}

import sifive.fpgashells.ip.xilinx.{IOBUF}
import sifive.fpgashells.shell._

import chipyard.harness._
import chipyard.iobinders._

// 1G Ethernet on the VCU128: AXI 1G Ethernet (tri-mode MAC + 1G PCS/PMA, SGMII over LVDS) to the on-board TI DP83867
// PHY. IP settings and pins reproduce the working MicroBlaze example (example_ethernet), which used the VCU128 board
// flow; the Chipyard flow has no board part, so they are written out explicitly here.
//
// Bring-up state (step 5): pins and placement as in the example; the register interface is on the chip's AXI4 MMIO
// port at its base (0x6000_0000). The streams are still tied off (DMA and interrupts come in step 6).

case object VCU128EthernetKey extends Field[Boolean](false)

class WithVCU128Ethernet extends Config(
  new WithVCU128EthernetMMIO ++
  new Config((site, here, up) => {
    case VCU128EthernetKey => true
  })
)

// Ports of the vcu128_eth IP (from its instantiation template, vcu128_eth.veo).
// The chipyard_fpga sbt project is built without the Chisel compiler plugin, which every Bundle needs, so this is a
// Record with an explicit element list.
class VCU128EthernetIO extends Record {
  private def in(n: String, w: Int = 1)  = n -> (if (w == 1) Input(Bool()) else Input(UInt(w.W)))
  private def out(n: String, w: Int = 1) = n -> (if (w == 1) Output(Bool()) else Output(UInt(w.W)))
  private def nibbles(f: Int => (String, Data)) = (1 to 3).map(f)

  val elements = SeqMap[String, Data](Seq(
    // clocks and resets
    "s_axi_lite_clk" -> Input(Clock()), in("s_axi_lite_resetn"),
    "axis_clk" -> Input(Clock()), in("axi_txd_arstn"), in("axi_txc_arstn"), in("axi_rxd_arstn"), in("axi_rxs_arstn"),
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
    nibbles(i => out(s"rx_btval_$i", 9)) ++ Seq(
    // AXI4-Lite register interface
    in("s_axi_araddr", 18), in("s_axi_arvalid"), out("s_axi_arready"),
    in("s_axi_awaddr", 18), in("s_axi_awvalid"), out("s_axi_awready"),
    in("s_axi_wdata", 32), in("s_axi_wstrb", 4), in("s_axi_wvalid"), out("s_axi_wready"),
    out("s_axi_bresp", 2), out("s_axi_bvalid"), in("s_axi_bready"),
    out("s_axi_rdata", 32), out("s_axi_rresp", 2), out("s_axi_rvalid"), in("s_axi_rready"),
    // AXI4-Stream TX data and control (from the DMA)
    in("s_axis_txd_tdata", 32), in("s_axis_txd_tkeep", 4), in("s_axis_txd_tlast"), in("s_axis_txd_tvalid"),
    out("s_axis_txd_tready"),
    in("s_axis_txc_tdata", 32), in("s_axis_txc_tkeep", 4), in("s_axis_txc_tlast"), in("s_axis_txc_tvalid"),
    out("s_axis_txc_tready"),
    // AXI4-Stream RX data and status (to the DMA)
    out("m_axis_rxd_tdata", 32), out("m_axis_rxd_tkeep", 4), out("m_axis_rxd_tlast"), out("m_axis_rxd_tvalid"),
    in("m_axis_rxd_tready"),
    out("m_axis_rxs_tdata", 32), out("m_axis_rxs_tkeep", 4), out("m_axis_rxs_tlast"), out("m_axis_rxs_tvalid"),
    in("m_axis_rxs_tready"),
    // pins: SGMII over LVDS, 625 MHz clock from the PHY, MDIO
    in("sgmii_rxp"), in("sgmii_rxn"), out("sgmii_txp"), out("sgmii_txn"),
    in("lvds_clk_clk_p"), in("lvds_clk_clk_n"),
    out("mdio_mdc"), in("mdio_mdio_i"), out("mdio_mdio_o"), out("mdio_mdio_t")
  ): _*)

  def apply(name: String): Data = elements(name)
  override def cloneType: this.type = (new VCU128EthernetIO).asInstanceOf[this.type]
}

class VCU128EthernetBlackBox extends BlackBox {
  override def desiredName = "vcu128_eth"
  // Named explicitly: without the compiler plugin (see VCU128EthernetIO) nothing else names it "io"
  val io = IO(new VCU128EthernetIO).suggestName("io")

  def bool(name: String) = io(name).asInstanceOf[Bool]
  def uint(name: String) = io(name).asInstanceOf[UInt]
}

// Bridge from the chip's AXI4 MMIO port (64-bit, bursts, 4 ID bits) to the core's 32-bit AXI4-Lite register
// interface, built from rocket-chip adapters: AXI4 -> TileLink (as rocket-chip's AXI4 slave port does), a TileLink
// crossbar, and TileLink -> single-beat 32-bit AXI4 with no ID bits (as rocket-chip's AXI4-Lite tests do), which is
// AXI4-Lite. Addresses in the MMIO window outside the register block get an error response from a TLError device.
class VCU128EthernetMMIO(edge: AXI4EdgeParameters, base: BigInt, size: BigInt)(implicit p: Parameters)
    extends LazyModule {
  val master = AXI4MasterNode(Seq(edge.master))
  val regs = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    slaves = Seq(AXI4SlaveParameters(
      address       = Seq(AddressSet(base + VCU128Ethernet.regsOffset, VCU128Ethernet.regsSize - 1)),
      supportsRead  = TransferSizes(1, 4),
      supportsWrite = TransferSizes(1, 4),
      interleavedId = Some(0))),
    beatBytes = 4)))
  val error = LazyModule(new TLError(
    DevNullParams(Seq(AddressSet(base + size - 0x1000, 0xfff)), maxAtomic = 4, maxTransfer = edge.slave.maxTransfer),
    beatBytes = edge.slave.beatBytes))
  val xbar = TLXbar()

  // wcorrupt = false: the chip's MMIO port has no write-corrupt bit
  xbar := TLFIFOFixer(TLFIFOFixer.all) := AXI4ToTL(wcorrupt = false) := AXI4UserYanker(Some(4)) := AXI4Fragmenter() := master
  regs := AXI4UserYanker() := AXI4IdIndexer(0) := TLToAXI4() :=
    TLFragmenter(4, edge.slave.maxTransfer, holdFirstDeny = true) := TLWidthWidget(edge.slave.beatBytes) := xbar
  error.node := xbar

  val io_axi4 = InModuleBody { master.makeIOs() }
  val io_regs = InModuleBody { regs.makeIOs() }

  lazy val module = new LazyModuleImp(this)
}

// Binds the chip's AXI4 MMIO port to the Ethernet core's register interface
class WithVCU128EthernetMMIO extends HarnessBinder({
  case (th: VCU128FPGATestHarnessImp, port: AXI4MMIOPort, chipId: Int) => {
    val eth = th.ethernet.getOrElse(throw new Exception("WithVCU128EthernetMMIO needs WithVCU128Ethernet"))
    val mmio = LazyModule(new VCU128EthernetMMIO(port.edge, port.params.base, port.params.size)(
      Parameters.empty.alterPartial { case MonitorsEnabled => false }))
    withClockAndReset(port.io.clock, th.referenceReset) { Module(mmio.module).suggestName("eth_mmio") }
    mmio.io_axi4.head <> port.io.bits

    // AXI4-Lite: every transfer is a single beat of at most 4 bytes, and there are no ID bits
    val regs = mmio.io_regs.head
    eth.io("s_axi_lite_clk") := port.io.clock
    eth.uint("s_axi_awaddr")  := regs.aw.bits.addr(VCU128Ethernet.regsAddrBits - 1, 0)
    eth.bool("s_axi_awvalid") := regs.aw.valid
    regs.aw.ready             := eth.bool("s_axi_awready")
    eth.uint("s_axi_wdata")   := regs.w.bits.data
    eth.uint("s_axi_wstrb")   := regs.w.bits.strb
    eth.bool("s_axi_wvalid")  := regs.w.valid
    regs.w.ready              := eth.bool("s_axi_wready")
    regs.b.bits               := DontCare
    regs.b.bits.id            := 0.U
    regs.b.bits.resp          := eth.uint("s_axi_bresp")
    regs.b.valid              := eth.bool("s_axi_bvalid")
    eth.bool("s_axi_bready")  := regs.b.ready
    eth.uint("s_axi_araddr")  := regs.ar.bits.addr(VCU128Ethernet.regsAddrBits - 1, 0)
    eth.bool("s_axi_arvalid") := regs.ar.valid
    regs.ar.ready             := eth.bool("s_axi_arready")
    regs.r.bits               := DontCare
    regs.r.bits.id            := 0.U
    regs.r.bits.data          := eth.uint("s_axi_rdata")
    regs.r.bits.resp          := eth.uint("s_axi_rresp")
    regs.r.bits.last          := true.B
    regs.r.valid              := eth.bool("s_axi_rvalid")
    eth.bool("s_axi_rready")  := regs.r.ready
  }
})

object VCU128Ethernet {
  // Register block of the core in the chip's MMIO window: 256 KiB (18 address bits) at the window's base
  val regsOffset   = BigInt(0)
  val regsAddrBits = 18
  val regsSize     = BigInt(1) << regsAddrBits

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

  // Creates the IP. Same settings as eth-step3/vcu128_eth_ip.tcl, which step 3 checked against the example's .xci
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
       |foreach {key expect} {rxnibblebitslice0used false rxlane0_placement DIFF_PAIR_2 txlane0_placement DIFF_PAIR_1 lvdsclkrate 625} {
       |  set got [get_property CONFIG.$$key [get_ips vcu128_eth]]
       |  puts "vcu128_eth: $$key = $$got"
       |  if {$$got ne $$expect} {
       |    error "vcu128_eth: $$key is $$got, expected $$expect"
       |  }
       |}
       |""".stripMargin

  // Creates the core, its pins and tie-offs. The register interface (and its clock) is connected by
  // WithVCU128EthernetMMIO.
  def apply(th: VCU128FPGATestHarnessImp): VCU128EthernetBlackBox = {
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
    def b(n: String) = eth.bool(n)
    def u(n: String) = eth.uint(n)

    // Register interface and streams run on the chip's bus clock
    val resetn = !th.referenceReset.asBool
    eth.io("axis_clk")       := th.referenceClock
    Seq("s_axi_lite_resetn", "axi_txd_arstn", "axi_txc_arstn", "axi_rxd_arstn", "axi_rxs_arstn").foreach(b(_) := resetn)

    // Pins
    b("sgmii_rxp") := sgmii_rxp
    b("sgmii_rxn") := sgmii_rxn
    sgmii_txp := b("sgmii_txp")
    sgmii_txn := b("sgmii_txn")
    b("lvds_clk_clk_p") := phyclk_p
    b("lvds_clk_clk_n") := phyclk_n
    b("dummy_port_in")  := dummy_in
    mdc := b("mdio_mdc")
    val mdioBuf = Module(new IOBUF)
    mdioBuf.io.I := b("mdio_mdio_o")
    mdioBuf.io.T := b("mdio_mdio_t")
    b("mdio_mdio_i") := mdioBuf.io.O
    attach(mdioBuf.io.IO, mdio)

    // Tie-offs as in the example's generated block-design wrapper: no other nibbles share this core's bitslice
    // logic, and signal_detect is unused with SGMII
    b("signal_detect") := true.B
    (1 to 3).foreach { i =>
      b(s"riu_valid_$i") := false.B
      b(s"riu_prsnt_$i") := false.B
      u(s"riu_rddata_$i") := 0.U
      Seq("tx_dly_rdy", "rx_dly_rdy", "tx_vtc_rdy", "rx_vtc_rdy").foreach(n => b(s"${n}_$i") := true.B)
    }

    // Streams tied off until the DMA is added (step 6)
    Seq("s_axis_txd_tdata", "s_axis_txd_tkeep", "s_axis_txc_tdata", "s_axis_txc_tkeep").foreach(u(_) := 0.U)
    Seq("s_axis_txd_tlast", "s_axis_txd_tvalid", "s_axis_txc_tlast", "s_axis_txc_tvalid").foreach(b(_) := false.B)
    Seq("m_axis_rxd_tready", "m_axis_rxs_tready").foreach(b(_) := true.B)

    eth
  }
}
