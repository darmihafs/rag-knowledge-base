/**
 * 轻量 CSV 导出：把已经加载到页面的表格数据写成一个下载文件。
 *
 * <p>两个刻意为之的细节：
 * <ol>
 *   <li><b>UTF-8 BOM</b>：Excel 在中文 Windows 下按 GBK 猜编码，没有 BOM 的 UTF-8 会显示成乱码，
 *       因此文件头补一个 {@code \uFEFF}；</li>
 *   <li><b>公式注入防护</b>：以 {@code = + - @} 开头的单元格会被 Excel 当公式执行，
 *       前面补单引号强制按文本处理——导出的都是用户自填内容，不能假定它安全。</li>
 * </ol>
 *
 * <p>不引第三方依赖：本项目已有的 axios/Element Plus 之外不新增库，一个 Blob + a[download] 足够。
 */

export type CsvCell = string | number | boolean | null | undefined

const FORMULA_PREFIXES = ['=', '+', '-', '@', '\t', '\r']

function escapeCell(value: CsvCell): string {
  const text = value === null || value === undefined ? '' : String(value)
  const guarded =
    FORMULA_PREFIXES.some((prefix) => text.startsWith(prefix)) && !text.startsWith("'")
      ? `'${text}`
      : text
  // 双引号转义为两个双引号，整个单元格加引号，这样换行和逗号都不会破坏列结构
  return `"${guarded.replace(/"/g, '""')}"`
}

/** 把一行行数据拼成 CSV 正文（含表头），换行统一用 CRLF 兼容 Excel */
export function toCsv(headers: string[], rows: CsvCell[][]): string {
  const lines = [headers, ...rows].map((row) => row.map(escapeCell).join(','))
  return lines.join('\r\n')
}

/**
 * 触发浏览器下载。
 *
 * @param filename 文件名，不含扩展名时自动补 .csv
 * @param headers  表头列名
 * @param rows     数据行，列数应与 headers 一致
 */
export function downloadCsv(filename: string, headers: string[], rows: CsvCell[][]): void {
  const name = filename.endsWith('.csv') ? filename : `${filename}.csv`
  // \uFEFF 是 BOM；TextEncoder 会按 UTF-8 编码它，Excel 读到即认定 UTF-8
  const blob = new Blob(['\uFEFF', toCsv(headers, rows)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 立刻回收，否则这份二进制会一直挂在页面内存里
  URL.revokeObjectURL(url)
}

/** 导出用的时间格式化：ISO 串 → `YYYY-MM-DD HH:mm:ss`，空值给空串 */
export function formatForCsv(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 19)
}
