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

	private fun isDvProfile7(format: Format?): Boolean =
		format != null &&
			format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
			(format.codecs?.startsWith("dvhe.07") == true ||
				format.codecs?.startsWith("dvh1.07") == true)

	// ── Format patching ───────────────────────────────────────────────────────

	/**
	 * Build a new [Format] with:
	 *  - codecs string rewritten from "dvhe.07.*" → "dvhe.08.*"
	 *  - initializationData[1] (DVCC box) patched: profile 7→8, el_present 1→0
	 */
	private fun patchToProfile8(format: Format): Format {
		val p8Codecs = format.codecs
			?.replace("dvhe.07", "dvhe.08")
			?.replace("dvh1.07", "dvh1.08")

		val p8InitData = format.initializationData.mapIndexed { index, bytes ->
			// initializationData[1] is the dvcC box (DVCC record)
			if (index == 1) patchDvccBytes(bytes) else bytes
		}

		return format.buildUpon()
			.setCodecs(p8Codecs)
			.setInitializationData(p8InitData)
			.build()
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
	 */
	private fun patchDvccBytes(bytes: ByteArray): ByteArray {
		if (bytes.size < 4) return bytes

		val word = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
		val profile = (word shr 9) and 0x7F

		if (profile != 7) return bytes // not Profile 7 — leave untouched

		val level = (word shr 3) and 0x3F
		val rpu = (word shr 2) and 0x01

		// Rebuild word: profile=8, same level, same rpu_present, el_present=0, bl_present=1
		val newWord = (8 shl 9) or (level shl 3) or (rpu shl 2) or (0 shl 1) or 1

		val result = bytes.copyOf()
		result[2] = ((newWord shr 8) and 0xFF).toByte()
		result[3] = (newWord and 0xFF).toByte()

		Timber.d("DV compat: patched DVCC — profile 7→8, el_present 1→0 (force=$forceCompatMode)")
		return result
	}

	// ── ExoPlayer hooks ───────────────────────────────────────────────────────

	/**
	 * Intercept the input format before the codec is initialized.
	 * Swapping to Profile 8 here ensures initializationData (DVCC bytes) reach
	 * MediaCodec as Profile 8.1.
	 */
	@Throws(ExoPlaybackException::class)
	override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
		if (isDvProfile7(formatHolder.format)) {
			Timber.d("DV compat: intercepting Profile 7 input format → rewriting as Profile 8.1")
			formatHolder.format = patchToProfile8(formatHolder.format!!)
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
		if (isDvProfile7(format)) {
			val p8Format = patchToProfile8(format)
			val decoders = super.getDecoderInfos(mediaCodecSelector, p8Format, requiresSecureDecoder)
			if (decoders.isNotEmpty()) {
				Timber.d("DV compat: routing Profile 7 → Profile 8 decoder: ${decoders.first().name}")
				return decoders
			}
			Timber.d("DV compat: no Profile 8 decoder found — ExoPlayer fallback will handle it")
		}
		return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
	}
}
