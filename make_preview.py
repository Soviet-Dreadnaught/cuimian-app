# 与 MainActivity 的片元着色器同一套数学的 numpy 实现，用来离线渲染预览 GIF（竖屏）
import os
import numpy as np
from PIL import Image

W, H    = 720, 1280           # 渲染分辨率（竖屏；几何与分辨率无关）
OW, OH  = 250, 444            # GIF 输出尺寸（9:16）
SEGS    = 180                 # 爱心轮廓采样点
LUT     = 4096                # 角度查找表
BANDS   = 15                  # 单位半径内的条纹数
FLOW    = 1.60                # 向外扩张速度（条/秒）
R_RATIO = 0.97                # 单位半径 / 屏幕短边
FPS     = 20
DUR     = 2.5                 # 秒（= 2 个完整周期，循环无缝）

COLOR_A = np.array([255, 255, 255], dtype=np.float32)   # 白
COLOR_B = np.array([255, 95, 162], dtype=np.float32)    # 粉 0xFFFF5FA2


def heart_points():
    """经典爱心参数方程 → 重心归零 → 归一化到单位圆。"""
    t = np.linspace(0.0, 2.0 * np.pi, SEGS + 1)
    x = 16.0 * np.sin(t) ** 3
    y = 13.0 * np.cos(t) - 5.0 * np.cos(2 * t) - 2.0 * np.cos(3 * t) - np.cos(4 * t)
    p = np.stack([x, -y], axis=1)                      # y 向下
    cr = p[:-1, 0] * p[1:, 1] - p[1:, 0] * p[:-1, 1]
    c2 = cr.sum()
    gx = ((p[:-1, 0] + p[1:, 0]) * cr).sum() / (3 * c2)
    gy = ((p[:-1, 1] + p[1:, 1]) * cr).sum() / (3 * c2)
    p -= (gx, gy)
    p /= np.hypot(p[:, 0], p[:, 1]).max()
    return p


def rho_of_angle(p):
    """各方向的爱心边界半径 rho(theta)。"""
    ang = np.linspace(-np.pi, np.pi, LUT, endpoint=False)
    dx, dy = np.cos(ang)[:, None], np.sin(ang)[:, None]
    x0, y0 = p[:-1, 0][None, :], p[:-1, 1][None, :]
    ex, ey = (p[1:, 0] - p[:-1, 0])[None, :], (p[1:, 1] - p[:-1, 1])[None, :]
    den = dx * ey - dy * ex
    with np.errstate(divide="ignore", invalid="ignore"):
        tt = (x0 * ey - y0 * ex) / den
        uu = (x0 * dy - y0 * dx) / den
    ok = np.isfinite(tt) & np.isfinite(uu) & (tt > 0) & (uu >= 0) & (uu <= 1)
    tt = np.where(ok, tt, np.inf)
    return ang, tt.min(axis=1)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def render(t, ang, rho):
    r = min(W, H) * R_RATIO
    px = (np.arange(W) + 0.5 - W * 0.5) / r
    py = (np.arange(H) + 0.5 - H * 0.5) / r
    pxx, pyy = np.meshgrid(px, py)
    a = np.arctan2(pyy, pxx)
    rad = np.hypot(pxx, pyy)
    rp = np.maximum(np.interp(a, ang, rho, period=2 * np.pi), 0.02)

    f = np.mod(rad / rp * BANDS - (t * FLOW) % 2.0, 2.0)
    h = f * 0.5
    w = np.clip(BANDS / (r * rp) * 0.25, 0.0005, 0.25)
    m = (smoothstep(0.5 - w, 0.5 + w, h) - smoothstep(1.0 - w, 1.0 + w, h))[..., None]
    return (COLOR_A * (1.0 - m) + COLOR_B * m).astype(np.uint8)


def main():
    ang, rho = rho_of_angle(heart_points())
    n = int(FPS * DUR)
    frames = [Image.fromarray(render(i / FPS, ang, rho)).resize((OW, OH), Image.LANCZOS)
                  .convert("P", palette=Image.ADAPTIVE, colors=32) for i in range(n)]
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "docs", "preview.gif")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    frames[0].save(out, save_all=True, append_images=frames[1:],
                   duration=int(1000 / FPS), loop=0, optimize=True, disposal=2)
    print("frames:", n, "->", out)


main()
