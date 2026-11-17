package com.screenrecorder.pro.engine

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.screenrecorder.pro.model.NormalizedRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardware-accelerated OpenGL ES 2.0 + EGL pipeline for:
 * 1. Dynamic orientation-aware Display Surface Mirroring (no stretching/distortion).
 * 2. Zero-copy GPU sub-region cropping before MediaCodec AVC input surface encoding.
 */
class GlSurfaceCropRenderer(
    private val encoderInputSurface: Surface,
    private val outputWidth: Int,
    private val outputHeight: Int,
    initialDisplayWidth: Int,
    initialDisplayHeight: Int,
    private val cropRegion: NormalizedRegion?
) {
    private val glThread = HandlerThread("GlCropPipelineThread")
    private lateinit var glHandler: Handler

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId: Int = 0
    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uStMatrixLoc: Int = -1
    private var uMvpMatrixLoc: Int = -1

    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private val isPaused = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private val displayDims = AtomicReference(initialDisplayWidth to initialDisplayHeight)

    private var pauseStartNs: Long = 0L
    private var accumulatedPausedNs: Long = 0L
    private var lastSubmittedPtsNs: Long = -1L

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val vertexCoords = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(vertexCoords)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun start(): Surface {
        glThread.start()
        glHandler = Handler(glThread.looper)

        val latch = CountDownLatch(1)
        var initError: Throwable? = null

        glHandler.post {
            try {
                initEgl()
                initGlProgram()
                updateTexCoords(cropRegion)
                updateViewportAspect(displayDims.get().first, displayDims.get().second)

                val st = SurfaceTexture(oesTextureId).apply {
                    setDefaultBufferSize(displayDims.get().first, displayDims.get().second)
                    setOnFrameAvailableListener({ texture ->
                        if (!isReleased.get()) {
                            renderFrame(texture)
                        }
                    }, glHandler)
                }
                surfaceTexture = st
                inputSurface = Surface(st)
            } catch (t: Throwable) {
                initError = t
            } finally {
                latch.countDown()
            }
        }

        latch.await()
        initError?.let { throw it }
        return requireNotNull(inputSurface)
    }

    fun updateDisplaySize(newWidth: Int, newHeight: Int) {
        if (isReleased.get()) return
        val safeW = (newWidth and 1.inv()).coerceAtLeast(2)
        val safeH = (newHeight and 1.inv()).coerceAtLeast(2)
        displayDims.set(safeW to safeH)
        glHandler.post {
            if (isReleased.get()) return@post
            surfaceTexture?.setDefaultBufferSize(safeW, safeH)
            updateViewportAspect(safeW, safeH)
        }
    }

    fun setPaused(paused: Boolean) {
        if (paused && !isPaused.getAndSet(true)) {
            pauseStartNs = System.nanoTime()
        } else if (!paused && isPaused.getAndSet(false)) {
            val delta = (System.nanoTime() - pauseStartNs).coerceAtLeast(0L)
            accumulatedPausedNs += delta
        }
    }

    fun release() {
        if (isReleased.getAndSet(true)) return
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                inputSurface?.release()
                surfaceTexture?.release()
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                }
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                }
                if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    }
                    if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        glThread.quitSafely()
    }

    private fun renderFrame(texture: SurfaceTexture) {
        texture.updateTexImage()
        if (isPaused.get()) {
            return
        }

        val rawNs = System.nanoTime()
        val adjustedNs = (rawNs - accumulatedPausedNs).coerceAtLeast(0L)
        if (adjustedNs <= lastSubmittedPtsNs) {
            return
        }
        lastSubmittedPtsNs = adjustedNs

        texture.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, adjustedNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Maps texture coordinates directly to the normalized crop region when Region Mode
     * is active. SurfaceTexture UV origin is bottom-left, whereas Android display bounds
     * are top-left, so stMatrix already handles SurfaceTexture inversion; we specify standard
     * OpenGL bottom-up UVs for [left..right] and [1-bottom..1-top].
     */
    private fun updateTexCoords(region: NormalizedRegion?) {
        val uLeft = region?.left ?: 0f
        val uRight = region?.right ?: 1f
        val vBottom = if (region != null) 1f - region.bottom else 0f
        val vTop = if (region != null) 1f - region.top else 1f

        val coords = floatArrayOf(
            uLeft,  vBottom,
            uRight, vBottom,
            uLeft,  vTop,
            uRight, vTop
        )
        texCoordBuffer.clear()
        texCoordBuffer.put(coords)
        texCoordBuffer.position(0)
    }

    /**
     * Preserves source aspect ratio inside outputWidth x outputHeight so orientation
     * changes never stretch or distort frames.
     */
    private fun updateViewportAspect(srcDisplayWidth: Int, srcDisplayHeight: Int) {
        Matrix.setIdentityM(mvpMatrix, 0)
        val region = cropRegion
        val effectiveSrcW = if (region != null) {
            srcDisplayWidth * (region.right - region.left)
        } else {
            srcDisplayWidth.toFloat()
        }
        val effectiveSrcH = if (region != null) {
            srcDisplayHeight * (region.bottom - region.top)
        } else {
            srcDisplayHeight.toFloat()
        }

        if (effectiveSrcW <= 0f || effectiveSrcH <= 0f) return
        val srcAspect = effectiveSrcW / effectiveSrcH
        val outAspect = outputWidth.toFloat() / outputHeight.toFloat()

        if (kotlin.math.abs(srcAspect - outAspect) < 0.005f) {
            return
        }
        if (srcAspect > outAspect) {
            // Source is wider than encoder surface -> letterbox vertically without stretching
            Matrix.scaleM(mvpMatrix, 0, 1f, outAspect / srcAspect, 1f)
        } else {
            // Source is taller than encoder surface -> pillarbox horizontally without stretching
            Matrix.scaleM(mvpMatrix, 0, srcAspect / outAspect, 1f, 1f)
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "Unable to initialize EGL14"
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            "Unable to choose EGLConfig"
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0
        )
        check(eglContext !== EGL14.EGL_NO_CONTEXT) { "Unable to create EGLContext" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            configs[0],
            encoderInputSurface,
            surfaceAttribs,
            0
        )
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun initGlProgram() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            "GL program link failed: ${GLES20.glGetProgramInfoLog(programId)}"
        }

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uStMatrixLoc = GLES20.glGetUniformLocation(programId, "uStMatrix")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMvpMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uStMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uStMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
