package nz.org.aotearoa.guandanscore.recognition

import org.json.JSONObject

/**
 * 注入 Gemini 页面的 JavaScript 单一事实来源。
 *
 * 规则：
 * - 所有脚本为可审计的静态字符串，不拼接来自用户的原始文本。
 * - 动态参数一律经 [quote] 做 JSON 编码后再嵌入。
 * - 所有脚本返回 JSON 字符串，含 `ok` 与失败时的 `why`；不得把异常吞成成功。
 * - 不读取、不返回 Cookie、令牌或图片 Base64。
 */
object GeminiScripts {

    /** JSON 安全编码。`JSONObject.quote` 会正确处理引号、反斜杠、换行、U+2028/2029。 */
    fun quote(raw: String): String = JSONObject.quote(raw)

    /**
     * AI-015：FILE_CHOOSER 触发脚本。
     *
     * 只尝试 `input[type=file]` 的 JS `.click()`；这在真实 WebView 上缺少 user activation，
     * 预期返回 `js-click-needs-user-activation`。真正可行的路径是 [locateUploadIcon] + 原生触摸。
     */
    fun uploadTrigger(): String = """
        (function() {
          var fs;
          try { fs = document.querySelectorAll('input[type="file"]'); } catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (fs && fs.length > 0) {
            try { fs[0].click(); } catch (e) {}
            return JSON.stringify({ ok: false, why: 'js-click-needs-user-activation', fileInputs: fs.length });
          }
          return JSON.stringify({ ok: false, why: 'no-file-input', hint: 'use locateUploadIcon + native tap' });
        })()
    """.trimIndent()

    /**
     * AI-015：返回 Gemini 工具栏上传入口（`Upload and tools`）的视口坐标，供原生触摸点击。
     *
     * 坐标是 CSS 像素。若按钮尚未渲染（Angular 异步），返回 `ok:false, why:'upload-tools-not-found'`，
     * 由驱动层重试；绝不在此处同步忙等。
     */
    fun locateUploadIcon(): String = """
        (function() {
          function center(r) {
            return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2),
                     w: Math.round(r.width), h: Math.round(r.height), left: r.left, top: r.top };
          }
          var el = null;
          try { el = document.querySelector(${quote(GeminiSelectors.uploadTools())}); } catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'upload-tools-not-found' });
          var r = el.getBoundingClientRect();
          if (r.width <= 0 || r.height <= 0) return JSON.stringify({ ok: false, why: 'upload-tools-hidden' });
          var c = center(r);
          c.ok = true;
          c.innerHeight = window.innerHeight;
          c.innerWidth = window.innerWidth;
          c.dpr = window.devicePixelRatio || 1;
          c.label = (el.getAttribute('aria-label') || '').toString();
          return JSON.stringify(c);
        })()
    """.trimIndent()

    /** 只读诊断：统计各选择器命中数量与页面基础状态。 */
    fun diagnose(): String = """
        (function() {
          function count(sel) {
            try { return document.querySelectorAll(sel).length; } catch (e) { return -1; }
          }
          function first(sel) {
            try { return document.querySelector(sel); } catch (e) { return null; }
          }
          var input = first(${quote(GeminiSelectors.input())});
          var inputTag = input ? input.tagName.toLowerCase() : '';
          return JSON.stringify({
            ok: true,
            href: location.href,
            readyState: document.readyState,
            innerHeight: window.innerHeight,
            inputCount: count(${quote(GeminiSelectors.input())}),
            inputTag: inputTag,
            inputEditable: !!(input && (input.isContentEditable || inputTag === 'textarea')),
            sendCount: count(${quote(GeminiSelectors.send())}),
            responseCount: count(${quote(GeminiSelectors.response())}),
            fileInputCount: count(${quote(GeminiSelectors.fileInput())}),
            loginIndicatorCount: count(${quote(GeminiSelectors.login())}),
            passwordCount: count(${quote(GeminiSelectors.password())}),
            generatingCount: count(${quote(GeminiSelectors.generating())})
          });
        })()
    """.trimIndent()

    /**
     * AI-030：逐候选选择器探针。
     *
     * diagnose() 只报并集命中数，无法回答“这条候选到底对不对”——并集为 1 也可能是
     * 第 4 条 textarea 兜底命中，而真正的输入框换了结构。这里对每组候选逐条数命中，
     * 输出形如 `0|0|1|1`，从 0 到非 0 的第一个下标即实际生效的候选。
     *
     * 只读：不点击、不写入、不读取 Cookie / Storage。
     */
    fun selectorProbe(): String = """
        (function() {
          function count(sel) {
            try { return document.querySelectorAll(sel).length; } catch (e) { return -1; }
          }
          function firstHit(counts) {
            for (var i = 0; i < counts.length; i++) if (counts[i] > 0) return i;
            return -1;
          }
          var groups = {
            input: ${jsonArray(GeminiSelectors.INPUT_CANDIDATES)},
            send: ${jsonArray(GeminiSelectors.SEND_CANDIDATES)},
            response: ${jsonArray(GeminiSelectors.RESPONSE_CANDIDATES)},
            uploadTools: ${jsonArray(GeminiSelectors.UPLOAD_TOOLS_CANDIDATES)},
            fileInput: ${jsonArray(GeminiSelectors.FILE_INPUT_CANDIDATES)},
            login: ${jsonArray(GeminiSelectors.LOGIN_CANDIDATES)},
            generating: ${jsonArray(GeminiSelectors.GENERATING_CANDIDATES)}
          };
          var result = { ok: true, href: location.href, readyState: document.readyState };
          for (var k in groups) {
            var counts = [];
            for (var i = 0; i < groups[k].length; i++) counts.push(count(groups[k][i]));
            result[k] = counts.join('|');
            result[k + 'First'] = firstHit(counts);
          }
          result.passwordCount = count(${quote(GeminiSelectors.password())});
          return JSON.stringify(result);
        })()
    """.trimIndent()

    /** 把候选列表编码成 JSON 数组字面量，避免手工拼接时漏引号或转义错。 */
    private fun jsonArray(items: List<String>): String =
        items.joinToString(",", prefix = "[", postfix = "]") { quote(it) }

    /** 读取当前回答快照：节点数量 + 最后一个非空回答的文本。 */
    fun snapshot(): String = """
        (function() {
          var nodes;
          try { nodes = document.querySelectorAll(${quote(GeminiSelectors.response())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          var list = [];
          for (var i = 0; i < nodes.length; i++) {
            var t = (nodes[i].innerText || '').trim();
            if (t) list.push(t);
          }
          var generating = 0;
          try { generating = document.querySelectorAll(${quote(GeminiSelectors.generating())}).length; }
          catch (e) { generating = 0; }
          var password = 0, loginUi = 0, attach = 0;
          try { password = document.querySelectorAll(${quote(GeminiSelectors.password())}).length; } catch (e) {}
          try { loginUi = document.querySelectorAll(${quote(GeminiSelectors.login())}).length; } catch (e) {}
          // AI-042：发送前的输入区现状与可读会话标识（只读，不修改页面）。
          var inputEl = null;
          try { inputEl = document.querySelector(${quote(GeminiSelectors.input())}); } catch (e) {}
          // 附件只数 composer 附近的预览：全局会把历史消息里的 blob 图片算进来。
          if (inputEl) {
            var box = inputEl;
            for (var bi = 0; bi < 6 && box.parentElement; bi++) box = box.parentElement;
            try { attach = box.querySelectorAll(${quote(GeminiSelectors.attachment())}).length; } catch (e) {}
          }
          var inputText = inputEl ? (inputEl.value === undefined ? (inputEl.textContent || '') : inputEl.value) : '';
          var sessionId = '';
          try {
            var sm = (location.pathname || '').match(/\/app\/([^\/?#]+)/);
            sessionId = sm ? sm[1] : '';
          } catch (e) {}
          return JSON.stringify({
            ok: true,
            responseCount: nodes.length,
            lastAnswerText: list.length ? list[list.length - 1] : '',
            generating: generating > 0,
            passwordCount: password,
            loginIndicatorCount: loginUi,
            inputText: inputText,
            attachmentCount: attach,
            attached: attach > 0,
            sessionId: sessionId,
            href: location.href
          });
        })()
    """.trimIndent()

    /** 聚焦输入区并把 prompt 写入（不提交）。参数经 JSON 编码。 */
    fun fillPrompt(prompt: String): String = """
        (function(text) {
          var el;
          try { el = document.querySelector(${quote(GeminiSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          el.focus();
          if (el.isContentEditable) {
            el.textContent = text;
            el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
          } else if (el.tagName && el.tagName.toLowerCase() === 'textarea') {
            var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
            setter.call(el, text);
            el.dispatchEvent(new Event('input', { bubbles: true }));
          } else {
            return JSON.stringify({ ok: false, why: 'unsupported-input-type' });
          }
          return JSON.stringify({ ok: true, length: text.length });
        })(${quote(prompt)})
    """.trimIndent()


    /** 点击发送按钮。 */
    fun submit(): String = """
        (function() {
          var btn;
          try { btn = document.querySelector(${quote(GeminiSelectors.send())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!btn) return JSON.stringify({ ok: false, why: 'send-button-not-found' });
          if (btn.disabled) return JSON.stringify({ ok: false, why: 'send-button-disabled' });
          btn.click();
          return JSON.stringify({ ok: true });
        })()
    """.trimIndent()

    /**
     * 在页面上下文里构造 [File] 并通过合成 paste 事件注入图片。
     * 返回 `dispatched: true` **只代表事件已派发**，不代表页面接受了附件；
     * 调用方必须再用 [verifyAttachment] 确认预览出现。
     */
    fun pasteImage(fileName: String, mimeType: String, base64: String): String = """
        (function(name, mime, b64) {
          var el;
          try { el = document.querySelector(${quote(GeminiSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          var bytes;
          try {
            var binary = atob(b64);
            bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'decode-failed' });
          }
          var file;
          try { file = new File([bytes], name, { type: mime }); }
          catch (e) { return JSON.stringify({ ok: false, why: 'file-construct-failed' }); }
          var dt = new DataTransfer();
          dt.items.add(file);
          el.focus();
          var ev;
          try {
            ev = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true, composed: true });
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'clipboard-event-unsupported' });
          }
          var accepted = el.dispatchEvent(ev);
          return JSON.stringify({ ok: true, dispatched: true, accepted: accepted, bytes: bytes.length });
        })(${quote(fileName)}, ${quote(mimeType)}, ${quote(base64)})
    """.trimIndent()

    /**
     * Gemini 专属：填文本 → 合成 paste 注入图片 → 点发送，一次注入完成。
     *
     * 顺序不可颠倒：Gemini 的 `contenteditable` 只有在文本已在框内、编辑器已聚焦时
     * 收到 paste，才会把图片挂到本条消息上（GeminiBridge `doc/image_upload.md` §3.3）。
     * 内部按 `[name, mime, base64]` 解码为原始字节，再构造 File。
     *
     * 返回 `{ok, filled, pasted, submitted, ...}`；`pasted` 只代表事件已派发，
     * 调用方仍须用 [verifyAttachment] 确认附件预览出现。
     */
    /**
     * AI-176：只读发送按钮状态，供驱动层在点击前轮询。
     *
     * Gemini 的发送按钮由 Angular 异步渲染，因此 `sendCount=0` 是正常的中间态；
     * 调用方必须轮询而不是同步一次就判失败。
     */
    fun sendState(): String = """
        (function() {
          var list = [];
          try { list = Array.prototype.slice.call(document.querySelectorAll(${quote(GeminiSelectors.send())})); } catch (e) {}
          var usable = null;
          for (var i = 0; i < list.length; i++) {
            var b = list[i];
            var r = b.getBoundingClientRect();
            var dis = b.disabled === true || b.getAttribute('aria-disabled') === 'true';
            if (!dis && r.width > 0 && r.height > 0) { usable = b; break; }
          }
          var input = null;
          try { input = document.querySelector(${quote(GeminiSelectors.input())}); } catch (e) {}
          var value = input ? (input.value === undefined ? (input.textContent || '') : input.value) : '';
          return JSON.stringify({
            ok: true,
            sendCount: list.length,
            sendDisabled: list.length > 0 && !usable,
            sendClickable: !!usable,
            valueEmpty: String(value || '').trim().length === 0,
            url: location.href
          });
        })()
    """.trimIndent()

    fun fillPasteAndSubmit(
        prompt: String,
        fileName: String,
        mimeType: String,
        base64: String
    ): String = """
        (function(text, name, mime, b64) {
          var el;
          try { el = document.querySelector(${quote(GeminiSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });

          // 1) 聚焦并写入文本。
          //    Gemini 编辑器是 Angular 组件：直接改 textContent 不会让框架感知，
          //    发送按钮也不会渲染。用 execCommand('insertText') 产生真实输入事件。
          el.focus();
          if (el.isContentEditable) {
            var sel = window.getSelection();
            var range = document.createRange();
            range.selectNodeContents(el);
            sel.removeAllRanges();
            sel.addRange(range);
            var inserted = false;
            try { inserted = document.execCommand('insertText', false, text); } catch (e) {}
            if (!inserted) {
              el.textContent = text;
              el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
            }
          } else if (el.tagName && el.tagName.toLowerCase() === 'textarea') {
            var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
            setter.call(el, text);
            el.dispatchEvent(new Event('input', { bubbles: true }));
          } else {
            return JSON.stringify({ ok: false, why: 'unsupported-input-type' });
          }

          // 2) 合成 paste 注入图片。失败不阻断提交，只记录 pasted=false。
          var pasted = false, pasteWhy = '';
          try {
            var binary = atob(b64);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            var file = new File([bytes], name, { type: mime });
            var dt = new DataTransfer();
            dt.items.add(file);
            var ev = new ClipboardEvent('paste', {
              clipboardData: dt, bubbles: true, cancelable: true, composed: true
            });
            // 粘贴前再聚焦一次：写文本或中间脚本可能已改变焦点。
            el.focus();
            el.dispatchEvent(ev);
            pasted = true;
          } catch (e) {
            pasteWhy = String(e && e.message || e);
          }

          // 3) 点发送。
          //    Gemini 是 Angular 组件：insertText 后发送按钮要到下一帧才渲染。
          //    同步 querySelector 会扑空并误退 Enter（真机实测 viaButton=false）。
          //    因此在异步路径里轮询等待按钮出现，再用一次 click。
          var result = {
            ok: true, filled: true, pasted: pasted, pasteWhy: pasteWhy,
            submitted: false, viaButton: false, length: text.length
          };

          function findSend() {
            var b = null;
            try { b = document.querySelector(${quote(GeminiSelectors.send())}); } catch (e) { b = null; }
            if (b) return b;
            // 兜底：任何 aria-label 含 Send / 发送、且有尺寸的元素。
            var all = document.querySelectorAll('[aria-label]');
            for (var k = 0; k < all.length; k++) {
              var lab = (all[k].getAttribute('aria-label') || '').toLowerCase();
              if (lab.indexOf('send') < 0 && lab.indexOf('发送') < 0) continue;
              var rr = all[k].getBoundingClientRect();
              if (rr.width <= 0 || rr.height <= 0) continue;
              return all[k];
            }
            return null;
          }

          function pressEnter() {
            el.focus();
            ['keydown', 'keypress', 'keyup'].forEach(function(type) {
              el.dispatchEvent(new KeyboardEvent(type, {
                key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                bubbles: true, cancelable: true
              }));
            });
          }

          // 发送按钮由 Angular 异步渲染（真机实测：填完文本的同步时刻 bySel=0）。
          // 绝不能用同步忙等——那会阻塞 JS 事件循环，Angular 永远无法渲染，
          // 实测 2s 内 bySel/byAria 始终为 0。
          //
          // evaluateJavascript 只能拿到同步返回值，所以这里：
          //   - 若按钮此刻已在，直接点；
          //   - 否则用 setTimeout 在下一帧后再试若干次（异步，不阻塞渲染）；
          //   - 都失败才退 Enter。
          // submitted 仅表示"已触达发送"，真实成败仍由调用方比对回答快照。
          var btn = findSend();
          if (btn && !btn.disabled && btn.getAttribute('aria-disabled') !== 'true') {
            btn.click();
            result.viaButton = true;
            result.submitted = true;
            result.submitVia = 'button';
          } else {
            var tries = 0;
            (function retry() {
              var b = findSend();
              if (b && !b.disabled && b.getAttribute('aria-disabled') !== 'true') {
                b.click();
                result.viaButton = true;
                result.submitted = true;
                result.submitVia = 'button-async';
                return;
              }
              if (tries++ < 40) { setTimeout(retry, 50); return; }
              pressEnter();
              result.viaButton = false;
              result.submitVia = 'enter';
            })();
            // 异步兜底路径：这里先按"已请求发送"上报，避免调用方等一个
            // 永远拿不到的同步结果。是否真的发出由 awaitSubmitStarted 判定。
            result.submitted = true;
          }

          return JSON.stringify(result);
        })(${quote(prompt)}, ${quote(fileName)}, ${quote(mimeType)}, ${quote(base64)})
    """.trimIndent()

    /**
     * 校验图片附件是否真的出现。
     *
     * 只看输入区附近的附件卡片 / 预览缩略图，避免命中页面里历史消息的图片或头像。
     * 判据优先级：输入区内的附件节点 > 输入框上方的 blob/data 缩略图。
     * 单纯派发事件成功不能作为上传成功依据。
     */
    fun verifyAttachment(): String = """
        (function() {
          var input;
          try { input = document.querySelector(${quote(GeminiSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!input) return JSON.stringify({ ok: false, why: 'composer-not-found' });

          var box = input;
          for (var i = 0; i < 6 && box && box.parentElement; i++) { box = box.parentElement; }
          if (!box) return JSON.stringify({ ok: false, why: 'no-container' });

          var previews = 0, thumbs = 0;
          try {
            previews = box.querySelectorAll(
              '[data-test-id*="attachment"], [data-test-id*="upload"], .attachment-preview, .file-preview'
            ).length;
          } catch (e) {}
          try {
            var imgs = box.querySelectorAll('img[src^="blob:"], img[src^="data:image"]');
            for (var j = 0; j < imgs.length; j++) {
              var r = imgs[j].getBoundingClientRect();
              // 缩略图：小而方，排除头像（通常带圆角且位于页头）。
              if (r.width >= 20 && r.width <= 200 && r.height >= 20 && r.height <= 200) thumbs++;
            }
          } catch (e) {}

          return JSON.stringify({
            ok: true,
            previewCount: previews,
            thumbCount: thumbs,
            attached: (previews + thumbs) > 0
          });
        })()
    """.trimIndent()
}
