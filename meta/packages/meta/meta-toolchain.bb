DESCRIPTION = "Meta package for building a installable toolchain"
LICENSE = "MIT"
DEPENDS = "ipkg-native ipkg-utils-native fakeroot-native sed-native"

inherit sdk meta

SDK_DIR = "${WORKDIR}/sdk"
SDK_OUTPUT = "${SDK_DIR}/image"
SDK_OUTPUT2 = "${SDK_DIR}/image-extras"
SDK_DEPLOY = "${TMPDIR}/deploy/sdk"

IPKG_HOST = "ipkg-cl -f ${IPKGCONF_SDK} -o ${SDK_OUTPUT}"
IPKG_TARGET = "ipkg-cl -f ${IPKGCONF_TARGET} -o ${SDK_OUTPUT}/temp-target"

TOOLCHAIN_HOST_TASK ?= "task-sdk-host"
TOOLCHAIN_TARGET_TASK ?= "task-poky-standalone-sdk-target task-poky-standalone-sdk-target-dbg"
TOOLCHAIN_OUTPUTNAME ?= "${SDK_NAME}-toolchain-${DISTRO_VERSION}"

RDEPENDS = "${TOOLCHAIN_TARGET_TASK} ${TOOLCHAIN_HOST_TASK}"

do_populate_sdk() {
	rm -rf ${SDK_OUTPUT}
	mkdir -p ${SDK_OUTPUT}

	package_update_index_ipk
	package_generate_ipkg_conf

	for arch in ${PACKAGE_ARCHS}; do
		revipkgarchs="$arch $revipkgarchs"
	done

	${IPKG_HOST} update
	${IPKG_HOST} -force-depends install ${TOOLCHAIN_HOST_TASK}

	${IPKG_TARGET} update
	${IPKG_TARGET} install ${TOOLCHAIN_TARGET_TASK}

	mkdir -p ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/include
	mkdir -p ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/.debug/
	mkdir -p ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/share
	mv ${SDK_OUTPUT}/temp-target/usr/lib/ipkg/status ${SDK_OUTPUT}/${prefix}/package-status
	rm -rf ${SDK_OUTPUT}/temp-target/usr/lib/ipkg/
	cp -pPR ${SDK_OUTPUT}/temp-target/usr/include/* ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/include/
	cp -pPR ${SDK_OUTPUT}/temp-target/usr/lib/* ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/
	if [ -d ${SDK_OUTPUT}/temp-target/usr/lib/.debug ]; then
		cp -pPR ${SDK_OUTPUT}/temp-target/usr/lib/.debug/* ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/.debug/
	fi
	cp -pPR ${SDK_OUTPUT}/temp-target/usr/share/* ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/share/
	cp -pPR ${SDK_OUTPUT}/temp-target/lib/* ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/
	if [ -d ${SDK_OUTPUT}/temp-target/lib/.debug ]; then
		cp -pPR ${SDK_OUTPUT}/temp-target/lib/.debug/* ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/.debug/
	fi
	rm -rf ${SDK_OUTPUT}/temp-target/

	for fn in `ls ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/`; do
		if [ -h ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/$fn ]; then
			link=`readlink ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/$fn`
			bname=`basename $link`
			if [ ! -e $link -a -e ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/$bame ]; then
				rm ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/$fn
				ln -s $bname ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/$fn
			fi
		fi
	done

	echo 'GROUP ( libpthread.so.0 libpthread_nonshared.a )' > ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/libpthread.so
	echo 'GROUP ( libc.so.6 libc_nonshared.a )' > ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/libc.so

	# remove unwanted housekeeping files
	mv ${SDK_OUTPUT}/usr/lib/ipkg/status ${SDK_OUTPUT}/${prefix}/package-status-host
	rm -Rf ${SDK_OUTPUT}/usr/lib

	# extract and store ipks, pkgdata and shlibs data
	target_pkgs=`cat ${SDK_OUTPUT}/${prefix}/package-status | grep Package: | cut -f 2 -d ' '`
	mkdir -p ${SDK_OUTPUT2}/${prefix}/ipk/
	mkdir -p ${SDK_OUTPUT2}/${prefix}/pkgdata/runtime/
	mkdir -p ${SDK_OUTPUT2}/${prefix}/${TARGET_SYS}/shlibs/
	for pkg in $target_pkgs ; do
		for arch in $revipkgarchs; do
			pkgnames=${DEPLOY_DIR_IPK}/$arch/${pkg}_*_$arch.ipk
			if [ -e $pkgnames ]; then
				echo "Found $pkgnames"
				cp $pkgnames ${SDK_OUTPUT2}/${prefix}/ipk/
				orig_pkg=`ipkg-list-fields $pkgnames | grep OE: | cut -d ' ' -f2`
				pkg_subdir=$arch${TARGET_VENDOR}${@['-' + bb.data.getVar('TARGET_OS', d, 1), ''][bb.data.getVar('TARGET_OS', d, 1) == ('' or 'custom')]}
				mkdir -p ${SDK_OUTPUT2}/${prefix}/pkgdata/$pkg_subdir/runtime
				cp ${STAGING_DIR}/pkgdata/$pkg_subdir/$orig_pkg ${SDK_OUTPUT2}/${prefix}/pkgdata/$pkg_subdir/
				subpkgs=`cat ${STAGING_DIR}/pkgdata/$pkg_subdir/$orig_pkg | grep PACKAGES: | cut -b 10-`
				for subpkg in $subpkgs; do
					cp ${STAGING_DIR}/pkgdata/$pkg_subdir/runtime/$subpkg ${SDK_OUTPUT2}/${prefix}/pkgdata/$pkg_subdir/runtime/
					if [ -e ${STAGING_DIR}/pkgdata/$pkg_subdir/runtime/$subpkg.packaged ];then
						cp ${STAGING_DIR}/pkgdata/$pkg_subdir/runtime/$subpkg.packaged ${SDK_OUTPUT2}/${prefix}/pkgdata/$pkg_subdir/runtime/
					fi
					if [ -e ${STAGING_DIR_TARGET}/shlibs/$subpkg.list ]; then
						cp ${STAGING_DIR_TARGET}/shlibs/$subpkg.* ${SDK_OUTPUT2}/${prefix}/${TARGET_SYS}/shlibs/
					fi
				done
				break
			fi
		done
	done

	# Remove broken .la files
	rm -f ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}/lib/*.la

	# Generate link for sysroot use
	# /usr/local/poky/eabi-glibc/arm/arm-poky-linux-gnueabi/usr -> .
	cd ${SDK_OUTPUT}/${prefix}/${TARGET_SYS}
	ln -sf . usr 

	# Setup site file for external use
	siteconfig=${SDK_OUTPUT}/${prefix}/site-config
	touch $siteconfig
	for sitefile in ${CONFIG_SITE} ; do
		cat $sitefile >> $siteconfig
	done

	# Create environment setup script
	script=${SDK_OUTPUT}/${prefix}/environment-setup
	touch $script
	echo 'export PATH=${prefix}/bin:$PATH' >> $script
	echo 'export PKG_CONFIG_SYSROOT_DIR=${prefix}/${TARGET_SYS}' >> $script
	echo 'export PKG_CONFIG_PATH=${prefix}/${TARGET_SYS}/lib/pkgconfig' >> $script
	echo 'export CONFIG_SITE=${prefix}/site-config' >> $script

	# Add version information
	versionfile=${SDK_OUTPUT}/${prefix}/version
	touch $versionfile
	echo 'Distro: ${DISTRO}' >> $versionfile
	echo 'Distro Version: ${DISTRO_VERSION}' >> $versionfile
	echo 'Metadata Revision: ${METADATA_REVISION}' >> $versionfile
	echo 'Timestamp: ${DATETIME}' >> $versionfile

	# Package it up
	mkdir -p ${SDK_DEPLOY}
	cd ${SDK_OUTPUT}
	fakeroot tar cfj ${SDK_DEPLOY}/${TOOLCHAIN_OUTPUTNAME}.tar.bz2 .
	cd ${SDK_OUTPUT2}
	fakeroot tar cfj ${SDK_DEPLOY}/${TOOLCHAIN_OUTPUTNAME}-extras.tar.bz2 .
}

do_populate_sdk[nostamp] = "1"
do_populate_sdk[recrdeptask] = "do_package_write"
addtask populate_sdk before do_build after do_install
