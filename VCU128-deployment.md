# VCU128 deployment

Build the VCU128 bitstream (Rocket core with 1G Ethernet), the Linux image and the `uart_tsi` loader, then boot Linux on the board.

All build commands run from the ua-rcl-chipyard root, after `build-setup.sh`, with the environment sourced:

```
source env.sh
```

```
source <vivado-install>/2024.2/settings64.sh
```

## 1. Bitstream

```
cd fpga && make SUB_PROJECT=vcu128 CONFIG=RocketVCU128EthernetConfig bitstream
```

Output:

```
fpga/generated-src/chipyard.fpga.vcu128.VCU128FPGATestHarness.RocketVCU128EthernetConfig/obj/VCU128FPGATestHarness.bit
```

`RocketVCU128Config` builds the same design without Ethernet.

The build directory also holds the Vivado project and the implemented design:

```
vivado fpga/generated-src/chipyard.fpga.vcu128.VCU128FPGATestHarness.RocketVCU128EthernetConfig/VCU128FPGATestHarness.xpr
```

```
vivado fpga/generated-src/chipyard.fpga.vcu128.VCU128FPGATestHarness.RocketVCU128EthernetConfig/obj/post_route.dcp
```

## 2. MAC address

**Set the board's MAC address before building the Linux image.** Put the address registered for the board in this file (one line):

```
echo 'xx:xx:xx:xx:xx:xx' > software/vcu128-eth/br-eth/mac-address
```

The file is git-ignored; do not commit it! 

## 3. Linux image

Needs step 1 (at least `make ... verilog`) first: the image's device tree is built from the generated design.

```
cd software/firemarshal && MARSHAL_BOARD_DIR=$PWD/boards/prototype ./marshal -v -d build ../vcu128-eth/br-eth.json
```

Output:

```
software/firemarshal/images/prototype/br-eth/br-eth-bin-nodisk
```

The first build also builds Buildroot (about an hour); later builds take minutes. A changed MAC address or device tree is picked up automatically. The image includes DHCP on `eth0` and an SSH server at boot.

The build applies a DP83867 PHY patch (`software/vcu128-eth/br-eth/patches/`) to FireMarshal's kernel, so `software/firemarshal` shows as modified in `git status`. That is expected; do not commit it.

## 4. uart_tsi loader

Built statically so it runs on the board machine without the Chipyard toolchain:

```
cd generators/testchipip/uart_tsi && g++ -O3 -static -L $RISCV/lib -I ../src/main/resources/testchipip/csrc -I $RISCV/include -std=c++17 -o uart_tsi testchip_uart_tsi.cc ../src/main/resources/testchipip/csrc/testchip_tsi.cc ../src/main/resources/testchipip/csrc/testchip_htif.cc -lfesvr -lpthread
```

`ldd uart_tsi` should print `not a dynamic executable`.

## 5. Files for the board machine

| File | Path in ua-rcl-chipyard |
|---|---|
| Bitstream | `fpga/generated-src/chipyard.fpga.vcu128.VCU128FPGATestHarness.RocketVCU128EthernetConfig/obj/VCU128FPGATestHarness.bit` |
| Linux image | `software/firemarshal/images/prototype/br-eth/br-eth-bin-nodisk` |
| Loader | `generators/testchipip/uart_tsi/uart_tsi` |

Put them in one directory on the board machine, together with `prog.tcl`:

```
connect; targets -set -filter {name =~ "xcvu37p*"}; fpga -file VCU128FPGATestHarness.bit; disconnect
```

and `chk.tcl` (reports the JTAG chain and FPGA state, changes nothing):

```
connect; puts [targets]; targets -set -filter {name =~ "xcvu37p*"}; puts [fpga -state]
```

`xsdb` ships with PetaLinux, Vitis and Vivado. With PetaLinux 2024.2 it is at `<petalinux>/components/xsct/bin/xsdb` (not on the `PATH`).

## 6. Boot

Connect the board's RJ45 port to the network before booting.

### Serial ports

Two of the board's USB UARTs are used, on different ports:

| UART | Use | Baud |
|---|---|---|
| UART0 | Linux console (`screen`) | 115200 |
| UART1 | Image loading (`uart_tsi`) | 921600 |

To find the load port, program the FPGA (below), then try each port other than the console. On the load port this prints a value right away; on any other port it hangs (Ctrl-C, next port):

```
./uart_tsi +tty=/dev/ttyUSB3 +baudrate=921600 +no_hart0_msip +init_read=0x10000 none
```

### Steps

Terminal 1, console:

```
screen -S FPGA_UART_CONSOLE /dev/ttyUSB2 115200
```

Terminal 2, program the FPGA:

```
/scratch/petalinux/components/xsct/bin/xsdb prog.tcl
```

Terminal 2, load and start Linux:

```
./uart_tsi +tty=/dev/<load-port> +baudrate=921600 br-eth-bin-nodisk
```

Loading takes several minutes. Leave `uart_tsi` running after `Done loading program`: it starts the core and serves it while Linux runs. Reprogram the FPGA before every boot.

The console shows the OpenSBI banner, then Linux, including:

```
Generating dropbear SSH host keys in the background
Starting eth0 (DHCP): OK
Starting dropbear sshd: OK
```

Log in as `root` with password `fpga`.

### After login

**Change the root password right away.** The root file system is in RAM, so do this after every boot:

```
passwd
```

Check the address:

```
ip addr show eth0
```

## 7. SSH

The SSH host key is new on every boot, so remove the previous one first:

```
ssh-keygen -R <board-ip>
```

```
ssh root@<board-ip>
```

## Troubleshooting

- **`Error 16 from open: Device or resource busy`:** another program has the port open, usually a `screen` session (`screen -ls`, then `screen -X -S <name> quit`). Check with `fuser -v /dev/ttyUSBx`.
- **Load finishes but the console stays silent:** the load went to the wrong port, or the FPGA was not reprogrammed (for example after a `petalinux-boot` test). Reprogram with `prog.tcl` before every attempt.
- **Image in `images/firechip/` instead of `images/prototype/`:** `MARSHAL_BOARD_DIR` was not set. That image does not boot on the VCU128.
- **No DHCP lease:** check `ip link show eth0` shows the registered MAC address (step 2), and `dmesg | grep eth0` shows `Link is Up - 1Gbps/Full`.
- **SSH warns `REMOTE HOST IDENTIFICATION HAS CHANGED`:** expected after a reboot; run `ssh-keygen -R <board-ip>`.
- **Permission denied on `/dev/ttyUSB*`:** add the user to the `dialout` group, or use `sudo`.
- **`xsdb: command not found`:** use the full path from step 5.
