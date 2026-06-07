import {useEffect, useReducer, useRef} from 'react';
import type {
  BlockEntry,
  ChatMessage,
  MaterialEntry,
  Phase,
  PlanEvent,
} from '../lib/types';

export interface PlanState {
  connected: boolean;
  idle: boolean;
  projectId: string;
  steveName: string;
  command: string;
  phase: Phase | '';
  totalBlocks: number;
  blocksPlaced: number;
  blocks: BlockEntry[];
  materials: MaterialEntry[];
  design: string;
  mempalaceRef: string;
  deadlineMs: number;
  history: Array<{ts: string; kind: string; message: string}>;
  chat: ChatMessage[];
  steves: string[];
}

const INITIAL: PlanState = {
  connected: false,
  idle: true,
  projectId: '',
  steveName: '',
  command: '',
  phase: '',
  totalBlocks: 0,
  blocksPlaced: 0,
  blocks: [],
  materials: [],
  design: '',
  mempalaceRef: '',
  deadlineMs: 0,
  history: [],
  chat: [],
  steves: [],
};

type Action =
  | {type: 'connected'; value: boolean}
  | {type: 'apply'; event: PlanEvent}
  | {type: 'reset'};

function reducer(state: PlanState, action: Action): PlanState {
  switch (action.type) {
    case 'connected':
      return {...state, connected: action.value};
    case 'reset':
      return {...INITIAL, connected: state.connected};
    case 'apply': {
      const ev = action.event;
      if (ev.type === 'snapshot') {
        const steves = Array.isArray(ev.steves) ? ev.steves : [];
        if (ev.idle || !ev.projectId) {
          return {...INITIAL, connected: state.connected, steves};
        }
        return {
          ...state,
          idle: false,
          projectId: ev.projectId,
          steveName: ev.steveName ?? '',
          steves,
          command: ev.command ?? '',
          phase: ev.phase ?? '',
          totalBlocks: ev.totalBlocks ?? 0,
          blocksPlaced: ev.blocksPlaced ?? 0,
          blocks: ev.blocks ?? [],
          materials: ev.materials ?? [],
          mempalaceRef: ev.mempalaceRef ?? '',
        };
      }
      // All other events are project-scoped: ignore if they don't match the
      // currently active project. A new snapshot will arrive if the active
      // project changes.
      const projectId = (ev as {projectId: string}).projectId;
      if (projectId && projectId !== state.projectId && ev.type !== 'plan.created') {
        return state;
      }
      if (ev.type === 'plan.created') {
        return {
          ...state,
          idle: false,
          projectId: ev.projectId,
          steveName: ev.steveName,
          command: ev.command,
          phase: ev.phase,
          totalBlocks: 0,
          blocks: [],
          materials: [],
          design: '',
          mempalaceRef: '',
          deadlineMs: 0,
          history: pushHistory(state, 'phase', `Project ${ev.projectId} for ${ev.steveName}: "${ev.command}"`),
        };
      }
      if (ev.type === 'plan.design_ready') {
        return {
          ...state,
          design: ev.design,
          totalBlocks: ev.totalBlocks,
          materials: ev.materials,
          blocks: ev.blocks,
          history: pushHistory(state, 'ok', `Design ready (${ev.totalBlocks} blocks)`),
        };
      }
      if (ev.type === 'plan.phase_changed') {
        return {
          ...state,
          phase: ev.next,
          deadlineMs: ev.deadlineMs ?? 0,
          history: pushHistory(state, 'phase', `${ev.prev} → ${ev.next}`),
        };
      }
      if (ev.type === 'plan.approved') {
        return pushHistory(state, 'ok', `Approved by ${ev.approvedBy}`);
      }
      if (ev.type === 'plan.halted') {
        return pushHistory(state, 'err', `Halted: ${ev.reason}`);
      }
      if (ev.type === 'plan.log') {
        const kind = ev.severity === 'ERROR' ? 'err' : ev.severity === 'WARN' ? 'warn' : 'phase';
        return pushHistory(state, kind, ev.message);
      }
      if (ev.type === 'plan.command_ack') {
        return pushHistory(state, ev.ok ? 'ok' : 'err',
          `${ev.action} ${ev.ok ? 'ok' : 'failed'}${ev.error ? ': ' + ev.error : ''}`);
      }
      if (ev.type === 'plan.chat') {
        const prev = Array.isArray(state.chat) ? state.chat : [];
        const steves = ev.steveName && !state.steves.includes(ev.steveName)
          ? [...state.steves, ev.steveName]
          : state.steves;
        const msg: ChatMessage = {
          id: `${Date.now()}-${prev.length}`,
          sender: ev.sender,
          steveName: ev.steveName ?? '',
          message: ev.message,
          ts: ev.timestamp ?? new Date().toISOString(),
        };
        const next = [...prev, msg].slice(-500);
        return {...state, chat: next, steves};
      }
      return state;
    }
  }
}

function pushHistory(state: PlanState, kind: string, message: string): PlanState {
  const ts = new Date().toLocaleTimeString();
  const prev = Array.isArray(state.history) ? state.history : [];
  const next = [{ts, kind, message}, ...prev].slice(0, 200);
  return {...state, history: next};
}

export function usePlanStore() {
  const [state, dispatch] = useReducer(reducer, INITIAL);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const es = new EventSource('/events');
    esRef.current = es;
    es.onopen = () => dispatch({type: 'connected', value: true});
    es.onerror = () => dispatch({type: 'connected', value: false});
    es.onmessage = (m) => {
      try {
        const data = JSON.parse(m.data);
        dispatch({type: 'apply', event: data as PlanEvent});
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('Bad SSE payload', e);
      }
    };
    return () => {
      es.close();
      esRef.current = null;
    };
  }, []);

  const sendCommand = (action: 'approve' | 'halt') => {
    if (!state.projectId) return;
    fetch('/command', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({action, projectId: state.projectId}),
    }).catch((err) => {
      dispatch({type: 'apply', event: {
        type: 'plan.log',
        projectId: state.projectId,
        severity: 'ERROR',
        message: `Network error: ${err.message}`,
        timestamp: new Date().toISOString(),
      }});
    });
  };

  const sendChat = (steveName: string, message: string) => {
    if (!steveName || !message.trim()) return;
    fetch('/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({steveName, message}),
    }).catch((err) => {
      dispatch({type: 'apply', event: {
        type: 'plan.chat',
        projectId: state.projectId,
        steveName,
        sender: 'SYSTEM',
        message: `Network error: ${err.message}`,
        timestamp: new Date().toISOString(),
      }});
    });
  };

  const startPlan = (description: string): Promise<{ok: boolean; error?: string}> => {
    return fetch('/plan', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({description}),
    }).then((res) => {
      if (!res.ok) {
        return res.json().then((j) => Promise.resolve({ok: false, error: (j && j.error) || `HTTP ${res.status}`}))
          .catch(() => Promise.resolve({ok: false, error: `HTTP ${res.status}`}));
      }
      return res.json().then(() => Promise.resolve({ok: true}))
        .catch(() => Promise.resolve({ok: true}));
    }).catch((err) => {
      dispatch({type: 'apply', event: {
        type: 'plan.chat',
        projectId: state.projectId,
        steveName: state.steves[0] ?? '',
        sender: 'SYSTEM',
        message: `Network error: ${err.message}`,
        timestamp: new Date().toISOString(),
      }});
      return Promise.resolve({ok: false, error: err.message});
    });
  };

  return {state, sendCommand, sendChat, startPlan};
}
