package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateAcceleratorTest {

    @Test
    fun `accelerate prefixes release asset url`() {
        assertEquals(
            "https://gh-proxy.com/https://github.com/Mupceet/EssentialReader/releases/download/3.26.16/legado-3.26.16-arm64-v8a.apk",
            UpdateAccelerator.accelerate(
                "https://github.com/Mupceet/EssentialReader/releases/download/3.26.16/legado-3.26.16-arm64-v8a.apk"
            )
        )
    }

    @Test
    fun `accelerate prefixes raw manifest url without rewriting original`() {
        assertEquals(
            "https://gh-proxy.com/https://raw.githubusercontent.com/Mupceet/EssentialReader/update-manifests/official.json",
            UpdateAccelerator.accelerate(
                "https://raw.githubusercontent.com/Mupceet/EssentialReader/update-manifests/official.json"
            )
        )
    }
}
