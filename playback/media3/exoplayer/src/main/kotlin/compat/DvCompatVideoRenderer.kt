package org.jellyfin.playback.media3.exoplayer.compat

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import timber.log.Timber

/**
 * A [MediaCodecVideoRenderer] that rewrites Dolby Vision Profile 7 streams as Profile 8.1
 * before presenting to MediaCodec.
 *
 * Profile 7 (DVHE.DTB) is a dual-layer format (BL + EL) rarely supported by Android TV decoders.
 * Profile 8.1 (DVHE.ST) is single-layer and broadly supported.
 *
 * This renderer patches the 4-byte DOVIDecoderConfigurationRecord (DVCC) to:
 *   - Set dv_profile  : 7 → 8
 *   - Set el_present  : 1 → 0  (strip Enhancement Layer signaling)
 *
 * When the DVCC is absent from initializationData (Media3 may derive MIME/codecs from
 * BlockAddIDExtraData without storing it in initializationData), we synthesize proper
 * Profile 8 DVCC bytes from the codecs string so the DV hardware decoder is configured
 * correctly.
 *
 * For MEL sources (most UHD Blu-ray rips) this is lossless — all DV metadata is in the BL RPU.
 * For FEL sources the EL pixel enhancement is discarded; DV tone-mapping metadata is preserved.
 */
@OptIn(UnstableApi::class)
class DvCompatVideoRenderer(
	context: Context,
	codecAdapterFactory: MediaCodecAdapter.Factory,
	mediaCodecSelector: MediaCodecSelector,
	allowedJoiningTimeMs: Long,
	enableDecoderFallback: Boolean,
	private val forceCompatMode: Boolean,
	eventHandler: Handler?,
	eventListener: VideoRendererEventListener?,
) : MediaCodecVideoRenderer(
	context,
	codecAdapterFactory,
	mediaCodecSelector,
	allowedJoiningTimeMs,
	enableDecoderFallback,
	eventHandler,
	eventListener,
	/* maxDroppedFramesToNotify= */ -1,
) {
	// ── Detection ─────────────────────────────────────────────────────────────

	/**
	 * Returns true if this is a Dolby Vision Profile 7 stream.
	 *
	 * Handles two source scenarios:
	 * 1. Media3 correctly detected DV via BlockAdditionMapping → sampleMimeType = VIDEO_DOLBY_VISION
	 *    with codecs string set (e.g. "dvhe.07.06")
	 * 2. Media3 detected DV via BlockAdditionMapping but no codecs string → fall back to
	 *    scanning initializationData for DVCC bytes
	 * 3. Media3 missed DV detection (MKV HEVC track without recognized DV signaling) →
	 *    sampleMimeType = VIDEO_H265, but initializationData may still contain DVCC bytes
	 */
	private fun isDvProfile7(format: Format?): Boolean {
		if (format == null) return false
		return when (format.sampleMimeType) {
			MimeTypes.VIDEO_DOLBY_VISION -> {
				val codecs = format.codecs
				if (codecs != null) {
					codecs.startsWith("dvhe.07") || codecs.startsWith("dvh1.07")
				} else {
					// No codecs string (can happen with MKV) — scan initializationData for DVCC
					format.initializationData.any { bytes -> getDvccProfile(bytes) == 7 }
				}
			}
			MimeTypes.VIDEO_H265 -> {
				// Fallback: MakeMKV-style MKV where Media3 may not set DV MIME type.
				// Check if any initializationData entry is a valid Profile 7 DVCC record.
				format.initializationData.any { bytes -> getDvccProfile(bytes) == 7 }
			}
			else -> false
		}
	}

	/**
	 * Extracts dv_profile from a DOVIDecoderConfigurationRecord byte array.
	 *
	 * Handles two layouts:
	 * - Raw record: bytes[0]=dv_version_major(1), bytes[1]=dv_version_minor,
	 *               bytes[2:3]=packed profile/level/flags
	 * - Full dvcC/dvvC box: 4-byte size + 4-byte fourcc + raw record starting at bytes[8]
	 *
	 * Returns the profile (1–9) or -1 if not a valid DVCC record.
	 */
	private fun getDvccProfile(bytes: ByteArray): Int {
		// Layout A: raw DOVIDecoderConfigurationRecord
		// bytes[0] = dv_version_major (must be 1)
		if (bytes.size >= 4 && bytes[0].toInt() == 1) {
			val word = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
			val profile = (word shr 9) and 0x7F
			if (profile in 1..9) return profile
		}
		// Layout B: full dvcC or dvvC ISO box (8-byte header before the record)
		// fourcc: "dvcC" = 0x64766343, "dvvC" = 0x64767643
		if (bytes.size >= 12) {
			val b4 = bytes[4].toInt() and 0xFF
			val b5 = bytes[5].toInt() and 0xFF
			val b6 = bytes[6].toInt() and 0xFF
			val b7 = bytes[7].toInt() and 0xFF
			val isDvcC = b4 == 0x64 && b5 == 0x76 && b6 == 0x63 && b7 == 0x43
			val isDvvC = b4 == 0x64 && b5 == 0x76 && b6 == 0x76 && b7 == 0x43
			if ((isDvcC || isDvvC) && bytes[8].toInt() == 1) {
				val word = ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
				val profile = (word shr 9) and 0x7F
				if (profile in 1..9) return profile
			}
		}
		return -1
	}

	// ── Format patching ───────────────────────────────────────────────────────

	/**
	 * Build a new [Format] with Profile 7 → Profile 8.1 rewrite.
	 *
	 * - Updates codecs string: "dvhe.07.*" → "dvhe.08.*"
	 * - Patches DVCC bytes in initializationData (profile 7→8, el_present 1→0)
	 * - If no DVCC found in initializationData, synthesizes Profile 8 DVCC from the
	 *   codecs string so the hardware DV decoder receives proper configuration data
	 * - Upgrades sampleMimeType from VIDEO_H265 to VIDEO_DOLBY_VISION when needed
	 */
	private fun patchToProfile8(format: Format): Format {
		val p8Codecs = format.codecs
			?.replace("dvhe.07", "dvhe.08")
			?.replace("dvh1.07", "dvh1.08")

		// Patch DVCC bytes in all initializationData entries
		var p8InitData = format.initializationData.map { bytes -> patchDvccBytes(bytes) }

		// If no Profile 8 DVCC ended up in initializationData after patching,
		// Media3 likely stored the DVCC only for MIME/codecs derivation (not in initData).
		// Synthesize and append Profile 8 DVCC so the DV hardware decoder is properly configured.
		if (p8InitData.none { bytes -> getDvccProfile(bytes) == 8 }) {
			val syntheticDvcc = buildProfile8Dvcc(format.codecs ?: p8Codecs)
			if (syntheticDvcc != null) {
				Timber.d("DV compat: no DVCC in initializationData — injecting synthetic Profile 8 DVCC")
				p8InitData = p8InitData + syntheticDvcc
			} else {
				Timber.w("DV compat: could not synthesize DVCC (codecs=${format.codecs})")
			}
		}

		val builder = format.buildUpon()
			.setCodecs(p8Codecs)
			.setInitializationData(p8InitData)

		// If Media3 typed this as HEVC (missed DV detection), upgrade MIME type to DV
		// so MediaCodec uses the DV decoder path instead of the plain HEVC decoder.
		if (format.sampleMimeType == MimeTypes.VIDEO_H265) {
			builder.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
		}

		return builder.build()
	}

	/**
	 * Rewrite the DOVIDecoderConfigurationRecord bytes.
	 *
	 * Byte layout (big-endian, bytes 2–3 form a 16-bit word):
	 *   Bits 15–9 : dv_profile  (7 bits)  ← 7 → 8
	 *   Bits  8–3 : dv_level    (6 bits)  ← preserved
	 *   Bit     2 : rpu_present (1 bit)   ← kept as-is
	 *   Bit     1 : el_present  (1 bit)   ← 1 → 0
	 *   Bit     0 : bl_present  (1 bit)   ← kept as-is (always 1)
	 *
	 * Handles both raw record and full dvcC/dvvC box layouts (see [getDvccProfile]).
	 */
	private fun patchDvccBytes(bytes: ByteArray): ByteArray {
		// Layout A: raw record
		if (bytes.size >= 4 && bytes[0].toInt() == 1) {
			val word = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
			val profile = (word shr 9) and 0x7F
			if (profile == 7) {
				Timber.d("DV compat: patching DVCC (raw) profile 7→8, el_present 1→0")
				return patchWordAt(bytes, 2, word)
			}
		}
		// Layout B: full dvcC/dvvC box
		if (bytes.size >= 12) {
			val b4 = bytes[4].toInt() and 0xFF
			val b5 = bytes[5].toInt() and 0xFF
			val b6 = bytes[6].toInt() and 0xFF
			val b7 = bytes[7].toInt() and 0xFF
			val isDvcC = b4 == 0x64 && b5 == 0x76 && b6 == 0x63 && b7 == 0x43
			val isDvvC = b4 == 0x64 && b5 == 0x76 && b6 == 0x76 && b7 == 0x43
			if ((isDvcC || isDvvC) && bytes[8].toInt() == 1) {
				val word = ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
				val profile = (word shr 9) and 0x7F
				if (profile == 7) {
					Timber.d("DV compat: patching DVCC (boxed) profile 7→8, el_present 1→0")
					return patchWordAt(bytes, 10, word)
				}
			}
		}
		return bytes
	}

	/** Rewrites the 16-bit packed field at [offset] in a copy of [bytes]: profile→8, el→0. */
	private fun patchWordAt(bytes: ByteArray, offset: Int, word: Int): ByteArray {
		val level = (word shr 3) and 0x3F
		val rpu = (word shr 2) and 0x01
		val newWord = (8 shl 9) or (level shl 3) or (rpu shl 2) or (0 shl 1) or 1
		val result = bytes.copyOf()
		result[offset] = ((newWord shr 8) and 0xFF).toByte()
		result[offset + 1] = (newWord and 0xFF).toByte()
		return result
	}

	/**
	 * Synthesizes a minimal Profile 8 DOVIDecoderConfigurationRecord from the codecs string.
	 *
	 * Used when Media3 derived MIME type/codecs from BlockAddIDExtraData but did not include
	 * the raw bytes in initializationData.
	 *
	 * @param codecs e.g. "dvhe.07.06" — the profile 7 source codecs string
	 */
	private fun buildProfile8Dvcc(codecs: String?): ByteArray? {
		if (codecs == null) return null
		// "dvhe.07.06" → parts[2] = "06" = level 6
		val level = codecs.split(".").getOrNull(2)?.toIntOrNull() ?: return null
		// Profile 8.1: dv_profile=8, same level, rpu_present=1, el_present=0, bl_present=1
		val word = (8 shl 9) or (level shl 3) or (1 shl 2) or (0 shl 1) or 1
		Timber.d("DV compat: synthesized Profile 8 DVCC for level=$level (word=0x${word.toString(16)})")
		return byteArrayOf(
			0x01.toByte(),                          // dv_version_major = 1
			0x00.toByte(),                          // dv_version_minor = 0
			((word shr 8) and 0xFF).toByte(),       // packed profile/level/flags high byte
			(word and 0xFF).toByte(),               // packed profile/level/flags low byte
			0x10.toByte(),                          // dv_bl_signal_compatibility_id=1 (BT.2020 PQ), reserved=0
			0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
			0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
			0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
			0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
			0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
		)
	}

	// ── ExoPlayer hooks ───────────────────────────────────────────────────────

	/**
	 * Intercept the input format before the codec is initialized.
	 * Swapping to Profile 8 here ensures initializationData (DVCC bytes) reach
	 * MediaCodec as Profile 8.1.
	 */
	@Throws(ExoPlaybackException::class)
	override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
		val fmt = formatHolder.format
		Timber.d(
			"DV compat: onInputFormatChanged " +
				"mime=${fmt?.sampleMimeType} codecs=${fmt?.codecs} " +
				"initDataCount=${fmt?.initializationData?.size}"
		)
		fmt?.initializationData?.forEachIndexed { i, bytes ->
			val preview = bytes.take(8).joinToString(" ") { "%02X".format(it) }
			Timber.d(
				"DV compat:   initData[$i] size=${bytes.size} " +
					"bytes=[$preview] dvccProfile=${getDvccProfile(bytes)}"
			)
		}

		if (isDvProfile7(formatHolder.format)) {
			Timber.d("DV compat: Profile 7 detected — rewriting as Profile 8.1 (force=$forceCompatMode)")
			formatHolder.format = patchToProfile8(formatHolder.format!!)

			// Log the patched format
			val patched = formatHolder.format
			Timber.d(
				"DV compat: patched → mime=${patched?.sampleMimeType} codecs=${patched?.codecs} " +
					"initDataCount=${patched?.initializationData?.size}"
			)
		} else {
			Timber.d("DV compat: not Profile 7 — passing through unchanged (mime=${fmt?.sampleMimeType})")
		}
		return super.onInputFormatChanged(formatHolder)
	}

	/**
	 * Override decoder selection so that on Profile 7 content we query for
	 * a Profile 8 decoder (broadly available) instead of a Profile 7 decoder (rare).
	 */
	@Throws(MediaCodecUtil.DecoderQueryException::class)
	override fun getDecoderInfos(
		mediaCodecSelector: MediaCodecSelector,
		format: Format,
		requiresSecureDecoder: Boolean,
	): List<MediaCodecInfo> {
		Timber.d(
			"DV compat: getDecoderInfos mime=${format.sampleMimeType} codecs=${format.codecs} " +
				"isDvP7=${isDvProfile7(format)}"
		)
		if (isDvProfile7(format)) {
			val p8Format = patchToProfile8(format)
			val decoders = super.getDecoderInfos(mediaCodecSelector, p8Format, requiresSecureDecoder)
			if (decoders.isNotEmpty()) {
				Timber.d("DV compat: routing Profile 7 → Profile 8 decoder: ${decoders.first().name}")
				return decoders
			}
			Timber.d("DV compat: no Profile 8 DV decoder found — ExoPlayer fallback will handle it")
		}
		return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
	}
}
