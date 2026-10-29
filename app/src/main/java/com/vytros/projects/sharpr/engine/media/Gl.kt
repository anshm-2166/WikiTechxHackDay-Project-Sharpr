package com.vytros.projects.sharpr.engine.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Minimal EGL wrapper: one GLES2 context that can render into a MediaCodec input surface. */
class EglCore {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun windowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0).also {
            check(it != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    fun swap(surface: EGLSurface, presentationNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationNs)
        EGL14.eglSwapBuffers(display, surface)
    }

    fun release(surface: EGLSurface?) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        surface?.let { EGL14.eglDestroySurface(display, it) }
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

private fun floats(vararg v: Float): FloatBuffer =
    ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

private fun compile(type: Int, src: String): Int {
    val s = GLES20.glCreateShader(type)
    GLES20.glShaderSource(s, src)
    GLES20.glCompileShader(s)
    val ok = IntArray(1)
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
    check(ok[0] != 0) { "Shader: " + GLES20.glGetShaderInfoLog(s) }
    return s
}

private fun program(vs: String, fs: String): Int {
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
    GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
    GLES20.glLinkProgram(p)
    val ok = IntArray(1)
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
    check(ok[0] != 0) { "Program: " + GLES20.glGetProgramInfoLog(p) }
    return p
}

private const val VS = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uTexMatrix;
varying vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexMatrix * aTex).xy;
}
"""

private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

/**
 * Decoder side: MediaCodec renders into a SurfaceTexture; we draw that (with its crop/flip matrix)
 * into an RGBA framebuffer and read it back top row first.
 */
class FrameReader(private val width: Int, private val height: Int) {
    private val oesTex: Int
    private val fbo: Int
    private val fboTex: Int
    private val prog: Int
    private val thread = HandlerThread("sharpr-frames").apply { start() }
    private val lock = Object()
    private var available = false
    private val texMatrix = FloatArray(16)

    val surfaceTexture: SurfaceTexture
    val surface: Surface

    // Image top must land on framebuffer row 0 (the bottom in GL), so the quad is drawn upside down.
    private val pos = floats(-1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f)
    private val tex = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init {
        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        oesTex = t[0]
        fboTex = t[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Incomplete framebuffer" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        prog = program(
            VS,
            """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = vec4(texture2D(sTex, vTex).rgb, 1.0); }
            """,
        )
        surfaceTexture = SurfaceTexture(oesTex).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({
                synchronized(lock) {
                    available = true
                    lock.notifyAll()
                }
            }, Handler(thread.looper))
        }
        surface = Surface(surfaceTexture)
    }

    /** Waits for the frame the decoder just released. */
    fun await(timeoutMs: Long = 3000): Boolean {
        synchronized(lock) {
            val until = System.currentTimeMillis() + timeoutMs
            while (!available) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            available = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        return true
    }

    /** Draws the latest frame and copies it, top row first, into [out] (width * height * 4 bytes). */
    fun read(out: ByteBuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, texMatrix, 0)
        drawQuad(prog, pos, tex)
        out.position(0)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        out.position(0)
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(2, intArrayOf(oesTex, fboTex), 0)
        GLES20.glDeleteProgram(prog)
        thread.quitSafely()
    }
}

/** Encoder side: uploads an RGBA frame (top row first) and draws it onto the current window surface. */
class FrameWriter(private val width: Int, private val height: Int, private val frameW: Int, private val frameH: Int) {
    private val tex: Int
    private val prog: Int
    private val pos = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    init {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameW, frameH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        prog = program(
            VS,
            """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
            """,
        )
    }

    fun draw(frame: ByteBuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        frame.position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameW, frameH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, IDENTITY, 0)
        drawQuad(prog, pos, uv)
    }

    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        GLES20.glDeleteProgram(prog)
    }
}

private fun drawQuad(prog: Int, pos: FloatBuffer, tex: FloatBuffer) {
    val aPos = GLES20.glGetAttribLocation(prog, "aPos")
    val aTex = GLES20.glGetAttribLocation(prog, "aTex")
    GLES20.glEnableVertexAttribArray(aPos)
    GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
    GLES20.glEnableVertexAttribArray(aTex)
    GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(aPos)
    GLES20.glDisableVertexAttribArray(aTex)
}
