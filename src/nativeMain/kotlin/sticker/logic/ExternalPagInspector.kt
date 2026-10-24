@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package sticker.logic

import kotlinx.cinterop.*
import platform.posix.*
import sticker.process.privchat_pag_spawn

actual fun configuredPagInspector(executable: String?, usage: String): PagInspector {
    if (executable.isNullOrBlank()) return PagValidation.unavailable
    require(executable.startsWith('/') && '\u0000' !in executable) { "PAG inspector must be a configured absolute executable path" }
    require(usage == "sticker" || usage == "effect")
    return PagInspector { bytes ->
        StickerRules.check(bytes.size in 1..2*1024*1024, "pag_size")
        memScoped {
            val inputName = "/tmp/privchat-pag-input-XXXXXX".cstr.getPointer(this)
            val input = mkstemp(inputName)
            StickerRules.check(input >= 0, "pag_inspector_io")
            val pipeFds=allocArray<IntVar>(2)
            var output = -1; var writer = -1
            try {
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = write(input,pinned.addressOf(offset),(bytes.size-offset).convert())
                        StickerRules.check(count > 0, "pag_inspector_io")
                        offset += count.toInt()
                    }
                }
                StickerRules.check(pipe(pipeFds)==0,"pag_inspector_io")
                output=pipeFds[0]; writer=pipeFds[1]
                StickerRules.check(fcntl(output,F_SETFL,O_NONBLOCK)==0,"pag_inspector_io")
                val pid = privchat_pag_spawn(executable,inputName.toKString(),usage,writer)
                StickerRules.check(pid > 0,"pag_inspector_unavailable")
                close(writer); writer=-1
                var reaped = false
                try {
                    val data=ByteArray(16385); var used=0
                    fun drain() {
                        data.usePinned { pinned ->
                            while(true) {
                                val count=read(output,pinned.addressOf(used),(data.size-used).convert())
                                if(count>0) {
                                    used+=count.toInt()
                                    StickerRules.check(used<=16384,"pag_inspector_output")
                                } else if(count==0L) break
                                else {
                                    if(errno==EINTR) continue
                                    StickerRules.check(errno==EAGAIN || errno==EWOULDBLOCK,"pag_inspector_io")
                                    break
                                }
                            }
                        }
                    }
                    val status = alloc<IntVar>()
                    val clock = alloc<timespec>()
                    fun monotonicMs(): Long {
                        StickerRules.check(clock_gettime(CLOCK_MONOTONIC.convert(),clock.ptr) == 0, "pag_inspector_io")
                        return clock.tv_sec * 1000L + clock.tv_nsec / 1000000L
                    }
                    val deadline = monotonicMs() + 10000
                    while (true) {
                        drain()
                        val result = waitpid(pid,status.ptr,WNOHANG)
                        StickerRules.check(result >= 0, "pag_inspector_failed")
                        if (result == pid) {
                            reaped = true
                            drain()
                            StickerRules.check(status.value == 0, "pag_inspector_failed")
                            break
                        }
                        StickerRules.check(monotonicMs() < deadline, "pag_inspector_timeout")
                        usleep(10000u)
                    }
                    StickerRules.check(used>0,"pag_inspector_output")
                    PagValidation.decodeOfficial(data.copyOf(used).decodeToString(),usage,bytes.size)
                } finally {
                    if (!reaped) { kill(pid,SIGKILL); waitpid(pid,null,0) }
                }
            } finally {
                close(input); unlink(inputName)
                if(output>=0) close(output)
                if(writer>=0) close(writer)
            }
        }
    }
}
