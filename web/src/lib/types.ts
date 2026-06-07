/** Wire types matching the JSON shape emitted by the Steve mod
 *  (`com.steve.ai.dashboard.PlanEventJson` and `PlanDashboardServer.buildSnapshot`). */

export type Phase =
  | 'FEASIBILITY'
  | 'DESIGN'
  | 'AWAITING_DESIGN_APPROVAL'
  | 'CONSTRUCTION'
  | 'AWAITING_ACCEPTANCE'
  | 'COMPLETED'
  | 'FAILED';

export interface BlockEntry {
  x: number;
  y: number;
  z: number;
  blockId: string; // e.g. "minecraft:oak_planks"
}

export interface MaterialEntry {
  name: string;
  count: number;
  percent?: number;
}

export interface SnapshotEvent {
  type: 'snapshot';
  projectId: string;
  idle?: boolean;
  steveName?: string;
  /** All active Steve names known to the dashboard, even when no plan is in
   *  flight. The browser uses this to populate the chat target selector and
   *  to enable the "Start plan" form. */
  steves?: string[];
  command?: string;
  phase?: Phase;
  totalBlocks?: number;
  blocksPlaced?: number;
  materials?: MaterialEntry[];
  mempalaceRef?: string;
  blocks?: BlockEntry[];
  timestamp?: string;
}

export interface PlanCreated {
  type: 'plan.created';
  projectId: string;
  steveName: string;
  command: string;
  templates: string[];
  phase: Phase;
  timestamp: string;
}

export interface PlanDesignReady {
  type: 'plan.design_ready';
  projectId: string;
  design: string;
  materials: MaterialEntry[];
  totalBlocks: number;
  blocks: BlockEntry[];
  timestamp: string;
}

export interface PlanPhaseChanged {
  type: 'plan.phase_changed';
  projectId: string;
  prev: Phase;
  next: Phase;
  deadlineMs?: number;
  timestamp: string;
}

export interface PlanApproved {
  type: 'plan.approved';
  projectId: string;
  phase: Phase;
  approvedBy: string;
  timestamp: string;
}

export interface PlanHalted {
  type: 'plan.halted';
  projectId: string;
  phase: Phase;
  reason: string;
  mempalaceRef?: string;
  blocksPlaced: number;
  totalBlocks: number;
  timestamp: string;
}

export interface PlanLog {
  type: 'plan.log';
  projectId: string;
  severity: 'INFO' | 'WARN' | 'ERROR';
  message: string;
  timestamp: string;
}

export interface PlanCommandAck {
  type: 'plan.command_ack';
  action: 'approve' | 'halt';
  projectId: string;
  ok: boolean;
  error?: string;
}

export interface PlanChat {
  type: 'plan.chat';
  projectId: string;
  steveName: string;
  sender: 'USER' | 'STEVE' | 'SYSTEM';
  message: string;
  timestamp: string;
}

export interface ChatMessage {
  id: string;
  sender: 'USER' | 'STEVE' | 'SYSTEM';
  steveName: string;
  message: string;
  ts: string;
}

export type PlanEvent =
  | SnapshotEvent
  | PlanCreated
  | PlanDesignReady
  | PlanPhaseChanged
  | PlanApproved
  | PlanHalted
  | PlanLog
  | PlanCommandAck
  | PlanChat;
