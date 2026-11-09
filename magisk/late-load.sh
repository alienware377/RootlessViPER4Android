#!/system/bin/sh
# KernelSU's late-load mode starts after the phone has booted, and runs this
# instead of post-fs-data.sh, still before the module's files are mounted.
# The audio HAL has read its effect list by then, but reads it again whenever
# it restarts, so our copies get the same checks before they are mounted:
# one made from a list a system update has since changed, or one that fails
# validation, is kept out. post-fs-data.sh skips the boot watchdog for this,
# since the boot it watches is already over.
RV4A_LATE_LOAD=1
. "${0%/*}/post-fs-data.sh"
