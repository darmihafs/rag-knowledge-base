/**
 * 引用溯源的「命中词句高亮」工具：从提问里抽候选词，在召回片段原文上标出命中部分。
 *
 * <p>为什么不做完整分词：这一步只是让用户肉眼对照「回答依据的到底是哪几句」，
 * 抽词够宽松即可；真正的召回质量由后端向量 + 关键词双路 RRF 决定。
 */

/** 问号、语气词这类高频停用词：拿它们去高亮只会满屏花块，毫无信息量 */
const STOP_WORDS = [
  '什么',
  '怎么',
  '怎样',
  '哪些',
  '哪个',
  '其中',
  '是否',
  '可以',
  '需要',
  '请问',
  '帮我',
  '一下',
  '这个',
  '那个',
  '我们',
  '你们',
  '他们',
  '一句话',
]

/** 单字停用词：中文词组里切到这些字就断开 */
const STOP_CHARS = '的了是和在对有与及或要把被给就都还也很更最与之'

const LATIN_WORD = /[A-Za-z0-9][A-Za-z0-9._-]{2,}/g

/**
 * 从提问中抽取候选高亮词。
 *
 * <p>中文：先按标点和停用字切成词组，词组原样保留；长度 >= 4 的词组再补 3 字滑窗，
 * 这样「文档的核心结论」这种长串即使原文只出现「核心结论」也能命中。
 * <p>英文与数字：整词（>= 3 字符）直接作为候选词。
 */
export function extractTerms(question: string): string[] {
  if (!question) {
    return []
  }
  const terms = new Set<string>()

  question
    .split(/[^一-龥A-Za-z0-9]+/)
    .flatMap((piece) => splitByStopChars(piece))
    .forEach((phrase) => {
      if (phrase.length < 2) {
        return
      }
      terms.add(phrase)
      if (isChinese(phrase) && phrase.length >= 4) {
        for (let i = 0; i + 3 <= phrase.length; i++) {
          terms.add(phrase.slice(i, i + 3))
        }
      }
    })

  ;(question.match(LATIN_WORD) ?? []).forEach((word) => terms.add(word))

  // 候选词过多会把整段染满，按长度优先保留最有信息量的长词
  return [...terms]
    .filter((term) => term.length >= 2 && !STOP_WORDS.includes(term))
    .sort((a, b) => b.length - a.length)
    .slice(0, 24)
}

/** 按停用字把连写串再切一刀：「文档的核心结论」→「文档」「核心结论」 */
function splitByStopChars(piece: string): string[] {
  if (!isChinese(piece)) {
    return [piece]
  }
  return piece
    .split(new RegExp(`[${STOP_CHARS}]`))
    .filter((part) => part.length > 0)
}

function isChinese(text: string): boolean {
  return /^[一-龥]+$/.test(text)
}

/**
 * 生成对照框 HTML：命中的词句包一层 `<mark>`。
 *
 * <p>先在原文上定位所有候选词的区间、合并重叠，再分段转义拼接，
 * 因此输出里除自己插入的 `<mark>` 之外全部是转义后的安全文本，可直接 v-html。
 */
export function highlightSnippet(snippet: string, terms: string[]): string {
  const text = snippet ?? ''
  if (!text) {
    return ''
  }

  const haystack = text.toLowerCase()
  const ranges: Array<{ start: number; end: number }> = []
  terms
    .filter((term) => term.length >= 2)
    .forEach((term) => {
      const needle = term.toLowerCase()
      let from = haystack.indexOf(needle)
      while (from >= 0) {
        ranges.push({ start: from, end: from + needle.length })
        from = haystack.indexOf(needle, from + needle.length)
      }
    })

  if (!ranges.length) {
    return escapeHtml(text)
  }

  // 合并重叠/相邻区间，避免同一个字被两层 <mark> 套住
  ranges.sort((a, b) => a.start - b.start || b.end - a.end)
  const merged: Array<{ start: number; end: number }> = []
  ranges.forEach((range) => {
    const last = merged[merged.length - 1]
    if (last && range.start <= last.end) {
      last.end = Math.max(last.end, range.end)
    } else {
      merged.push({ ...range })
    }
  })

  let html = ''
  let cursor = 0
  merged.forEach(({ start, end }) => {
    html += escapeHtml(text.slice(cursor, start))
    html += `<mark>${escapeHtml(text.slice(start, end))}</mark>`
    cursor = end
  })
  return html + escapeHtml(text.slice(cursor))
}

/** 对照框展示长度兜底，超长片段截断 */
export function truncate(text: string, max = 600): string {
  const value = (text ?? '').trim()
  return value.length > max ? `${value.slice(0, max)}…` : value
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
