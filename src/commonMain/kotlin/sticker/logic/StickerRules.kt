package sticker.logic

import neton.core.http.BadRequestException
import sticker.dto.PackWrite

object StickerRules {
    const val DRAFT = 1
    const val PUBLISHED = 2
    const val OFFLINE = 3
    const val MAX_IMAGE = 500 * 1024
    const val MAX_PACKAGE = 32 * 1024 * 1024 - 1
    const val MAX_STICKERS = 256
    fun check(ok: Boolean, code: String) { if (!ok) throw BadRequestException("STICKER_${code.uppercase()}") }
    fun id(value: String): Long = value.toLongOrNull()?.takeIf { it > 0 } ?: throw BadRequestException("STICKER_INVALID_ID")
    fun pack(p: PackWrite) {
        check(Regex("[a-zA-Z0-9_-]{1,128}").matches(p.packId), "invalid_pack_id")
        id(p.authorId)
        check(p.version > 0, "invalid_version")
        check(p.name.isNotEmpty() && p.name.size <= 32 && p.name.all { it.key.length in 1..32 && it.value.isNotBlank() && it.value.length <= 128 }, "invalid_name")
    }
    fun draft(state: Int) = check(state == DRAFT, "draft_required")
    fun transition(from: Int, to: Int) = check((from == DRAFT && to == PUBLISHED) || (from == PUBLISHED && to == OFFLINE), "invalid_transition")
    fun filename(name: String) = check(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}").matches(name) && !name.contains(".."), "unsafe_filename")
}
