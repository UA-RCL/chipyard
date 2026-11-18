#!/bin/bash
# FireMarshal host-init for br-eth, run before every build:
#  1. Applies the kernel patches in patches/ to FireMarshal's Linux source (skipped if already applied). The
#     DP83867 patch is from the working VCU128 MicroBlaze/PetaLinux example: the VCU128 does not strap the PHY for
#     SGMII, so without it the PHY stays in RGMII mode and no frames pass, although the copper link comes up.
#  2. Writes local-mac.dtsi with the board's MAC address from the git-ignored file mac-address (one line,
#     aa:bb:cc:dd:ee:ff). Without that file the placeholder address in vcu128-eth.dts is used.
#  3. Builds vcu128-eth.dtb, the device tree OpenSBI embeds (FW_FDT_PATH in br-eth.json) and passes to Linux in
#     place of the one in the bootrom. It is the chip's generated device tree with the Ethernet node from
#     vcu128-eth.dts added, so the RocketVCU128EthernetConfig design must have been elaborated in this checkout
#     first.
#  4. If the device tree differs from the one the current image was built with, deletes the image so FireMarshal
#     rebuilds it. FireMarshal does not track the device tree itself, so a changed MAC address or .dts would
#     otherwise not reach the image.
set -euo pipefail
cd "$(dirname "$0")"

# 1. Kernel patches
LINUX=../../firemarshal/boards/default/linux
for p in patches/*.patch; do
  if git -C "$LINUX" apply --reverse --check "$PWD/$p" 2>/dev/null; then
    echo "host-init: $p already applied"
  elif git -C "$LINUX" apply --check "$PWD/$p"; then
    git -C "$LINUX" apply "$PWD/$p"
    echo "host-init: applied $p"
  else
    echo "host-init: $p does not apply to $LINUX" >&2
    exit 1
  fi
done

# 2. MAC address
if [ -f mac-address ]; then
  MAC=$(tr -d ' \t\r\n' < mac-address)
  if ! [[ "$MAC" =~ ^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$ ]]; then
    echo "host-init: mac-address must hold one address like aa:bb:cc:dd:ee:ff, got '$MAC'" >&2
    exit 1
  fi
  if (( 0x${MAC:0:2} & 1 )); then
    echo "host-init: $MAC is a multicast address, not usable as a MAC address" >&2
    exit 1
  fi
  echo "&eth0 { local-mac-address = [${MAC//:/ }]; };" > local-mac.dtsi
  echo "host-init: MAC address $MAC (from mac-address)"
else
  echo "// No mac-address file: the placeholder in vcu128-eth.dts is used" > local-mac.dtsi
  echo "host-init: WARNING: no mac-address file, using the placeholder MAC address from vcu128-eth.dts" >&2
fi

# 3. Device tree
CONFIG=chipyard.fpga.vcu128.VCU128FPGATestHarness.RocketVCU128EthernetConfig
GEN=../../../fpga/generated-src/$CONFIG
if [ ! -f "$GEN/$CONFIG.dts" ]; then
  echo "host-init: $GEN/$CONFIG.dts not found." >&2
  echo "host-init: run 'make SUB_PROJECT=vcu128 CONFIG=RocketVCU128EthernetConfig verilog' in fpga/ first." >&2
  exit 1
fi
dtc -I dts -O dtb -i "$GEN" -o vcu128-eth.dtb vcu128-eth.dts
echo "host-init: wrote $(pwd)/vcu128-eth.dtb"

# 4. Rebuild the image if the device tree changed
IMAGES=../../firemarshal/images/prototype/br-eth
SUM=$(sha256sum vcu128-eth.dtb | cut -d' ' -f1)
if [ "$(cat "$IMAGES/.vcu128-eth.dtb.sha256" 2>/dev/null)" != "$SUM" ]; then
  rm -f "$IMAGES/br-eth-bin" "$IMAGES/br-eth-bin-nodisk"
  mkdir -p "$IMAGES"
  echo "$SUM" > "$IMAGES/.vcu128-eth.dtb.sha256"
  echo "host-init: device tree changed, the br-eth image will be rebuilt"
fi
