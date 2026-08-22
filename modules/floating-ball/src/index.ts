/**
 * FloatingBall 悬浮球模块 JS 接口
 * Android 专属能力，其他平台静默降级为无操作。
 */

import { Platform } from 'react-native';
import { requireNativeModule } from 'expo-modules-core';

/** 手势类型 */
export type FloatingBallGesture =
  | 'tap'
  | 'doubleTap'
  | 'longPress'
  | 'swipeUp'
  | 'swipeDown'
  | 'swipeLeft'
  | 'swipeRight';

/** 悬浮球动作（原生侧解析执行） */
export type FloatingBallAction =
  | 'panelAll'
  | 'panelText'
  | 'panelImage'
  | 'panelFile'
  | 'panelFav'
  | 'openApp'
  | 'upload'
  | 'download'
  | 'none';

export type FloatingBallGestureMap = Partial<Record<FloatingBallGesture, FloatingBallAction>>;

interface FloatingBallModuleInterface {
  isShowing(): boolean;
  show(sizeDp: number, opacity: number, actions: Record<string, string>): boolean;
  hide(): void;
  updateConfig(sizeDp: number, opacity: number, actions: Record<string, string>): boolean;
  resetPosition(): void;
  openMainApp(): boolean;
}

const nativeModule: FloatingBallModuleInterface | null =
  Platform.OS === 'android' ? requireNativeModule('FloatingBallModule') : null;

/**
 * 显示悬浮球
 * @returns 权限不足或平台不支持时返回 false
 */
export function showFloatingBall(
  sizeDp: number,
  opacity: number,
  actions: FloatingBallGestureMap = {}
): boolean {
  if (!nativeModule) return false;
  return nativeModule.show(sizeDp, opacity, sanitizeActions(actions));
}

/** 隐藏悬浮球 */
export function hideFloatingBall(): void {
  if (!nativeModule) return;
  nativeModule.hide();
}

/** 悬浮球是否正在显示 */
export function isFloatingBallShowing(): boolean {
  if (!nativeModule) return false;
  return nativeModule.isShowing();
}

/** 运行时更新大小/透明度/手势映射 */
export function updateFloatingBallConfig(
  sizeDp: number,
  opacity: number,
  actions: FloatingBallGestureMap = {}
): boolean {
  if (!nativeModule) return false;
  return nativeModule.updateConfig(sizeDp, opacity, sanitizeActions(actions));
}

/** 清除记忆的悬浮球位置 */
export function resetFloatingBallPosition(): void {
  if (!nativeModule) return;
  nativeModule.resetPosition();
}

/** 从悬浮面板跳转到应用主界面 */
export function openMainApp(): boolean {
  if (!nativeModule) return false;
  return nativeModule.openMainApp();
}

function sanitizeActions(actions: FloatingBallGestureMap): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(actions)) {
    if (typeof value === 'string' && value !== 'none') {
      result[key] = value;
    }
  }
  return result;
}
