package org.graphiks.hellodawn4k

import kotlin.test.Test
import kotlin.test.assertTrue

class CornellBoxNormalsTest {
    @Test
    fun sideWallNormalsFaceIntoTheBox() {
        val scene = CornellBox.scene()
        val leftWallNormalX = scene.quadData[3 * Scene.FLOATS_PER_QUAD + 20]
        val rightWallNormalX = scene.quadData[4 * Scene.FLOATS_PER_QUAD + 20]

        assertTrue(leftWallNormalX > 0f, "red wall should face +X into the room, normalX=$leftWallNormalX")
        assertTrue(rightWallNormalX < 0f, "green wall should face -X into the room, normalX=$rightWallNormalX")
    }
}