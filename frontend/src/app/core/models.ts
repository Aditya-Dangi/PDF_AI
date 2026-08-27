export interface AuthResponse {
  token: string;
  userId: string;
  email: string;
}

export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED';

export interface DocumentSummary {
  id: string;
  filename: string;
  pageCount: number;
  status: DocumentStatus;
  failureReason: string | null;
  createdAt: string;
}

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Evidence {
  chunkId: string;
  page: number;
  rects: Rect[];
  text: string;
  similarity: number;
}

export interface AnswerResponse {
  messageId: string;
  question: string | null;
  documentClaim: string;
  explanation: string;
  insufficientContext: boolean;
  retrievalConfidence: number;
  fidelityConfidence: number;
  evidence: Evidence[];
  durationMs: number | null;
}

export type Verdict =
  | 'SUPPORTED'
  | 'PARTIALLY_SUPPORTED'
  | 'MISLEADING'
  | 'UNSUPPORTED'
  | 'CONTRADICTED'
  | 'INSUFFICIENT_EVIDENCE';

export interface Source {
  url: string;
  title: string;
  snippet: string;
  stance: 'SUPPORTS' | 'CONTRADICTS' | 'MIXED' | 'NOT_RELEVANT';
  authorityTier: 'PRIMARY_AUTHORITY' | 'ESTABLISHED' | 'CONTEXT_ONLY' | 'UNKNOWN';
  publishedDate: string | null;
}

export interface FactCheckResponse {
  messageId: string;
  claimText: string;
  claimType: string;
  checkable: boolean;
  verdict: Verdict;
  webConfidence: number;
  summary: string;
  sources: Source[];
  durationMs: number | null;
}

/** A plain summary of a selected passage or image region - deliberately separate from
 *  FactCheckResponse: no claim extraction, no web search, no verdict, just "what does this say". */
export interface SummaryResponse {
  messageId: string;
  sourceText: string;
  summaryText: string;
  durationMs: number | null;
}

export type MessageRole = 'USER' | 'ASSISTANT';

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  createdAt: string;
  answer: AnswerResponse | null;
  factCheck: FactCheckResponse | null;
  summary: SummaryResponse | null;
}

export type TemporalStatus = 'NOT_TIME_SENSITIVE' | 'CURRENT' | 'HISTORICAL_OUTDATED' | 'TIME_SENSITIVE_UNVERIFIED';
export type ClaimMode = 'NORMAL' | 'CHALLENGE';

export interface Claim {
  id: string;
  documentId: string;
  messageId: string | null;
  sourceClaimId: string | null;
  claimText: string;
  claimType: string;
  timeSensitive: boolean;
  checkable: boolean;
  mode: ClaimMode;
  verdict: Verdict;
  retrievalConfidence: number;
  fidelityConfidence: number;
  webConfidence: number;
  sourceIndependenceScore: number;
  independentSourceCount: number;
  rawSourceCount: number;
  temporalStatus: TemporalStatus;
  evidence: Evidence[];
  supportSources: Source[];
  counterSources: Source[];
  rationale: string;
  createdAt: string;
}

export type AuditStatus = 'NONE' | 'RUNNING' | 'DONE' | 'FAILED';

export interface AuditResponse {
  status: AuditStatus;
  claimsDetected: number;
  claimsInvestigated: number;
  evidenceCoverage: number;
  verdictCounts: Record<string, number>;
  failureReason: string | null;
  claims: Claim[];
}
