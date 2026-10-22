/** 后端统一响应结构（对应 com.bishe.kb.common.Result） */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** MyBatis-Plus 分页对象（com.baomidou...pagination.Page 的 JSON 形态） */
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export interface HealthInfo {
  status: string
  chatModel: string
  embeddingEnabled: boolean
  apiKeyConfigured: boolean
}

/** 角色：ADMIN 管理员（全功能）/ USER 普通用户（仅智能问答 + 本人会话） */
export type UserRole = 'ADMIN' | 'USER'

/** 当前登录用户信息（对应后端 UserVO，绝不含密码） */
export interface UserVO {
  id: number
  username: string
  nickname: string | null
  role: UserRole
}

/** 登录/注册成功返回（对应后端 LoginVO） */
export interface LoginVO {
  token: string
  user: UserVO
  /** token 有效期（毫秒） */
  expiresIn: number
}


export interface DocumentVO {
  id: number
  spaceId: number | null
  title: string
  fileType: string
  fileSize: number | null
  status: string
  chunkCount: number
  errorMsg: string | null
  createTime: string
}

/** 知识库空间（对应后端 SpaceVO） */
export interface SpaceVO {
  id: number
  name: string
  description: string | null
  docCount: number
  chunkCount: number
  createTime: string
  updateTime: string
}

/** 知识库空间成员授权（对应后端 SpaceMemberVO） */
export interface SpaceMemberVO {
  userId: number
  username: string
  nickname: string | null
  /** 账号角色，与被授权人的 sys_user.role 一致，不代表他在此空间有特殊身份 */
  role: UserRole
  /** 授权时间 */
  grantTime: string
}

/** 文档分段（对应后端 ChunkVO），enabled=1 参与检索，0 人工禁用 */
export interface ChunkVO {
  id: number
  documentId: number
  chunkIndex: number
  /** PDF 分段的起始页码（从 1 开始）；非 PDF 为 null */
  pageNumber: number | null
  charCount: number
  enabled: number
  content: string
}

/** 近 N 日提问走势上的一个点 */
export interface TrendPoint {
  date: string
  count: number
}

/** 文档格式分布饼图的一个扇区 */
export interface FormatSlice {
  type: string
  count: number
}

/** 首页数据大盘统计（对应后端 DashboardStatsVO） */
export interface DashboardStats {
  spaceCount: number
  documentCount: number
  chunkCount: number
  todayQuestions: number
  trend: TrendPoint[]
  formats: FormatSlice[]
}

export interface AskRequest {
  question: string
  /** 限定只在该知识库空间内检索，不传则全库检索 */
  spaceId?: number
  topK?: number
}

/** 流式问答入参：比同步接口多一个必填 sessionId，后端据此带上下文并落库 */
export interface AskStreamRequest {
  question: string
  sessionId: number
  topK?: number
  /** 与同步问答同一口径：把召回收敛到指定知识库空间 */
  spaceId?: number
}

/**
 * 回答引用（对应后端 AskResponse.Citation）。
 *
 * <p>pageNumber 由 PDF 逐页切分时写入 kb_chunk.page_number，从 1 开始；
 * 非 PDF 文档没有稳定页概念，为 null，前端此时只做「原文片段对照」而不跳页。
 */
export interface Citation {
  documentId: number
  title: string
  chunkIndex: number
  pageNumber: number | null
  snippet: string
  score: number | null
}

export interface AskResponse {
  answer: string
  citations: Citation[]
  elapsedMillis: number
}

/** 会话列表项（对应后端 SessionVO） */
export interface SessionVO {
  id: number
  title: string
  createTime: string
  updateTime: string
}

/** 历史消息（对应后端 ChatMessageVO），citations 已由后端反序列化成结构化数组 */
export interface ChatMessageVO {
  id: number
  sessionId: number
  role: 'user' | 'assistant'
  content: string
  citations: Citation[]
  createTime: string
}

// ------------------------------------------------------------------------ 检索对比实验室

/** 单条召回结果（对应后端 RetrieveCompareVO.Item） */
export interface RetrievalItem {
  rank: number
  chunkId: number
  documentId: number
  documentTitle: string | null
  chunkIndex: number
  pageNumber: number | null
  charCount: number | null
  snippet: string
}

/** 一条召回路的执行结果 */
export interface RetrievalRoute {
  /** LEXICAL / SEMANTIC / HYBRID */
  strategy: string
  strategyCn: string
  description: string
  elapsedMillis: number
  /** false 表示这条路本次不可用（如向量化未开启），items 为空但 note 会给出原因 */
  available: boolean
  note: string | null
  hitCount: number
  items: RetrievalItem[]
}

/** 本次对比用到的引擎参数与各路策略说明 */
export interface RetrievalEngine {
  rrfK: number
  embeddingModel: string
  vectorAvailable: boolean
  lexicalStrategy: string
  semanticStrategy: string
  hybridStrategy: string
  /** 检索范围口径：全库，或已收敛到某个知识库空间（三路共用同一个范围） */
  scopeStrategy: string
}

/** POST /api/knowledge/retrieve-compare 响应（对应后端 RetrieveCompareVO） */
export interface RetrieveCompareResult {
  question: string
  topK: number
  /** 本次召回收敛到的空间 ID；null 表示全库检索 */
  spaceId: number | null
  engine: RetrievalEngine
  routes: RetrievalRoute[]
}

// ------------------------------------------------------------------------ 用户与权限管理

/** 管理员侧的账号行（对应后端 AdminUserVO），带该账号的活跃度统计 */
export interface AdminUserVO {
  id: number
  username: string
  nickname: string | null
  role: UserRole
  /** 1 启用 / 0 禁用 */
  status: number
  createTime: string
  sessionCount: number
  questionCount: number
}

// ------------------------------------------------------------------------ 问答审计日志

/** 审计列表一行：一次提问 + 回答摘要 + 引用切片数（对应后端 AuditMessageVO） */
export interface AuditMessageVO {
  messageId: number
  sessionId: number
  sessionTitle: string
  userId: number | null
  username: string
  nickname: string | null
  question: string
  answerSummary: string | null
  citationCount: number
  askTime: string
}

/** 审计详情：完整回答与解析后的引用来源（对应后端 AuditDetailVO） */
export interface AuditDetailVO {
  messageId: number
  sessionId: number
  sessionTitle: string
  userId: number | null
  username: string
  nickname: string | null
  question: string
  answer: string | null
  askTime: string
  citations: Citation[]
}

// ------------------------------------------------------------------------ 引擎参数配置

/** GET /api/admin/config 响应（对应后端 SystemConfigVO） */
export interface SystemConfig {
  topK: number
  rrfK: number
  semanticMinScore: number
  chunkMaxChars: number
  chunkOverlapChars: number
  historyMessages: number
  historyMaxChars: number
  systemPrompt: string
  embeddingEnabled: boolean
  mockEnabled: boolean
  /** 进程启动时从 yaml 读到的出厂快照，用于「恢复默认配置」 */
  defaults: Record<string, unknown>
  /** 装配期固化的只读信息（模型名、baseUrl 等），改动需重启 */
  runtime: Record<string, unknown>
  /** 各参数的作用范围说明 */
  hints: Record<string, string>
}

/** PUT /api/admin/config 入参：字段为 null/undefined 表示保持原值 */
export interface SystemConfigPayload {
  topK?: number
  rrfK?: number
  semanticMinScore?: number
  chunkMaxChars?: number
  chunkOverlapChars?: number
  historyMessages?: number
  historyMaxChars?: number
  systemPrompt?: string
}

// ------------------------------------------------------------------------ 大盘第二排

/** 组件健康明细里的一行键值 */
export interface ComponentField {
  label: string
  value: string
}

/** ONLINE 在线 / DEGRADED 降级 / OFFLINE 离线 / DISABLED 配置主动关闭 */
export type ComponentState = 'ONLINE' | 'DEGRADED' | 'OFFLINE' | 'DISABLED'

/** 单个核心组件的体检结果（对应后端 ComponentStatusVO） */
export interface ComponentStatus {
  key: string
  name: string
  state: ComponentState
  stateCn: string
  endpoint: string | null
  fields: ComponentField[]
  note: string | null
}

/** GET /api/dashboard/components 响应（对应后端 DashboardHealthVO） */
export interface DashboardHealth {
  checkedAt: string
  components: ComponentStatus[]
  online: number
  total: number
}

/** 最近入库文档（对应后端 ActivityVO.RecentDocument） */
export interface RecentDocument {
  id: number
  title: string
  fileType: string
  spaceName: string | null
  chunkCount: number
  status: string
  statusCn: string
  createTime: string
}

/** 最近提问（对应后端 ActivityVO.RecentQuestion） */
export interface RecentQuestion {
  messageId: number
  sessionId: number
  username: string
  nickname: string | null
  question: string
  citationCount: number | null
  createTime: string
}

/** GET /api/dashboard/activity 响应（对应后端 ActivityVO） */
export interface DashboardActivity {
  recentDocuments: RecentDocument[]
  recentQuestions: RecentQuestion[]
}

// ------------------------------------------------------------------------ 全局切片语料中心

/**
 * 语料中心的一行切片（对应后端 CorpusChunkVO）。
 *
 * <p>与单文档视角的 ChunkVO 相比多了归属信息：documentTitle / spaceName 由后端一次批量回表补齐，
 * 前端不再为每行另发请求；源文档被删时后端给兜底文案，因此这两列不会是 null。
 */
export interface CorpusChunkVO {
  id: number
  documentId: number
  documentTitle: string
  spaceId: number | null
  spaceName: string
  fileType: string | null
  chunkIndex: number
  pageNumber: number | null
  charCount: number
  /** 1 参与检索 / 0 人工禁用（历史数据可能为 null，后端按启用处理） */
  enabled: number
  content: string
  createTime: string
}

/** GET /api/chunks/corpus-stats 响应（对应后端 ChunkCorpusStatsVO），口径为全库 */
export interface ChunkCorpusStats {
  totalChunks: number
  enabledChunks: number
  disabledChunks: number
  indexedDocuments: number
  activeSpaces: number
}

// ------------------------------------------------------------------------ 问答质量反馈

/** 工单类型归因，与后端 ChatFeedback.ISSUE_TYPES 白名单一一对应 */
export const FEEDBACK_ISSUE_TYPES = ['检索漏检', '答案幻觉', '引用不准', '其他'] as const
export type FeedbackIssueType = (typeof FEEDBACK_ISSUE_TYPES)[number]

/** POST /api/feedback 与 GET /api/feedback/mine 的轻量结构（对应后端 FeedbackBriefVO） */
export interface FeedbackBriefVO {
  messageId: number
  /** 1 点赞 / -1 点踩 */
  rating: number
  issueType: string | null
  comment: string | null
}

/** 管理端工单行（对应后端 FeedbackVO），已带当时的提问、回答与提交人 */
export interface FeedbackTicketVO {
  id: number
  messageId: number
  sessionId: number
  userId: number | null
  username: string
  nickname: string | null
  rating: number
  issueType: string | null
  comment: string | null
  /** 0 待处理 / 1 已修复 / 2 已忽略 */
  status: number
  adminNote: string | null
  question: string | null
  answer: string | null
  createTime: string
  updateTime: string
}

/** 某个问题类型的工单数与占比（ratio 为 0~1 小数，由后端算好） */
export interface FeedbackIssueTypeCount {
  issueType: string
  count: number
  ratio: number
}

/** GET /api/admin/feedback/stats 响应（对应后端 FeedbackStatsVO） */
export interface FeedbackStatsVO {
  totalFeedback: number
  thumbsUp: number
  thumbsDown: number
  positiveRate: number
  pendingBadCase: number
  fixedTickets: number
  ignoredTickets: number
  issueTypes: FeedbackIssueTypeCount[]
}

// ------------------------------------------------------------------------ 提示词模板市场

/** 模板行（对应后端 TemplateVO），分类与后端 PromptTemplate.CATEGORIES 对齐 */
export const TEMPLATE_CATEGORIES = ['学术总结', '概念解析', '对比分析', '自测出题'] as const
export type TemplateCategory = (typeof TEMPLATE_CATEGORIES)[number]

export interface PromptTemplateVO {
  id: number
  title: string
  category: string
  /** 引导前缀，含 {topic} 占位符时由前端替换为光标处内容 */
  promptContent: string
  description: string | null
  /** 1 上架（问答页可见）/ 0 下架 */
  enabled: number
  sortOrder: number
  createTime: string
  updateTime: string
}

/** POST|PUT /api/admin/templates 入参（对应后端 TemplateSaveRequest） */
export interface TemplateSavePayload {
  title: string
  category?: string
  promptContent: string
  description?: string
  enabled?: number
  sortOrder?: number
}

// ------------------------------------------------------------------------ 个人中心

/** GET /api/auth/my-stats 响应（对应后端 MyStatsVO），口径为当前登录用户本人 */
export interface MyStatsVO {
  sessionCount: number
  questionCount: number
  answerCount: number
  feedbackCount: number
  badCaseCount: number
  registerTime: string
}
