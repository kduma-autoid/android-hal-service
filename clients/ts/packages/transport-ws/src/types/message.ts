// Client -> Server messages
export type WsClientMessage =
  | WsRequestTokenMessage
  | WsAuthenticateMessage
  | WsCommandMessage
  | WsSubscribeMessage
  | WsUnsubscribeMessage;

export interface WsRequestTokenMessage {
  id: string;
  type: 'requestToken';
  clientId: string;
  serviceKey?: string;
  requestedPermissions?: string[];
}

export interface WsAuthenticateMessage {
  id: string;
  type: 'authenticate';
  token: string;
}

export interface WsCommandMessage {
  id: string;
  type: 'command';
  method: string;
  params: string; // JSON string — server expects string
}

export interface WsSubscribeMessage {
  id: string;
  type: 'subscribe';
  events: string[];
}

export interface WsUnsubscribeMessage {
  id: string;
  type: 'unsubscribe';
  events: string[];
}

// Server -> Client messages
export type WsServerMessage =
  | WsResponseMessage
  | WsErrorMessage
  | WsEventMessage;

export interface WsResponseMessage {
  id: string;
  type: 'response';
  result: unknown;
  /** Provider that handled the call (interface methods), in the frame header. Absent otherwise. */
  provider?: string;
}

export interface WsErrorMessage {
  id?: string;
  type: 'error';
  error: {
    code: string;
    message: string;
  };
}

export interface WsEventMessage {
  type: 'event';
  event: string;
  data: unknown;
  /** Plugin id that emitted the event (frame header, sibling of `data`). */
  source?: string;
}
