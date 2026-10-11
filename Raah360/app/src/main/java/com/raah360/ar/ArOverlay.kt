package com.raah360.ar

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2

/**
 * What to draw on the real floor this frame, in AR world coordinates (metres, y up).
 * Built on the UI thread, read on the GL thread.
 */
class ArScene(
    /** Chevrons: x, z, heading (degrees, AR plane compass style) per chevron. */
    val chevrons: FloatArray,
    /** Height of the floor in AR coordinates. */
    val floorY: Float,
    /** RGBA of the chevrons. */
    val color: FloatArray,
    /** Destination (x, z) when it is on this floor and ahead, else null. */
    val destination: FloatArray?,
)

/**
 * Draws the route on the real floor over the camera picture: flat chevrons that point the way,
 * and a pin standing on the destination. Plain GLES 2, no 3D library.
 */
class ArOverlay {
    @Volatile var scene: ArScene? = null

    private var program = 0
    private var aPos = 0
    private var uMvp = 0
    private var uColor = 0
    private lateinit var chevron: FloatBuffer
    private lateinit var diamond: FloatBuffer
    private lateinit var pole: FloatBuffer
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    private fun buffer(v: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    private fun create() {
        // chevron pointing +x, lying on the floor (x, y, z)
        chevron = buffer(floatArrayOf(
            0.32f, 0f, 0f, -0.22f, 0f, -0.28f, -0.04f, 0f, 0f,
            0.32f, 0f, 0f, -0.04f, 0f, 0f, -0.22f, 0f, 0.28f,
        ))
        // upright diamond (faces +z) and a thin pole, turned towards the camera when drawn
        diamond = buffer(floatArrayOf(
            0f, 0.3f, 0f, -0.22f, 0f, 0f, 0.22f, 0f, 0f,
            0f, -0.3f, 0f, 0.22f, 0f, 0f, -0.22f, 0f, 0f,
        ))
        pole = buffer(floatArrayOf(
            -0.03f, 0f, 0f, 0.03f, 0f, 0f, 0.03f, 1.45f, 0f,
            -0.03f, 0f, 0f, 0.03f, 1.45f, 0f, -0.03f, 1.45f, 0f,
        ))
        val vs = compile(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            void main() { gl_Position = u_Mvp * a_Position; }
        """.trimIndent())
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
    }

    /** Called on the GL thread after the camera picture, with ARCore's view/projection matrices. */
    fun draw(view: FloatArray, projection: FloatArray, camX: Float, camZ: Float) {
        val sc = scene ?: return
        if (program == 0) create()
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // guidance is always visible, never hidden behind walls
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnableVertexAttribArray(aPos)

        val c = sc.chevrons
        var i = 0
        while (i + 2 < c.size) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, c[i], sc.floorY + 0.02f, c[i + 1])
            // AR plane heading h points along (sin h, -cos h) in world x/z; +x of the mesh must face that way
            Matrix.rotateM(model, 0, 90f - c[i + 2], 0f, 1f, 0f)
            shape(chevron, 6, sc.color)
            i += 3
        }
        sc.destination?.let { d ->
            val face = Math.toDegrees(atan2((camX - d[0]).toDouble(), (camZ - d[1]).toDouble())).toFloat()
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, d[0], sc.floorY, d[1])
            Matrix.rotateM(model, 0, face, 0f, 1f, 0f)
            shape(pole, 6, PIN)
            Matrix.translateM(model, 0, 0f, 1.75f, 0f)
            shape(diamond, 6, PIN)
        }
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun shape(buf: FloatBuffer, count: Int, color: FloatArray) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        val GREEN = floatArrayOf(0.13f, 0.77f, 0.37f, 0.85f)
        val RED = floatArrayOf(0.94f, 0.27f, 0.27f, 0.85f)
        private val PIN = floatArrayOf(0.13f, 0.77f, 0.37f, 0.95f)
    }
}
