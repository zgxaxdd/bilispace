package com.naaammme.bbspace.core.designsystem.motion.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleRendererPolicyTest {

    @Test
    fun `shouldNotifyParticleAnimationComplete fires once after duration`() {
        assertFalse(
            shouldNotifyParticleAnimationComplete(
                hasAnimationCompleted = false,
                currentTimeSec = 0.40f,
                animationDurationSec = 0.82f
            )
        )
        assertTrue(
            shouldNotifyParticleAnimationComplete(
                hasAnimationCompleted = false,
                currentTimeSec = 0.90f,
                animationDurationSec = 0.82f
            )
        )
        assertFalse(
            shouldNotifyParticleAnimationComplete(
                hasAnimationCompleted = true,
                currentTimeSec = 1.20f,
                animationDurationSec = 0.82f
            )
        )
    }
}
