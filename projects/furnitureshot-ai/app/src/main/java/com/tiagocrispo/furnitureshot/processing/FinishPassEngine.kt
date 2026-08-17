package com.tiagocrispo.furnitureshot.processing

/**
 * Fidelity guard kept for UI compatibility.
 *
 * Previous versions re-segmented the already-composited JPEG, re-scaled the furniture,
 * applied a second tone pass and drew another oval shadow. That was a major source of
 * floating shadows, edge damage and lost wood microtexture.
 *
 * The high-fidelity pipeline now performs the only cutout/composition pass. This stage
 * deliberately does not repaint, resize or add shadows to the result.
 */
object FinishPassEngine {
    fun apply(resultPath: String): String = resultPath
}
