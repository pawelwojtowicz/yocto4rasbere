Changes to default:

-> mosquitto broker added
-> static IP configured to 192.168.10.49

Preparation

1. Install Ubuntu 18.04
2. Install the required dependencies

	sudo apt install gawk wget git-core diffstat unzip texinfo gcc-multilib build-essential chrpath python3-distutils -y

Build:

1. Initialize the environment:

	source oe-init-build-env wojtech-rpi2

2. Build it:

	bitbake core-image-minimal

3. Install it on the raspberrypi2:

	sudo bmaptool copy core-image-minimal-raspberrypi2-20201214081717.rootfs.wic.bz2 /dev/sdc


