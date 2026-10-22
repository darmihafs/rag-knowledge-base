import { marked, type TokenizerAndRendererExtension, type Tokens } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染 + XSS 消毒 + 引用角标可点击化。
 *
 * 回答内容来自大模型，属于不可信输入，v-html 之前必须过一遍 DOMPurify。
 */

/**
 * 把正文里的 `[1]` `[2]` 这类引用角标渲染成可点击的 sup 标签。
 *
 * <p>只匹配「纯数字 + 后面不跟括号」，避免误伤 markdown 链接语法 `[标题](url)`；
 * `data-ref` 存的是 0 基下标，正好对应 message.citations 数组，点击时直接取引用。
 */
const citationMarker: TokenizerAndRendererExtension = {
  name: 'citeRef',
  level: 'inline',
  start: (src: string) => /^\[\d{1,2}\](?!\()/.test(src) ? 0 : undefined,
  tokenizer: (src: string): Tokens.Generic | undefined => {
    const match = /^\[(\d{1,2})\](?!\()/.exec(src)
    if (!match) {
      return undefined
    }
    return { type: 'citeRef', raw: match[0], text: match[1] }
  },
  renderer: (token: Tokens.Generic): string => {
    const ref = Number(token.text) - 1
    return `<sup class="cite-ref" data-ref="${ref}" title="点击查看引用原文">[${ref + 1}]</sup>`
  },
}

marked.use({ extensions: [citationMarker] })

export function renderMarkdown(text: string): string {
  if (!text) {
    return ''
  }
  const html = marked.parse(text, {
    async: false,
    gfm: true,
    breaks: true,
  }) as string
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ['target', 'rel'],
  })
}
