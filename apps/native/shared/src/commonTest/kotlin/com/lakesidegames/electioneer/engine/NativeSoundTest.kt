package com.lakesidegames.electioneer.engine

import kotlin.test.*

class NativeSoundTest {
    @Test fun allWebCuesHaveValidBoundedMonoWaveforms() {
        for (cue in listOf("turnAdvance", "pollUp", "pollDown", "eventPopup", "win", "lose")) {
            val wav = NativeSound.wav(cue, 0.6)
            assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
            assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
            assertEquals(1, wav[22].toInt())
            assertEquals(16, wav[34].toInt())
            assertTrue(wav.size in 3000..26000)
            assertTrue(wav.drop(44).any { it != 0.toByte() })
            assertTrue(NativeSound.wav(cue, 0.0).drop(44).all { it == 0.toByte() })
            assertContentEquals(NativeSound.wav(cue, 1.0), NativeSound.wav(cue, 2.0))
            assertContentEquals(NativeSound.wav(cue, 0.0), NativeSound.wav(cue, Double.NaN))
        }
        assertTrue(NativeSound.wav("unknown", 0.6).isEmpty())
    }
    @Test fun guidesCoverEveryEngineAndUseActualVictoryRules() {
        assertEquals(10, NativeHelp.guide().size)
        for (country in listOf("US", "UK", "CA", "DE", "FR", "AU")) {
            val goal = if (country == "US") "Win 270 electoral votes" else MobileCampaign.start(country, MobileCampaign.elections(country).first().nativeId, MobileCampaign.parties(country, MobileCampaign.elections(country).first().nativeId).first().id, "normal", "guide").goalText()
            val steps = NativeHelp.steps(country, goal)
            assertEquals(8, steps.size)
            assertEquals(goal, steps.first().body)
        }
    }
}
