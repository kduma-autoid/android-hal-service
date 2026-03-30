import { ref } from 'vue';

export type ToastType = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  message: string;
  type: ToastType;
}

let nextId = 0;
const toasts = ref<Toast[]>([]);

function addToast(message: string, type: ToastType = 'info', durationMs = 4000): void {
  const id = ++nextId;
  toasts.value.push({ id, message, type });
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id);
  }, durationMs);
}

function success(message: string, durationMs?: number): void {
  addToast(message, 'success', durationMs);
}

function error(message: string, durationMs?: number): void {
  addToast(message, 'error', durationMs ?? 6000);
}

function info(message: string, durationMs?: number): void {
  addToast(message, 'info', durationMs);
}

function dismiss(id: number): void {
  toasts.value = toasts.value.filter((t) => t.id !== id);
}

export function useToast() {
  return { toasts, addToast, success, error, info, dismiss };
}
