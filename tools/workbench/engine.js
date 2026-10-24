/**
 * 素材处理引擎 —— 把用户导入的「任何东西」变成可用的透明 webm。
 *
 * ## 支持的输入
 *
 * | 输入 | 处理方式 |
 * |---|---|
 * | 视频（mp4/mov/webm/mkv…） | 逐帧解码 → 自动抠背景（如需要）→ 重编码 |
 * | GIF | 拆帧 → 自动抠背景（如需要） |
 * | PNG/JPG 序列 | 直接读帧 → 自动抠背景（如需要） |
 *
 * ## 为什么要自动抠背景
 *
 * 用户手上的素材绝大多数是**有背景**的：AI 生成的白底视频、剪映导出的 mp4。
 * 要求用户自己先抠图是最大的门槛。所以引擎会：
 *
 * 1. 取画面四角像素，判断是不是纯色背景
 * 2. 是 → 把接近该颜色的像素变透明（带容差与边缘羽化）
 * 3. 不是（已经透明，或背景复杂）→ 原样保留
 *
 * ## 为什么能输出透明 webm
 *
 * Chrome 的 `MediaRecorder` 在录制 canvas 时会保留 alpha：
 * canvas 的透明像素 → 编码后 `TAG:alpha_mode=1`。
 * （Firefox / Safari 不支持，所以工作台会检测并提示用 Chrome。）
 *
 * ## 为什么是工厂（`create()`）而不是单例
 *
 * 一个桌宠要**几十个动作**，每个动作是一次独立的导入/抠色/导出。上一版引擎是
 * 单例，状态（`frames` / `bbox`）是模块级变量，于是「导入第二个动作时第一个的帧
 * 被覆盖」—— 这也是上一版工作台只能「导一个 → 导出 → 记一个」的原因。
 *
 * 现在每个动作一个实例，状态互不干扰。
 *
 * 全部在浏览器本地完成，不上传任何数据。
 */
const PetEngine = (() => {

  /** 单个素材的抽帧上限（@24fps ≈ 4 秒）。再多会让内存与录制时间都失控。 */
  const MAX_FRAMES = 96;

  /** 抽帧帧率上限。 */
  const MAX_FPS = 30;

  // ── 纯函数工具 ──────────────────────────────────────────────────────

  /** 读文件为 ImageBitmap。失败返回 null。 */
  async function loadBitmap(file) {
    try {
      return await createImageBitmap(file);
    } catch {
      return null;
    }
  }

  /** 按文件名里的数字自然排序（frame_2 要排在 frame_10 前面）。 */
  function naturalSort(a, b) {
    const num = (s) => {
      const m = s.match(/(\d+)(?!.*\d)/);
      return m ? parseInt(m[1], 10) : 0;
    };
    return num(a.name) - num(b.name) || a.name.localeCompare(b.name);
  }

  /** 把 video 元素 seek 到指定时间并等它画得出来。 */
  function seekTo(video, t) {
    return new Promise(resolve => {
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        video.onseeked = null;
        resolve();
      };
      video.onseeked = finish;
      // ⚠️ 有些格式 seek 完不触发 `seeked`（尤其是末帧），必须兜底超时，
      //    否则整个导入会卡死在最后一帧。
      setTimeout(finish, 800);
      try { video.currentTime = t; } catch { finish(); }
    });
  }

  /** 解码 GIF 的所有帧（用 ImageDecoder，Chrome 支持）。 */
  async function decodeGif(file) {
    if (typeof ImageDecoder === 'undefined') return null;
    try {
      const data = await file.arrayBuffer();
      const dec = new ImageDecoder({ data, type: file.type || 'image/gif' });
      await dec.tracks.ready;
      const track = dec.tracks.selectedTrack;
      const count = track.frameCount;
      const out = [];
      for (let i = 0; i < count; i++) {
        const { image } = await dec.decode({ frameIndex: i });
        const delay = (image.duration || 40000) / 1000;   // μs → ms
        out.push({ bitmap: image, w: image.displayWidth, h: image.displayHeight, delay });
      }
      return out;
    } catch {
      return null;
    }
  }

  /**
   * 判断一帧是否有「纯色背景」需要抠。
   *
   * 判据：
   * 1. 四角若已透明 ⇒ 素材本来就有 alpha，不用抠
   * 2. 四角颜色必须彼此接近（否则是复杂背景）
   * 3. 与该颜色相近的像素占比 ≥ 25%（否则"背景"可能就是角色本身的大色块）
   * 4. 但也不能 ≈ 100% —— 那时画面里除了这一个颜色几乎没有别的像素，
   *    说明四角取到的其实是**角色**的颜色（角色占满画面、或干脆是空镜头）。
   *    照着抠会把宠物整个抹掉，输出一段全透明的空白 webm，
   *    而用户看到的是"宠物不见了"，且没有任何报错。
   *
   *    阈值取 99.5% 而不是更低：正常的宠物素材背景往往占到 90% 以上
   *    （角色在画面中间小小的），那条不能误伤。
   *
   * @returns {[number,number,number]|null} 背景色，或 null（无需抠）
   */
  function detectBackground(data, w, h) {
    const sample = (x, y) => {
      const o = (y * w + x) * 4;
      return [data[o], data[o + 1], data[o + 2], data[o + 3]];
    };

    const corners = [
      sample(2, 2), sample(w - 3, 2), sample(2, h - 3), sample(w - 3, h - 3),
    ];

    // ① 已经是透明背景
    const transparentCorners = corners.filter(c => c[3] < 40).length;
    if (transparentCorners >= 3) return null;

    // ② 四角颜色必须接近
    const base = corners[0];
    const close = corners.every(c =>
      Math.abs(c[0] - base[0]) + Math.abs(c[1] - base[1]) + Math.abs(c[2] - base[2]) < 60
    );
    if (!close) return null;

    // ③ 背景占比
    let similar = 0;
    let sampled = 0;
    for (let y = 0; y < h; y += 3) {
      for (let x = 0; x < w; x += 3) {
        const o = (y * w + x) * 4;
        const d = Math.abs(data[o] - base[0])
                + Math.abs(data[o + 1] - base[1])
                + Math.abs(data[o + 2] - base[2]);
        if (d < 60) similar++;
        sampled++;
      }
    }
    if (sampled === 0) return null;
    const ratio = similar / sampled;
    if (ratio < 0.25) return null;
    // ④ 几乎整屏都是同一色 ⇒ 那不是背景，是角色本体（或空镜头）
    if (ratio > 0.995) return null;

    return [base[0], base[1], base[2]];
  }

  /**
   * 把接近背景色的像素变透明。
   *
   * ## 容差与羽化
   *
   * - 距离 ≤ `tolerance` ⇒ 全透明
   * - `tolerance` ~ `tolerance+feather` ⇒ 线性过渡（避免锯齿硬边）
   *
   * ⚠️ **不能只做「等于背景色 → 透明」**：视频有压缩噪点，
   *    严格相等几乎命中不了几个像素，结果是背景纹丝不动。
   *
   * ⚠️ 用**欧氏距离**而不是曼哈顿距离：后者对深色背景会误伤太多。
   */
  function removeBackground(data, w, h, bg, tolerance, feather) {
    const [br, bgc, bb] = bg;
    let removed = 0;
    for (let i = 0; i < data.length; i += 4) {
      const dr = data[i] - br;
      const dg = data[i + 1] - bgc;
      const db = data[i + 2] - bb;
      const dist = Math.sqrt(dr * dr + dg * dg + db * db);

      if (dist <= tolerance) {
        data[i + 3] = 0;
        removed++;
      } else if (dist < tolerance + feather) {
        const t = (dist - tolerance) / feather;
        data[i + 3] = Math.round(data[i + 3] * t);
      }
    }
    return removed;
  }

  /** 一个空的离屏画布（带 willReadFrequently，抠色要频繁读回像素）。 */
  function scratch(w, h) {
    const cv = (typeof OffscreenCanvas !== 'undefined')
      ? new OffscreenCanvas(w, h)
      : Object.assign(document.createElement('canvas'), { width: w, height: h });
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    return { cv, ctx };
  }

  /**
   * 把浏览器原始报错翻译成用户能照做的话。
   *
   * 最要紧的是 canvas 被污染那条：`file://` 打开页面、视频又是 `blob:`
   * 来源时，部分浏览器不允许 `getImageData` 读回像素，报 `SecurityError`。
   * 原文完全看不出该怎么办，而解法很具体（换个打开方式 / 换浏览器）。
   */
  function friendlyError(err) {
    const msg = (err && err.message) || String(err);
    if (/SecurityError|tainted|insecure|not allowed to access|unsafe/i.test(msg)) {
      return '浏览器不允许读取视频像素（画布被标记为不安全），没法抠背景。'
        + '试试：① 把本页面放进本地文件夹再打开，别用 file:// 直接双击；'
        + '② 或者换 Chrome / Edge；③ 或者把「背景处理」改成「保持原样」，'
        + '只做裁剪和转码。原始错误：' + msg;
    }
    if (/decode|not supported|unsupported/i.test(msg)) {
      return '这个视频解不开（格式不支持）。转成 mp4（H.264）再试。原始错误：' + msg;
    }
    return msg;
  }

  // ── 素材实例 ────────────────────────────────────────────────────────

  /**
   * 创建一个素材实例。每个动作一个，互不干扰。
   */
  function create() {
    /** [{ bitmap: ImageBitmap, w, h, delay }] */
    let frames = [];
    let sourceKind = '';        // 'video' | 'gif' | 'images'
    let sourceLabel = '';
    let bbox = null;            // 所有帧的并集包围盒 { x0, y0, x1, y1 }
    let warnings = [];
    let detectedBg = null;      // 检测到的背景色 [r,g,b]；null = 未抠
    let bgRemoved = false;
    let durationSec = 0;

    /** 释放所有 ImageBitmap（显存，不释放会一直涨）。 */
    function dispose() {
      for (const f of frames) {
        try { f.bitmap.close(); } catch { /* 已经关过 */ }
      }
      frames = [];
      bbox = null;
    }

    /**
     * 对全部帧执行抠背景。
     *
     * 用**第一帧**检测背景色（视频里背景通常不变），然后套用到所有帧。
     */
    async function applyBgRemoval(opt) {
      const mode = opt.mode || 'auto';
      if (mode === 'none' || frames.length === 0) return { removed: false, bg: null };

      const W = frames[0].w, H = frames[0].h;
      const { cv, ctx } = scratch(W, H);

      let bg = opt.color || null;
      if (mode === 'auto') {
        ctx.clearRect(0, 0, W, H);
        ctx.drawImage(frames[0].bitmap, 0, 0, W, H);
        bg = detectBackground(ctx.getImageData(0, 0, W, H).data, W, H);
      }
      if (!bg) return { removed: false, bg: null };

      detectedBg = bg;
      bgRemoved = true;

      const tolerance = opt.tolerance ?? 42;
      const feather = opt.feather ?? 30;

      for (let k = 0; k < frames.length; k++) {
        const fr = frames[k];
        ctx.clearRect(0, 0, W, H);
        ctx.drawImage(fr.bitmap, 0, 0, W, H);
        const img = ctx.getImageData(0, 0, W, H);
        removeBackground(img.data, W, H, bg, tolerance, feather);
        ctx.putImageData(img, 0, 0);

        // ⚠️ 必须换回 ImageBitmap：直接留着被 putImageData 污染的 canvas
        //   会在下一轮被 clearRect 覆盖，等于白抠。
        const old = fr.bitmap;
        fr.bitmap = await createImageBitmap(cv);
        try { old.close(); } catch { /* 忽略 */ }
      }
      return { removed: true, bg };
    }

    /**
     * 计算所有帧的**并集**包围盒（去掉透明空白）。
     *
     * ⚠️ 必须并集：逐帧各裁会让画面尺寸不一致，播放时角色抖。
     */
    function computeBBox(alphaThreshold = 8) {
      if (frames.length === 0) return null;

      const W = frames[0].w, H = frames[0].h;
      const { ctx } = scratch(W, H);

      let x0 = W, y0 = H, x1 = -1, y1 = -1;

      for (const fr of frames) {
        ctx.clearRect(0, 0, W, H);
        ctx.drawImage(fr.bitmap, 0, 0, W, H);
        const data = ctx.getImageData(0, 0, W, H).data;

        for (let y = 0; y < H; y++) {
          const row = y * W * 4;
          for (let x = 0; x < W; x++) {
            if (data[row + x * 4 + 3] > alphaThreshold) {
              if (x < x0) x0 = x;
              if (x > x1) x1 = x;
              if (y < y0) y0 = y;
              if (y > y1) y1 = y;
            }
          }
        }
      }

      if (x1 < 0) {
        warnings.push('所有帧都是全透明的 —— 检查一下素材？');
        bbox = { x0: 0, y0: 0, x1: W - 1, y1: H - 1, W, H };
        return bbox;
      }

      bbox = { x0, y0, x1, y1, W, H };
      return bbox;
    }

    /**
     * 从视频里抽帧。
     *
     * ⚠️ 用 `currentTime` seek 逐帧取，而不是边播边录：
     *   播放有掉帧，而 seek 能保证拿到的是**确定的**那一帧。
     */
    async function importVideo(file, opt) {
      const url = URL.createObjectURL(file);
      const video = document.createElement('video');
      video.src = url;
      video.muted = true;
      video.playsInline = true;

      try {
        await new Promise((res, rej) => {
          video.onloadedmetadata = res;
          video.onerror = () => rej(new Error('视频加载失败（格式不支持？）'));
          setTimeout(() => rej(new Error('视频加载超时')), 15000);
        });

        const W = video.videoWidth, H = video.videoHeight;
        if (!W || !H) throw new Error('读不到视频尺寸');

        const dur = video.duration;
        if (!isFinite(dur) || dur <= 0) throw new Error('读不到视频时长');
        durationSec = dur;

        const fps = Math.min(opt.fps || 24, MAX_FPS);
        const maxSec = Math.max(0.2, opt.maxSeconds || dur);
        const take = Math.min(dur, maxSec);

        let total = Math.round(take * fps);
        if (total > MAX_FRAMES) total = MAX_FRAMES;
        if (total < 1) total = 1;

        if (dur > take + 0.05) {
          warnings.push(`原视频 ${dur.toFixed(1)}s，只取了前 ${take.toFixed(1)}s`);
        }

        for (let i = 0; i < total; i++) {
          await seekTo(video, i / fps);
          const { cv, ctx } = scratch(W, H);
          ctx.clearRect(0, 0, W, H);
          ctx.drawImage(video, 0, 0, W, H);
          const bmp = await createImageBitmap(cv);
          frames.push({ bitmap: bmp, w: W, h: H, delay: 1000 / fps });
        }

        sourceKind = 'video';
      } finally {
        video.src = '';
        URL.revokeObjectURL(url);
      }
    }

    /**
     * 导入一个动作的全部文件。
     *
     * @param {File[]} files - 一个动作的全部文件（图片序列就是多张，其余一张）。
     * @param {object} opt
     * @param {number} opt.fps - 抽帧帧率（默认 24，上限 30）。
     * @param {number} opt.maxSeconds - 最多取几秒（默认全长）。
     * @param {string} opt.bgMode - 'auto' | 'none' | 'manual'。
     * @param {number[]|null} opt.bgColor - manual 时的背景色。
     * @param {number} opt.tolerance - 抠色容差（默认 42）。
     * @param {number} opt.feather - 边缘羽化（默认 30）。
     */
    async function load(files, opt = {}) {
      dispose();
      warnings = [];
      detectedBg = null;
      bgRemoved = false;
      durationSec = 0;
      bbox = null;
      sourceKind = '';
      sourceLabel = '';

      const list = Array.from(files || []);
      if (list.length === 0) return { ok: false, message: '没有文件' };

      const videoFile = list.find(f =>
        /^video\//.test(f.type) || /\.(mp4|mov|webm|mkv|avi|m4v)$/i.test(f.name)
      );

      try {
        if (videoFile) {
          await importVideo(videoFile, opt);
          sourceLabel = videoFile.name;
        } else if (list.length === 1 && /\.gif$/i.test(list[0].name)) {
          const gifFrames = await decodeGif(list[0]);
          if (gifFrames && gifFrames.length > 0) {
            frames = gifFrames;
            sourceKind = 'gif';
            sourceLabel = list[0].name;
            durationSec = gifFrames.reduce((s, f) => s + (f.delay || 40), 0) / 1000;
            if (frames.length > MAX_FRAMES) {
              warnings.push(`GIF 有 ${frames.length} 帧，只取前 ${MAX_FRAMES} 帧`);
              for (const f of frames.slice(MAX_FRAMES)) {
                try { f.bitmap.close(); } catch { /* 忽略 */ }
              }
              frames = frames.slice(0, MAX_FRAMES);
            }
          } else {
            warnings.push('这个浏览器解不了 GIF 动画，只取到第一帧。建议用 Chrome。');
          }
        }

        if (frames.length === 0) {
          const imgs = list.filter(f =>
            /\.(png|jpe?g|webp|bmp)$/i.test(f.name) || /^image\//.test(f.type)
          );
          if (imgs.length === 0) {
            return { ok: false, message: '不支持的文件类型' };
          }
          imgs.sort(naturalSort);
          const pick = imgs.slice(0, MAX_FRAMES);
          if (imgs.length > MAX_FRAMES) {
            warnings.push(`共 ${imgs.length} 张图，只取前 ${MAX_FRAMES} 张`);
          }
          for (const f of pick) {
            const bmp = await loadBitmap(f);
            if (bmp) frames.push({ bitmap: bmp, w: bmp.width, h: bmp.height, delay: null });
          }
          if (frames.length === 0) return { ok: false, message: '图片解码失败' };
          sourceKind = 'images';
          sourceLabel = pick.length === 1 ? pick[0].name : `${pick.length} 张图`;
        }

        // 抠背景（需要先把帧画到 canvas 上读像素）
        const bgResult = await applyBgRemoval({
          mode: opt.bgMode || 'auto',
          color: opt.bgColor || null,
          tolerance: opt.tolerance,
          feather: opt.feather,
        });
        if (opt.bgMode === 'manual' && !bgResult.removed) {
          warnings.push('手动指定的背景色没匹配到像素，检查一下颜色');
        }

        computeBBox();

        const f0 = frames[0];
        return { ok: true, message: `${f0.w}×${f0.h} · ${frames.length} 帧` };
      } catch (err) {
        dispose();
        return { ok: false, message: friendlyError(err) };
      }
    }

    /** 当前状态摘要。 */
    function info() {
      const f0 = frames[0];
      return {
        frameCount: frames.length,
        width: f0?.w ?? 0,
        height: f0?.h ?? 0,
        sourceKind,
        sourceLabel,
        durationSec,
        bbox,
        detectedBg,
        bgRemoved,
        warnings: [...warnings],
      };
    }

    /**
     * 归一化的并集包围盒（0..1）。
     *
     * 用**归一化**而不是像素：各动作可能来自不同分辨率的视频，
     * 归一化之后才能求「所有动作的统一画布」。
     */
    function normalizedBox() {
      const f0 = frames[0];
      if (!bbox || !f0) return { x0: 0, y0: 0, x1: 1, y1: 1 };
      return {
        x0: bbox.x0 / f0.w,
        y0: bbox.y0 / f0.h,
        x1: (bbox.x1 + 1) / f0.w,
        y1: (bbox.y1 + 1) / f0.h,
      };
    }

    /** 第一帧的缩略图 dataURL（用于动作列表里认素材）。 */
    function thumbDataUrl(maxPx = 84) {
      const f0 = frames[0];
      if (!f0) return '';
      const k = Math.min(1, maxPx / Math.max(f0.w, f0.h));
      const w = Math.max(1, Math.round(f0.w * k));
      const h = Math.max(1, Math.round(f0.h * k));
      const cv = document.createElement('canvas');
      cv.width = w; cv.height = h;
      const ctx = cv.getContext('2d');
      ctx.clearRect(0, 0, w, h);
      ctx.drawImage(f0.bitmap, 0, 0, w, h);
      return cv.toDataURL('image/png');
    }

    /**
     * 把全部帧录成透明 webm。
     *
     * 原理：canvas → `captureStream(fps)` → `MediaRecorder('vp9')`。
     * Chrome 保留 canvas 的 alpha，输出带 `alpha_mode=1`。
     *
     * ⚠️ **必须实时录制**：MediaRecorder 按 wall clock 采样，
     *    24 帧 @24fps 就是 1 秒真实时间，不能"瞬间"生成。
     *
     * @param {object} opt
     * @param {{x0,y0,x1,y1}} opt.box - 归一化裁剪框。**全部动作共用同一个**，
     *   这样切换动作时角色的位置与大小完全一致。
     */
    async function exportWebm(opt) {
      const { box, fps, outW, outH, quality, onProgress } = opt;

      if (frames.length === 0) throw new Error('没有素材');
      if (typeof MediaRecorder === 'undefined') {
        throw new Error('这个浏览器不支持 MediaRecorder，请用 Chrome / Edge');
      }
      const mime = 'video/webm;codecs=vp9';
      if (!MediaRecorder.isTypeSupported(mime)) {
        throw new Error('这个浏览器不支持 VP9 编码，请用 Chrome / Edge');
      }

      const cv = document.createElement('canvas');
      cv.width = outW;
      cv.height = outH;
      const ctx = cv.getContext('2d', { alpha: true });

      /** 把第 i 帧按统一裁剪框画到输出画布上。 */
      function paint(i) {
        const fr = frames[i];
        if (!fr) return;
        const bx = box || { x0: 0, y0: 0, x1: 1, y1: 1 };
        const nx0 = Math.max(0, Math.min(1, bx.x0));
        const ny0 = Math.max(0, Math.min(1, bx.y0));
        const nx1 = Math.max(0, Math.min(1, bx.x1));
        const ny1 = Math.max(0, Math.min(1, bx.y1));
        const sx = nx0 * fr.w;
        const sy = ny0 * fr.h;
        const sw = (nx1 - nx0) * fr.w;
        const sh = (ny1 - ny0) * fr.h;
        ctx.clearRect(0, 0, outW, outH);
        if (sw <= 0 || sh <= 0) return;

        // ⚠️ **必须 contain，不能直接拉伸到 outW×outH**。
        //    各动作素材的宽高比未必相同（AI 生成的视频可能一会儿 16:9
        //    一会儿 1:1），拉伸会让角色时胖时瘦。
        //    宽高比一致时 k 恰好让目标矩形铺满画布，位置与尺寸**逐像素不变**。
        const k = Math.min(outW / sw, outH / sh);
        const dw = sw * k;
        const dh = sh * k;
        // 底部对齐：宠物是"脚踩地面"的，居中会让它在宽高比不一致时上下漂。
        ctx.drawImage(fr.bitmap, sx, sy, sw, sh, (outW - dw) / 2, outH - dh, dw, dh);
      }

      const frameMs = frames.map(fr => (fr.delay && fr.delay > 10 ? fr.delay : 1000 / fps));
      const totalMs = frameMs.reduce((a, b) => a + b, 0);

      const stream = cv.captureStream(fps);
      const bitrate = Math.round((0.3 + quality * 3.7) * 1_000_000);
      const rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: bitrate });

      const chunks = [];
      rec.ondataavailable = e => { if (e.data && e.data.size > 0) chunks.push(e.data); };
      const stopped = new Promise(res => { rec.onstop = res; });

      paint(0);
      rec.start();

      const t0 = performance.now();
      let elapsed = 0;

      for (let i = 0; i < frames.length; i++) {
        paint(i);
        if (onProgress) onProgress(Math.min(1, elapsed / totalMs));
        await new Promise(r => setTimeout(r, frameMs[i]));
        elapsed += frameMs[i];
      }

      await new Promise(r => setTimeout(r, 1000 / fps));
      rec.stop();
      await stopped;
      if (onProgress) onProgress(1);

      const blob = new Blob(chunks, { type: 'video/webm' });
      return { blob, size: blob.size, durationMs: performance.now() - t0 };
    }

    return {
      load,
      info,
      normalizedBox,
      thumbDataUrl,
      exportWebm,
      dispose,
      get frameCount() { return frames.length; },
      get bbox() { return bbox; },
    };
  }

  return { create, MAX_FRAMES, MAX_FPS };
})();
