package dev.lumen.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarUrlTest {
    private val helix = "https://static-cdn.jtvnw.net/jtv_user_pictures/abc-profile_image-300x300.png"

    @Test
    fun picksTheSmallestTwitchSizeThatCoversTheAvatar() {
        assertEquals(helix.replace("300x300", "70x70"), sizedAvatarUrl(helix, 72))
        assertEquals(helix.replace("300x300", "150x150"), sizedAvatarUrl(helix, 96))
        assertEquals(helix, sizedAvatarUrl(helix, 240))
        assertEquals(helix.replace("300x300", "600x600"), sizedAvatarUrl(helix, 2000))
    }

    @Test
    fun leavesOtherAddressesAlone() {
        val other = "https://cdn.example/avatar.png"
        assertEquals(other, sizedAvatarUrl(other, 72))
        assertEquals("", sizedAvatarUrl("", 72))
    }
}
