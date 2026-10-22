/**
 * ECharts 按需注册：只装大屏真正用到的两张图与它们的交互件，避免把整个 echarts 打进路由分包。
 *
 * <p>之前 {@code import * as echarts from 'echarts'} 会把全部 25 种图表（地图、3D、关系图、
 * 箱线、桑基……）、全部组件和两种渲染器一起拽进来，DashboardView 的分包因此涨到 1.1MB+，
 * 而这里其实只画了一条折线和一个饼图。按需注册后体积能掉到三分之一左右。
 *
 * <p>挑出来的模块与为什么不能少：
 * <ol>
 *   <li>{@code CanvasRenderer}：大屏在管理员电脑上跑，Canvas 比 SVG 更稳；</li>
 *   <li>{@code LineChart} + {@code PieChart}：提问走势与文档格式分布；</li>
 *   <li>{@code GridComponent}：折线的 {@code xAxis/yAxis} 依赖直角坐标系（它顺带装了 axisPointer）；</li>
 *   <li>{@code TooltipComponent}：鼠标悬浮看数值，{@code trigger: 'axis'} 需要它；</li>
 *   <li>{@code LegendComponent}：饼图底部可滚动的图例（它同时装了 plain 与 scroll 两种）。</li>
 * </ol>
 *
 * <p>以后要加图表：在这里补一行 {@code use([...])}，业务侧继续只从本文件导入 echarts，
 * 全站的注册口径就不会散落到各个页面。
 */
import { init, use } from 'echarts/core'
import type { LinearGradientObject } from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

export { init }
export type { EChartsType } from 'echarts/core'

/**
 * 区域填充的渐变色。echarts 除了 {@code new graphic.LinearGradient(...)} 还支持声明式写法，
 * 用对象字面量就不用再从 echarts 拉 {@code graphic} 命名空间，效果完全一致。
 */
export function verticalGradient(stops: Array<{ offset: number; color: string }>): LinearGradientObject {
  return { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: stops }
}
