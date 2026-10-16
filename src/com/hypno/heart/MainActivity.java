package com.hypno.heart;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 极简“催眠爱心”：粉白相间的爱心条纹，从中心不断向外扩张、铺满整个屏幕，不旋转。
 *
 * 纹理由片元着色器逐像素生成：每个像素换算成“爱心坐标”下的半径 d，
 * 再按 parity(floor(d * N - phase)) 上色。CPU 不参与逐帧绘制，任意分辨率都能满帧。
 * 爱心边界半径 rho(θ) 由参数方程离线采样成 512×1 查找表（GL_LUMINANCE_ALPHA 双通道 = 16bit）。
 */
public class MainActivity extends Activity {

    private GLSurfaceView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(new HypnoRenderer());
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) view.onResume();
    }

    @Override
    protected void onPause() {
        if (view != null) view.onPause();
        super.onPause();
    }

    static class HypnoRenderer implements GLSurfaceView.Renderer {

        private static final String TAG = "HypnoHeart";

        private static final int   SEGS    = 180;    // 爱心轮廓采样点
        private static final int   LUT     = 512;    // 角度方向查找表长度（2 的幂）
        private static final int   BANDS   = 15;     // 单位半径内的条纹数
        private static final float FLOW    = 1.60f;  // 向外扩张速度（条/秒）
        private static final float R_RATIO = 0.97f;  // 单位半径 / 屏幕短边

        private static final float PI = 3.14159265f;

        private static final String VS =
                "attribute vec2 aPos;\n"
              + "uniform vec2 uHalf;\n"
              + "varying vec2 vP;\n"
              + "void main() {\n"
              + "  vP = vec2(aPos.x * uHalf.x, -aPos.y * uHalf.y);\n"  // y 轴向下，与数学一致
              + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
              + "}\n";

        private static final String FS =
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
              + "precision highp float;\n"
              + "#else\n"
              + "precision mediump float;\n"
              + "#endif\n"
              + "varying vec2 vP;\n"
              + "uniform sampler2D uRho;\n"
              + "uniform float uN;\n"
              + "uniform float uPhase;\n"
              + "uniform float uR;\n"
              + "uniform vec3 uA;\n"
              + "uniform vec3 uB;\n"
              + "const float PI = 3.14159265;\n"
              + "void main() {\n"
              + "  float ang = atan(vP.y, vP.x);\n"
              + "  vec4 t4 = texture2D(uRho, vec2((ang + PI) / (2.0 * PI), 0.5));\n"
              + "  float rho = max((t4.r * 256.0 + t4.a) / 257.0, 0.02);\n"   // 双通道还原 16bit
              + "  float f = mod(length(vP) / rho * uN - uPhase, 2.0);\n"
              + "  float h = f * 0.5;\n"
              + "  float w = clamp(uN / (uR * rho) * 0.25, 0.0005, 0.25);\n"  // 约 1 像素过渡
              + "  float t = smoothstep(0.5 - w, 0.5 + w, h)"
              + "          - smoothstep(1.0 - w, 1.0 + w, h);\n"
              + "  gl_FragColor = vec4(mix(uA, uB, t), 1.0);\n"
              + "}\n";

        private final float[] hx = new float[SEGS + 1];
        private final float[] hy = new float[SEGS + 1];
        private final long t0 = System.nanoTime();

        private int program, aPos, uHalf, uN, uPhase, uR, uA, uB, uRho;
        private FloatBuffer quad;
        private int rhoTex;
        private float radius = 720f;   // 单位半径对应的像素数
        private float halfW = 1f, halfH = 1f;

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            buildHeart();
            program = buildProgram(VS, FS);
            aPos   = GLES20.glGetAttribLocation(program, "aPos");
            uHalf  = GLES20.glGetUniformLocation(program, "uHalf");
            uN     = GLES20.glGetUniformLocation(program, "uN");
            uPhase = GLES20.glGetUniformLocation(program, "uPhase");
            uR     = GLES20.glGetUniformLocation(program, "uR");
            uA     = GLES20.glGetUniformLocation(program, "uA");
            uB     = GLES20.glGetUniformLocation(program, "uB");
            uRho   = GLES20.glGetUniformLocation(program, "uRho");

            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            quad.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}).position(0);

            rhoTex = buildRhoTexture();
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            radius = Math.min(w, h) * R_RATIO;
            halfW = w * 0.5f / radius;
            halfH = h * 0.5f / radius;
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClearColor(1f, 0.3725f, 0.6353f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program == 0) return;

            float phase = ((System.nanoTime() - t0) / 1_000_000_000f * FLOW) % 2f;

            GLES20.glUseProgram(program);
            GLES20.glUniform2f(uHalf, halfW, halfH);
            GLES20.glUniform1f(uN, BANDS);
            GLES20.glUniform1f(uPhase, phase);
            GLES20.glUniform1f(uR, radius);
            GLES20.glUniform3f(uA, 1f, 1f, 1f);              // 白
            GLES20.glUniform3f(uB, 1f, 0.3725f, 0.6353f);    // 粉 0xFFFF5FA2
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rhoTex);
            GLES20.glUniform1i(uRho, 0);

            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
        }

        // ---------- 爱心 ----------

        /** 经典爱心参数方程 → 重心归零、归一化到单位圆内。 */
        private void buildHeart() {
            for (int i = 0; i <= SEGS; i++) {
                double t = i * 2.0 * Math.PI / SEGS;
                double x = 16.0 * Math.pow(Math.sin(t), 3.0);
                double y = 13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t)
                         - 2.0 * Math.cos(3 * t) - Math.cos(4 * t);
                hx[i] = (float) x;
                hy[i] = (float) -y;                       // 屏幕坐标 y 向下
            }

            float cross2 = 0f, mx = 0f, my = 0f;
            for (int i = 0; i < SEGS; i++) {
                float c = hx[i] * hy[i + 1] - hx[i + 1] * hy[i];
                cross2 += c;
                mx += (hx[i] + hx[i + 1]) * c;
                my += (hy[i] + hy[i + 1]) * c;
            }
            float gx = mx / (3f * cross2);
            float gy = my / (3f * cross2);

            float max = 1e-6f;
            for (int i = 0; i <= SEGS; i++) {
                hx[i] -= gx;
                hy[i] -= gy;
                max = Math.max(max, (float) Math.hypot(hx[i], hy[i]));
            }
            for (int i = 0; i <= SEGS; i++) {
                hx[i] /= max;
                hy[i] /= max;
            }
        }

        /** 从重心沿单位方向 (dx,dy) 求与爱心边界的距离。 */
        private float rayHit(float dx, float dy) {
            float best = Float.MAX_VALUE;
            for (int i = 0; i < SEGS; i++) {
                float x0 = hx[i], y0 = hy[i];
                float ex = hx[i + 1] - x0, ey = hy[i + 1] - y0;
                float den = dx * ey - dy * ex;
                if (den > -1e-9f && den < 1e-9f) continue;
                float t = (x0 * ey - y0 * ex) / den;
                if (t <= 0f || t >= best) continue;
                float u = (x0 * dy - y0 * dx) / den;
                if (u < 0f || u > 1f) continue;
                best = t;
            }
            return best;
        }

        /** rho(θ) → 512×1 纹理，亮度存高字节、alpha 存低字节（16bit 精度，线性插值够准）。 */
        private int buildRhoTexture() {
            ByteBuffer buf = ByteBuffer.allocateDirect(LUT * 2).order(ByteOrder.nativeOrder());
            for (int i = 0; i < LUT; i++) {
                float ang = -PI + 2f * PI * i / LUT;
                float hit = rayHit((float) Math.cos(ang), (float) Math.sin(ang));
                if (hit <= 0f || hit >= Float.MAX_VALUE) hit = 1f;
                int v = Math.round(hit * 65535f);
                buf.put((byte) ((v >> 8) & 0xFF));
                buf.put((byte) (v & 0xFF));
            }
            buf.position(0);

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, LUT, 1, 0,
                    GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf);
            return tex[0];
        }

        private int buildProgram(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) return 0;
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
                return 0;
            }
            return p;
        }

        private int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
                return 0;
            }
            return s;
        }
    }
}
