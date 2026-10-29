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

  /**
   * 探出真实时长（专门治 `duration === Infinity`）。
   *
   * WebM 里 Duration 字段是可选写的。MediaRecorder 实时录制时文件头在录制
   * 开始就写死了，那时还不知道总长，所以字段留空 —— 浏览器读出来就是
   * `Infinity`，但这个文件完全能正常播放。
   *
   * 探测办法：把 `currentTime` 设成一个极大值，浏览器为了响应 seek 必须
   * 走到文件末尾，走完就把真实时长算出来了。设 `1e101` 而不是 `Infinity`
   * 是因为部分实现会直接忽略非有限值。
   *
   * @returns {Promise<number>} 真实时长（秒），探不出来时返回原值
   */
  function probeDuration(video) {
    return new Promise(resolve => {
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        video.ondurationchange = null;
        video.onseeked = null;
        resolve(video.duration);
      };
      video.ondurationchange = finish;
      video.onseeked = finish;
      setTimeout(finish, 4000);
      try { video.currentTime = 1e101; } catch { finish(); }
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
    return detectBackgroundEx(data, w, h).bg;
  }

  /**
   * 检测背景色，**并说明为什么没检测到**。
   *
   * 为什么要把原因带出来：自动检测失败时画面会整块保留背景，而用户看到
   * 的只是"没抠干净"，无从判断是四角颜色不一致、还是角色占满了画面。
   * 这四种失败各自对应完全不同的解决办法，不区分就只能瞎试参数。
   *
   * @returns {{bg: number[]|null, reason: string, ratio: number, corners: number[][]}}
   *   reason 取值：ok / already-transparent / corners-differ /
   *   too-little-background / no-background-at-all
   */
  /**
   * 把画面四周的"边饰"探掉（黑边、白边、纯色条）。
   *
   * 逐边独立向内走：只要当前这一整条边**几乎同色**，就认为它还是边饰，
   * 往里收一格再看。真实画面在边缘处通常已经有内容起伏（角色头发、背景
   * 渐变、噪点），不会整条边完全同色，所以会自然停住。
   *
   * 同时把"接近纯黑/纯白"作为强判据：黑边是最常见的，纯黑整条边几乎必然
   * 是 letterbox 而不是画面内容。
   *
   * @returns {{x0:number,y0:number,x1:number,y1:number}} 有效画面的内接矩形
   */
  function trimBorders(data, w, h) {
    const rowUniform = (y, xa, xb, fromLeft) => {
      const sx = fromLeft ? xa : xb;
      const o0 = (y * w + sx) * 4;
      const r = data[o0], g = data[o0 + 1], b = data[o0 + 2];
      for (let x = xa; x <= xb; x += 4) {
        const o = (y * w + x) * 4;
        if (Math.abs(data[o] - r) + Math.abs(data[o + 1] - g)
          + Math.abs(data[o + 2] - b) > 40) return false;
      }
      return true;
    };
    const colUniform = (x, ya, yb) => {
      const o0 = (ya * w + x) * 4;
      const r = data[o0], g = data[o0 + 1], b = data[o0 + 2];
      for (let y = ya; y <= yb; y += 4) {
        const o = (y * w + x) * 4;
        if (Math.abs(data[o] - r) + Math.abs(data[o + 1] - g)
          + Math.abs(data[o + 2] - b) > 40) return false;
      }
      return true;
    };

    // ⚠️ 边饰必须是"窄带"。
    //
    //    不设上限一直往里收，**纯色背景本身就会被当成边饰裁掉**：白底图
    //    整条边都是白的，会被一路收到中间，然后在"有效画面"里再也找不到
    //    大面积背景色 —— 白底素材全都判 no-background-at-all。
    //
    //    真实黑边的宽度有上限：横屏录屏左右黑边通常 <12%，竖屏视频上下
    //    letterbox 最多到 40%。取 18% 作为单边上限（够覆盖常见黑边），
    //    超过这个宽度的"同色边"更可能是背景本身，不该裁。
    const maxBand = Math.floor(Math.min(w, h) * 0.18);

    // 边饰的判据：整条边同色，**并且**这一条边的颜色明显区别于画面内侧
    // 的颜色（黑边 vs 白底、白边 vs 深底）。只靠"同色"不够 —— 纯色背景
    // 的边也同色，那会被误裁。
    const colColor = x => {
      const o = (Math.floor(h / 2) * w + x) * 4;
      return [data[o], data[o + 1], data[o + 2]];
    };
    const rowColor = y => {
      const o = (y * w + Math.floor(w / 2)) * 4;
      return [data[o], data[o + 1], data[o + 2]];
    };
    const far = (a, b, t = 100) =>
      Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) > t;

    const measure = (uniform, colorAt, max, innerAt) => {
      let n = 0;
      while (n < max && uniform(n)) n++;
      // 没量到边饰，或者这条边和内侧颜色相近 → 不是边饰
      if (n === 0) return 0;
      const edgeCol = colorAt(n > 0 ? n - 1 : 0);
      const innerCol = innerAt(n);
      if (!far(edgeCol, innerCol)) return 0;
      return n;
    };

    let left = measure(
      n => colUniform(n, 0, h - 1), x => colColor(x), maxBand, n => colColor(n));
    let right = measure(
      n => colUniform(w - 1 - n, 0, h - 1), x => colColor(w - 1 - x), maxBand,
      n => colColor(w - 1 - n));
    let top = measure(
      n => rowUniform(n, 0, w - 1, true), y => rowColor(y), maxBand, n => rowColor(n));
    let bot = measure(
      n => rowUniform(h - 1 - n, 0, w - 1, true), y => rowColor(h - 1 - y), maxBand,
      n => rowColor(h - 1 - n));

    // 左右（或上下）都吃满上限 ⇒ 整幅同色，不是边饰，别裁
    if (left >= maxBand && right >= maxBand) { left = 0; right = 0; }
    if (top >= maxBand && bot >= maxBand) { top = 0; bot = 0; }

    // 一边有边饰、另一边没有时，通常是对称 letterbox，按大的那边对齐
    if (left !== right) {
      const m = Math.max(left, right);
      // 只在小的一侧对齐，且对齐后不能超出上限
      if (m < maxBand) { left = m; right = m; }
    }
    if (top !== bot) {
      const m = Math.max(top, bot);
      if (m < maxBand) { top = m; bot = m; }
    }

    // 过渡带：黑边与画面之间常有抗锯齿/压缩噪点
    const pad = Math.max(1, Math.round(Math.min(w, h) * 0.01));
    const x0 = left > 0 ? left + pad : 0;
    const y0 = top > 0 ? top + pad : 0;
    const x1 = right > 0 ? w - 1 - right - pad : w - 1;
    const y1 = bot > 0 ? h - 1 - bot - pad : h - 1;

    return {
      x0: Math.min(Math.max(0, x0), w - 1),
      y0: Math.min(Math.max(0, y0), h - 1),
      x1: Math.max(Math.min(w - 1, x1), 0),
      y1: Math.max(Math.min(h - 1, y1), 0),
    };
  }

  function detectBackgroundEx(data, w, h) {
    const sample = (x, y) => {
      const o = (y * w + x) * 4;
      return [data[o], data[o + 1], data[o + 2], data[o + 3]];
    };
    const near = (a, b, t = 60) =>
      Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) < t;

    // ⚠️ **不能取最外圈那几个像素**。真实素材经常带黑边（录屏、显示器
    //    录制、AI 生成视频垫黑底），圆角卡片、投影、暗角也都会污染最外圈。
    //    早先固定取 (2,2) 这类位置，用户一个带黑边的 1280×720 视频，
    //    四角全是纯黑，于是把黑边当成背景 —— 检测出 18% 占比直接失败，
    //    而真正的白色背景占了 65%。
    //
    // ⚠️ 但**固定按百分比内缩也不行**。同一个用户视频左右黑边各 117px，
    //    占宽度 9.1%，8% 的内缩量正好还落在黑边里，照样取到纯黑。
    //    黑边多宽没有上限（竖屏视频上下黑边可能占 40%），猜不出来。
    //
    //    正确做法：**逐边探进去**，一直走到像素不再像"边饰"为止。
    //    判据是"这一整条边是不是几乎同色且暗/纯"—— 黑边、白边、纯色边
    //    都会命中；而真实画面在边缘处就有内容起伏，不会整条同色。
    const trim = trimBorders(data, w, h);
    const { x0, y0, x1, y1 } = trim;

    // 整幅都被判成边饰（比如纯色空镜头）→ 没有可抠的背景
    if (x1 - x0 < w * 0.1 || y1 - y0 < h * 0.1) {
      return { bg: null, reason: 'no-background-at-all', ratio: 0, corners: [], trim };
    }

    // ⚠️ 四角**不能只取一个点**。真实视频在画面边缘常有一条暗线/灰带
    //    （编码器 padding、源素材的细边框），单点采样会正好落在上面：
    //    用户那个 1280×720 视频 y=0 整行是 (128,133,141) 灰带，四角全取到
    //    灰色且彼此不同 → 误判 corners-differ，而真正的背景是纯白。
    //
    //    改成每个角取一小块（边长约为画面短边的 6%），用**中位数**代表
    //    该角的颜色。中位数能直接跳过少量异常像素（暗线、噪点），
    //    又不会被角色颜色带偏（角色不会同时占据四个角）。
    const cs = Math.max(4, Math.round(Math.min(w, h) * 0.06));
    const cornerBlock = (cx, cy) => {
      const ax = Math.max(0, Math.min(w - 1, cx));
      const ay = Math.max(0, Math.min(h - 1, cy));
      const xa = Math.max(0, Math.min(ax, cx < w / 2 ? ax : ax - cs));
      const ya = Math.max(0, Math.min(ay, cy < h / 2 ? ay : ay - cs));
      const xb = Math.min(w - 1, xa + cs);
      const yb = Math.min(h - 1, ya + cs);
      const rs = [], gs = [], bs = [], as = [];
      for (let y = ya; y <= yb; y += 2) {
        for (let x = xa; x <= xb; x += 2) {
          const o = (y * w + x) * 4;
          rs.push(data[o]); gs.push(data[o + 1]); bs.push(data[o + 2]); as.push(data[o + 3]);
        }
      }
      const med = arr => {
        if (arr.length === 0) return 0;
        arr.sort((a, b) => a - b);
        return arr[arr.length >> 1];
      };
      return [med(rs), med(gs), med(bs), med(as)];
    };

    const corners = [
      cornerBlock(x0, y0), cornerBlock(x1, y0),
      cornerBlock(x0, y1), cornerBlock(x1, y1),
    ];

    // ① 已经是透明背景
    const transparentCorners = corners.filter(c => c[3] < 40).length;
    if (transparentCorners >= 3) {
      return { bg: null, reason: 'already-transparent', ratio: 0, corners, trim };
    }

    // ② 四角颜色必须接近
    const base = corners[0];
    if (!corners.every(c => near(c, base))) {
      return { bg: null, reason: 'corners-differ', ratio: 0, corners, trim };
    }

    // ③ 全图统计：先看四角候选色能覆盖多少，覆盖不足时再试"最常见色"。
    //    有些素材角色居中、背景是渐变色，四角颜色接近但占比低，
    //    这时用画面里出现最多的颜色当背景反而更准。
    const stats = countSimilar(data, w, h, base);
    if (stats.sampled === 0) {
      return { bg: null, reason: 'no-background-at-all', ratio: 0, corners, trim };
    }
    let ratio = stats.ratio;

    // ④ 四角色占比太低时，退一步试"边缘主色"
    //
    //    ⚠️ 必须限定：主色**不能偏离四角色太远**。
    //       不带这个限制的话，四角是深色窄边、画面主体是浅色时，边缘主色
    //       会取到主体色，于是把**角色自己**当背景抠掉 —— 测出来 ratio
    //       0.956，看上去"覆盖率很高"，实际是把整只宠物挖空了。
    //       这里允许一定偏差（容差 120，比四角判定用的 60 宽松），
    //       但主色必须是四角色的"近亲"，也就是同一片背景在明暗上的浮动。
    if (ratio < 0.25) {
      const dominant = dominantEdgeColor(data, w, h, trim);
      if (dominant && near(dominant, base, 120)) {
        const st2 = countSimilar(data, w, h, dominant);
        if (st2.ratio >= 0.25 && st2.ratio > ratio) {
          return finalize(dominant, st2.ratio, corners, data, w, h, trim);
        }
      }
      return { bg: null, reason: 'too-little-background', ratio, corners, trim };
    }

    return finalize(base, ratio, corners, data, w, h, trim);
  }

  /** 统计与给定颜色相近的像素占比（抽样）。 */
  function countSimilar(data, w, h, base) {
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
    return { similar, sampled, ratio: sampled ? similar / sampled : 0 };
  }

  /**
   * 从画面四边（跳过边饰）找出现最多的颜色。
   *
   * 只看边缘而不是全图：全图最常见色在"角色大面积同为纯色"时会把角色
   * 当成背景，那时候该判失败而不是硬抠。
   */
  function dominantEdgeColor(data, w, h, trim) {
    const { x0, y0, x1, y1 } = trim;
    const buckets = new Map();
    const push = (x, y) => {
      const o = (y * w + x) * 4;
      // 量化到 16 一档，抗压缩噪点
      const key = (data[o] >> 4) + ',' + (data[o + 1] >> 4) + ',' + (data[o + 2] >> 4);
      const cur = buckets.get(key) || { n: 0, r: 0, g: 0, b: 0 };
      cur.n++; cur.r += data[o]; cur.g += data[o + 1]; cur.b += data[o + 2];
      buckets.set(key, cur);
    };
    // 沿"去掉边饰之后"的矩形四条边采样，而不是原图边缘 ——
    // 否则黑边会主导统计，取出来的"主色"就是黑的。
    for (let x = x0; x <= x1; x += 4) {
      push(x, y0);
      push(x, y1);
    }
    for (let y = y0; y <= y1; y += 4) {
      push(x0, y);
      push(x1, y);
    }
    let best = null;
    for (const v of buckets.values()) {
      if (!best || v.n > best.n) best = v;
    }
    if (!best || best.n < 8) return null;
    return [Math.round(best.r / best.n), Math.round(best.g / best.n), Math.round(best.b / best.n)];
  }

  /** 收尾检查：几乎整屏同色说明那不是背景，是角色本体（或空镜头）。 */
  /**
   * 把边饰区域（trim 矩形之外的部分）清成全透明。
   *
   * 这些像素是黑边/白边/暗线，不属于角色。留着它们会：
   * ① 出现在导出的动画里，宠物两边各拖一条黑杠；
   * ② 撑大裁剪框，角色被缩得很小。
   *
   * @param {Uint8ClampedArray} data - 像素数据（原地修改）
   */
  function clearBorders(data, w, h, trim) {
    const { x0, y0, x1, y1 } = trim;
    if (x0 <= 0 && y0 <= 0 && x1 >= w - 1 && y1 >= h - 1) return;
    for (let y = 0; y < h; y++) {
      const outsideRow = y < y0 || y > y1;
      for (let x = 0; x < w; x++) {
        if (outsideRow || x < x0 || x > x1) {
          data[(y * w + x) * 4 + 3] = 0;
        }
      }
    }
  }

  function finalize(bg, ratio, corners, data, w, h, trim) {
    if (ratio > 0.995) {
      return { bg: null, reason: 'no-background-at-all', ratio, corners, trim };
    }
    return { bg: [bg[0], bg[1], bg[2]], reason: 'ok', ratio, corners, trim };
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

  /**
   * 把抠背景失败的原因翻译成「用户下一步该做什么」。
   *
   * 四种失败对应四种完全不同的解法，混成一句"没抠干净"用户只能瞎试参数。
   * 所以这里逐条给出**具体动作**，而不只是描述现象。
   *
   * @param {string} reason - detectBackgroundEx 的 reason
   * @param {number} ratio - 背景色像素占比（0~1）
   * @returns {{level: string, text: string}}
   */
  function explainBgFailure(reason, ratio) {
    const pct = Math.round((ratio || 0) * 100);
    switch (reason) {
      case 'already-transparent':
        return { level: 'ok',
          text: '素材本来就是透明背景，不需要抠 —— 已原样保留' };
      case 'corners-differ':
        return { level: 'warn',
          text: '四个角的颜色不一致，判断不出统一背景色。'
            + '常见原因：背景有渐变/阴影、角色占到了边角、或者背景不是纯色。'
            + '解决：换一个纯色背景的视频，或把「背景处理」改成「手动指定」并吸取背景色' };
      case 'too-little-background':
        return { level: 'warn',
          text: '背景色只占画面 ' + pct + '%，太少了（至少要 25%）。'
            + '多半是角色把画面占满了。解决：让角色小一点、离镜头远一点再拍' };
      case 'no-background-at-all':
        return { level: 'warn',
          text: '整幅画面几乎都是同一个颜色（' + pct + '%），分不清哪是背景哪是角色。'
            + '解决：确认视频里有角色，且背景和角色颜色有明显区别' };
      case 'disabled':
        return { level: 'ok', text: '按设置保留了原始背景，没做抠图' };
      case 'manual':
        return { level: 'warn',
          text: '手动指定的背景色在画面里没匹配到像素，检查一下颜色对不对' };
      case 'ok':
        return { level: 'ok',
          text: '已抠掉背景（原背景色占 ' + pct + '%）' };
      default:
        return { level: 'warn', text: '抠背景没成功（原因：' + reason + '）' };
    }
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
    let bbox = null;
    /** 上一次抠背景的结果（含失败原因），给界面用。 */
    let lastBg = null;            // 所有帧的并集包围盒 { x0, y0, x1, y1 }
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
     * 把第 i 帧按统一裁剪框画到任意画布上。
     *
     * ⚠️ **预览与导出必须走这同一个函数**。分成两份实现的话，只要有一处
     *    改动忘了同步，用户在预览里看到的和真正导出的就会不一样 ——
     *    而那是最难发现的错（两边各自看起来都"对"）。
     *
     * @param {CanvasRenderingContext2D} ctx - 目标画布上下文
     * @param {number} i - 帧索引
     * @param {{x0,y0,x1,y1}} box - 归一化裁剪框
     * @param {number} outW - 输出宽
     * @param {number} outH - 输出高
     */
    function drawFrameTo(ctx, i, box, outW, outH) {
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
      const k = Math.min(outW / sw, outH / sh);
      const dw = sw * k;
      const dh = sh * k;
      // 底部对齐：宠物是"脚踩地面"的，居中会让它在宽高比不一致时上下漂。
      ctx.drawImage(fr.bitmap, sx, sy, sw, sh, (outW - dw) / 2, outH - dh, dw, dh);
    }

    /** 第 i 帧该停留多久（ms）。图片序列没有自带时长，回落到帧率。 */
    function frameDelay(i, fps) {
      const fr = frames[i];
      return (fr && fr.delay && fr.delay > 10) ? fr.delay : 1000 / fps;
    }

    /**
     * 对全部帧执行抠背景。
     *
     * 用**第一帧**检测背景色（视频里背景通常不变），然后套用到所有帧。
     */
    async function applyBgRemoval(opt) {
      const mode = opt.mode || 'auto';
      if (frames.length === 0) {
        return { removed: false, bg: null, reason: 'no-frames' };
      }

      // ⚠️ **每次都从原始帧重算，不在上一次的结果上叠加**。
      //    否则调一次参数就多抠一层，羽化边缘会一次次变糊，
      //    用户看到的效果取决于他拖了几次滑块 —— 这没法用。
      await restoreOriginalFrames();

      if (mode === 'none') return { removed: false, bg: null, reason: 'disabled' };

      const W = frames[0].w, H = frames[0].h;
      const { cv, ctx } = scratch(W, H);

      let bg = opt.color || null;
      let reason = 'manual';
      let ratio = 0;
      let detTrim = null;

      if (mode === 'auto') {
        ctx.clearRect(0, 0, W, H);
        ctx.drawImage(frames[0].bitmap, 0, 0, W, H);
        const det = detectBackgroundEx(ctx.getImageData(0, 0, W, H).data, W, H);
        bg = det.bg;
        reason = det.reason;
        ratio = det.ratio;
        detTrim = det.trim || null;
      }

      if (!bg) {
        detectedBg = null;
        bgRemoved = false;
        return { removed: false, bg: null, reason, ratio };
      }

      detectedBg = bg;
      bgRemoved = true;

      const tolerance = opt.tolerance ?? 42;
      const feather = opt.feather ?? 30;

      // 边饰（黑边/白边/暗线）不算内容，一起清成透明。
      // 不清的话，导出动画里会带着两条黑边 —— 用户的 1280×720 素材左右
      // 各 117px 黑边，裁剪框（bbox）也会因此变成全幅，角色缩得很小。
      const trim = mode === 'auto' ? detTrim : null;

      for (let k = 0; k < frames.length; k++) {
        const fr = frames[k];
        ctx.clearRect(0, 0, W, H);
        ctx.drawImage(fr.bitmap, 0, 0, W, H);
        const img = ctx.getImageData(0, 0, W, H);
        removeBackground(img.data, W, H, bg, tolerance, feather);
        if (trim) clearBorders(img.data, W, H, trim);
        ctx.putImageData(img, 0, 0);

        // ⚠️ 必须换回 ImageBitmap：直接留着被 putImageData 污染的 canvas
        //   会在下一轮被 clearRect 覆盖，等于白抠。
        const old = fr.bitmap;
        fr.bitmap = await createImageBitmap(cv);
        try { old.close(); } catch { /* 忽略 */ }
      }
      return { removed: true, bg, reason: 'ok', ratio };
    }

    /**
     * 把每帧换回未抠背景的原始位图。
     *
     * ## 为什么不能在上一次的结果上再抠
     *
     * 抠背景是**破坏性**的：像素一旦变透明就回不来了。如果调一次参数就在
     * 成品上再抠一层，羽化边缘会一次次变糊，用户看到的效果取决于他拖了
     * 几次滑块 —— 同一个参数，拖两次和拖一次结果不同，这没法用。
     *
     * 所以每帧导入时留一份 `orig`（未抠的原图），每次重抠都从它克隆出发。
     */
    async function restoreOriginalFrames() {
      for (const fr of frames) {
        if (!fr.orig) continue;
        // ⚠️ 只关掉**当前成品**，绝不能碰 fr.orig ——
        //    orig 是唯一源头，关掉它第二次重抠就没得克隆了。
        try { fr.bitmap.close(); } catch { /* 忽略 */ }
        fr.bitmap = await createImageBitmap(fr.orig);
      }
    }

    /** 给每帧留一份未抠背景的原始位图（导入后调一次）。 */
    async function snapshotOriginals() {
      for (const fr of frames) {
        if (fr.orig) continue;
        fr.orig = await createImageBitmap(fr.bitmap);
      }
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

        let dur = video.duration;
        if (!isFinite(dur) || dur <= 0) {
          // ⚠️ `duration === Infinity` 是 WebM 的常见情况，**不是坏文件**。
          //    MediaRecorder 边录边写的 webm 往往没有 Duration 字段，浏览器
          //    只能边解码边推算，此时读 duration 就是 Infinity。
          //
          //    解法和文件本身无关：把 currentTime 设到一个极大值，浏览器为了
          //    响应 seek 会把整个文件走一遍，走完就知道总长了，duration 随之
          //    变成真实值。这不是 hack，是规范允许的探测方式。
          //
          //    早先这里直接 `throw '读不到视频时长'`，等于把一大批能正常播放的
          //    webm 判成坏文件 —— 用户看到的就是"导入失败"。
          dur = await probeDuration(video);
        }
        if (!isFinite(dur) || dur <= 0) {
          throw new Error('读不到视频时长（这个视频可能没有时长信息，'
            + '用剪辑软件重新导出一次 mp4 再试）');
        }
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

        // 先留一份未抠的原图，之后调参重抠才有源头
        await snapshotOriginals();

        // 抠背景（需要先把帧画到 canvas 上读像素）
        const bgResult = await applyBgRemoval({
          mode: opt.bgMode || 'auto',
          color: opt.bgColor || null,
          tolerance: opt.tolerance,
          feather: opt.feather,
        });
        lastBg = bgResult;
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
        drawFrameTo(ctx, i, box, outW, outH);
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

    /**
     * 按新参数重新抠背景（不重新解码）。
     *
     * 用户拖完容差/羽化滑块点一下就能看到效果，不用把视频重新拖一遍 ——
     * 解码一段视频要几秒，为了看边缘调一次参数就重来一遍没法用。
     */
    async function reapplyBackground(opt) {
      if (frames.length === 0) return { ok: false, message: '还没有素材' };
      try {
        const r = await applyBgRemoval(opt);
        lastBg = r;
        computeBBox();
        return { ok: true, result: r };
      } catch (err) {
        return { ok: false, message: friendlyError(err) };
      }
    }

    /** 上一次抠背景的结果摘要（给界面显示原因）。 */
    function bgReport() {
      return lastBg;
    }

    return {
      load,
      info,
      reapplyBackground,
      bgReport,
      normalizedBox,
      drawFrameTo,
      frameDelay,
      thumbDataUrl,
      exportWebm,
      dispose,
      get frameCount() { return frames.length; },
      get bbox() { return bbox; },
    };
  }

  return {
    create, MAX_FRAMES, MAX_FPS,
    __t: {
      detectBackground, detectBackgroundEx, removeBackground,
      naturalSort, explainBgFailure, trimBorders,
    },
  };
})();
